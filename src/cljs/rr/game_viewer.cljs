(ns rr.game-viewer
  (:require [reagent.core :as reagent :refer [atom]]
            [reagent.session :as session]))

(def game-state (atom nil))

(defn game-title
  []

  )


(defn start-new-game-root
  []


  )

(defn game-viewer-root []
  [:div.row.game-viewer-root
   [:section.left.col-xs-3

    ]
   [:section.middle.col-xs-6
    [game-title]
    ]
   [:section.right.col-xs-3

    ]])
