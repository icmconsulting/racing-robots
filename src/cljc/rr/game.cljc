(ns rr.game
  (:require [com.rpl.specter :refer [transform setval ALL VAL if-path]]
            [clojure.spec :as s])
  #?(:clj (:import [java.util UUID Date])))

(defn uuid-str
  []
  (str
    #?(:cljs (random-uuid)
       :clj  (UUID/randomUUID))))

(defn timestamp
  []
  #?(:clj (.getTime (Date.))
     :cljs (.getTime (js/Date.))))

(defn abs
  [x]
  (#?(:clj Math/abs :cljs js/Math.abs) x))

(defprotocol RRBoard
  (board-size [board])
  (rows [board])
  (square-at [board point])
  (docking-bay-position [board num])) ;; => [status, players-in-order-of-victory-status], status -> :active, :game-over

(defprotocol RRGame
  (id [game])
  (board [game])
  (start-next-turn [game]) ;; => returns a RRGameTurn
  (complete-turn [game turn])
  (after-each-register-for-turn [game turn]) ;; => seq of [game] for each register in turn
  (clean-up [game turn player-commands])
  (update-player-profile [game player-id profile])
  (players  [game])
  (turns [game])
  (victory-status [game]))

(defprotocol RRGameTurn
  (turn-number [turn])
  (deal-cards-to-players [turn])
  (player-enters-registers [turn player-id registers powering-down-next-turn?])
  (player-invalid-response [turn player-id])
  (player-timedout [turn player-id])
  (registers-for-turn [turn])                               ;; => map from player-id -> []
  (registers-required-for-turn [turn])
  (players-with-invalid-response [turn])
  (players-powering-down-next-turn [turn]))

(defn player-by-id
  [state player-id]
  (first (filter #(= player-id (:id %)) (:players state))))

(def ^:dynamic *current-turn*)

(defn robot-event
  [event-type & args]
  {:time (timestamp)
   :type event-type
   :args (when (first args) (vec args))
   :turn (when *current-turn* (turn-number *current-turn*))})

(defn add-robot-event
  [robot event-type & args]
  (assert (some? event-type) "No event type found for robot event")
  (update robot :events conj (apply robot-event event-type args)))

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
  [state player-id new-robot-attrs [event pushed-by]]
  (let [path (player-robot-path player-id)]
    (->> state
         (transform path #(merge % new-robot-attrs))
         (transform path #(add-robot-event % event pushed-by)))))

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
  [{:keys [board] :as state} {:keys [robot] :as player} & [pushed-by-robot]]
  (if (movable-robot-states (:state robot))
    (let [{:keys [position direction]} robot
          new-position (if (is-move-possible? state player 1)
                         (translate-position position direction 1)
                         position)
          new-board-square (square-at board new-position)
          [new-attrs event] (cond
                              (nil? new-board-square) [destroyed-robot-attributes :destroyed/fell-off-board]
                              :else [{:position new-position}
                                     (if (= position new-position)
                                       :move/blocked
                                       (keyword "move" (name direction)))])
          new-state (update-robot-for-player state (:id player) new-attrs [event pushed-by-robot])
          owner-player-at-new-position (owner-player-at-position state new-position)]
      (if (and owner-player-at-new-position
               (not= (:id owner-player-at-new-position)
                     (:id player)))
        (move-player-robot-by-single-square new-state
                                            (assoc-in owner-player-at-new-position
                                                      [:robot :direction] direction)
                                            robot)
        new-state))
    state))

(def rotate-delta
  {:left   {:north :west
            :west  :south
            :south :east
            :east  :north}
   :right  {:north :east
            :east  :south
            :south :west
            :west  :north}
   :u-turn {:north :south
            :south :north
            :east  :west
            :west  :east}})

(defmethod execute-player-register* :move
  [state [player-id {:keys [value]}]]
  (let [backward? (= -1 value)]
    (reduce
      (fn [state v]
        (move-player-robot-by-single-square state
                                            (if backward?
                                              (update-in (player-by-id state player-id)
                                                         [:robot :direction]
                                                         (:u-turn rotate-delta))
                                              (player-by-id state player-id))))
      state (if backward? [value] (range 0 value)))))

(defmethod execute-player-register* :rotate
  [state [player-id {:keys [value]}]]
  (transform [:players ALL (if-path [:id (partial = player-id)] [:robot])]
             (comp
               #(add-robot-event % (keyword "rotate" (name value)))
               #(update % :direction (get rotate-delta value)))
             state))

(defn on-belt?
  [board position]
  (when position (:belt (square-at board position))))

(defn apply-belt-movement
  [board express-only? {:keys [position direction] :as robot}]
  (if-let [[belt-direction express] (on-belt? board position)]
    (let [position-after-belt (translate-position position belt-direction 1)
          square-after-belt (square-at board position-after-belt)
          [new-attrs event] (cond
                              (nil? square-after-belt) [destroyed-robot-attributes :destroyed/belt-pushed-off-board]

                              ;; move the robot
                              (or (not express-only?) (and express-only? express))
                              [{:position  position-after-belt
                                :direction (if-let [[new-belt-direction] (:belt square-after-belt)]
                                             new-belt-direction
                                             direction)} :belt/moved-by-belt])]
      (cond->
        (merge robot new-attrs)
        event (add-robot-event event)))
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
    (-> robot
        (update :direction (get rotate-delta rotator-direction))
        (add-robot-event (keyword "rotator" (name rotator-direction))))
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
    (transform (player-robot-path id)
               (comp
                 #(add-robot-event % :destroyed/fell-into-pit)
                 #(merge % destroyed-robot-attributes))
               state)
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
  [{:keys [board] :as state} [laser-position {[laser-wall number-lasers] :laser fired-by-robot :robot} :as laser]]
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
                                      (comp
                                        #(add-robot-event %
                                                          (if fired-by-robot
                                                            :damage/by-robot-laser
                                                            :damage/by-wall-laser)
                                                          (or fired-by-robot laser-position))
                                        #(apply-damage-to-robot % number-lasers))
                                      state)))

                (not (can-laser-pass-out-of-square? square laser-direction))
                (reduced state)

                :else state))
            state (square-seq board laser-position laser-direction))))

(defn fire-wall-lasers [{:keys [board] :as state}]
  (let [wall-lasers (all-wall-lasers board)]
    (reduce fire-laser state wall-lasers)))

(defn robot->laser [board {:keys [direction position] :as robot}]
  {:pre [position direction]}
  ;; robots shouldn't be able to shoot themselves, so laser should only take effect
  ;; in a square ahead of the robot in its direction. Obvsouly shouldn't be the case
  ;; if there is a wall between the robot and the next square
  (let [advanced-position (translate-position position direction 1)]
    (when (and (can-laser-pass-out-of-square? (square-at board position) direction)
               (can-laser-pass-into-square? (square-at board advanced-position) direction))
      [advanced-position
       {:laser [(get-in rotate-delta [:u-turn direction]) 1] :robot robot}])))

(defn fire-robot-lasers [{:keys [players board] :as state}]
  (let [robot-lasers (keep (comp (partial robot->laser board) :robot)
                           (filter (comp :position :robot) players))]
    (reduce fire-laser state robot-lasers)))

(defn apply-touched-flag
  [flag-number {:keys [flags] :as robot}]
  (if (or (and (empty? flags) (= flag-number 1))
          (= (first (sort > (:flags robot))) (dec flag-number)))
    (-> (update robot :flags conj flag-number)
        (add-robot-event :victory/touched-flag flag-number))
    robot))

(defn robot-touching-flag
  [state flag-position]
  (if-let [player-at-position (owner-player-at-position state flag-position)]
    (let [flag-number (:flag (square-at (:board state) flag-position))]
      (transform (player-robot-path (:id player-at-position))
                 (partial apply-touched-flag flag-number)
                 state))
    state))

(defn touch-flags [{:keys [board] :as state}]
  (let [flag-squares (squares-matching board :flag)]
    (reduce robot-touching-flag state flag-squares)))

(defn robot-places-archive-marker [state archive-square-position]
  (if-let [player-at-position (owner-player-at-position state archive-square-position)]
    (transform (player-robot-path (:id player-at-position))
               (comp
                 #(add-robot-event % :archive-marker/moved-to archive-square-position)
                 #(assoc % :archive-marker archive-square-position))
               state)
    state))

(defn player-still-active?
  [player]
  (not= :dead (:state player)))

(defn touch-archive-point
  [state]
  (let [archive-squares (squares-matching (:board state) (some-fn :flag :repair))]
    (reduce robot-places-archive-marker state archive-squares)))

(defn calculate-victory-status
  [state]
  (let [{:keys [board players]} state
        active-players (filter player-still-active? players)
        flags-on-board (map (comp :flag (partial square-at board)) (squares-matching board :flag))
        target-flag (when (seq flags-on-board) (reduce max flags-on-board))
        player-has-target-flag? #((get-in % [:robot :flags]) target-flag)]
    (cond
      (and target-flag (seq (filter player-has-target-flag? players))) ;; someone has landed on the last flag
      [:game-over (map #(assoc % :victory-state (if (player-has-target-flag? %) :winner :loser)) players)]

      (= 1 (count active-players))
      [:game-over (map #(assoc % :victory-state (if (player-still-active? %) :winner :loser)) players)]

      (empty? active-players)
      [:game-over (let [flag-count-fn (comp count :flags :robot)
                        max-flags (apply max (map flag-count-fn players))]
                    (map #(assoc % :victory-state (if (= max-flags (flag-count-fn %)) :winner :loser)) players))]

      :else [:active players])))

(defn execute-register-number
  [state [_ player-id->register]]
  (let [prioritised-players (sort-by (partial priority state) > player-id->register)
        state (->
                (reduce execute-player-register state prioritised-players)
                (move-players-along-conveyer-belt true)
                (move-players-along-conveyer-belt false)
                (into-pits)
                (move-rotator-gears)
                (fire-wall-lasers)
                (fire-robot-lasers)
                (touch-flags)
                (touch-archive-point))]
    (if (= :game-over (first (calculate-victory-status state)))
      (reduced state)
      state)))

(defn registers-by-execution-order
  [player-ids->registers]
  (reduce-kv (fn [registers player-id rs]
               (let [r-by-idx (map-indexed #(array-map %1 [[player-id %2]]) rs)]
                 (apply merge-with (comp vec concat) registers r-by-idx)))
             {} player-ids->registers))

(defn apply-locked-registers
  [player-ids->registers state]
  (into {}
        (map (fn [[player-id registers]]
               (let [{:keys [robot]} (player-by-id state player-id)]
                 [player-id (when-not (:powered-down? robot)
                              (vec (concat registers (reverse (:locked-registers robot)))))]))
             player-ids->registers)))

(defn execute-registers-for-turn
  [state turn]
  (reductions execute-register-number state
              (-> turn
                  (registers-for-turn)
                  (apply-locked-registers state)
                  (registers-by-execution-order))))

(defn heal-powered-down-robots
  [game]
  (transform [:state :players ALL (if-path [:robot :powered-down? true?] [:robot])]
             (comp
               #(add-robot-event % :damage/repair-powered-down)
               #(assoc % :damage (min 0 (:damage %)))) game))

(def invalid-response-damage-penalty 5)

(defn damage-players-with-invalid-responses
  [game turn]
  (reduce
    (fn [game player-id]
      (transform (vec (cons :state (player-robot-path player-id)))
                 (comp
                   #(add-robot-event % :damage/invalid-response)
                   #(apply-damage-to-robot % invalid-response-damage-penalty)) game))
    game (players-with-invalid-response turn)))

(defn execute-turn
  [game turn]
  {:pre [((every-pred :players :board :id :turns :program-deck) (:state game))]}
  (let [base-game (-> game
                      (assoc-in [:state :turns (turn-number turn)] turn)
                      (heal-powered-down-robots)
                      (damage-players-with-invalid-responses turn))
        state-changes (execute-registers-for-turn (:state base-game) turn)]
    (map #(assoc base-game :state %) state-changes)))

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

(defn repair-damage
  [{:keys [damage] :as robot}]
  (if (zero? damage)
    robot
    (-> robot
        (update :damage dec)
        (add-robot-event :damage/repair))))

(defn repair-robot-on-square
  [state repair-square]
  (if-let [{:keys [id]} (owner-player-at-position state repair-square)]
    (transform (player-robot-path id) repair-damage state)
    state))

(defn repair-robots-on-repair-squares
  [state]
  (let [repair-squares (squares-matching (:board state) :repair)]
    (reduce repair-robot-on-square state repair-squares)))

(defn can-be-placed-on-square?
  [board position]
  (let [square (square-at board position)]
    (and square
      (not (:pit square)))))

(defn random-adjacent-square
  [{:keys [board players]} [x y]]
  (let [all-adj-squares (->>
                          (concat (map vector (range (dec x) (inc (inc x))) (repeat (dec y)))
                                  [[(dec x) y] [(inc x) y]]
                                  (map vector (range (dec x) (inc (inc x))) (repeat (inc y))))
                          (filter (partial can-be-placed-on-square? board))
                          (set))
        all-player-squares (set (keep (comp :position :robot) players))]
    (rand-nth (seq (clojure.set/difference all-adj-squares all-player-squares)))))

(defn respawn-robot
  [new-position robot]
  (merge robot {:position         new-position
                :locked-registers []
                :direction        (rand-nth [:west :east :north :south])
                :damage           2}))

(defn apply-player-bonus
  [{:keys [bonus-modifier]} robot]
  (if bonus-modifier
    (-> robot
        (bonus-modifier)
        (add-robot-event :player/bonus-applied))
    robot))

(defn respawn
  [state {:keys [robot id] :as player}]
  (if (and (zero? (:lives robot)) (not= :dead (:state player)))
    (->> state
         (setval [:players ALL #(= id (:id %)) :state] :dead)
         (transform (player-robot-path id) #(assoc % :position nil))
         (transform (player-robot-path id) #(add-robot-event % :player/died)))
    (let [player-on-archive (some #(= (:archive-marker robot) (get-in % [:robot :position])) (:players state))]
      (->> state
           (transform (conj (player-robot-path id) :lives) dec)
           (transform (player-robot-path id) (comp
                                               (partial apply-player-bonus player)
                                               (partial respawn-robot (if player-on-archive
                                                                            (random-adjacent-square state (:archive-marker robot))
                                                                            (:archive-marker robot)))))
           (transform (player-robot-path id) #(add-robot-event % :player/lost-life))))))

(defn players-with-destroyed-robots
  [state]
  (->> (filter #(= :destroyed (get-in % [:robot :state])) (:players state))
       (remove #(= :dead (:state %)))))

(defn respawn-destroyed-robots
  [state]
  (reduce respawn state (players-with-destroyed-robots state)))

(defn reset-destroyed-robot-state
  [state {:keys [id] :as player}]
  (if-not (= (:state player) :dead)
    (transform (player-robot-path id)
               #(assoc % :state :ready)
               state)
    state))

(defn reset-all-robot-state
  [state]
  (reduce reset-destroyed-robot-state state (players-with-destroyed-robots state)))

(defn num-cards-for-this-turn
  [{:keys [robot] :as player}]
  (if (:powered-down? robot)
    0
    (- 9 (:damage robot))))

(defn num-registers-for-this-turn
  [{:keys [robot] :as player}]
  (let [cards-for-turn (num-cards-for-this-turn player)]
    (if (< 4 cards-for-turn) 5 cards-for-turn)))

(defn calculate-locked-registers
  [locked-registers player-registers-for-turn num-registers-for-next-turn]
  (take (- 5 num-registers-for-next-turn)
        (concat locked-registers (reverse player-registers-for-turn))))

(defn lock-robot-registers
  [num-registers-for-this-turn registers-for-turn robot]
  (let [old-locked-registers (:locked-registers robot)
        new-locked-registers (calculate-locked-registers (:locked-registers robot)
                                                         registers-for-turn
                                                         num-registers-for-this-turn)]
    (cond-> robot
            true (assoc :locked-registers new-locked-registers)
            (not= old-locked-registers new-locked-registers) (add-robot-event :registers/locked-register-change old-locked-registers))))

(defn lock-player-registers
  [state turn]
  (let [damaged-players (->> (:players state)
                             (filter player-still-active?)
                             (map #(assoc % :num-registers (num-registers-for-this-turn %)))
                             (filter #(not= 5 (:num-registers %))))]
    (reduce (fn [state {:keys [id] :as damaged-player}]
              (let [registers (-> turn (registers-for-turn) (get id))]
                (transform (player-robot-path id)
                           (partial lock-robot-registers (:num-registers damaged-player) registers)
                           state)))
            state damaged-players)))

(defn allowable-clean-up-commands
  [{:keys [robot id]} turn]
  (cond-> #{:no-action}
          (and (= :destroyed (:state robot)) ((set (map :id (players-powering-down-next-turn turn))) id))
          (conj :power-down-override)
          (:powered-down? robot) (conj :power-down)))

(defn robot-maybe-powering-down
  [player-ids-powering-down players-with-robots-destroyed players-overriding {:keys [id]} robot]
  (let [[powering-down? event-type] (if (and (players-with-robots-destroyed id) (players-overriding id))
                                      [false :power-down/overridden]
                                      [(some? (player-ids-powering-down id)) :power-down/start])]
    (cond-> robot
            true (assoc :powered-down? powering-down?)
            powering-down? (add-robot-event event-type))))

(defn power-down-players
  [state turn player-commands]
  (let [players-powering-down (players-powering-down-next-turn turn)
        player-ids (set (concat (map :id players-powering-down)
                                (map key (filter #(= :power-down (val %)) player-commands))))
        players-overriding (set (map key (filter #(= :power-down-override (val %)) player-commands)))
        robots-destroyed (set (map :id (players-with-destroyed-robots state)))]
    (transform [:players ALL player-still-active? VAL :robot]
               (partial robot-maybe-powering-down player-ids robots-destroyed players-overriding)
               state)))

(defn execute-clean-up
  [{:keys [state] :as game} turn player-commands]
  (assoc game :state
              (-> state
                  (repair-robots-on-repair-squares)
                  (respawn-destroyed-robots)
                  (lock-player-registers turn)
                  (power-down-players turn player-commands)
                  (reset-all-robot-state))))


(defn cut-and-deal-cards
  [players deck]
  (:players
    (reduce (fn [{:keys [remaining-deck players] :as r} player]
              (let [cards-due (num-cards-for-this-turn player)]
                (assoc r :remaining-deck (drop cards-due remaining-deck)
                         :players (conj players (assoc player :dealt (take cards-due remaining-deck))))))
            {:remaining-deck deck :players []} players)))

(defn registers-for-players
  [{:keys [players]}]
  (zipmap players (map num-registers-for-this-turn players)))

(defrecord RRGameTurnState [turn-number players shuffled-deck]
  RRGameTurn
  (turn-number [_] turn-number)
  (deal-cards-to-players [_] (cut-and-deal-cards players shuffled-deck))
  (player-enters-registers [turn player-id registers powering-down-next-turn?]
    (cond-> (assoc-in turn [:registers player-id] registers)
            powering-down-next-turn? (update-in [:powering-down] (comp set conj) player-id)))
  (player-invalid-response [turn player-id] (update-in turn [:invalid] (comp set conj) player-id))
  (player-timedout [turn player-id] (update-in turn [:timed-out] (comp set conj) player-id))
  (registers-for-turn [turn] (:registers turn)) ;; => map from player -> []
  (registers-required-for-turn [turn] (registers-for-players turn)) ;; => map from player -> number
  (players-with-invalid-response [turn] (:invalid turn))
  (players-powering-down-next-turn [turn] (map (partial player-by-id turn) (:powering-down turn))))

(defn deck-for-next-turn
  [state]
  (let [cards-in-deck (set (:program-deck state))
        locked-registers (set (mapcat (comp :locked-registers :robot) (:players state)))]
    (seq (clojure.set/difference cards-in-deck locked-registers))))

(defn new-game-turn
  [state]
  (when-not (= :game-over (first (calculate-victory-status state)))
    (let [new-turn-number (inc (count (:turns state)))]
      (->RRGameTurnState new-turn-number
                         (vec (filter player-still-active? (:players state)))
                         (shuffle (deck-for-next-turn state))))))

(defn do-update-player-profile
  [game player-id profile]
  (transform [:state :players ALL (if-path [:id (partial = player-id)] [])]
             (comp
               #(assoc-in % [:robot :name] (:robot-name %))
               #(merge % (select-keys profile [:name :robot-name :avatar])))
             game))

(defrecord RRGameState [state]
  RRGame
  (id [game] (:id state))
  (board [game] (get-in game [:state :board]))
  (start-next-turn [game] (new-game-turn (:state game)))
  (complete-turn [game turn] (binding [*current-turn* turn] (last (execute-turn game turn))))
  (after-each-register-for-turn [game turn] (binding [*current-turn* turn] (doall (execute-turn game turn))))
  (update-player-profile [game player-id profile] (do-update-player-profile game player-id profile))
  (players  [game] (get-in game [:state :players]))
  (turns [game] (get-in game [:state :turns]))
  (clean-up [game turn player-commands] (binding [*current-turn* turn] (execute-clean-up game turn player-commands)))
  (victory-status [game] (calculate-victory-status (:state game))))

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

(defn adjacent-to?
  "Is p2 in a square next to p1, incl. diagonally?"
  [[x1 y1 :as p1] [x2 y2 :as p2]]
  (some? (and (#{0 1} (abs (- x1 x2))) (#{0 1} (abs (- y1 y2))))))

(defrecord RRSeqBoard [board-squares]
  RRBoard
  (board-size [board] [(count (first board-squares)) (count board-squares)])
  (rows [board] (:board-squares board))
  (square-at [board [x y]]
    (let [[max-x max-y] (board-size board)]
      (when (and  ((set (range 0 max-x)) x) ((set (range 0 max-y)) y))
        (nth (nth board-squares y) x))))
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

(def blank-robot
  {:direction        :north
   :state            :ready
   :lives            4
   :damage           0
   :flags            #{}
   :locked-registers []
   :events []})

(defn bonus-2-damage-points-modifier
  [robot]
  (update robot :damage - 2))

(defn player-with-robot
  [board idx player]
  (let [start-position (docking-bay-position board (inc idx))]
    (assoc player
      :robot (merge {:name             (:robot-name player)
                     :position         start-position
                     :archive-marker   start-position
                     :docking-bay      (inc idx)}
                    blank-robot))))

(defn player-with-game-id
  [player]
  (assoc player :id (uuid-str)))

(defn player-with-bot-instance
  [player]
  (if-let [bot-instance-fn (:bot-instance-fn player)]
    (assoc player :bot-instance (bot-instance-fn player))
    player))

(defn player-with-bonus-modifier
  [player]
  (update player :robot (partial apply-player-bonus player)))

(defn new-game
  [players board]
  (let [program-deck (shuffle program-card-deck)]
    (->RRGameState
      {:id           (uuid-str)
       :program-deck program-deck
       :board        board
       :players      (vec (map-indexed (comp player-with-bonus-modifier
                                             player-with-bot-instance
                                             player-with-game-id
                                             (partial player-with-robot board))
                                       (shuffle players)))
       :turns        {}})))

;; TODO:

;; - log hitting wall?

;; - start test harness by 6th Oct - includes the game engine/driver/controller
;; - timed out player surplus cards passed to next player
;; - player becomes inactive (x number of errors in game) -> player is removed from game
;; - player cheats with cards (cards not dealt) lose life and move back to archive-marker
