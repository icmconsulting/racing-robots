(ns rr.http-bot-sample
  (:use ring.server.standalone
        [ring.middleware file-info file])
  (:require [compojure.core :refer [POST GET PUT DELETE defroutes]]
            [compojure.route :refer [not-found resources]]
            [base64-clj.core :as base64]
            [ring.util.response :as resp]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]])
  (:import [java.io ByteArrayOutputStream]))

(defonce server (atom nil))

(defn avatar []
  (with-open [is (clojure.java.io/input-stream
                   (clojure.java.io/resource "public/images/testbot-avatar.png"))
              os (ByteArrayOutputStream.)]
    (clojure.java.io/copy is os)
    (str "data:image/png;base64," (String. (base64/encode-bytes (.toByteArray os)) "UTF-8"))))

(defroutes bot-routes
           (POST "/game/:game-id" {:keys [body]}
             (println "My ID:" (:player-id body))
             (resp/response {:response :ready
                             :profile {:name "Tester McGee"
                                       :robot-name "Testbot 3000"
                                       :avatar (avatar)}}))

           (DELETE "/game/:game-id" {:keys [body]}
             (println body)
             (resp/response {:response :YAY!}))

           (POST "/game/:game-id/:turn-number" {:keys [params body]}
             (println "NEW TURN!")
             (println (:game-id params))
             (println (:turn-number params))
             (println (:cards body))
             (println (:num-registers body))
             (let [cards (:cards body)]
               (resp/response {:registers (->> cards shuffle (take 5))
                               :powering-down false})))

           (PUT "/game/:game-id/:turn-number" {:keys [params body]}
                (println "COMPLETE TURN!")
                (println (:turn-number params))
                (println (:other-players body))
                (println (:available-responses body))
                (resp/response {:response :no-action})))

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