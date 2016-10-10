(ns rr.board-browser
  (:require [rr.boards :as boards]
            [rr.game :as game]
            [rr.bs :refer [list-group list-group-item]]
            [reagent.core :refer [atom dom-node]]
            [rr.konva :as k]))

;;TODO: tweak these

(def board-size (atom {:width 1000 :height 450}))

(defn calculate-optimal-board-size
  [viewport-size board]
  (let [[cols rows] (game/board-size board)]
    (if (<= cols rows)
      (let [optimal-height (- (:height viewport-size) 20)
            row-width (/ optimal-height rows)]
        {:width  (* cols row-width)
         :height optimal-height})

      (let [optimal-width (- (:width viewport-size) 20)
            row-height (/ optimal-width cols)]
        {:width  optimal-width
         :height (* rows row-height)}))))

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

(defn base-renderer
  [_ {:keys [height width x y]}]
  [k/image {:height height
            :width  height
            :image  base-square-image
            :x      x
            :y      y}])

(defn outline-renderer
  [_ {:keys [height width x y]}]
  [k/rect {:x      x :y y
           :height height :width height
           :stroke "#ffffff" :stroke-width 1}])

(def flag-colours ["orange" "red" "blue" "green" "purple"])

(defn flag-renderer
  [{:keys [flag] :as s} {:keys [height width x y]}]

  (when flag
    (println (get flag-colours (dec flag)))
    [k/group
     [k/circle {:radius width
                :x (+ x (/ width 2))
                :y (+ y (/ height 2))
                ;:fill (get flag-colours (dec flag))
                :stroke "black"
                :stroke-width 2}]]
    )
  )

(def square-renderers
  [base-renderer
   outline-renderer
   #_flag-renderer
   ]
  )

(defn board-row
  [height y row]
  ^{:key (str y)}
  [k/group
   (map-indexed
     (fn [idx square]
       (let [x (* idx height)]
         ^{:key (str idx "-" y)}
         [k/group
          (map-indexed (fn [idx r]
                         ^{:key idx}
                         [r square {:width height :height height :x x :y y}])
                       square-renderers)]))
     row)])

(defn board-view
  [board]
  (let [rows (game/rows board)
        {board-width :width board-height :height} (calculate-optimal-board-size @board-size board)
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

(defn middle-section*
  [selected-board]
  [:section#board-section.middle [selected-board-view selected-board]])

(let [prev-to (atom nil)]
  (defn on-window-resize [target-node ev]
    (when-let [to @prev-to] (.clearTimeout js/window to))

    (reset! prev-to (.setTimeout js/window
                                 (fn []
                                   (reset! board-size {:width (.-clientWidth target-node)
                                                       :height (.-clientHeight target-node)}))
                                 500))))

(def middle-section
  (with-meta middle-section*
             {:component-did-mount #(.addEventListener js/window "resize" (partial on-window-resize (dom-node %)))
              :component-will-unmount #(.removeEventListener js/window "resize" (partial on-window-resize (dom-node %)))}))


(defn board-info
  []
  )

(defn board-browser-root
  [id?]
  (let [selected-board (atom (when id? [id? (get boards/all-available-boards id?)]))]
    (fn [_]
      [:section.board-browser-root
       [:section.left [board-list selected-board]]
       [middle-section selected-board]
       [:section.right [board-info]]])))

