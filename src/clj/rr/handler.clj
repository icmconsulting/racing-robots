(ns rr.handler
  (:require [compojure.core :refer [GET defroutes context]]
            [compojure.route :refer [not-found resources]]
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
   [:link {:rel "stylesheet" :href "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-alpha.4/css/bootstrap.min.css"
           :integrity "sha384-2hfp1SzUoho7/TsGGGDaFdsuuDL0LX2hnUp6VkX3CUQ2K4K+xjboZdsXyp4oUHZj"
           :crossorigin "anonymous"}]
   (include-css (if (env :dev) "/css/rr.css" "/css/rr.min.css"))])

(defn loading-page []
  (html5
    (head)
    [:body {:class "body-container"}
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
