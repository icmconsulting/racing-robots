(ns rr.game
  (:require [com.rpl.specter :refer :all]
            [clojure.spec :as s])
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
  (players  [game])
  (is-game-over? [game]))

(defprotocol RRBoard
  (board-size [board])
  (square-at [board point])
  (move-robot [board starting-point all-robots instruction]
    "If the robot moves given the instruction, return the new position")
  (docking-bay-position [board num]))

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
  ;;TODO: (whole deck - locked registers) is reshuffled and dealth at the beginning of each turn!
  (let [players (mapv #(assoc %1 :cards-due %2) players (map num-cards-for-this-turn players))
        [dealt-cards remaining-deck] (split-at (reduce + (map :cards-due players))
                                               (:program-deck state))]
    (-> state
        (update :turns conj {:players (deal-cards-to-players dealt-cards players)})
        (assoc :program-deck remaining-deck))))

(defn player-by-id
  [state player-id]
  (first (filter #(= player-id (:id %)) (:players state))))

(defmulti execute-player-register* (fn [_ [_ {:keys [type]}]] type))

(defn execute-player-register
  [state [player-id :as reg-map]]
  (if-not (= :destroyed (get-in (player-by-id state player-id) [:robot :state]))
    (execute-player-register* state reg-map)
    state))

(def move-delta
  {:north [0 -1]
   :east [1 0]
   :west [-1 0]
   :south [0 1]})

(defn owner-player-at-position
  [state position]
  (->> (filter #(= position (get-in % [:robot :position])) (:players state))
       (first)))

(defn translate-position
  [start-position direction move-amount]
  (let [[x y] start-position
        [delta-x delta-y] (move-delta direction)]
    [(+ x (* delta-x move-amount))
     (+ y (* delta-y move-amount))]))

(def movable-robot-states #{:ready :powered-down})

(defn player-robot-path
  [player-id]
  [:players ALL (if-path [:id (partial = player-id)] [:robot])])

(defn update-robot-for-player
  [state player-id new-robot-attrs]
  (transform (player-robot-path player-id) #(merge % new-robot-attrs) state))

(defn can-move-in-direction?
  [board from direction]
  {:pre [(vector? from) (keyword? direction)]}
  (nil? ((:walls (square-at board  from) #{}) direction)))

(defn can-move-to-square?
  [board to direction]
  {:pre [(vector? to) (keyword? direction)]}
  (if-let [new-square (square-at board to)]
    (nil? ((:walls new-square #{})
            ({:west  :east
              :north :south
              :east  :west
              :south :north} direction)))
    true)) ;; can always move off the board

(defn is-move-possible?
  [{:keys [board] :as state} {:keys [robot]} move-amount]
  (let [{:keys [position direction]} robot
        potential-new-position (translate-position position direction move-amount)
        owner-player-at-new-position (owner-player-at-position state potential-new-position)]
    (and (can-move-in-direction? board position direction)
         (can-move-to-square? board potential-new-position direction)
         (or (nil? owner-player-at-new-position)
             (is-move-possible? state (assoc-in owner-player-at-new-position [:robot :direction] direction) move-amount)))))

(def destroyed-robot-attributes {:state :destroyed :position nil :direction nil})

(defn move-player-robot-by-single-square
  [{:keys [board] :as state} {:keys [robot] :as player}]
  (if (movable-robot-states (:state robot))
    (let [{:keys [position direction]} robot
          new-position (if (is-move-possible? state player 1)
                         (translate-position position direction 1)
                         position)
          new-board-square (square-at board new-position)
          new-attrs (cond
                      (nil? new-board-square) destroyed-robot-attributes
                      :else {:position new-position})
          new-state (update-robot-for-player state (:id player) new-attrs)
          owner-player-at-new-position (owner-player-at-position state new-position)]
      (if (and owner-player-at-new-position
               (not= (:id owner-player-at-new-position)
                     (:id player)))
        (move-player-robot-by-single-square new-state
                                            (assoc-in owner-player-at-new-position
                                               [:robot :direction] direction))
        new-state))
    state))

(defmethod execute-player-register* :move
  [state [player-id {:keys [value]}]]
  (reduce
    (fn [state _] (move-player-robot-by-single-square state (player-by-id state player-id)))
    state (range 0 value)))

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

(defmethod execute-player-register* :rotate
  [state [player-id {:keys [value]}]]
  (transform [:players ALL (if-path [:id (partial = player-id)] [:robot :direction])]
                     (get rotate-delta value)
                     state))

(defn on-belt?
  [board position]
  (when position (:belt (square-at board position))))

(defn apply-belt-movement
  [board express-only? {:keys [position direction] :as robot}]
  (if-let [[belt-direction express] (on-belt? board position)]
    (let [position-after-belt (translate-position position belt-direction 1)
          square-after-belt (square-at board position-after-belt)]
      (merge robot
             (cond
               ;; move the robot
               (or (not express-only?) (and express-only? express))
               {:position  position-after-belt
                :direction (if-let [[new-belt-direction] (:belt square-after-belt)]
                             new-belt-direction
                             direction)}
               (nil? square-after-belt) destroyed-robot-attributes)))
    robot))

(defn move-players-along-conveyer-belt
  [{:keys [players board] :as state} express-only?]
  (let [new-player-pos (map (juxt :id (comp (partial apply-belt-movement board express-only?) :robot)) players)]
    (reduce (fn [state [player-id {:keys [position] :as robot}]]
              (if (< 1 (count (filter #(= position (:position (second %))) new-player-pos)))
                state
                (setval (player-robot-path player-id) robot state)))
            state new-player-pos)))

(defn priority
  [state [player-id register]]
  (+ (:priority register)
     (/ 1 (get-in (player-by-id state player-id) [:robot :docking-bay]))))

(defn on-rotator-gear?
  [board position]
  (when position (:rotator (square-at board position))))

(defn apply-rotator-gear-movement
  [board robot]
  (if-let [rotator-direction (on-rotator-gear? board (:position robot))]
    (update robot :direction (get rotate-delta rotator-direction))
    robot))

(defn squares-matching
  "Return all [x y]'s of squares in the board that return truey for the given predicate"
  [board pred]
  (let [[max-x max-y] (board-size board)]
    (keep (fn [y]
            (when-let [x (first (keep (fn [x] (when (pred (square-at board [x y])) x))
                                      (range max-x)))]
              [x y]))
          (range max-y))))

(defn destroy-robot-in-pit
  [state pit-position]
  (if-let [{:keys [id]} (owner-player-at-position state pit-position)]
    (transform (player-robot-path id) #(merge % destroyed-robot-attributes) state)
    state))

(defn into-pits
  [{:keys [board] :as state}]
  (let [pits (squares-matching board :pit)]
    (reduce destroy-robot-in-pit state pits)))

(defn move-rotator-gears [state]
  (transform [:players ALL :robot]
             (partial apply-rotator-gear-movement (:board state))
             state))

(defn all-player-positions [{:keys [players]}] (map (comp :position :robot) players))

(defn all-wall-lasers
  [board]
  (let [laser-positions (squares-matching board :laser)]
    (map (juxt identity (partial square-at board)) laser-positions)))

(defn square-seq
  "Returns a seq of board squares from start-pos to the end of the board"
  [board start-pos direction]
  (lazy-seq
    (when-let [square (square-at board start-pos)]
      (cons [start-pos square]
            (let [next-pos (translate-position start-pos direction 1)]
              (square-seq board
                          next-pos
                          direction))))))

(defn robot-destroyed?
  [robot]
  (<= 10 (:damage robot)))

(defn apply-damage-to-robot
  [robot damage]
  (let [damaged-robot (update robot :damage + damage)]
    (if (robot-destroyed? damaged-robot)
      (merge damaged-robot destroyed-robot-attributes)
      damaged-robot)))

(defn can-laser-pass-out-of-square?
  [square direction]
  (nil? ((:walls square #{}) direction)))

(defn can-laser-pass-into-square?
  [square laser-direction]
  (nil? ((:walls square #{}) (get-in rotate-delta [:u-turn laser-direction]))))

(defn fire-laser
  [{:keys [board] :as state} [laser-position {[laser-wall number-lasers] :laser} :as laser]]
  (let [laser-direction (get-in rotate-delta [:u-turn laser-wall])
        player-positions (set (all-player-positions state))]
    ;; walk the laser through the board, until it hits either a robot, a wall, or the edge of the board
    (reduce (fn [state [position square]]
              (cond
                (and (not= laser-position position)
                     (not (can-laser-pass-into-square? square laser-direction)))
                (reduced state)

                (player-positions position)
                (let [player (owner-player-at-position state position)]
                  (reduced (transform (player-robot-path (:id player))
                                      #(apply-damage-to-robot % number-lasers)
                                      state)))

                (not (can-laser-pass-out-of-square? square laser-direction))
                (reduced state)

                :else state))
            state (square-seq board laser-position laser-direction))))

(defn fire-wall-lasers [{:keys [board] :as state}]
  (let [wall-lasers (all-wall-lasers board)]
    (reduce fire-laser state wall-lasers)))

(defn robot->laser [board {:keys [direction position]}]
  {:pre [position direction]}
  ;; robots shouldn't be able to shoot themselves, so laser should only take effect
  ;; in a square ahead of the robot in its direction. Obvsouly shouldn't be the case
  ;; if there is a wall between the robot and the next square
  (let [advanced-position (translate-position position direction 1)]
    (when (and (can-laser-pass-out-of-square? (square-at board position) direction)
               (can-laser-pass-into-square? (square-at board advanced-position) direction))
      [advanced-position
       {:laser [(get-in rotate-delta [:u-turn direction]) 1]}])))

(defn fire-robot-lasers [{:keys [players board] :as state}]
  (let [robot-lasers (keep (comp (partial robot->laser board) :robot)
                                  (filter (comp :position :robot) players))]
    (reduce fire-laser state robot-lasers)))

(defn apply-touched-flag
  [flag-number {:keys [flags] :as robot}]
  (if (or (empty? flags)
          (= (inc (apply max (:flags robot))) flag-number))
    (update robot :flags conj flag-number)
    robot))

(defn robot-touching-flag
  [state flag-position]
  (if-let [player-at-position (owner-player-at-position state flag-position)]
    (transform (player-robot-path (:id player-at-position))
               (partial apply-touched-flag (:flag (square-at (:board state) flag-position)))
               state)
    state))

(defn touch-flags [{:keys [board] :as state}]
  (let [flag-squares (squares-matching board :flag)]
    (reduce robot-touching-flag state flag-squares)))

(defn robot-places-archive-marker [state archive-square-position]
  (if-let [player-at-position (owner-player-at-position state archive-square-position)]
    (transform (player-robot-path (:id player-at-position))
               #(assoc % :archive-marker archive-square-position)
               state)
    state))

(defn touch-archive-point
  [state]
  (let [archive-squares (squares-matching (:board state) (some-fn :flag :repair))]
    (reduce robot-places-archive-marker state archive-squares)))

(defn execute-register-number
  [state [_ player-id->register]]
  (let [prioritised-players (sort-by (partial priority state) > player-id->register)]
    (->
      (reduce execute-player-register state prioritised-players)
      (move-players-along-conveyer-belt true)
      (move-players-along-conveyer-belt false)
      (into-pits)
      (move-rotator-gears)
      (fire-wall-lasers)
      (fire-robot-lasers)
      (touch-flags)
      (touch-archive-point)))

  ;; 1. move robots
  ;; 2. board elements move
  ;; 3. lasers fire
  ;; 4. touch checkpoints and repair sites
  ;; TODO!!
  )

(defn registers-by-execution-order
  [player-ids->registers]
  (reduce-kv (fn [registers player-id rs]
               (let [r-by-idx (map-indexed #(array-map %1 [[player-id %2]]) rs)]
                 (apply merge-with (comp vec concat) registers r-by-idx)))
             {} player-ids->registers))

(defn execute-each-register
  [game player-ids->registers]
  {:pre [(map? player-ids->registers) ((every-pred :players :board :id :turns :program-deck) (:state game))]}
  ;;TODO: assert each player has a set of registers or is :powered-down

  (update-in game [:state]
             #(reduce execute-register-number % (registers-by-execution-order player-ids->registers))))

(defn player-state-fn
  [{:keys [board]}]
  (let [flag-numbers (set (map (comp :flag (partial square-at board))
                               (squares-matching board :flag)))]
    (fn [{:keys [robot]}]
      (cond (= flag-numbers (:flags robot))
            :finished
            (zero? (:lives robot))
            :dead
            :else
            :playing))))

(defn dec-damage
  [damage]
  (if (zero? damage) damage (dec damage)))

(defn repair-robot-on-square
  [state repair-square]
  (if-let [{:keys [id]} (owner-player-at-position state repair-square)]
    (transform (conj (player-robot-path id) :damage) dec-damage state)
    state))

(defn repair-robots-on-repair-squares
  [state]
  (let [repair-squares (squares-matching (:board state) :repair)]
    (reduce repair-robot-on-square state repair-squares)))

(defn random-adjacent-square
  [{:keys [board players]} [x y]]
  (let [all-adj-squares (->>
                          (concat (map vector (range (dec x) (inc (inc x))) (repeat (dec y)))
                                     [[(dec x) y] [(inc x) y]]
                                     (map vector (range (dec x) (inc (inc x))) (repeat (inc y))))
                          (filter (partial square-at board))
                          (set))
        all-player-squares (set (keep (comp :position :robot) players))]
    (rand-nth (seq (clojure.set/difference all-adj-squares all-player-squares)))))

(defn respawn
  [state {:keys [robot id] :as player}]
  (if (zero? (:lives robot))
    (setval [:players ALL #(= id (:id %)) :state] :dead state)
    (let [player-on-archive (some #(= (:archive-marker robot) (get-in % [:robot :position])) (:players state))]
      (->> state
           (transform (conj (player-robot-path id) :lives) dec)
           (transform (player-robot-path id) #(merge % {:position  (if player-on-archive
                                                                     (random-adjacent-square state (:archive-marker robot))
                                                                     (:archive-marker robot))
                                                        :direction (rand-nth [:west :east :north :south])
                                                        :damage    2}))))))

(defn respawn-destroyed-robots
  [state]
  (reduce respawn state (filter #(= :destroyed (get-in % [:robot :state])) (:players state))))

(defn execute-clean-up
  [{:keys [state] :as game} player-commands]
  (assoc game :state
              (-> state
                  (repair-robots-on-repair-squares)
                  (respawn-destroyed-robots))))

(defrecord RRGameState [state]
  RRGame
  (start-next-turn [game] (update-in game [:state] start-next-turn*))
  (complete-registers [game player-id->registers] (execute-each-register game player-id->registers))
  (players  [game] (get-in game [:state :players]))
  (clean-up [game player-commands] (execute-clean-up game player-commands))
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

(s/def ::board-squares #(= 1 (count (distinct (map count %)))))

(s/def ::seq-board (s/keys :req-un [::board-squares]))

(defn abs
  [x]
  (#?(:clj Math/abs :cljs js/Math.abs) x))

(defn adjacent-to?
  "Is p2 in a square next to p1, incl. diagonally?"
  [[x1 y1 :as p1] [x2 y2 :as p2]]
  (some? (and (#{0 1} (abs (- x1 x2))) (#{0 1} (abs (- y1 y2))))))

(defrecord RRSeqBoard [board-squares]
  RRBoard
  (board-size [board] [(count (first board-squares)) (count board-squares)])
  (square-at [board [x y]]
    (let [[max-x max-y] (board-size board)]
      (when (and  ((set (range 0 max-x)) x) ((set (range 0 max-y)) y))
        (nth (nth board-squares y) x))))
  (move-robot [board [x y] all-robots {:keys [type value]}]
    ;; TODO
    )
  (docking-bay-position [board num] (first (squares-matching board #(= (:docking-bay %) num)))))

(s/fdef RRSeqBoard
        :args (s/cat ::board-squares ::board-squares)
        :ret ::seq-board)

(def blank-square {:walls #{} :repair false :flag nil :belt nil :rotator nil :pit false :laser nil})

(defn docking-bay-square
  [num]
  (assoc blank-square :docking-bay num))

(defn with-walls
  [square & walls]
  (assoc square :walls (set walls)))

(defn with-lasers
  [square laser-wall num]
  {:pre [((:walls square) laser-wall) ((set (range 1 4)) num)]}
  (assoc square :laser [laser-wall num]))

(defn with-belt
  [square belt-direction & [express?]]
  {:pre [(#{:south :north :east :west} belt-direction)]}
  (assoc square :belt [belt-direction express?]))

(defn with-rotator
  [square direction]
  {:pre [(#{:right :left :u-turn} direction)]}
  (assoc square :rotator direction))

(defn with-flag
  [square flag-number]
  {:pre [((set (range 1 6)) flag-number)]}
  (assoc square :flag flag-number))

(defn with-pit
  [square]
  (assoc square :pit true))

(defn with-repair
  [square]
  (assoc square :repair true))

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
              :flags            #{}
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
;; - powering down
;; -
