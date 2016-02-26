(ns urania.core
  (:require [promesa.core :as prom]
            [clojure.string :as s])
  #?(:clj
     (:import java.util.concurrent.ForkJoinPool
              java.util.concurrent.Executor))
  (:refer-clojure :exclude (run!)))

(defprotocol IExecutor
  "A policy for executing tasks."
  (-execute [ex task] "Perform a task."))

(defprotocol DataSource
  "A remote data source."
  (-identity [this]
    "Return an identifier for this data source.
    Used for caching, note that data Sources of different types are cached separately.")
  (-fetch [this]
    "Fetch this data source "))

(defprotocol BatchedSource
  "A remote data source that can be fetched in batches."
  (-fetch-multi [this resources]
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
    (let [next (map (partial inject-into env) values)]
      (if (= (count next) (count (filter -done? next)))
        (Done. (apply f (map :value next)))
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
    (let [next (map (partial inject-into env) values)]
      (if (every? -done? next)
        (let [result (apply f (map :value next))]
          ;; xxx: refactor to avoid dummy leaves creation
          (if (satisfies? DataSource result) (Map. identity [result]) result))
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
  [v]
  (assert (not (ast? v))
          (str "The value is already an AST: " v))
  (Done. v))

(defn fmap
  "Given a function and one or more data sources, return a new
  data source that will apply the given function to the results.
  When mapping over multiple data sources the results will be passed
  as positional arguments to the given function."
  [f muse & muses]
  (if (and (not (seq muses))
           (satisfies? ComposedAST muse))
    (-compose-ast muse f)
    (Map. f (cons muse muses))))

(defn flat-map
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
    (apply (partial fmap vector) muses)
    (value [])))

(defn traverse
  "Given a function and a collection of data sources, apply the function once
  to each data source and collect the resulting data source results into a data
  source with every result."
  [f muses]
  (flat-map #(collect (map f %)) muses))

;; Fetching

(defn- run-fetch
  [executor muse]
  (prom/promise
   (fn [resolve reject]
     (-execute executor #(prom/branch (-fetch muse) resolve reject)))))

(defn- run-fetch-multi
  [executor muse muses]
  (prom/promise
   (fn [resolve reject]
     (-execute executor #(prom/branch (-fetch-multi muse muses) resolve reject)))))

(defn- fetch-many-caching
  [executor sources]
  (let [ids (map cache-id sources)
        responses (map (partial run-fetch executor) sources)]
    (prom/then (prom/all responses) #(zipmap ids %))))

(defn- fetch-one-caching
  [executor source]
  (prom/then (run-fetch executor source)
             (fn [res]
               {(cache-id source) res})))

(defn- fetch-sources
  [executor [head & tail :as sources]]
  (if-not (seq tail)
    (fetch-one-caching executor head)
    (if (satisfies? BatchedSource head)
      (run-fetch-multi executor head tail)
      (fetch-many-caching executor sources))))

(defn- dedupe-sources
  [sources]
  (->> sources
       (group-by cache-id)
       vals
       (map first)))

(defn- fetch-resource
  [executor [resource-name sources]]
  (prom/then (fetch-sources executor (dedupe-sources sources))
             (fn [resp]
               [resource-name resp])))

;; AST interpreter

(defn- next-level
  [ast-node]
  (if (satisfies? DataSource ast-node)
    (list ast-node)
    (when-let [values (-children ast-node)]
      (mapcat next-level values))))

(defn- interpret-ast
  [ast-node {:keys [cache executor] :as opts} success! error!]
  (let [ast-node (inject-into opts ast-node)
        requests (next-level ast-node)]
    (if-not (seq requests)
      (if (-done? ast-node)
        (success! [(:value ast-node) cache])
        (recur ast-node opts success! error!))
      (let [requests-by-type (group-by resource-name requests)
            responses (map (partial fetch-resource executor) requests-by-type)]
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

   In Clojure you can pass a `java.util.concurrent.Executor` instance."
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

   In Clojure you can pass a `java.util.concurrent.Executor` instance."
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
