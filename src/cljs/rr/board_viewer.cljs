(ns rr.board-viewer
  (:require [reagent.core :refer [atom dom-node cursor]]
            [rr.game :as game]
            [rr.konva :as k]
            [rr.utils :refer [image-obj]]
            [taoensso.timbre :as timbre]))

(defonce board-size (atom {:width 1000 :height 450}))

(let [prev-to (atom nil)]
  (defn on-window-resize [target-node ev]
    (when-let [to @prev-to] (.clearTimeout js/window to))

    (reset! prev-to (.setTimeout js/window
                                 (fn []
                                   (reset! board-size {:width (.-clientWidth target-node)
                                                       :height (.-clientHeight target-node)}))
                                 500))))

(def board-parent-resize-props
  {:component-did-mount    #(do
                             (on-window-resize (dom-node %) nil)
                             (.addEventListener js/window "resize" (partial on-window-resize (dom-node %))))
   :component-will-unmount #(.removeEventListener js/window "resize" (partial on-window-resize (dom-node %)))})

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

(def base-square-image (image-obj "/images/floor.gif"))
(def flag-image (image-obj "/images/flag.png"))
(def repair-image (image-obj "/images/repair.gif"))
(def pit-image (image-obj "/images/pit.jpg"))
(def belt-arrow-image (image-obj "/images/cnv_1_east.gif"))
(def belt-express-arrow-image (image-obj "/images/cnv_2_east.gif"))
(def rotate-right-image (image-obj "/images/rotate_clock.gif"))
(def rotate-left-image (image-obj "/images/rotate_counter.gif"))
(def laser-point-image (image-obj "/images/laser-point.png"))
(def powered-down-image (image-obj "/images/recharge.png"))
(def wall-image (image-obj "/images/wall.gif"))

(defn base-renderer
  [_ {:keys [height width x y]}]
  (fn []
    [k/image {:height height
              :width  height
              :image  base-square-image
              :x      x
              :y      y}]))

(defn outline-renderer
  [_ {:keys [height width x y]}]
  (fn []
    [k/rect {:x            x
             :y            y
             :height       height
             :width        width
             :stroke       "#ffffff"
             :stroke-width 1}]))

(defn repair-renderer
  [{:keys [repair]} {:keys [height width x y]}]
  (when repair
    (fn []
      [k/image {:height height
                :width  width
                :image  repair-image
                :x      x
                :y      y }])))

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
  {:north [270 {:x 0 :y 1}]
   :east [0 {:y 0}]
   :south [90 {:x 1 :y 0}]
   :west [180 {:y 1 :x 1}]})

(defn belt-renderer
  [{:keys [belt]} {:keys [height width x y]}]
  (when belt
    (fn []
      (let [[belt-direction express?] belt
            [rot position-adj] (arrow-adjustment belt-direction)]
        [k/image {:height   height
                  :width    width
                  :image    (if express? belt-express-arrow-image belt-arrow-image)
                  :x        (+ x (* width (:x position-adj 0)))
                  :y        (+ y (* height (:y position-adj 0)))
                  :rotation rot
                  :fill     "#676767"}]))))

(def wall-square-ratio 0.15)

