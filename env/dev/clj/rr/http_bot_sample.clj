(ns rr.http-bot-sample
  (:use ring.server.standalone
        [ring.middleware file-info file])
  (:require [compojure.core :refer [POST GET defroutes]]
            [compojure.route :refer [not-found resources]]
            [ring.util.response :as resp]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]))

(defonce server (atom nil))

(defroutes bot-routes
           (POST "/game/:game-id" [game-id]
             (resp/response {:response :ready}))
           (POST "/game/:game-id/:turn-number" {:keys [params body]}
             (println "NEW TURN!")
             (println (:game-id params))
             (println (:turn-number params))
             (println (:cards body))
             (resp/response {}))
           )

(def app
  (-> bot-routes
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

(defn get-handler [] #'app)

(defn start-server
  "used for starting the server in development mode from REPL"
  [& [port]]
  (let [port (if port (Integer/parseInt port) 9000)]
    (reset! server
            (serve (get-handler)
                   {:port port
                    :auto-reload? true
                    :join? false}))
    (println (str "Test bot available at -> http://localhost:" port))))

(defn stop-server []
  (.stop @server)
  (reset! server nil))

(comment
  (start-server)
  (stop-server)
  )