(ns urania.runner
  (:require [clojure.string :as str]
            [cljs.test :as test]
            [urania.core-spec]))

(enable-console-print!)

(defn main []
  (test/run-tests (test/empty-env)
                  'urania.core-spec))

(set! *main-cli-fn* main)
