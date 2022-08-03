(ns urania.runner
  (:require [cljs.test :as test]
            [urania.core-spec-test]))

(enable-console-print!)

(defn main []
  (test/run-tests (test/empty-env)
                  'urania.core-spec-test))

(set! *main-cli-fn* main)