(defn wall-for-direction
  [dir {:keys [height width x y] :as square-props}]
  {:height (if (#{:north :south} dir) (* wall-square-ratio height) height)
   :width  (if (#{:east :west} dir) (* wall-square-ratio width) width)
   :x      (if (#{:north :west :south} dir) x (+ x (- width (* wall-square-ratio width))))
   :y      (if (#{:north :west :east} dir) y (+ y (- height (* wall-square-ratio height))))})

(defn walls-renderer
  [{:keys [walls]} square-props]
  (when (seq walls)
    (fn []
      (let []
        [k/group
         (for [dir walls]
           ^{:key (str (name dir) "-" (:y square-props))}
           [k/image (merge
                      {:image wall-image}
                      (wall-for-direction dir square-props))]
           #_[k/rect (merge
                     (wall-for-direction dir square-props)
                     {:fill   "#676767"})])]))))

(def laser-shooter-adjustment
  {:north [0 {:x 0.1 :y 0}]
   :east [90 {:y 0.1 :x 0}]
   :south [180 {:x -0.1 :y 0}]
   :west [270 {:y -0.1 :x 0}]})

(defn laser-points
  [laser-wall total-number num laser-squares {:keys [height width x y]}]
  (let [wall-width (* width wall-square-ratio)]
    (case laser-wall
      :north (let [x (+ x (* num (/ width (inc total-number))))]
               [x (+ y wall-width) x (- (+ y (* (count laser-squares) height)) wall-width)])
      :south (let [x (+ x (* num (/ width (inc total-number))))]
               [x (- (+ y height) wall-width) x (+ (- (+ y height) (* (count laser-squares) height)) wall-width)])
      :east (let [y (+ y (* num (/ height (inc total-number))))]
              [(- (+ x width) wall-width) y (+ (- (+ x width) (* (count laser-squares) width)) wall-width) y])
      :west (let [y (+ y (* num (/ height (inc total-number))))]
              [(+ x wall-width) y (- (+ x (* (count laser-squares) width)) wall-width) y]))))

(def laser-width-ratio 0.1)

(defn laser-squares-seq
  [board start-position laser-direction]
  (let [laser-squares (game/square-seq board start-position laser-direction)]
    (reduce
      (fn [squares [_ square]]
        (if (and
              (game/can-laser-pass-into-square? square laser-direction)
              (game/can-laser-pass-out-of-square? (last squares) laser-direction))
          (conj squares square)
          (reduced squares)))
      [(last (first laser-squares))] (rest laser-squares))))

(defn laser-renderer
  [{:keys [laser]} {:keys [height width x y board position] :as props}]
  (when-let [[laser-wall num] laser]
    (fn []
      (let [laser-direction (get-in game/rotate-delta [:u-turn laser-wall])
            laser-squares (laser-squares-seq board position laser-direction)
            [rotation {dx :x dy :y}] (laser-shooter-adjustment laser-wall)]
        [k/group
         (for [laser-num (range num)]
           (let [laser-points (laser-points laser-wall num (inc laser-num) laser-squares props)]
             ^{:key (str position laser-num)}
             [k/group
              [k/line {:stroke       "red"
                       :stroke-width (* width laser-width-ratio)
                       :opacity      0.5
                       :points       laser-points}]
              [k/image {:height   (* 0.2 height)
                        :width    (* 0.2 width)
                        :image    laser-point-image
                        :x        (- (first laser-points) (* (or dx 0) width))
                        :y        (- (second laser-points) (* (or dy 0) height))
                        :rotation rotation}]]))]))))

(defn click-collector-renderer
  "Renderer purely just over a square to register a click"
  [_ {:keys [height width x y]}]
  (fn []
    [k/rect {:x            x
             :y            y
             :height       height
             :width        width}]))

(defn highlight-renderer
  [_ {:keys [height width x y highlight?]}]
  (when highlight?
    (fn []
      [k/rect {:x            x :y y
               :height       height :width width
               :stroke       "red"
               :stroke-width 2}])))


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

(def bottom-renderers [base-renderer
                       outline-renderer
                       flag-renderer
                       repair-renderer
                       pit-renderer
                       belt-renderer
                       rotator-renderer
                       docking-bay-renderer
                       walls-renderer])

(defn square-renderers
  [renderers square props]
  (let [renderers-to-apply (keep #(% square props) renderers)]
    (map-indexed (fn [idx r]
                   ^{:key idx}
                   [r])
                 renderers-to-apply)))

(defn highlight-square
  [highlighted-cur position square]
  (reset! highlighted-cur [position square]))

(defn board-row
  [renderers board row-idx height y row & {:keys [highlighted highlight?] :or {highlighted (atom nil)}}]
  ^{:key (str y)}
  [k/group
   (let [[highlighted-position] @highlighted]
     (map-indexed
       (fn [idx square]
         (let [x (* idx height)
               position [idx row-idx]]
           ^{:key (str idx "-" y)}
           [k/group
            (when highlight?
              {:on-click #(highlight-square highlighted position square)
               :on-mouse-over #(highlight-square highlighted position square)})
            (square-renderers renderers
                              square
                              {:board      board
                               :width      height
                               :height     height
                               :x          x
                               :y          y
                               :position   position
                               :highlight? (= position highlighted-position)})]))
       row))])

(defn base-board-layer
  [board rows row-height]
  [k/layer
   [k/group
    (map-indexed (fn [idx row]
                   ^{:key idx} [board-row bottom-renderers board idx row-height (* idx row-height) row])
                 rows)]])

(defn laser-layer
  [board rows row-height]
  [k/layer
   [k/group
    (map-indexed (fn [idx row]
                   ^{:key idx} [board-row [laser-renderer] board idx row-height (* idx row-height) row])
                 rows)]])

(defn highlight-layer
  [board rows row-height board-attrs]
  [k/layer
   [k/group
    (map-indexed (fn [idx row]
                   ^{:key idx} [board-row [highlight-renderer click-collector-renderer] board idx row-height (* idx row-height) row
                                :highlighted (cursor board-attrs [:highlighted]) :highlight? true])
                 rows)]])

(def robot-adjustment
  {:north [0 {:x 0 :y 0}]
   :east [90 {:x 0.7 :y 0}]
   :south [180 {:x 0.7 :y 0.7}]
   :west [270 {:y 0.7 :x 0}]})

(defn robot-laser-group
  [board square-dim {:keys [position direction]} laser-colour render-props]
  (let [laser-squares (laser-squares-seq board position direction)]
    [k/group
     (let [laser-points (laser-points (get-in game/rotate-delta [:u-turn direction]) 1 1 laser-squares render-props)]
       [k/line {:stroke       laser-colour
                :stroke-width (* square-dim 0.04)
                :opacity      0.4
                :points       laser-points}])]))

(def laser-colours ["#39A939" "#D38647" "#D3D347" "#566EA4"])

(defn robot-layer
  [board square-dim players-cur]
  [k/layer
   [k/group
    (for [{:keys [id robot robot-image state laser-colour] :as p}
          (map #(assoc %1 :laser-colour %2) players-cur laser-colours)]
      (when-not (or (= state :dead) (nil? (:position robot)))
        (let [[x y] (:position robot)
              [rot {dx :x dy :y}] (robot-adjustment (:direction robot))
              start-x (* x square-dim)
              start-y (* y square-dim)
              highlighter-radius (/ (- square-dim (* wall-square-ratio square-dim)) 2)]
          ^{:key id}
          [k/group
           [k/circle {:radius highlighter-radius
                      :opacity 0.4
                      :fill-radial-gradient-start-point 0
                      :fill-radial-gradient-start-radius 0
                      :fill-radial-gradient-end-point 0
                      :fill-radial-gradient-end-radius (* highlighter-radius 0.75)
                      :fill-radial-gradient-color-stops [0 "transparent" 0.5 "transparent" 0.8 "white"]
                      :x (+ start-x (/ square-dim 2))
                      :y (+ start-y (/ square-dim 2))}]

           [k/image {:height   (- square-dim (* 2 wall-square-ratio square-dim))
                     :width    (- square-dim (* 2 wall-square-ratio square-dim))
                     :image    robot-image
                     :x        (+ start-x (* wall-square-ratio square-dim) (* square-dim dx))
                     :y        (+ start-y (* wall-square-ratio square-dim) (* square-dim dy))
                     :rotation rot}]
           [robot-laser-group board square-dim robot laser-colour {:x start-x :y start-y :height square-dim :width square-dim}]
           (when (:powered-down? robot)
             [k/image {:height   (* 0.5 square-dim)
                       :width    (* 0.5 square-dim)
                       :image    powered-down-image
                       :x        (+ start-x (* 0.5 square-dim))
                       :y        (+ start-y (* 0.5 square-dim))}])])))]])

(defn board-view
  [board-attrs]
  (let [{:keys [board container-id]} @board-attrs
        rows (game/rows board)
        {board-width :width board-height :height} (calculate-optimal-board-size @board-size board)
        row-height (/ board-height (count rows))]
    [k/stage {:ref "board" :width board-width :height board-height :container container-id}
     [base-board-layer board rows row-height]
     [laser-layer board rows row-height]
     (when (contains? @board-attrs :highlighted)
       [highlight-layer board rows row-height board-attrs])
     (when (contains? @board-attrs :players)
       [robot-layer board row-height (:players @board-attrs)])]))
