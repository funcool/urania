(ns urania.core
  (:require [promesa.core :as prom]
            [clojure.string :as s])
  #?(:clj (:import java.util.concurrent.ForkJoinPool))
  (:refer-clojure :exclude (run!)))

(declare fmap)
(declare flat-map)
(declare value)

(defprotocol Executor
  (-submit [ex task]))

(defprotocol DataSource
  "Defines fetch method for the concrete data source. Relies on a promise
   as a result value for fetch call (to return immediately to the caller
   and perform fetch operations asynchronously). If defrecord is used
   to define data source, name of record class will be used to batch fetches
   from the same round of execution as well as to cache previous results
   and sent fetch requests. Use LabeledSource protocol when using reify to
   build data source instance.

   See example here: https://github.com/funcool/urania/blob/master/docs/sql.md"
  (-fetch [this]))

(defprotocol LabeledSource
  "The id of DataSource instance, used in cache, tracing and stat.
   If not specified library will use (:id data-source) as an identifier.
   In order to redefine resource-name you should return seq of 2 elements:
   '(name id) as a result of resource-id call"
  (-resource-id [this]))

(defprotocol BatchedSource
  "Group few data fetches into a single request to remote source (i.e.
   Redis MGET or SQL SELECT .. IN ..). Return promise and write to it
   map from ID to generic fetch response (as it was made without batching).

   See example here: https://github.com/funcool/urania/blob/master/docs/sql.md"
  (-fetch-multi [this resources]))

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

(defn- resource-name [v]
  (pr-str (type v)))

(defn- cache-id
  [res]
  (let [id (if (satisfies? LabeledSource res)
             (-resource-id res)
             (:id res))]
    (assert (not (nil? id))
            (str "Resource is not identifiable: " res
                 " Please, use LabeledSource protocol or record with :id key"))
    id))

(def ^:private cache-path (juxt resource-name cache-id))

(defn- cached-or [env res]
  (let [cache (get env :cache)
        cached (get-in cache (cache-path res) ::not-found)]
    (if (= ::not-found cached)
      res
      (Done. cached))))

(defn- inject-into [env node]
  (if (satisfies? DataSource node)
    (cached-or env node)
    (-inject node env)))

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
     (-submit executor #(prom/branch (-fetch muse) resolve reject)))))

(defn- run-fetch-multi
  [executor muse muses]
  (prom/promise
   (fn [resolve reject]
     (-submit executor #(prom/branch (-fetch-multi muse muses) resolve reject)))))

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
  (let [requests (next-level ast-node)]
    (if-not (seq requests)
      (if (-done? ast-node)
        (success! (:value ast-node))
        (let [next-ast (inject-into {:cache cache} ast-node)]
          (recur next-ast opts success! error!)))
      (let [requests-by-type (group-by resource-name requests)
            responses (map (partial fetch-resource executor) requests-by-type)]
        (prom/branch (prom/all responses)
                     (fn [results]
                       (let [next-cache (into cache results)
                             next-ast (inject-into {:cache next-cache} ast-node)
                             next-opts (assoc opts :cache next-cache)]
                         (interpret-ast next-ast next-opts success! error!)))
                     error!)))))

;; Public API

(def default-executor
  #?(:clj
     (reify Executor
       (-submit [_ task]
         (.submit (ForkJoinPool/commonPool) task)))
     :cljs
     (reify Executor
       (-submit [_ task]
         (task)))))

(def run-defaults {:cache {}
                   :executor default-executor})

(defn run!
  "Asynchronously executes the body, returning immediately to the
  calling thread. Rebuild body AST in order to:

   * fetch data sources asynchronously (when possible)
   * cache result of previously made fetches
   * batch calls to the same data source (when applicable)

  Returns a promise which will receive the result of
  the body when completed."
  ([ast]
   (run! ast run-defaults))
  ([ast opts]
   (prom/promise
    (fn [resolve reject]
      (interpret-ast ast (merge run-defaults opts) resolve reject)))))

#? (:clj
    (defmacro run!!
      "Dereferences the the promise returned by (run! ast).
      Will block if nothing is available. Not available on
      ClojureScript."
      [ast]
      `(deref (run! ~ast))))

