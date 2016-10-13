(ns rr.board-browser
  (:require [rr.boards :as boards]
            [rr.game :as game]
            [rr.bs :refer [list-group list-group-item]]
            [reagent.core :refer [atom dom-node]]
            [rr.konva :as k]))

(defonce board-size (atom {:width 1000 :height 450}))

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
(def laser-point-image (image-obj "/images/laser-point.png"))

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
           [k/rect (merge
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
               (println x height (* (count laser-squares) height) y (- y (* (count laser-squares) height)) wall-width)
               [x (- (+ y height) wall-width) x (+ (- (+ y height) (* (count laser-squares) height)) wall-width)])
      :east (let [y (+ y (* num (/ height (inc total-number))))]
              [(- (+ x width) wall-width) y (+ (- (+ x width) (* (count laser-squares) width)) wall-width) y])
      :west (let [y (+ y (* num (/ height (inc total-number))))]
              [(+ x wall-width) y (- (+ x (* (count laser-squares) width)) wall-width) y]))))

(def laser-width-ratio 0.1)

(defn laser-renderer
  [{:keys [laser]} {:keys [height width x y board position] :as props}]
  (when-let [[laser-wall num] laser]
    (fn []
      (let [laser-direction (get-in game/rotate-delta [:u-turn laser-wall])
            laser-squares (game/square-seq board position laser-direction)
            laser-squares (reduce
                            (fn [squares [_ square]]
                              (if (and
                                    (game/can-laser-pass-into-square? square laser-direction)
                                    (game/can-laser-pass-out-of-square? (last squares) laser-direction))
                                (conj squares square)
                                (reduced squares)))
                            [(last (first laser-squares))] (rest laser-squares))
            [rotation {dx :x dy :y}] (laser-shooter-adjustment laser-wall)]
        [k/group
         (for [laser-num (range num)]
           (do
             (when (= position [3 6]) (println (count laser-squares)
                                               (laser-points laser-wall num (inc laser-num) laser-squares props)))
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
                          :rotation rotation}]
                ])))]))))

(defonce highlighted-square (atom nil))

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
                       walls-renderer
                       rotator-renderer
                       docking-bay-renderer])

(def top-renderers [laser-renderer highlight-renderer])

(defn square-renderers
  [renderers square props]
  (let [renderers-to-apply (keep #(% square props) renderers)]
    (map-indexed (fn [idx r]
                   ^{:key idx}
                   [r])
                 renderers-to-apply)))

(defn highlight-square
  [position square]
  (reset! highlighted-square [position square]))

(defn board-row
  [renderers board row-idx height y row]
  (let [highlighted (first @highlighted-square)]
    ^{:key (str y)}
    [k/group
     (map-indexed
       (fn [idx square]
         (let [x (* idx height)
               position [idx row-idx]]
           ^{:key (str idx "-" y)}
           [k/group
            {:on-click (partial highlight-square position square)
             :on-touch (partial highlight-square position square)}
            (square-renderers renderers
                              square
                              {:board      board
                               :width      height
                               :height     height
                               :x          x
                               :y          y
                               :position   position
                               :highlight? (= position highlighted)})]))
       row)]))

(defn board-view
  [board]
  (let [rows (game/rows board)
        {board-width :width board-height :height} (calculate-optimal-board-size @board-size board)
        row-height (/ board-height (count rows))]
    [k/stage {:ref "board" :width board-width :height board-height :container "board-section"}
     [k/layer
      [k/group
       (map-indexed (fn [idx row]
                      ^{:key idx} [board-row bottom-renderers board idx row-height (* idx row-height) row])
                    rows)]]
     [k/layer
      ;; apply lasers over the top
      [k/group
       (map-indexed (fn [idx row]
                      ^{:key idx} [board-row top-renderers board idx row-height (* idx row-height) row])
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
             {:component-did-mount    #(do
                                        (on-window-resize (dom-node %) nil)
                                        (.addEventListener js/window "resize" (partial on-window-resize (dom-node %))))
              :component-will-unmount #(.removeEventListener js/window "resize" (partial on-window-resize (dom-node %)))}))


(defn square-info
  [selected-board]
  (when @selected-board
    (if-let [[[x y] square] @highlighted-square]
      (into [:dl [:dt "Square coordinates"] [:dd (str "[" x "," y "]")]]
            (concat (when-let [db (:docking-bay square)]
                      [[:dt "Docking bay number"] [:dd db]])
                    (when-let [walls (seq (:walls square))]
                      [[:dt "Walls"] [:dd (clojure.string/join "," (map name walls))]])
                    (when-let [[laser-wall num] (:laser square)]
                      [[:dt "Laser"] [:dd num " laser, on the " (name laser-wall) " wall"]])
                    (when-let [[belt-dir express?] (:belt square)]
                      [[:dt "Conveyer belt"] [:dd (when express? "Express belt, ") "travelling " (name belt-dir)]])
                    (when-let [rot (:rotator square)]
                      [[:dt "Rotator"] [:dd "Rotates " (name rot)]])
                    (when-let [flag (:flag square)]
                      [[:dt "Victory flag"] [:dd "Flag number " flag]
                       [:dt "Archive marker compatable?"] [:dd "Yes"]])
                    (when-let [pit (:pit square)]
                      [[:dt "Pit"] [:dd (rand-nth ["Certain death" "Try it" "An abyss" "The dark expanse is tempting. Try me."
                                                   "Seems like it goes on forever" "Like I like my Saturday Cartoons...dark"])]])
                    (when-let [repair (:repair square)]
                      [[:dt "Repair stop"] [:dd "Juice up here"]
                       [:dt "Archive marker compatable?"] [:dd "Yes"]])))
      [:p "Board Square Inspector: click a square on the board to see attributes of a square"])))

(defn board-browser-root
  [id?]
  (let [selected-board (atom (when id? [id? (get boards/all-available-boards id?)]))]
    (fn [_]
      [:section.board-browser-root
       [:section.left [board-list selected-board]]
       [middle-section selected-board]
       [:section.right [square-info selected-board]]])))

