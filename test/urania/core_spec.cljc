(ns urania.core-spec
  (:require [clojure.test :refer [deftest is testing are] :refer-macros [async are]]
            [promesa.core :as prom]
            [urania.core :as u]))

(deftype DList [size]
  u/DataSource
  (-identity [_] size)
  (-fetch [_ _] (prom/resolved (range size))))

(deftype DListFail [size]
  u/DataSource
  (-identity [_] size)
  (-fetch [_ _] (prom/rejected (ex-info "Invalid size" {:size size}))))

(deftype SingleItem [seed]
  u/DataSource
  (-identity [_] seed)
  (-fetch [_ _] (prom/resolved seed)))

(defn reified-single-item [seed]
  ^{:type 'ReifiedSingleItem}
   (reify u/DataSource
     (-identity [_] seed)
     (-fetch [_ _] (prom/resolved seed))))

(defn metadata-single-item [seed]
  ^{:type 'MetaSingleItem
    `u/-identity (fn [_] seed)
    `u/-fetch (fn [_ _] (prom/resolved seed))}
  {})

(deftype Pair [seed]
  u/DataSource
  (-identity [_] seed)
  (-fetch [_ _] (prom/resolved [seed seed])))

(defn- mk-pair [seed] (Pair. seed))

(defn- sum-pair [[a b]] (+ a b))

(defn- id [v] (u/value v))

(defn- assert-ast
  ([expected ast]
   (assert-ast expected ast nil {}))
  ([expected ast callback]
   (assert-ast expected ast callback {}))
  ([expected ast callback opts]
   #?(:clj
      (is (= expected (u/run!! ast opts)))
      :cljs
      (async done (prom/then (u/run! ast opts)
                             (fn [r]
                               (is (= expected r))
                               (when callback (callback))
                               (done)))))))

(defn- assert-err
  ([rx ast] (assert-err rx ast nil))
  ([rx ast callback]
   #?(:clj
      (try
        (u/run!! ast)
      (catch Exception e
        (is (re-find rx (.getMessage e)))))
      :cljs
      (async done (prom/catch (u/run! ast)
                              (fn [r]
                                (is (re-find rx (ex-message r)))
                                (when callback (callback))
                                (done)))))))

(deftest datasource-ast
  #?(:clj (is (= 10 (count (u/run!! (DList. 10))))))
  #?(:clj (is (= 20 (count (u/run!! (DList. 20))))))
  (assert-ast 30 (u/map count (DList. 30)))
  (assert-ast 40 (u/map inc (u/map count (DList. 39))))
  (assert-ast 50 (u/map count (u/map concat (DList. 30) (DList. 20))))
  (assert-ast 42 (u/mapcat id (SingleItem. 42)))
  (assert-ast 42 (u/mapcat id (reified-single-item 42)))
  (assert-ast 42 (u/mapcat id (metadata-single-item 42)))
  (assert-ast 42 (u/mapcat id (u/value 42)))
  (assert-ast [15 15] (u/mapcat mk-pair (SingleItem. 15)))
  (assert-ast [15 15] (u/mapcat mk-pair (reified-single-item 15)))
  (assert-ast [15 15] (u/mapcat mk-pair (metadata-single-item 15)))
  (assert-ast [15 15] (u/mapcat mk-pair (u/value 15)))
  (assert-ast 60 (u/map sum-pair (u/mapcat mk-pair (SingleItem. 30))))
  (assert-ast 60 (u/map sum-pair (u/mapcat mk-pair (reified-single-item 30))))
  (assert-ast 60 (u/map sum-pair (u/mapcat mk-pair (metadata-single-item 30))))
  (assert-ast 60 (u/map sum-pair (u/mapcat mk-pair (u/value 30)))))

(deftest error-propagation
  (assert-err #"Invalid size"
              (u/map concat
                    (DList. 10)
                    (DListFail. 30)
                    (DList. 10))))

