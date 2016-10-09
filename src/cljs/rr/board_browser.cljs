(ns rr.board-browser
  (:require [rr.boards :as boards]
            [rr.game :as game]
            [rr.bs :refer [list-group list-group-item]]
            [reagent.core :refer [atom]]
            [rr.konva :as k]))

;;TODO: tweak these
(def board-width 1000)
(def board-height 450)

(def base-square-image
  (let [obj (js/Image.)]
    (set! obj -src "/images/floor-tile.jpg")
    obj))

(defn board-list
  [selected-board]
  [list-group
   (doall (for [[id {:keys [description]}] boards/all-available-boards]
            [list-group-item {:key    id
                              :href   (str "/boards/" (name id))
                              :active (= id (first @selected-board))
                              :header (name id)}
             description]))])


(defn board-row
  [height y row]
  (let [square-width (/ board-width (count row))]
    ^{:key (str y)}
    [k/group
     (map-indexed
       (fn [idx square]
         (let [x (* idx square-width)]
           ^{:key (str idx "-" y)}
           [k/group
            [k/image {:height height
                      :width  square-width
                      :image  base-square-image
                      :x      x
                      :y      y}]
            [k/rect {:x x :y y
                     :height height :width square-width
                     :stroke "#ffffff" :stroke-width 1}]]))
       row)]))

(defn board-view
  [board]
  (let [rows (game/rows board)
        row-height (/ board-height (count rows))]
    [k/stage {:ref "board" :width board-width :height board-height :container "board-section"}
     [k/layer
      [k/group
       (map-indexed (fn [idx row]
                      ^{:key idx} [board-row row-height (* idx row-height) row])
                    rows)]]]))

(defn selected-board-view
  [selected-board]
  (if @selected-board
    [board-view (get (second @selected-board) :board)]
    [:div.no-board-selected [:p "Select a board to view from the board list on the left."]]))

(defn board-info
  []
  )

(defn board-browser-root
  [id?]
  (let [selected-board (atom (when id? [id? (get boards/all-available-boards id?)]))]
    (fn [_]
      [:section.board-browser-root
       [:section.left [board-list selected-board]]
       [:section#board-section.middle [selected-board-view selected-board]]
       [:section.right [board-info]]])))
