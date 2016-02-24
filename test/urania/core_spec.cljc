(ns urania.core-spec
  #?(:clj
     (:require [clojure.test :refer (deftest is)]
               [promesa.core :as prom]
               [urania.core :as u :refer (fmap flat-map)])
     :cljs
     (:require [cljs.test :refer-macros (deftest is async)]
               [promesa.core :as prom]
               [urania.core :as u :refer (fmap flat-map)])))

(defrecord DList [size]
  u/DataSource
  (-fetch [_] (prom/resolved (range size)))
  u/LabeledSource
  (-resource-id [_] size))

(defrecord DListFail [size]
  u/DataSource
  (-fetch [_] (prom/rejected (ex-info "Invalid size" {:size size})))
  u/LabeledSource
  (-resource-id [_] size))

(defrecord Single [seed]
  u/DataSource
  (-fetch [_] (prom/resolved seed))
  u/LabeledSource
  (-resource-id [_] seed))

(defrecord Pair [seed]
  u/DataSource
  (-fetch [_] (prom/resolved [seed seed]))
  u/LabeledSource
  (-resource-id [_] seed))

(defn- mk-pair [seed] (Pair. seed))

(defn- sum-pair [[a b]] (+ a b))

(defn- id [v] (u/value v))

(defn- assert-ast
  ([expected ast] (assert-ast expected ast nil))
  ([expected ast callback]
   #?(:clj
      (is (= expected (u/run!! ast)))
      :cljs
      (async done (prom/then (u/run! ast)
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
  (assert-ast 30 (fmap count (DList. 30)))
  (assert-ast 40 (fmap inc (fmap count (DList. 39))))
  (assert-ast 50 (fmap count (fmap concat (DList. 30) (DList. 20))))
  (assert-ast 42 (flat-map id (Single. 42)))
  (assert-ast 42 (flat-map id (u/value 42)))
  (assert-ast [15 15] (flat-map mk-pair (Single. 15)))
  (assert-ast [15 15] (flat-map mk-pair (u/value 15)))
  (assert-ast 60 (fmap sum-pair (flat-map mk-pair (Single. 30))))
  (assert-ast 60 (fmap sum-pair (flat-map mk-pair (u/value 30)))))

(deftest error-propagation
  (assert-err #"Invalid size"
              (fmap concat
                    (DList. 10)
                    (DListFail. 30)
                    (DList. 10))))

(deftest higher-level-api
  (assert-ast [0 1] (u/collect [(Single. 0) (Single. 1)]))
  (assert-ast [] (u/collect []))
  (assert-ast [[0 0] [1 1]] (u/traverse mk-pair (DList. 2)))
  (assert-ast [] (u/traverse mk-pair (DList. 0))))

(defn- recur-next [seed]
  (if (= 5 seed)
    (u/value seed)
    (flat-map recur-next (Single. (inc seed)))))

(deftest recur-with-value
  (assert-ast 10 (u/value 10))
  (assert-ast 5 (flat-map recur-next (Single. 0))))

(defn- assert-failed? [f]
  (is (thrown? #?(:clj AssertionError :cljs js/Error) (f))))

(deftest value-from-ast
  (assert-failed? #(u/value (Single. 0)))
  (assert-failed? #(u/value (fmap inc (u/value 0)))))

;; attention! never do such mutations within "fetch" in real code
(defrecord Trackable [tracker seed]
  u/DataSource
  (-fetch [_]
    (swap! tracker inc)
    (prom/resolved seed))
  u/LabeledSource
  (-resource-id [_] seed))

(defrecord TrackableId [tracker id]
  u/DataSource
  (-fetch [_]
    (swap! tracker inc)
    (prom/resolved id)))

;; caching

#?(:clj
   (deftest prepopulated-cache
     (let [t (atom 0)
           t10 (Trackable. t 10)
           t20 (Trackable. t 20)
           cache {(u/resource-name t10) {(u/cache-id t10) 10
                                         (u/cache-id t20) 20}}]
       (is (= 40
              (u/run!! (fmap + t10 t10 t20) {:cache cache})))
       (is (= 0 @t)))))

;; w explicit source labeling
#?(:clj
   (deftest caching-explicit-labels
     (let [t (atom 0)]
       (assert-ast 40
                   (fmap + (Trackable. t 10) (Trackable. t 10) (Trackable. t 20)))
       (is (= 2 @t)))
     (let [t1 (atom 0)]
       (assert-ast 400
                   (fmap + (Trackable. t1 100) (Trackable. t1 100) (Trackable. t1 200)))
       (is (= 2 @t1))))

   :cljs
   (deftest caching-explicit-labels
     (let [t (atom 0)]
       (assert-ast 40
                   (fmap + (Trackable. t 10) (Trackable. t 10) (Trackable. t 20))
                   (fn [] (is (= 2 @t)))))
     (let [t1 (atom 0)]
       (assert-ast 400
                   (fmap + (Trackable. t1 100) (Trackable. t1 100) (Trackable. t1 200))
                   (fn [] (is (= 2 @t1)))))))

;; w/o explicit source labeling
#?(:clj
   (deftest caching-implicit-labels
     (let [t2 (atom 0)]
       (assert-ast 100 (fmap * (TrackableId. t2 10) (TrackableId. t2 10)))
       (is (= 1 @t2))))
   :cljs
   (deftest caching-implicit-labels
     (let [t2 (atom 0)]
       (assert-ast 100
                   (fmap * (TrackableId. t2 10) (TrackableId. t2 10))
                   (fn [] (is (= 1 @t2)))))))

;; different tree branches/levels
#?(:clj
   (deftest caching-multiple-trees
     (let [t3 (atom 0)]
       (assert-ast 140 (fmap +
                             (Trackable. t3 50)
                             (fmap (fn [[a b]] (+ a b))
                                   (u/collect [(Trackable. t3 40) (Trackable. t3 50)]))))
       (is (= 2 @t3)))
     (let [t4 (atom 0)]
       (assert-ast 1400 (fmap +
                              (Trackable. t4 500)
                              (fmap (fn [[a b]] (+ a b))
                                    (u/collect [(Trackable. t4 400) (Trackable. t4 500)]))))
       (is (= 2 @t4)))))

#?(:cljs
   (deftest caching-multiple-trees
     (let [t3 (atom 0)]
       (assert-ast 140 (fmap +
                             (Trackable. t3 50)
                             (fmap (fn [[a b]] (+ a b))
                                   (u/collect [(Trackable. t3 40) (Trackable. t3 50)])))
                   (fn [] (is (= 2 @t3)))))))

;; batching

(defrecord BatchedTrackable [tracker seed]
  u/DataSource
  (-fetch [_]
    (swap! tracker inc)
    (prom/resolved seed))
  u/LabeledSource
  (-resource-id [_] seed)

  u/BatchedSource
  (-fetch-multi [_ trackables]
    (let [seeds (cons seed (map :seed trackables))]
      (swap! tracker inc)
      (prom/resolved (zipmap seeds seeds)))))

#?(:clj
   (deftest batching-explicit-labels
     (let [t (atom 0)]
       (assert-ast 40
                   (fmap + (BatchedTrackable. t 10) (BatchedTrackable. t 10) (BatchedTrackable. t 20)))
       (is (= 1 @t)))
     (let [t1 (atom 0)]
       (assert-ast 400
                   (fmap + (BatchedTrackable. t1 100) (BatchedTrackable. t1 100) (BatchedTrackable. t1 200)))
       (is (= 1 @t1))))

   :cljs
   (deftest batching-explicit-labels
     (let [t (atom 0)]
       (assert-ast 40
                   (fmap + (BatchedTrackable. t 10) (BatchedTrackable. t 10) (BatchedTrackable. t 20))
                   (fn [] (is (= 1 @t)))))
     (let [t1 (atom 0)]
       (assert-ast 400
                   (fmap + (BatchedTrackable. t1 100) (BatchedTrackable. t1 100) (BatchedTrackable. t1 200))
                   (fn [] (is (= 1 @t1)))))))

(defrecord BatchedTrackableId [tracker id]
  u/DataSource
  (-fetch [_]
    (swap! tracker inc)
    (prom/resolved id))

  u/BatchedSource
  (-fetch-multi [_ trackables]
    (let [ids (cons id (map :id trackables))]
      (swap! tracker inc)
      (prom/resolved (zipmap ids ids)))))

#?(:clj
   (deftest batching-implicit-labels
     (let [t2 (atom 0)]
       (assert-ast 100 (fmap * (BatchedTrackableId. t2 10) (BatchedTrackableId. t2 10)))
       (is (= 1 @t2))))
   :cljs
   (deftest batching-implicit-labels
     (let [t2 (atom 0)]
       (assert-ast 100
                   (fmap * (TrackableId. t2 10) (TrackableId. t2 10))
                   (fn [] (is (= 1 @t2)))))))

;; multiple trees

#?(:clj
   (deftest batching-multiple-trees
     (let [t3 (atom 0)]
       (assert-ast 140 (fmap +
                             (BatchedTrackable. t3 50)
                             (fmap (fn [[a b]] (+ a b))
                                   (u/collect [(BatchedTrackable. t3 40) (BatchedTrackable. t3 50)]))))
       (is (= 1 @t3)))
     (let [t4 (atom 0)]
       (assert-ast 1400 (fmap +
                              (BatchedTrackable. t4 500)
                              (fmap (fn [[a b]] (+ a b))
                                    (u/collect [(BatchedTrackable. t4 400) (BatchedTrackable. t4 500)]))))
       (is (= 1 @t4)))))

#?(:cljs
   (deftest batching-multiple-trees
     (let [t3 (atom 0)]
       (assert-ast 140 (fmap +
                             (BatchedTrackable. t3 50)
                             (fmap (fn [[a b]] (+ a b))
                                   (u/collect [(BatchedTrackable. t3 40) (BatchedTrackable. t3 50)])))
                   (fn [] (is (= 1 @t3)))))))

;; errors

(defrecord Country [iso-id]
  u/DataSource
  (-fetch [_] (prom/resolved {:regions [{:code 1} {:code 2} {:code 3}]})))

(defrecord Region [country-iso-id url-id]
  u/DataSource
  (-fetch [_] (prom/resolved (inc url-id))))

(deftest impossible-to-cache
  (assert-err #"Resource is not identifiable"
              (->> (Country. "es")
                   (u/fmap :regions)
                   (u/traverse #(Region. "es" (:code %))))))
