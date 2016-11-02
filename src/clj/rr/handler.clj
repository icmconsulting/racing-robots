(ns rr.handler
  (:require [compojure.core :refer [GET defroutes context]]
            [compojure.route :refer [not-found resources]]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [hiccup.page :refer [include-js include-css html5]]
            [rr.middleware :refer [wrap-middleware]]
            [rr.connectors :refer [bot-routes]]
            [config.core :refer [env]]))

(def mount-target
  [:div#app [:h3 "Standby for racing robots..."]])

(defn head []
  [:head
   [:title "racing robots test harness"]
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport"
           :content "width=device-width, initial-scale=1"}]
   (include-css (if (env :dev) "/css/rr.css" "/css/rr.min.css"))])

(defn loading-page []
  (html5
    (head)
    [:body {:class "body-container" :data-csrf *anti-forgery-token*}
     mount-target
     (include-js "/js/app.js")]))

(defn cards-page []
  (html5
    (head)
    [:body
     mount-target
     (include-js "/js/app_devcards.js")]))

(defroutes routes
           (GET "/" [] (loading-page))
           (GET "/newgame" [] (loading-page))
           (GET "/boards" [] (loading-page))
           (GET "/boards/*" [] (loading-page))
           (context "/bot" [] bot-routes)
           (GET "/cards" [] (cards-page))
           (resources "/")
           (not-found "Not Found"))

(def app (wrap-middleware #'routes))
