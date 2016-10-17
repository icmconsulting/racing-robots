(ns rr.board-browser
  (:require [rr.boards :as boards]
            [rr.game :as game]
            [rr.bs :refer [list-group list-group-item]]
            [rr.board-viewer :refer [board-view board-parent-resize-props]]
            [reagent.core :refer [atom dom-node cursor]]
            [rr.konva :as k]))

(defn board-list
  [selected-board]
  [list-group
   (doall (for [[id {:keys [description]}] boards/all-available-boards]
            [list-group-item {:key    id
                              :href   (str "/boards/" (name id))
                              :active (= id (:id @selected-board))
                              :header (name id)}
             description]))])

(defn selected-board-view
  [selected-board]
  (if @selected-board
    [board-view selected-board]
    [:div.no-board-selected [:p "Select a board to view from the board list on the left."]]))

(defn middle-section*
  [selected-board]
  [:section#board-section.middle [selected-board-view selected-board]])

(def middle-section (with-meta middle-section* board-parent-resize-props))

(defn square-info
  [selected-board]
  (when @selected-board
    (let [{:keys [highlighted]} @selected-board]
      (if-let [[[x y] square] highlighted]
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
        [:p "Board Square Inspector: click a square on the board to see attributes of a square"]))))

(defn board-browser-root
  [id?]
  (let [selected-board (atom (when id?
                               {:id          id?
                                :board       (:board (get boards/all-available-boards id?))
                                :container-id "board-section"
                                :highlighted nil}))]
    (fn [_]
      [:section.board-browser-root
       [:section.left [board-list selected-board]]
       [middle-section selected-board]
       [:section.right [square-info selected-board]]])))
