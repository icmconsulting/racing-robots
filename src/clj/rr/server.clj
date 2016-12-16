(ns rr.server
  (:require [rr.handler :refer [app tournament-mode?]]
            [config.core :refer [env]]
            [taoensso.timbre :refer [info]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.tools.nrepl.server :refer [start-server stop-server]])
  (:gen-class))

(defn start-web
  []
  (let [port (Integer/parseInt (or (env :port) "3000"))]
    (run-jetty app {:port port :join? false})
    (info "Server started on port " port)))

(defn maybe-start-nrepl
  []
  (when (tournament-mode?)
    (let [server (start-server)]
      (info "nrepl Server started on port " (:port server))
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (stop-server server)))))))

 (defn -main [& args]
   (start-web)
   (maybe-start-nrepl))
