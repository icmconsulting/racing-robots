(ns rr.core
    (:require [reagent.core :as reagent :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [cljsjs.react-bootstrap]
              [rr.game-viewer :as game-viewer]
              [rr.board-browser :as board-browser]))

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


(defn current-page []
  [(session/get :current-page)])

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'game-viewer/game-viewer-root))

(secretary/defroute "/newgame" []
  (session/put! :current-page #'game-viewer/game-viewer-root))

(secretary/defroute "/boards" []
  (session/put! :current-page #'board-browser/board-browser-root))

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
