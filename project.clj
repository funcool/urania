(defproject funcool/muse "0.4.0"
  :description "A Clojure library that simplifies access to remote data (db, cache, http services)"
  :url "https://github.com/funcool/muse"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"
            :distribution :repo}
  :global-vars {*warn-on-reflection* false}
  :dependencies []
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
                                   :main muse.runner
                                   :optimizations :none
                                   :target :nodejs
                                   :pretty-print true}}]}

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/clojurescript "1.7.145"]
                                  [funcool/cats "1.2.0"]
                                  [funcool/promesa "0.6.0"]
                                  [datascript "0.13.2"]]
                   :plugins [[lein-cljsbuild "1.1.1"]]}})
