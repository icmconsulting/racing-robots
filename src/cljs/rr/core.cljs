(ns rr.core
    (:require [reagent.core :as reagent :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [taoensso.timbre :refer [info warn]]
              [rr.bs :as bs]
              [rr.logger :as logger]
              [rr.game-viewer :as game-viewer]
              [rr.board-browser :as board-browser]
              [rr.registration :as registration]))

(def mode
  (or (some-> (.getElementsByTagName js/document "body")
              (aget 0)
              (.-dataset)
              (.-mode)
              (keyword))
      :test-harness))

;; -------------------------
;; Routes

(secretary/defroute root-route "/" []
  (session/put! :current-page #'game-viewer/game-viewer-root))

(secretary/defroute new-game-route "/newgame" []
  (session/put! :current-page #'game-viewer/game-viewer-root))

(secretary/defroute boards-route "/boards" []
  (session/put! :current-page #'board-browser/board-browser-root))

(secretary/defroute boards-id-route "/boards/:id" {id :id}
  (session/put! :current-page (partial #'board-browser/board-browser-root (keyword id))))

;; Registration routes
(secretary/defroute registration-id-route "/registration/:id" {id :id}
  (session/put! :current-page (partial #'registration/registration-root id)))



;; -------------------------
;; Views

(defn rr-test-harness-nav
  []
  [bs/navbar {:inverse true}
   [bs/navbar-header
    [bs/navbar-brand "Racing Robots"]
    [bs/navbar-toggle]]
   (when (= :test-harness mode)
     [bs/navbar-collapse
      [bs/nav
       [bs/navbar-item {:href (new-game-route)} "Game"]
       [bs/navbar-item {:href (boards-route)} "Boards"]]])])

(defn current-page []
  [:div.page-root
   [rr-test-harness-nav]
   [(session/get :current-page)]])

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
  (logger/print-log-header)
  (mount-root))
