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

(defn image-obj
  [src]
  (let [obj (js/Image.)]
    (set! obj -src src)
    obj))

(def base-square-image (image-obj "/images/floor-tile.jpg"))
(def base-square-image-rivets (image-obj "/images/floor-rivets.jpg"))
(def flag-image (image-obj "/images/flag.png"))
(def repair-image (image-obj "/images/repair.png"))
(def pit-image (image-obj "/images/pit.jpg"))
(def belt-arrow-image (image-obj "/images/belt-arrow.png"))
(def belt-express-arrow-image (image-obj "/images/belt-express-arrow.png"))
(def belt-tubes-pattern-image (image-obj "/images/belt-tubes-pattern.jpg"))
(def rotate-right-image (image-obj "/images/rotate-right.png"))
(def rotate-left-image (image-obj "/images/rotate-left.png"))

(def safety-colour "#A8DB92")

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
  (fn []
    [k/image {:height height
              :width  height
              :image  base-square-image-rivets
              :x      x
              :y      y}]))

(defn outline-renderer
  [_ {:keys [height width x y]}]
  (fn []
    [k/rect {:x      x :y y
             :height height :width height
             :stroke "#ffffff" :stroke-width 1}]))

(defn repair-renderer
  [{:keys [repair]} {:keys [height width x y]}]
  (when repair
    (fn []
      [k/image {:height (* height 0.7)
                :width  (* width 0.7)
                :image  repair-image
                :x      (+ x (* width 0.15))
                :y      (+ y (* height 0.15)) }])))

(defn pit-renderer
  [{:keys [pit]} {:keys [height width x y]}]
  (when pit
    (fn []
      [k/image {:height height
                :width  height
                :image  pit-image
                :x      x
                :y      y}])))

(defn rotator-renderer
  [{:keys [rotator]} {:keys [height width x y]}]
  (when rotator
    (fn []
      [k/image {:height height
                :width  height
                :image  (if (= :left rotator) rotate-left-image rotate-right-image)
                :x      x
                :y      y}])))

(def arrow-adjustment
  {:north [270 {:x 0.2 :y 1}]
   :east [0 {:y 0.2}]
   :south [90 {:x 0.8 :y 0}]
   :west [180 {:y 0.8 :x 1}]})

(defn belt-renderer
  [{:keys [belt]} {:keys [height width x y]}]
  (when belt
    (fn []
      (let [[belt-direction express?] belt
            [rot position-adj] (arrow-adjustment belt-direction)]
        [k/group
         [k/rect {:x x :y y
                  :height height :width width
                  :fill-pattern-image belt-tubes-pattern-image
                  :fill-pattern-rotation rot
                  :fill-pattern-repeat "repeat-x"
                  :fill-pattern-scale-x (/ width 560)
                  :fill-pattern-scale-y (/ height 300)}]
         [k/image {:height   (* 0.6 height)
                   :width    width
                   :image    (if express? belt-express-arrow-image belt-arrow-image)
                   :x        (+ x (* width (:x position-adj 0)))
                   :y        (+ y (* height (:y position-adj 0)))
                   :rotation rot
                   :fill     "#676767"}]]))))

(defn wall-for-direction
  [dir {:keys [height width x y] :as square-props}]
  {:height (if (#{:north :south} dir) (* 0.15 height) height)
   :width  (if (#{:east :west} dir) (* 0.15 width) width)
   :x      (if (#{:north :west :south} dir) x (+ x (- width (* 0.15 width))))
   :y      (if (#{:north :west :east} dir) y (+ y (- height (* 0.15 height))))})

(defn walls-renderer
  [{:keys [walls]} square-props]
  (when (seq walls)
    (fn []
      (let []
        [k/group
         (for [dir walls]
           ^{:key (str (name dir) "-" (:y square-props))}
           [k/rect (merge
                     (wall-for-direction dir square-props)
                     {:fill   "#676767"})])]))))

;;TODO pick some better colours
(def bay-colours ["orange" "red" "blue" "green" "purple"])

(defn docking-bay-renderer
  [{:keys [docking-bay] :as s} {:keys [height width x y]}]
  (when (and docking-bay (pos? width))
    (fn []
      (let [half-width (/ width 2)
            radius (- (/ width 3) 1)]
        [k/group
         [k/circle {:radius       radius
                    :x            (+ x half-width)
                    :y            (+ y (/ height 2))
                    :fill         "#7B808E"
                    :stroke       "black"
                    :stroke-width 1}]
         [k/text {:x (+ x (/ width 2.5))
                  :y (+ y (/ height 3.25))
                  :text (str docking-bay)
                  :font-size (* height 0.4)
                  :font-family "Courier"
                  :fill "black"}]]))))


(defn flag-renderer
  [{:keys [flag] :as s} {:keys [height width x y]}]
  (when (and flag (pos? width))
    (fn []
      [k/group
       [k/image {:height height
                 :width  height
                 :image  flag-image
                 :x      x
                 :y      y }]
       [k/text {:x (+ x (/ width 3))
                :y (+ y (/ height 10))
                :text (str flag)
                :font-size (* height 0.5)
                :font-family "Courier"
                :fill "white"}]])))

(def renderers [base-renderer
                outline-renderer
                flag-renderer
                repair-renderer
                pit-renderer
                belt-renderer
                walls-renderer
                rotator-renderer
                docking-bay-renderer])

(defn square-renderers
  [square props]
  (let [renderers-to-apply (keep #(% square props) renderers)]
    (map-indexed (fn [idx r]
                   ^{:key idx}
                   [r])
                 renderers-to-apply)))

(defn board-row
  [height y row]
  ^{:key (str y)}
  [k/group
   (map-indexed
     (fn [idx square]
       (let [x (* idx height)]
         ^{:key (str idx "-" y)}
         [k/group (square-renderers square {:width height :height height :x x :y y})]))
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

