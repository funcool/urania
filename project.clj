(defproject funcool/urania "0.1.1"
  :description "Elegant and Efficient remote data access for Clojure(Script)"
  :url "https://github.com/funcool/urania"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"
            :distribution :repo}
  :global-vars {*warn-on-reflection* false}
  :dependencies [[funcool/promesa "4.0.2" :scope "provided"]]

  :test-paths ["test"]

  :cljsbuild {:test-commands {"test" ["node" "output/tests.js"]}
              :builds [{:id "test"
                        :source-paths ["src" "test"]
                        :notify-command ["node" "output/tests.js"]
                        :compiler {:output-to "output/tests.js"
                                   :output-dir "output"
                                   :source-map true
                                   :static-fns true
                                   :cache-analysis false
                                   :main urania.runner
                                   :optimizations :none
                                   :target :nodejs
                                   :pretty-print true}}]}

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  [org.clojure/clojurescript "1.10.597"]
                                  [funcool/promesa "4.0.2"]
                                  [datascript "0.13.2"]]
                   :plugins [[lein-cljsbuild "1.1.7"]]}})
