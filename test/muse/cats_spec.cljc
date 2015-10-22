(ns muse.cats-spec
  #?(:clj
     (:require [clojure.test :refer (deftest is)]
               [muse.core :as muse]
               [promissum.core :as prom]
               [cats.core :as m]
               [cats.context :refer [with-context]])
     :cljs
     (:require [cljs.test :refer-macros (deftest is async)]
               [promesa.core :as prom]
               [muse.core :as muse]
               [cats.core :as m]))
  #?(:cljs (:require-macros [cats.context :refer (with-context)]))
  (:refer-clojure :exclude (run!)))

(defrecord DList [size]
  muse/DataSource
  (fetch [_] (prom/resolved (range size)))
  muse/LabeledSource
  (resource-id [_] #?(:clj size :cljs [:DList size])))

(defrecord Single [seed]
  muse/DataSource
  (fetch [_] (prom/resolved seed))
  muse/LabeledSource
  (resource-id [_] #?(:clj seed :cljs [:Single seed])))

(deftest cats-api
  (is (satisfies? muse/MuseAST (m/fmap count (muse/value (range 10)))))
  (is (satisfies? muse/MuseAST
                  (with-context muse/ast-monad
                    (m/fmap count (DList. 10)))))
  (is (satisfies? muse/MuseAST
                  (with-context muse/ast-monad
                    (m/bind (Single. 10) (fn [num] (Single. (inc num))))))))

(defn assert-ast [expected ast-factory]
  #?(:clj (is (= expected (muse/run!! (ast-factory))))
     :cljs (async done (prom/then (muse/run! (ast-factory)) (fn [r] (is (= expected r)) (done))))))

(deftest runner-macros
  #?(:clj (is (= 5 (deref (muse/run! (m/fmap count (DList. 5)))))))
  (assert-ast 10 (fn [] (m/fmap count (DList. 10))))
  (assert-ast 15 (fn [] (m/bind (Single. 10) (fn [num] (Single. (+ 5 num)))))))

#?(:clj
   (deftest cats-syntax
     (assert-ast 30 (fn [] (m/mlet [x (DList. 5)
                                    y (DList. 10)
                                    z (Single. 15)]
                                   (m/return (+ (count x) (count y) z)))))))
