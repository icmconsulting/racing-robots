(ns rr.registration
  (:require [compojure.core :refer [GET POST defroutes context routes]]
            [taoensso.timbre :as timbre]
            [ring.util.response :as resp]
            [clojure.data.json :as json]
            [cognitect.transit :as transit]
            [config.core :refer [env]]
            [alandipert.enduro :as enduro]
            [camel-snake-kebab.core :refer :all]
            [clojure.core.async :as async :refer [go go-loop <! >!]]
            [ring.middleware.transit :refer [wrap-transit-response wrap-transit-body]]
            [rr.middleware :refer [wrap-middleware]]
            [rr.bots :as bots]
            [rr.boards :as boards]
            [rr.game :as game])
  (:import [java.util UUID]))

(def empty-registrations {:players {}})

(defonce registrations
  (if-let [reg-file (env :reg-file)]
    (enduro/file-atom empty-registrations reg-file)
    (enduro/mem-atom empty-registrations)))

(defn generate-registrations!
  [teams]
  (enduro/reset! registrations (zipmap (repeatedly (comp str #(UUID/randomUUID))) teams)))

;; TODO: generate player registrations from team entries
;; - Done via special page - enter in text area, 1 team player names per line
;; - Generates URLs - will slack manually to teams
;; TODO: players visit registration page, enters their submission details

(defn registration-id-routes
  [registration-id]
  (if-let [registration (get @registrations #spy/p registration-id)]
    (routes
      (GET "/" [] (resp/response {:registration (assoc registration :id registration-id)}))

      )
    (resp/not-found {})))

(defroutes registration-routes*
           (context "/:registration-id" [registration-id]
             (registration-id-routes registration-id)))

(def registration-routes
  (-> registration-routes*
      (wrap-transit-body {:keywords? false})
      (wrap-transit-response {:encoding :json :opts {}})))
