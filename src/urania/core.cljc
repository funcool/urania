(ns urania.core
  (:require [promesa.core :as prom]
            [clojure.string :as s])
  #?(:clj
     (:import java.util.concurrent.ForkJoinPool
              java.util.concurrent.Executor))
  (:refer-clojure :exclude (map mapcat run!)))

(defprotocol IExecutor
  "A policy for executing tasks."
  (-execute [ex task] "Perform a task."))

(defprotocol DataSource
  "A remote data source."
  (-identity [this]
    "Return an identifier for this data source.
    Used for caching, note that data sources of different types are cached separately.")
  (-fetch [this env]
    "Fetch this data source "))

(defprotocol BatchedSource
  "A remote data source that can be fetched in batches."
  (-fetch-multi [this resources env]
    "Fetch this and other data sources in a single batch.
    The returned promise must be a map from the data source identities to their results."))

;; AST

(declare inject-into)

(defprotocol AST
  (-children [this])
  (-inject [this env])
  (-done? [this]))

(defprotocol ComposedAST
  (-compose-ast [this f]))

(defrecord Done [value]
  ComposedAST
  (-compose-ast [_ f2] (Done. (f2 value)))

  AST
  (-children [_] nil)
  (-done? [_] true)
  (-inject [this _] this))


(deftype Map [f values]
  ComposedAST
  (-compose-ast [_ f2] (Map. (comp f2 f) values))

  AST
  (-children [_] values)
  (-done? [_] false)
  (-inject [_ env]
    (let [next (clojure.core/map (partial inject-into env) values)]
      (if (every? -done? next)
        (Done. (apply f (clojure.core/map :value next)))
        (Map. f next)))))

(defn- ast?
  [ast]
  (or (satisfies? AST ast)
      (satisfies? DataSource ast)))

(defn- assert-ast!
  [ast]
  (assert (ast? ast)))

(deftype FlatMap [f values]
  AST
  (-children [_] values)
  (-done? [_] false)
  (-inject [_ env]
    (let [next (clojure.core/map (partial inject-into env) values)]
      (if (every? -done? next)
        (let [result (apply f (clojure.core/map :value next))]
          ;; xxx: refactor to avoid dummy leaves creation
          (if (satisfies? DataSource result)
            (Map. identity [result])
            result))
        (FlatMap. f next)))))

(deftype Value [value]
  ComposedAST
  (-compose-ast [_ f2] (Map. f2 [value]))

  AST
  (-children [_] [value])
  (-done? [_] false)
  (-inject [_ env]
    (let [next (inject-into env value)]
      (if (-done? next)
        (Done. (:value next))
        next))))

(defn resource-name [v]
  (pr-str (type v)))

(defn cache-id
  [res]
  (-identity res))

(def cache-path (juxt resource-name cache-id))

(defn- cached-or [env res]
  (let [cache (get env :cache)
        cached (get-in cache (cache-path res) ::not-found)]
    (if (= ::not-found cached)
      (Map. identity [res])
      (Done. cached))))

(defn- inject-into [env node]
  (if (satisfies? DataSource node)
    (cached-or env node)
    (-inject node env)))

;; Combinators

(defn value
  "Given a plain value, wrap it in a data source that will return the
  value immediately."
  [v]
  (assert (not (ast? v))
          (str "The value is already an AST: " v))
  (Done. v))

(defn map
  "Given a function and one or more data sources, return a new
  data source that will apply the given function to the results.
  When mapping over multiple data sources the results will be passed
  as positional arguments to the given function."
  [f muse & muses]
  (if (and (not (seq muses))
           (satisfies? ComposedAST muse))
    (-compose-ast muse f)
    (Map. f (cons muse muses))))

(defn mapcat
  "Given a function and one or more data sources, return a new data
  source that will apply the given function to the results. The function
  is assumed to return more data sources that will be flattened into a single
  data source."
  [f muse & muses]
  (FlatMap. f (cons muse muses)))

(defn collect
  "Given a collection of data sources, return a new data source that will
  contain a collection with the values of every data source when fetched."
  [muses]
  (if (seq muses)
    (apply (partial map vector) muses)
    (value [])))

(defn traverse
  "Given a function and a collection of data sources, apply the function once
  to each data source and collect the resulting data source results into a data
  source with every result."
  [f muses]
  (mapcat #(collect (clojure.core/map f %)) muses))

;; Fetching

(defn- run-fetch
  [{:keys [executor env]} muse]
  (prom/promise
   (fn [resolve reject]
     (-execute executor #(prom/branch (-fetch muse env) resolve reject)))))

(defn- run-fetch-multi
  [{:keys [executor env]} muse muses]
  (prom/promise
   (fn [resolve reject]
     (-execute executor #(prom/branch (-fetch-multi muse muses env) resolve reject)))))

(defn- fetch-many-caching
  [opts sources]
  (let [ids (clojure.core/map cache-id sources)
        responses (clojure.core/map (partial run-fetch opts) sources)]
    (prom/then (prom/all responses) #(zipmap ids %))))

(defn- fetch-one-caching
  [opts source]
  (prom/then (run-fetch opts source)
             (fn [res]
               {(cache-id source) res})))

(defn- fetch-sources
  [opts [head & tail :as sources]]
  (if-not (seq tail)
    (fetch-one-caching opts head)
    (if (satisfies? BatchedSource head)
      (run-fetch-multi opts head tail)
      (fetch-many-caching opts sources))))

(defn- dedupe-sources
  [sources]
  (->> sources
       (group-by cache-id)
       vals
       (clojure.core/map first)))

(defn- fetch-resource
  [opts [resource-name sources]]
  (prom/then (fetch-sources opts (dedupe-sources sources))
             (fn [resp]
               [resource-name resp])))

;; AST interpreter

(defn- next-level
  [ast-node]
  (if (satisfies? DataSource ast-node)
    (list ast-node)
    (when-let [values (-children ast-node)]
      (clojure.core/mapcat next-level values))))

(defn- interpret-ast
  [ast-node {:keys [cache] :as opts} success! error!]
  (let [ast-node (inject-into opts ast-node)
        requests (next-level ast-node)]
    (if-not (seq requests)
      (if (-done? ast-node)
        (success! [(:value ast-node) cache])
        (recur ast-node opts success! error!))
      (let [requests-by-type (group-by resource-name requests)
            responses (clojure.core/map (partial fetch-resource opts) requests-by-type)]
        (prom/branch (prom/all responses)
                     (fn [results]
                       (let [next-cache (into cache results)
                             next-opts (assoc opts :cache next-cache)]
                         (interpret-ast ast-node next-opts success! error!)))
                     error!)))))

;; Public API

#?(:clj
   (extend Executor
     IExecutor
     {:-execute (fn [ex task] (.execute ex task))}))

(def default-executor
  #?(:clj
     (ForkJoinPool/commonPool)
     :cljs
     (reify IExecutor
       (-execute [_ task]
         (js/setTimeout task 0)))))

(def run-defaults {:cache {}
                   :executor default-executor})

(defn execute!
  "Executes the data fetching, returning a promise of the `[cache result]`
  pair.

   * fetch data sources concurrently (when possible)
   * cache result of previously made fetches
   * batch calls to the same data source (when applicable)

  You can pass a second argument with the following options:

  - `:cache`: A map to use as the cache.

  - `:executor`: An implementation of `IExecutor` that will be used
   to run the fetches. Defaults to `urania.core/default-executor`.

   In Clojure you can pass a `java.util.concurrent.Executor` instance.

  - `:env`: An environment that will be passed to every data fetching
  function."
  ([ast]
   (execute! ast run-defaults))
  ([ast opts]
   (prom/promise
    (fn [resolve reject]
      (interpret-ast ast (merge run-defaults opts) resolve reject)))))

(defn run!
  "Executes the data fetching, returning a promise of the result.

   * fetch data sources concurrently (when possible)
   * cache result of previously made fetches
   * batch calls to the same data source (when applicable)

  You can pass a second argument with the following options:

  - `:cache`: A map to use as the cache.

  - `:executor`: An implementation of `IExecutor` that will be used
   to run the fetches. Defaults to `urania.core/default-executor`.

   In Clojure you can pass a `java.util.concurrent.Executor` instance.

  - `:env`: An environment that will be passed to every data fetching
  function."
  ([ast]
   (run! ast run-defaults))
  ([ast opts]
   (prom/then (execute! ast opts) first)))

#?(:clj
   (defn run!!
     "Dereferences the the promise returned by `run!`, blocking until
     a result is available.

     Not implemented on ClojureScript."
     ([ast]
      (deref (run! ast {})))
     ([ast opts]
      (deref (run! ast opts)))))
