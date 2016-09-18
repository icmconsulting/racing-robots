(ns rr.game
  (:require [com.rpl.specter :refer :all])
  #?(:clj
     (:import [java.util UUID])))

(defn uuid
  []
  (str
    #?(:cljs (random-uuid)
       :clj  (UUID/randomUUID))))

(defprotocol RRGame
  (start-next-turn [game])
  (complete-registers [game player-id->registers])
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

(defn player-by-id
  [state player-id]
  (first (filter #(= player-id (:id %)) (:players state))))

(defmulti execute-player-register (fn [_ [_ {:keys [type]}]] type))

(def move-delta
  {:north [0 -1]
   :east [1 0]
   :west [-1 0]
   :south [0 1]})

(defn position-with-delta
  [{:keys [board players]} move-amount {:keys [position direction] :as robot}]
  (let [[x y] position
        [delta-x delta-y] (move-delta direction)]
    (assoc robot
      :position [(+ x (* delta-x move-amount))
                 (+ y (* delta-y move-amount))])))

(defn can-move-to?
  [state player new-position]
  true) ;;TODO: check for walls or other robots

(defmethod execute-player-register :move
  [state [player-id {:keys [value]}]]
  (transform [:players ALL (if-path [:id (partial = player-id)] [:robot])]
             (partial position-with-delta state value)
             state))

(def rotate-delta
  {:left {:north :west
          :west :south
          :south :east
          :east :north}
   :right {:north :east
           :east :south
           :south :west
           :west :north}
   :u-turn {:north :south
            :south :north
            :east :west
            :west :east}})

(defmethod execute-player-register :rotate
  [state [player-id {:keys [value]}]]
  (transform [:players ALL (if-path [:id (partial = player-id)] [:robot :direction])]
                     (get rotate-delta value)
                     state))

;(def test-game (new-game [{:name "player1"}] blank-board))
;(:players (reduce execute-player-register
;                  (:state test-game)
;                  [["5819fd66-4911-486f-8ed2-a63af64313f3" {:type :rotate :value :right}]
;                   ["5819fd66-4911-486f-8ed2-a63af64313f3" {:type :move :value 2}]]))

(defn priority
  [state [player-id register]]
  (+ (:priority register) (- 1 (/ (:docking-bay (player-by-id state player-id)) 10))))

(defn execute-register-number
  [player-id->registers state register-num]
  (let [player-id->register (map (fn [[player-id rs]]
                                   [player-id (nth rs register-num)])
                                 player-id->registers)
        prioritised-players (sort-by (partial priority state) player-id->register)]
    (reduce execute-player-register state prioritised-players))

  ;; 1. move robots
  ;; 2. board elements move
  ;; 3. lasers fire
  ;; 4. touch checkpoints
  ;; TODO!!
  )

;;TODO -
(defn execute-each-register
  [game player-ids->registers]
  {:pre [(map? player-ids->registers) ((every-pred :players :board :id :turns :program-deck) (:state game))]}
  ;;TODO: assert each player has a set of registers or is :powered-down
  (update-in game [:state]
             #(reduce (partial execute-register-number player-ids->registers) % (range 0 5))))

(defrecord RRGameState [state]
  RRGame
  (start-next-turn [game] (update-in game [:state] start-next-turn*))
  (complete-registers [game player-id->registers] (execute-each-register game player-id->registers))
  (clean-up [game player-commands])
  (is-game-over? [game]))

;; program card deck
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


;; RR Game boards
(defprotocol RRBoard
  (board-size [board])
  (square-at [board point])
  (move-robot [board starting-point all-robots instruction]
    "If the robot moves given the instruction, return the new position")
  (docking-bay-position [board num]))

(defrecord RRSeqBoard [board-squares]
  RRBoard
  (board-size [board] [(count (first board-squares)) (count board-squares)])
  (square-at [board [x y]] (nth (nth board-squares y) x))
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

(defn player-with-robot
  [board idx player]
  (let [start-position (docking-bay-position board (inc idx))]
    (assoc player
      :robot {:position         start-position
              :direction        :north
              :archive-marker   start-position
              :docking-bay      (inc idx)
              :state            :ready
              :lives            4
              :damage           0
              :flags-touched    []
              :locked-registers #{}})))

(defn player-with-game-id
  [player]
  (assoc player :id (uuid)))

(defn new-game
  [players board]
  (let [program-deck (shuffle program-card-deck)]
    (->RRGameState
      {:id           (uuid)
       :program-deck program-deck
       :board        board
       :players      (vec (map-indexed (comp player-with-game-id (partial player-with-robot board)) (shuffle players)))
       :turns        []})))

;; TODO:
;; - Lock registers for players