(deftest higher-level-api
  (assert-ast [0 1] (u/collect [(SingleItem. 0) (SingleItem. 1)]))
  (assert-ast [0 1] (u/collect [(reified-single-item 0) (reified-single-item 1)]))
  (assert-ast [0 1] (u/collect [(metadata-single-item 0) (metadata-single-item 1)]))
  (assert-ast [] (u/collect []))
  (assert-ast [[0 0] [1 1]] (u/traverse mk-pair (DList. 2)))
  (assert-ast [] (u/traverse mk-pair (DList. 0))))

(defn- recur-next [seed]
  (if (= 5 seed)
    (u/value seed)
    (u/mapcat recur-next (SingleItem. (inc seed)))))

(deftest recur-with-value
  (assert-ast 10 (u/value 10))
  (assert-ast 5 (u/mapcat recur-next (SingleItem. 0))))

(defn- assert-failed? [f]
  (is (thrown? #?(:clj AssertionError :cljs js/Error) (f))))

(deftest value-from-ast
  (assert-failed? #(u/value (SingleItem. 0)))
  (assert-failed? #(u/value (u/map inc (u/value 0)))))

;; attention! never do such mutations within "fetch" in real code
(deftype Trackable [tracker seed]
  u/DataSource
  (-identity [_] seed)
  (-fetch [_ _]
    (swap! tracker inc)
    (prom/resolved seed)))

(defn metadata-trackable [tracker seed]
  ^{:type 'MetaTrackable
    `u/-identity (fn [_] seed)
    `u/-fetch (fn [_ _]
                (swap! tracker inc)
                (prom/resolved seed))}
  {})

(defn reified-trackable [tracker seed]
  ^{:type 'ReifiedTrackable}
   (reify u/DataSource
     (-identity [_] seed)
     (-fetch [_ _]
       (swap! tracker inc)
       (prom/resolved seed))))

;; caching

#?(:clj
   (deftest prepopulated-cache
     (are [f]  (let [t (atom 0)
                     t10 (f t 10)
                     t20 (f t 20)
                     cache {(u/resource-name t10) {(u/cache-id t10) 10
                                                   (u/cache-id t20) 20}}]
                 (is (= 40
                        (u/run!! (u/map + t10 t10 t20) {:cache cache})))
                 (is (= 0 @t)))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)

     (are [f] (let [t (atom 0)
                    t10 (f t 10)
                    t20 (f t 20)
                    cache {(u/resource-name t10) {(u/cache-id t10) 10
                                                  (u/cache-id t20) 20}}]
                (is (= [30 {(u/resource-name t10) {(u/cache-id t10) 10
                                                   (u/cache-id t20) 20}}]
                       (deref (u/execute! (u/map + t10 t20) {:cache cache}))))
                (is (= 0 @t)))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable))

   :cljs
   (deftest prepopulated-cache
     (are [f] (let [t     (atom 0)
                    t10   (f t 10)
                    t20   (f t 20)
                    cache {(u/resource-name t10) {(u/cache-id t10) 10
                                                  (u/cache-id t20) 20}}]
                (assert-ast 40
                            (u/map + t10 t10 t20)
                            (fn [] (is (= 0 @t)))
                            {:cache cache}))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)))

#?(:clj
   (deftest caching
     (are [f] (let [t (atom 0)]
                (assert-ast 40
                            (u/map + (f t 10) (f t 10) (f t 20)))
                (is (= 2 @t)))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)
     (are [f] (let [t1 (atom 0)]
                (assert-ast 400
                            (u/map + (f t1 100) (f t1 100) (f t1 200)))
                (is (= 2 @t1)))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable))

   :cljs
   (deftest caching
     (are [f] (let [t (atom 0)]
                (assert-ast 40
                            (u/map + (f t 10) (f t 10) (f t 20))
                            (fn [] (is (= 2 @t)))))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)
     (are [f] (let [t1 (atom 0)]
                (assert-ast 400
                            (u/map + (f t1 100) (f t1 100) (f t1 200))
                            (fn [] (is (= 2 @t1)))))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)))

#?(:clj
   (deftest caching-multiple-levels
     (are [f] (let [t4 (atom 0)]
                (assert-ast 1400 (u/map +
                                        (f t4 500)
                                        (u/map (fn [[a b]] (+ a b))
                                               (u/collect [(f t4 400) (f t4 500)]))))
                (is (= 2 @t4)))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)

     (are [f] (let [t4 (atom 0)]
                (assert-ast 100 (u/map +
                                       (f t4 50)
                                       (u/mapcat
                                        (fn [n] (f t4 n))
                                        (f t4 50))))
                (is (= 1 @t4)))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)

     (are [f] (let [t4 (atom 0)]
                (assert-ast 100 (u/map +
                                       (f t4 50)
                                       (u/mapcat
                                        (fn [n] (u/mapcat
                                                 (fn [m] (f t4 m))
                                                 (f t4 n)))
                                        (f t4 50))))
                (is (= 1 @t4)))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)))

#?(:cljs
   (deftest caching-multiple-levels
     (are [f] (let [t3 (atom 0)]
                (assert-ast 140 (u/map +
                                       (f t3 50)
                                       (u/map (fn [[a b]] (+ a b))
                                              (u/collect [(f t3 40) (f t3 50)])))
                            (fn [] (is (= 2 @t3)))))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)


     (are [f] (let [t3 (atom 0)]
                (assert-ast 100 (u/map +
                                       (f t3 50)
                                       (u/mapcat
                                        (fn [n] (f t3 n))
                                        (f t3 50)))
                            (fn [] (is (= 1 @t3)))))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)

     (are [f] (let [t3 (atom 0)]
                (assert-ast 100 (u/map +
                                       (f t3 50)
                                       (u/mapcat
                                        (fn [n] (u/mapcat
                                                 (fn [m] (f t3 m))
                                                 (f t3 n)))
                                        (f t3 50)))
                            (fn [] (is (= 1 @t3)))))
       (fn [t seed] (Trackable. t seed))
       reified-trackable
       metadata-trackable)))

;; batching

(defrecord BatchedTrackable [tracker seed]
  u/DataSource
  (-identity [_] seed)
  (-fetch [_ _]
    (swap! tracker inc)
    (prom/resolved seed))

  u/BatchedSource
  (-fetch-multi [_ trackables _]
    (let [seeds (cons seed (map :seed trackables))]
      (swap! tracker inc)
      (prom/resolved (zipmap seeds seeds)))))

(defn metadata-batched-trackable [tracker seed]
  ^{:type           'MetaBatchedTrackable
    `u/-identity    (fn [_] seed)
    `u/-fetch       (fn [_ _]
                      (swap! tracker inc)
                      (prom/resolved seed))
    `u/-fetch-multi (fn [_ trackables _]
                      (let [seeds (cons seed (map :seed trackables))]
                        (swap! tracker inc)
                        (prom/resolved (zipmap seeds seeds))))}
  {:seed seed})

(defn reified-batched-trackable [tracker seed]
  ^{:type 'ReifiedBatchedTrackable
    :seed seed} ;; the seed is used in -fetch-multi
   (reify
     u/DataSource
     (-identity [_] seed)
     (-fetch [_ _]
       (swap! tracker inc)
       (prom/resolved seed))
     u/BatchedSource
    (u/-fetch-multi [_ trackables _]
      (let [seeds (cons seed (map (comp :seed meta) trackables))]
        (swap! tracker inc)
        (prom/resolved (zipmap seeds seeds))))))

#?(:clj
   (deftest batching
     (are [f] (let [t (atom 0)]
                (assert-ast 40
                            (u/map + (f t 10) (f t 10) (f t 20)))
                (is (= 1 @t)))
       (fn [t seed] (BatchedTrackable. t seed))
       metadata-batched-trackable
       reified-batched-trackable))

   :cljs
   (deftest batching
     (are [f] (let [t (atom 0)]
                (assert-ast 40
                            (u/map + (f t 10) (f t 10) (f t 20))
                            (fn [] (is (= 1 @t)))))
       (fn [t seed] (BatchedTrackable. t seed))
       metadata-batched-trackable
       reified-batched-trackable)

     (are [f] (let [t1 (atom 0)]
                (assert-ast 400
                            (u/map + (f t1 100) (f t1 100) (f t1 200))
                            (fn [] (is (= 1 @t1)))))
       (fn [t seed] (BatchedTrackable. t seed))
       metadata-batched-trackable
       reified-batched-trackable)))

#?(:clj
   (deftest batching-multiple-levels
     (are [f] (let [t3 (atom 0)]
                (assert-ast 140 (u/map +
                                       (f t3 50)
                                       (u/map (fn [[a b]] (+ a b))
                                              (u/collect [(f t3 40) (f t3 50)]))))
                (is (= 1 @t3)))
       (fn [t seed] (BatchedTrackable. t seed))
       metadata-batched-trackable
       reified-batched-trackable)
     (are [f] (let [t4 (atom 0)]
                (assert-ast 1400 (u/map +
                                        (f t4 500)
                                        (u/map (fn [[a b]] (+ a b))
                                               (u/collect [(f t4 400) (f t4 500)]))))
                (is (= 1 @t4)))
       (fn [t seed] (BatchedTrackable. t seed))
       metadata-batched-trackable
       reified-batched-trackable)))

#?(:cljs
   (deftest batching-multiple-levels
     (are [f] (let [t3 (atom 0)]
                (assert-ast 140 (u/map +
                                       (f t3 50)
                                       (u/map (fn [[a b]] (+ a b))
                                              (u/collect [(f t3 40) (f t3 50)])))
                            (fn [] (is (= 1 @t3)))))
       (fn [t seed] (BatchedTrackable. t seed))
       metadata-batched-trackable
       reified-batched-trackable)))

;; executors

#?(:clj
   (deftest accepts-any-java-util-concurrent-executor
     (is (= 42 (u/run!! (u/map + (SingleItem. 21) (SingleItem. 21))
                        {:executor (java.util.concurrent.Executors/newFixedThreadPool 2)})))))

(def sync-executor
  (reify u/IExecutor
    (-execute [_ task]
      (task))))

#?(:clj
   (deftest accepts-a-custom-executor-implementation
     (is (= 42 (u/run!! (u/map + (SingleItem. 21) (SingleItem. 21))
                        {:executor sync-executor}))))
   :cljs
   (deftest accepts-a-custom-executor-implementation
     (assert-ast 42
                 (u/map + (SingleItem. 21) (SingleItem. 21))
                 identity
                 {:executor sync-executor})))

;; environment

(defrecord Environment [id]
  u/DataSource
  (-identity [_] id)
  (-fetch [_ env] (prom/resolved env))

  u/BatchedSource
  (-fetch-multi [_ envs env]
    (let [ids (cons id (map :id envs))]
      (prom/resolved (zipmap ids (map vector ids (repeat env)))))))

#?(:clj
   (deftest env-is-passed-to-fetch
     (is (= :the-environment
            (u/run!! (Environment. 42)
                     {:env :the-environment}))))
   :cljs
   (deftest env-is-passed-to-fetch
     (assert-ast :the-environment
                 (Environment. 42)
                 identity
                 {:env :the-environment})))

#?(:clj
   (deftest env-is-passed-to-fetch-multi
     (is (= [[42 :the-environment] [99 :the-environment]]
            (u/run!! (u/collect [(Environment. 42) (Environment. 99)])
                     {:env :the-environment}))))
   :cljs
   (deftest env-is-passed-to-fetch-multi
     (assert-ast [[42 :the-environment] [99 :the-environment]]
                 (u/collect [(Environment. 42) (Environment. 99)])
                 identity
                 {:env :the-environment})))

(deftest satisfies?-test
  (testing "reified type"
    (let [example (reify u/IExecutor
                    (-execute [_ task]
                      (task)))]
      (is (true?  (u/satisfies? u/IExecutor  example)))
      (is (false? (u/satisfies? u/DataSource example)))))

  (testing "metadata"
    (let [example (with-meta {} {`u/-execute (fn [_ task] (task))})]
      (is (true?  (u/satisfies? u/IExecutor  example)))
      (is (false? (u/satisfies? u/DataSource example))))))
