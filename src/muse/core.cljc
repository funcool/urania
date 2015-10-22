(ns muse.core
  #?(:cljs (:require-macros [muse.core :refer (run!)]
                            [cats.context :refer (with-context)])
     :clj (:require [promissum.core :as prom]
                    [clojure.string :as s]
                    [cats.core :as m]
                    [cats.context :as ctx :refer [with-context]]
                    [cats.protocols :as proto]))
  #?(:cljs (:require [promesa.core :as prom]
                     [clojure.string :as s]
                     [cats.core :as m]
                     [cats.context :as ctx]
                     [cats.protocols :as proto]))
  (:refer-clojure :exclude (run!)))


(declare fmap)
(declare flat-map)
(declare value)

(def ast-monad
  (reify
    proto/Context
    (-get-level [_] ctx/+level-default+)

    proto/Functor
    (-fmap [_ f mv] (fmap f mv))

    proto/Monad
    (-mreturn [_ v] (value v))
    (-mbind [_ mv f] (flat-map f mv))))

(defprotocol DataSource
  "Defines fetch method for the concrete data source. Relies on core.async
   channel as a result value for fetch call (to return immediately to the
   calling thread and perform fetch asynchronously). If defrecord is used
   to define data source, name of record class will be used to batch fetches
   from the same round of execution as well as to cache previous results
   and sent fetch requests. Use LabeledSource protocol when using reify to
   build data source instance.

   See example here: https://github.com/funcool/muse/blob/master/docs/sql.md"
  (fetch [this]))

(defprotocol LabeledSource
  "The id of DataSource instance, used in cache, tracing and stat.
   If not specified library will use (:id data-source) as an identifier.
   In order to redefine resource-name you should return seq of 2 elements:
   '(name id) as a result of resource-id call"
  (resource-id [this]))

(defprotocol BatchedSource
  "Group few data fetches into a single request to remote source (i.e.
   Redis MGET or SQL SELECT .. IN ..). Return channel and write to it
   map from ID to generic fetch response (as it was made without batching).

   See example here: https://github.com/funcool/muse/blob/master/docs/sql.md"
  (fetch-multi [this resources]))

(defn- pair-name-id? [id]
  (and (sequential? id) (= 2 (count id))))

(defn- labeled-resource-name [v]
  (when (satisfies? LabeledSource v)
    (let [id (resource-id v)]
      (when (pair-name-id? id)
        (first id)))))

