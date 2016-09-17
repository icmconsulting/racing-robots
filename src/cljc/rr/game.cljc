(ns rr.game)

(defprotocol RRGame
  (start-next-turn [game])
  (complete-registers [game player-registers])
  (clean-up [game player-commands])
  (is-game-over? [game]))

(defn robot-destroyed?
  [{:keys [robot] :as player}]
  (= 10 (:damage robot)))

(defn num-cards-for-this-turn
  [{:keys [robot] :as player}]
  (- 9 (:damage robot)))

(defn deal-cards-to-players
  [dealt-cards players]
  (loop [[card & rest-cards :as all-cards] dealt-cards
         [current-player & rest-players :as all-players] players]
    (if card
      (if (< (count (:dealt current-player [])) (:cards-due current-player))
        (recur rest-cards (concat rest-players [(update current-player :dealt conj card)]))
        (recur all-cards (concat rest-players [current-player])))
      all-players)))

(defn start-next-turn*
  [{:keys [players] :as state}]
  (let [players (mapv #(assoc %1 :cards-due %2) players (map num-cards-for-this-turn players))
        [dealt-cards remaining-deck] (split-at (reduce + (map :cards-due players))
                                               (:program-deck state))]
    (-> state
        (update :turns conj {:players (deal-cards-to-players dealt-cards players)})
        (assoc :program-deck remaining-deck))))

(comment (let [players (vec (map-indexed (partial default-player-positions blank-board)
                                         (shuffle [{:name "1"} {:name "2"} {:name "3"} {:name "4"}])))]
           (:turns (start-next-turn*
                     {:program-deck (shuffle program-card-deck)
                      :board        blank-board
                      :players      (assoc-in players [0 :robot :damage] 9)
                      :turns        []}))))

(defrecord RRGameState [state]
  RRGame
  (start-next-turn [game] (update-in game [:state] start-next-turn*))
  (complete-registers [game player-registers])
  (clean-up [game player-commands])
  (is-game-over? [game]))

(def priorities (reverse (map #(* 20 %) (range 1 19))))
(def two-thirds-priorities (->> (partition-all 3 priorities)
                                (map butlast)
                                (apply concat)))
(def one-third-priorities (->> (partition-all 3 priorities)
                                (map first)))


(def program-card-deck
  (concat
    (map (fn [p] {:type :move :value 1 :priority p}) priorities)
    (map (fn [p] {:type :move :value 2 :priority p}) two-thirds-priorities)
    (map (fn [p] {:type :move :value 3 :priority p}) one-third-priorities)
    (map (fn [p] {:type :move :value -1 :priority p}) one-third-priorities)

    (map (fn [p] {:type :rotate :value :right :priority p}) priorities)
    (map (fn [p] {:type :rotate :value :left :priority p}) priorities)
    (map (fn [p] {:type :rotate :value :u-turn :priority p}) one-third-priorities)))

(defprotocol RRBoard
  (board-size [board])
  (square-at [board point])
  (move-robot [board starting-point all-robots instruction]
    "If the robot moves given the instruction, return the new position")
  (docking-bay-position [board num]))

(defrecord RRSeqBoard [board-squares]
  RRBoard
  (board-size [board] [(count (first board-squares)) (count board-squares)])
  (square-at [board [x y]]
    (assert (> (first (board-size board)) x) (str "X value out of bounds - maximum x value is " (dec (first (board-size board)))))
    (assert (> (second (board-size board)) y) (str "Y value out of bounds - maximum y value is " (dec (second (board-size board)))))
    (nth (nth board-squares y) x))
  (move-robot [board [x y] all-robots {:keys [type value]}]
    ;; TODO
    )
  (docking-bay-position [board num]
    (let [[max-x max-y] (board-size board)]
      (first (keep (fn [y]
                     (when-let [x (first (keep (fn [x] (when (= (:docking-bay (square-at board [x y])) num) x))
                                               (range max-x)))]
                       [x y]))
                   (range max-y))))))

(def blank-square {:walls #{} :repair? false :flag nil :belt nil :pit nil :laser nil})
(defn docking-bay-square
  [num]
  (assoc blank-square :docking-bay num))

;; Simple 12x16 board with no obstacles, walls or repair pods
(def blank-board
  (->RRSeqBoard
    (concat
      (repeat 15 (repeat 12 blank-square))
      [(concat (repeat 4 blank-square)
               (map docking-bay-square (range 1 5))
               (repeat 4 blank-square))])))

(defn default-player-positions
  [board idx player]
  (let [start-position (docking-bay-position board (inc idx))]
    (assoc player
      :robot {:position start-position
              :archive-marker start-position
              :docking-bay (inc idx)
              :lives 4
              :damage 0
              :flags-touched []
              :locked-registers #{}})))

(defn new-game
  [players board]
  (let [program-deck (shuffle program-card-deck)]
    (->RRGameState
      {:program-deck program-deck
       :board        board
       :players      (map-indexed (partial default-player-positions board) (shuffle players))
       :turns        []})))


;; TODO:
;; - Lock registers for players
