(defproject rr "1.0.6-SNAPSHOT"
  :description "Racin' robots"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.9.0-alpha12"]
                 [com.taoensso/timbre "4.7.4"]
                 [ring-server "0.4.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [reagent "0.6.0" :exclusions [cljsjs/react cljsjs/react-dom]]
                 [reagent-forms "0.5.26"]
                 [reagent-utils "0.2.0"]
                 [cljsjs/react-bootstrap "0.30.2-0" :exclusions [cljsjs/react]]
                 [camel-snake-kebab "0.4.0"]
                 [ring "1.5.0"]
                 [ring/ring-defaults "0.2.1"]
                 [ring/ring-json "0.4.0" :exclusions [cheshire]]
                 [metosin/scjsv "0.4.0"]
                 [ring-transit "0.1.6"]
                 [compojure "1.5.1"]
                 [hiccup "1.0.5"]
                 [http-kit "2.2.0"]
                 [com.spotify/docker-client "6.1.0" :exclusions [com.google.guava/guava]]
                 [amazonica "0.3.77" :exclusions [com.google.guava/guava]]
                 [com.google.guava/guava "19.0"]
                 [cljs-ajax "0.5.8"]
                 [org.clojure/core.async "0.2.395"]
                 [base64-clj "0.1.1"]
                 [alandipert/enduro "1.2.0"]
                 [yogthos/config "0.8"]
                 [com.rpl/specter "0.13.1"]
                 [org.clojure/clojurescript "1.9.293" :scope "provided"]
                 [secretary "1.2.3"]
                 [venantius/accountant "0.1.7"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/test.check "0.9.0" :scope "test"]]

  :plugins [[lein-environ "1.0.2"]
            [lein-cljsbuild "1.1.4"]
            [icm-consulting/lein-less "1.7.6-SNAPSHOT"]
            [lein-asset-minifier "0.2.7"
             :exclusions [org.clojure/clojure]]]

  :ring {:handler rr.handler/app
         :uberwar-name "rr.war"}

  :less {:source-paths ["src/less/rr.less"]
         :target-path "resources/public/css"
         :clean-path "resources/public/css/rr.css"}

  :min-lein-version "2.5.0"

  :uberjar-name "rr.jar"

  :main rr.server

  :clean-targets ^{:protect false}
  [:target-path
   [:cljsbuild :builds :app :compiler :output-dir]
   [:cljsbuild :builds :app :compiler :output-to]]

  :source-paths ["src/clj" "src/cljc"]
  :test-paths ["test/clj"]
  :resource-paths ["resources" "target/cljsbuild"]

  :minify-assets
  {:assets
   {"resources/public/css/rr.min.css" "resources/public/css/rr.css"}}

  :cljsbuild
  {:builds {:min
            {:source-paths ["src/cljs" "src/cljc" "env/prod/cljs"]
             :compiler
                           {:output-to     "target/cljsbuild/public/js/app.js"
                            :output-dir    "target/uberjar"
                            :optimizations :advanced
                            :pretty-print  false
                            :externs ["src/js/externs/react.js"
                                      "src/js/externs/react-konva.js"
                                      "src/js/externs/data.js"]
                            :foreign-libs  [{:file     "src/js/konva/react-konva.bundle.js"
                                             :provides ["cljsjs.react.dom"
                                                        "cljsjs.react.dom"
                                                        "cljsjs.react"
                                                        "react-konva.core"]
                                             :requires ["konva.core"]}
                                            {:file     "src/js/konva/konva.js"
                                             :provides ["konva.core"]}]}}
            :app
            {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
             :compiler
                           {:main          "rr.dev"
                            :asset-path    "/js/out"
                            :output-to     "target/cljsbuild/public/js/app.js"
                            :output-dir    "target/cljsbuild/public/js/out"
                            :source-map    true
                            :optimizations :none
                            :pretty-print  true
                            :foreign-libs  [{:file     "src/js/konva/react-konva.bundle.js"
                                             :provides ["cljsjs.react.dom"
                                                        "cljsjs.react.dom"
                                                        "cljsjs.react"
                                                        "react-konva.core"]
                                             :requires ["konva.core"]}
                                            {:file     "src/js/konva/konva.js"
                                             :provides ["konva.core"]}]}}
            :test
            {:source-paths ["src/cljs" "src/cljc" "test/cljs"]
             :compiler     {:main          rr.doo-runner
                            :asset-path    "/js/out"
                            :output-to     "target/test.js"
                            :output-dir    "target/cljstest/public/js/out"
                            :optimizations :whitespace
                            :pretty-print  true
                            :foreign-libs [{:file     "src/js/konva/react-konva.bundle.js"
                                            :provides ["cljsjs.react.dom"
                                                       "cljsjs.react.dom"
                                                       "cljsjs.react"
                                                       "react-konva.core"]
                                            :requires ["konva.core"]}
                                           {:file     "src/js/konva/konva.js"
                                            :provides ["konva.core"]}]}}

            :devcards
            {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
             :figwheel     {:devcards true}
             :compiler     {:main                 "rr.cards"
                            :asset-path           "js/devcards_out"
                            :output-to            "target/cljsbuild/public/js/app_devcards.js"
                            :output-dir           "target/cljsbuild/public/js/devcards_out"
                            :source-map-timestamp true
                            :optimizations        :none
                            :pretty-print         true
                            :foreign-libs [{:file     "src/js/konva/react-konva.bundle.js"
                                            :provides ["cljsjs.react.dom"
                                                       "cljsjs.react.dom"
                                                       "cljsjs.react"
                                                       "react-konva.core"]
                                            :requires ["konva.core"]}
                                           {:file     "src/js/konva/konva.js"
                                            :provides ["konva.core"]}]}}
            }
   }


  :figwheel
  {:http-server-root "public"
   :server-port 3449
   :nrepl-port 7002
   :nrepl-middleware ["cemerick.piggieback/wrap-cljs-repl"
                      ]
   :css-dirs ["resources/public/css"]
   :ring-handler rr.handler/app}



  :profiles {:dev {:repl-options {:init-ns rr.repl}

                   :dependencies [[ring/ring-mock "0.3.0"]
                                  [ring/ring-devel "1.5.0"]
                                  [prone "1.1.2"]
                                  [figwheel-sidecar "0.5.8"]
                                  [org.clojure/tools.nrepl "0.2.12"]
                                  [com.cemerick/piggieback "0.2.2-SNAPSHOT"]
                                  [lein-doo "0.1.7"]
                                  [devcards "0.2.2" :exclusions [cljsjs/react cljsjs/react-dom]]
                                  [pjstadig/humane-test-output "0.8.1"]]

                   :source-paths ["env/dev/clj"]
                   :plugins [[lein-figwheel "0.5.8"]
                             [lein-doo "0.1.7"]]

                   :injections [(require 'pjstadig.humane-test-output)
                                (pjstadig.humane-test-output/activate!)]

                   :env {:dev true}}
             :tournament {:env {:mode "tournament"}}

             :uberjar {:hooks [minify-assets.plugin/hooks]
                       :source-paths ["env/prod/clj"]
                       :prep-tasks ["compile" ["cljsbuild" "once" "min"]]
                       :env {:production true}
                       :aot :all
                       :omit-source true}}

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version"
                   "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["uberjar"]
                  ["change" "version"
                   "leiningen.release/bump-version" "patch"]
                  ["vcs" "commit"]])