(defn- resource-name [v]
  (let [value (or (labeled-resource-name v)
                  #?(:clj (.getName (.getClass v))))]
    (assert (not (nil? value))
            (str "Resource name is not identifiable: " v
                 " Please, use record definition (for automatic resolve)"
                 " or LabeledSource protocol (to specify it manually)"))
    value))

(defprotocol MuseAST
  (childs [this])
  (inject [this env])
  (done? [this]))

(defprotocol ComposedAST
  (compose-ast [this f]))

(defrecord MuseDone [value]
  proto/Contextual
  (-get-context [_] ast-monad)

  ComposedAST
  (compose-ast [_ f2] (MuseDone. (f2 value)))

  MuseAST
  (childs [_] nil)
  (done? [_] true)
  (inject [this _] this))

(defn labeled-cache-id
  [res]
  (let [id (resource-id res)]
    (if (pair-name-id? id) (second id) id)))

(defn cache-id
  [res]
  (let [id (if (satisfies? LabeledSource res)
             (labeled-cache-id res)
             (:id res))]
    (assert (not (nil? id))
            (str "Resource is not identifiable: " res
                 " Please, use LabeledSource protocol or record with :id key"))
    id))

(defn cache-path
  [res]
  [(resource-name res) (cache-id res)])

(defn cached-or [env res]
  (let [cached (get-in env (cons :cache (cache-path res)) ::not-found)]
    (if (= ::not-found cached)
      res
      (MuseDone. cached))))

(defn inject-into [env node]
  (if (satisfies? DataSource node)
    (cached-or env node)
    (inject node env)))

(defn print-node
  [node]
  (if (satisfies? DataSource node)
    (str (resource-name node) "[" (cache-id node) "]")
    (with-out-str (print node))))

(defn print-childs
  [nodes]
  (s/join " " (map print-node nodes)))

(deftype MuseMap [f values]
  proto/Contextual
  (-get-context [_] ast-monad)

  ComposedAST
  (compose-ast [_ f2] (MuseMap. (comp f2 f) values))

  MuseAST
  (childs [_] values)
  (done? [_] false)
  (inject [_ env]
    (let [next (map (partial inject-into env) values)]
      (if (= (count next) (count (filter done? next)))
        (MuseDone. (apply f (map :value next)))
        (MuseMap. f next))))

  Object
  (toString [_] (str "(" f " " (print-childs values) ")")))

(defn- ast?
  [ast]
  (or (satisfies? MuseAST ast)
      (satisfies? DataSource ast)))

(defn assert-ast!
  [ast]
  (assert (ast? ast)))

(deftype MuseFlatMap [f values]
  proto/Contextual
  (-get-context [_] ast-monad)

  MuseAST
  (childs [_] values)
  (done? [_] false)
  (inject [_ env]
    (let [next (map (partial inject-into env) values)]
      (if (= (count next) (count (filter done? next)))
        (let [result (apply f (map :value next))]
          ;; xxx: refactor to avoid dummy leaves creation
          (if (satisfies? DataSource result) (MuseMap. identity [result]) result))
        (MuseFlatMap. f next))))

  Object
  (toString [_] (str "(" f " " (print-childs values) ")")))

(deftype MuseValue [value]
  proto/Contextual
  (-get-context [_] ast-monad)

  ComposedAST
  (compose-ast [_ f2] (MuseMap. f2 [value]))

  MuseAST
  (childs [_] [value])
  (done? [_] false)
  (inject [_ env]
    (let [next (inject-into env value)]
      (if (done? next) (MuseDone. (:value next)) next)))

  Object
  (toString [_] (print-node value)))

(defn value
  [v]
  (assert (not (ast? v))
          (str "The value is already an AST: " v))
  (MuseDone. v))

(defn fmap
  [f muse & muses]
  (if (and (not (seq muses))
           (satisfies? ComposedAST muse))
    (compose-ast muse f)
    (MuseMap. f (cons muse muses))))

;; xxx: make it compatible with algo.generic
(defn flat-map
  [f muse & muses]
  (MuseFlatMap. f (cons muse muses)))

;; xxx: use macro instead for auto-partial function building
(def <$> fmap)
(defn >>= [muse f] (flat-map f muse))

(defn collect
  [muses]
  (if (seq muses)
    (apply (partial fmap vector) muses)
    (value [])))

(defn traverse
  [f muses]
  (flat-map #(collect (map f %)) muses))

(defn next-level
  [ast-node]
  (if (satisfies? DataSource ast-node)
    (list ast-node)
    (if-let [values (childs ast-node)]
      (mapcat next-level values)
      '())))

(defn fetch-cached
  [head tail]
  (let [all-res (->> tail
                     (cons head)
                     (group-by cache-id)
                     (map (fn [[_ v]] (first v))))]
    (prom/promise (fn [resolve]
                    (let [ids (map cache-id all-res)
                          fetch-promises (map fetch all-res)]
                      (with-context prom/promise-context
                        (prom/then (prom/all fetch-promises)
                                   (fn [fetch-results]
                                     (resolve (zipmap ids fetch-results))))))))))

(defn fetch-head
  [head]
  (prom/then (fetch head)
             (fn [res]
               {(cache-id head) res})))

(defn fetch-group*
  [head tail]
  (if (not (seq tail))
    (fetch-head head)
    (if (satisfies? BatchedSource head)
      (fetch-multi head tail)
      (fetch-cached head tail))))

(defn fetch-group
  [[resource-name [head & tail]]]
  (prom/then (fetch-group* head tail)
             (fn [resp]
               [resource-name resp])))

(defn interpret-ast*
 [ast-node cache resolve reject]
 (let [fetches (next-level ast-node)]
   (if (not (seq fetches))
     ;; xxx: should be MuseDone, assert & throw exception otherwise
     (if (done? ast-node)
       (resolve (:value ast-node))
       (recur (inject-into {:cache cache} ast-node) cache resolve reject))
     (let [by-type (group-by resource-name fetches)
           fetch-promises (map fetch-group by-type)]
       (with-context prom/promise-context
         (prom/branch (prom/all fetch-promises)
                      (fn [fetch-groups]
                        (let [next-cache (into cache fetch-groups)]
                          (interpret-ast* (inject-into {:cache next-cache} ast-node)
                                          next-cache
                                          resolve
                                          reject)))
                      reject))))))

#?(:clj
   (defn deferred []
     (let [p (prom/promise)
           resolver (partial prom/deliver p)]
       [p resolver resolver]))
   :cljs
   (defn deferred []
     (let [vresolve (volatile! nil)
           vreject (volatile! nil)
           p (prom/promise (fn [resolve reject]
                             (vreset! vresolve resolve)
                             (vreset! vreject reject)))]
       [p @vresolve @vreject])))

(defn interpret-ast
  [ast]
  (let [[p resolve reject] (deferred)]
    (interpret-ast* ast {} resolve reject)
    p))

#?(:clj
   (defmacro run!
     "Asynchronously executes the body, returning immediately to the
      calling thread. Rebuild body AST in order to:
      * fetch data sources async (when possible)
      * cache result of previously made fetches
      * batch calls to the same data source (when applicable)
      Returns a channel which will receive the result of
      the body when completed."
     [ast]
     `(with-context ast-monad (interpret-ast ~ast))))

#?(:clj
   (defmacro run!!
     "takes a val from the channel returned by (run! ast).
      Will block if nothing is available. Not available on
      ClojureScript."
     [ast]
     `(deref (run! ~ast))))
