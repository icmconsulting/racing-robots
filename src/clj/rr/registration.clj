(ns rr.registration
  (:require [compojure.core :refer [GET POST defroutes]]
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
            [rr.game :as game]))

(def empty-registrations {:players {}})

(def registrations
  (if-let [reg-file (env :reg-file)]
    (enduro/file-atom empty-registrations reg-file)
    (enduro/mem-atom empty-registrations)))

;; TODO: generate player registrations from team entries
;; - Done via special page - enter in text area, 1 team player names per line
;; - Generates URLs - will slack manually to teams
;; TODO: players visit registration page, enters their submission details


(defroutes registration-routes*


           )

(def registration-routes
  (-> registration-routes*
      (wrap-transit-body {:keywords? false})
      (wrap-transit-response {:encoding :json :opts {}})))
