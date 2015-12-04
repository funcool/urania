(ns urania.cats-spec
  #?(:clj
     (:require [clojure.test :refer (deftest is)]
               [urania.core :as u]
               [promesa.core :as prom]
               [cats.core :as m]
               [cats.context :as ctx])
     :cljs
     (:require [cljs.test :refer-macros (deftest is async)]
               [promesa.core :as prom]
               [urania.core :as u]
               [cats.core :as m]
               [cats.context :as ctx :include-macros true]))
  (:refer-clojure :exclude (run!)))

(defrecord DList [size]
  u/DataSource
  (fetch [_] (prom/resolved (range size)))
  u/LabeledSource
  (resource-id [_] #?(:clj size :cljs [:DList size])))

(defrecord Single [seed]
  u/DataSource
  (fetch [_] (prom/resolved seed))
  u/LabeledSource
  (resource-id [_] #?(:clj seed :cljs [:Single seed])))

(deftest cats-api
  (is (satisfies? u/AST (m/fmap count (u/value (range 10)))))
  (is (satisfies? u/AST
                  (ctx/with-context u/ast-monad
                    (m/fmap count (DList. 10)))))
  (is (satisfies? u/AST
                  (ctx/with-context u/ast-monad
                    (m/bind (Single. 10) (fn [num] (Single. (inc num))))))))

(defn assert-ast [expected ast-factory]
  #?(:clj (is (= expected (u/run!! (ast-factory))))
     :cljs (async done (prom/then (u/run! (ast-factory)) (fn [r] (is (= expected r)) (done))))))

(deftest runner-macros
  (assert-ast 10 (fn [] (m/fmap count (DList. 10))))
  (assert-ast 15 (fn [] (m/bind (Single. 10) (fn [num] (Single. (+ 5 num)))))))

#?(:clj
   (deftest cats-syntax
     (assert-ast 30 (fn [] (m/mlet [x (DList. 5)
                                    y (DList. 10)
                                    z (Single. 15)]
                                   (m/return (+ (count x) (count y) z)))))))
