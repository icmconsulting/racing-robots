(ns rr.core
    (:require [reagent.core :as reagent :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]))

;; -------------------------
;; Views

;; TODO:
;; - Test game harness page
;;   - start new game, select port for server, select 3 bots, select board name, click start
;;   - during game, select "autoplay", or directed play (next, rewind)
;;   - ticking, scrollable log (written to js console? sounds better)
;;
;; - For each player in game:
;;   - Number of lives left, current robot damage, number of flags touched
;;   - player logo, name, robot name
;;   -
;; - board browser page

(defn home-page []
  [:div [:h2 "Welcome to rr"]
   [:div [:a {:href "/about"} "go to about page"]]])

(defn about-page []
  [:div [:h2 "About rr"]
   [:div [:a {:href "/"} "go to the home page"]]])

(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'home-page))

(secretary/defroute "/rr" []
  (session/put! :current-page #'about-page))

;; -------------------------
;; Initialize app

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!
    {:nav-handler
     (fn [path]
       (secretary/dispatch! path))
     :path-exists?
     (fn [path]
       (secretary/locate-route path))})
  (accountant/dispatch-current!)
  (mount-root))
