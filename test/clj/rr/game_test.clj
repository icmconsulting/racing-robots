(ns rr.game-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer :all]
            [rr.game :refer :all]))

(defn turn-with-registers
  ([registers] (turn-with-registers 0 registers))
  ([turn-number registers]
    (reify RRGameTurn
      (turn-number [turn] turn-number)
      (deal-cards-to-players [turn])
      (player-timedout [turn player-id])
      (registers-for-turn [turn] (zipmap (map (fn [id] {:id id}) (keys registers)) (vals registers)))
      (players-powering-down-next-turn [turn]))))

(def t turn-with-registers)

(def blank-board
  (->RRSeqBoard
    (concat
      (repeat 15 (repeat 12 blank-square))
      [(concat (repeat 4 blank-square)
               (map docking-bay-square (range 1 5))
               (repeat 4 blank-square))])))

;; test robot movement registers
(defn player-state
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :state]))

(defn player-position
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :position]))

(defn player-direction
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :direction]))

(defn player-damage
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :damage]))

(defn player-flags
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :flags]))

(defn player-archive-marker
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :archive-marker]))

(defn player-lives
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :lives]))

(defn player-locked-registers
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :locked-registers]))

(deftest moving-robot
  (let [base-game (new-game [{:name "player 1"}] blank-board)
        player-id (get-in base-game [:state :players 0 :id])]
    (testing "Moving robot north"
      (is (= [4 10]
             (player-position
               (complete-turn base-game
                              (t {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))}))
               1))))
    (testing "Moving robot east"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :east)
                          (assoc-in [:state :players 0 :robot :position] [0 0]))]
        (is (= [5 0]
               (player-position
                 (complete-turn base-game
                                (t {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))}))
                 1)))))
    (testing "Moving robot west"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :west)
                          (assoc-in [:state :players 0 :robot :position] [5 0]))]
        (is (= [0 0]
               (player-position
                 (complete-turn base-game
                                (t {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))}))
                 1)))))
    (testing "Moving robot south"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [0 0]))]
        (is (= [0 5]
               (player-position
                 (complete-turn base-game
                                (t {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))}))
                 1)))))

    (testing "Moving robot off board destroys it"
      (let [base-games [(assoc-in base-game [:state :players 0 :robot :direction] :south)
                        (-> (assoc-in base-game [:state :players 0 :robot :direction] :east)
                            (assoc-in [:state :players 0 :robot :position] [11 0]))
                        (-> (assoc-in base-game [:state :players 0 :robot :direction] :west)
                            (assoc-in [:state :players 0 :robot :position] [4 0]))
                        (-> (assoc-in base-game [:state :players 0 :robot :direction] :north)
                            (assoc-in [:state :players 0 :robot :position] [4 4]))]]
        (doseq [game base-games]
          (let [base-game (complete-turn game
                                         (t {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))}))]
            (is (= :destroyed (player-state base-game 1)))
            (is (nil? (player-position base-game 1)))
            (is (nil? (player-direction base-game 1)))))))))

(deftest robot-movement-interactions
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] blank-board)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "Example from the game instructions"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [0 0])
                          (assoc-in [:state :players 1 :robot :position] [0 1])
                          (assoc-in [:state :players 1 :robot :direction] :east)
                          (complete-turn (t {player1-id [{:type :move :value 1 :priority 330}]
                                             player2-id [{:type :move :value 1 :priority 290}]})))]
        (is (= [0 1] (player-position base-game 1)))
        (is (= [1 2] (player-position base-game 2)))))

    (testing "Pushing robot over edge destroys it"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [0 0])
                          (assoc-in [:state :players 1 :robot :position] [0 1])
                          (assoc-in [:state :players 1 :robot :direction] :north)
                          (complete-turn (t {player1-id [{:type :move :value 1 :priority 290}]
                                             player2-id [{:type :move :value 1 :priority 330}]})))]
        (is (= :destroyed (player-state base-game 1)))
        (is (= :ready (player-state base-game 2)))))))

(def board-with-walls
  (->RRSeqBoard
    (concat
      (repeat 2 (repeat 4 blank-square))
      [[(with-walls blank-square :east) (with-walls blank-square :south) blank-square blank-square]]
      [(repeat 4 blank-square)]
      [(map docking-bay-square (range 1 5))])))

(deftest robot-movement-with-walls
  (let [base-single-player-game (new-game [{:name "player 1"}] board-with-walls)
        player1-id (get-in base-single-player-game [:state :players 0 :id])]
    (testing "Player against a wall can't move in that direction"
      (let [base-single-player-game (-> (assoc-in base-single-player-game [:state :players 0 :robot :direction] :east)
                                        (assoc-in [:state :players 0 :robot :position] [0 2])
                                        (complete-turn (t {player1-id [{:type :move :value 1 :priority 290}]})))]
        (is (= [0 2] (player-position base-single-player-game 1)))))
    (testing "Player against a wall can't move in that direction when moving more than 1 space"
      (let [base-single-player-game (-> (assoc-in base-single-player-game [:state :players 0 :robot :direction] :south)
                                        (assoc-in [:state :players 0 :robot :position] [1 1])
                                        (complete-turn (t {player1-id [{:type :move :value 3 :priority 290}]})))]
        (is (= [1 2] (player-position base-single-player-game 1)))))
    (testing "Player in adjacent square to one with a wall can't move through that wall"
      (let [base-single-player-game (-> (assoc-in base-single-player-game [:state :players 0 :robot :direction] :west)
                                        (assoc-in [:state :players 0 :robot :position] [1 2])
                                        (complete-turn (t {player1-id [{:type :move :value 3 :priority 290}]})))]
        (is (= [1 2] (player-position base-single-player-game 1)))))
    (testing "Robots don't take damage when hitting a wall"
      (let [base-single-player-game (-> (assoc-in base-single-player-game [:state :players 0 :robot :direction] :west)
                                        (assoc-in [:state :players 0 :robot :position] [1 2])
                                        (complete-turn (t {player1-id [{:type :move :value 3 :priority 290}]})))]
        (is (= 0 (player-damage base-single-player-game 1)))))))

(deftest robot-interaction-with-walls
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"} {:name "player 3"}] board-with-walls)
        player1-id (get-in base-game [:state :players 0 :id])]
    (testing "Robot can't push another robot thats already against a wall"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [1 1])
                          (assoc-in [:state :players 1 :robot :direction] :east)
                          (assoc-in [:state :players 1 :robot :position] [1 2])
                          (complete-turn (t {player1-id [{:type :move :value 1 :priority 290}]})))]
        (is (= [1 1] (player-position base-game 1)))
        (is (= [1 2] (player-position base-game 2)))))
    (testing "Robot can't push another robot further than a wall"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [1 0])
                          (assoc-in [:state :players 1 :robot :direction] :east)
                          (assoc-in [:state :players 1 :robot :position] [1 1])
                          (complete-turn (t {player1-id [{:type :move :value 3 :priority 290}]})))]
        (is (= [1 1] (player-position base-game 1)))
        (is (= [1 2] (player-position base-game 2)))))
    (testing "Robot can't push another 2 robots further than a wall"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [1 0])
                          (assoc-in [:state :players 1 :robot :direction] :east)
                          (assoc-in [:state :players 1 :robot :position] [1 1])
                          (assoc-in [:state :players 2 :robot :direction] :west)
                          (assoc-in [:state :players 2 :robot :position] [1 2])
                          (complete-turn (t {player1-id [{:type :move :value 3 :priority 290}]})))]
        (is (= [1 0] (player-position base-game 1)))
        (is (= [1 1] (player-position base-game 2)))
        (is (= [1 2] (player-position base-game 3)))))))

(def board-with-belts
  (->RRSeqBoard
    (concat
      [(concat [blank-square (with-belt blank-square :south true)] (repeat 2 blank-square))]
      [[blank-square (with-belt blank-square :south true) (with-belt blank-square :west true) blank-square]]
      [(repeat 4 (with-belt blank-square :east))]
      [[(with-belt blank-square :east) (with-belt blank-square :south) (with-belt blank-square :west) blank-square]]
      [(map docking-bay-square (range 1 5))])))

(deftest conveyer-belt-interaction
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"} {:name "player 3"}] board-with-belts)
        player1-id (get-in base-game [:state :players 0 :id])
        player3-id (get-in base-game [:state :players 2 :id])]

    (testing "Robot on a non express belt after a turn is moved by 1 square in the direction of the belt"
      (let [base-single-player-game (complete-turn base-game (t {player1-id [{:type :move :value 2 :priority 290}]}))]
        (is (= [1 2] (player-position base-single-player-game 1)))))

    (testing "Robot on an express belt after a turn is moved by 2 squares in the direction of the belt"
      (let [base-single-player-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :east)
                                        (assoc-in [:state :players 0 :robot :position] [0 0])
                                        (complete-turn (t {player1-id [{:type :move :value 1 :priority 290}]})))]
        (is (= [1 2] (player-position base-single-player-game 1)))))

    (testing "Robots being moved into a conflicting spot will not be moved by the belt"
      (let [two-player-game (complete-turn base-game (t {player1-id [{:type :move :value 1 :priority 290}]
                                                         player3-id [{:type :move :value 1 :priority 330}]}))]
        (is (= [0 3] (player-position two-player-game 1)))
        (is (= [2 3] (player-position two-player-game 3)))))

    (testing "Robot being moved off a belt onto a square with a conflicting spot will not be moved"
      (let [single-player-game (complete-turn base-game (t {player1-id [{:type :move :value 1 :priority 290}
                                                                        {:type :move :value 0 :priority 290}]}))] ;;not realistic, but only way on this map
        (is (= [1 3] (player-position single-player-game 1)))))

    (testing "Robots are moved around corner on corner belts"
      (let [single-player-game (-> base-game
                                   (assoc-in [:state :players 0 :robot :direction] :south)
                                   (assoc-in [:state :players 0 :robot :position] [2 2])
                                   (assoc-in [:state :players 1 :robot :position] [0 0])
                                   (complete-turn (t {player1-id [{:type :move :value 2 :priority 290}
                                                                  {:type :move :value 0 :priority 290}]})))] ;;not realistic, but only way on this map
        (is (= [2 4] (player-position single-player-game 1)))
        (is (= :south (player-direction single-player-game 1)))))

    (testing "Robots are moved around corner on corner belts, and rotated if required"
      (let [single-player-game (-> base-game
                                   (assoc-in [:state :players 0 :robot :direction] :west)
                                   (assoc-in [:state :players 0 :robot :position] [3 1])
                                   (complete-turn (t {player1-id [{:type :move :value 1 :priority 290}]})))]
        (is (= [1 2] (player-position single-player-game 1)))
        (is (= :east (player-direction single-player-game 1)))))

    (testing "Robots are destroyed if a belt moves them off the board"
      (let [single-player-game (-> base-game
                                   (assoc-in [:state :players 0 :robot :position] [3 4])
                                   (complete-turn (t {player1-id [{:type :move :value 2 :priority 290}]})))]
        (is (= :destroyed (player-state single-player-game 1)))))))

(def board-with-rotators
  (->RRSeqBoard
    (concat
      [(concat [(with-rotator blank-square :left)] (repeat 3 blank-square))]
      [(repeat 4 blank-square)]
      [(concat (repeat 3 blank-square) [(with-rotator blank-square :right)])]
      [(concat (repeat 3 blank-square) [(with-rotator blank-square :u-turn)])]
      [(map docking-bay-square (range 1 5))])))

(deftest rotator-interaction
  (let [base-game (new-game [{:name "player 1"}] board-with-rotators)
        player1-id (get-in base-game [:state :players 0 :id])]
    (testing "Robot landing on a rotate-left square is rotated left"
      (let [base-single-player-game (complete-turn base-game (t {player1-id [{:type :move :value 2 :priority 290}
                                                                             {:type :move :value 2 :priority 290}]}))]
        (is (= :west (player-direction base-single-player-game 1)))))

    (testing "Robot landing on a rotator right square is rotated right"
      (let [base-single-player-game (complete-turn base-game (t {player1-id [{:type :move :value 2 :priority 290}
                                                                             {:type :rotate :value :right :priority 100}
                                                                             {:type :move :value 3 :priority 290}]}))]
        (is (= :south (player-direction base-single-player-game 1)))))

    (testing "Robot landing on a uturn rotator rotated 180 degrees"
      (let [base-single-player-game (->
                                      base-game
                                      (assoc-in [:state :players 0 :robot :direction] :east)
                                      (complete-turn (t {player1-id [{:type :move :value 3 :priority 290}
                                                                     {:type :rotate :value :left :priority 100}
                                                                     {:type :move :value 1 :priority 290}]})))]
        (is (= :south (player-direction base-single-player-game 1)))))))

(def board-with-lasers
  (->RRSeqBoard
    (concat
      [(concat [(-> blank-square (with-walls :west :north) (with-lasers :north 1))] (repeat 3 blank-square))]
      [(repeat 4 blank-square)]
      [[(with-walls blank-square :south) (-> blank-square (with-walls :west) (with-lasers :west 3))
        (with-belt blank-square :north) blank-square]]
      [(repeat 4 blank-square)]
      [(map docking-bay-square (range 1 5))])))

(deftest wall-laser-interaction
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] board-with-lasers)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "Robot landing on a single laser square gains 1 damage"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :west)
                          (assoc-in [:state :players 0 :robot :position] [2 0])
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}]})))]
        (is (= 1 (player-damage base-game 1)))))

    (testing "Robot hiding behind a wall does not gain damage from a laser"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :north)
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}]})))]
        (is (zero? (player-damage base-game 1)))))

    (testing "Lasers don't pass through robots - only first one takes the damage"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :west)
                          (assoc-in [:state :players 0 :robot :position] [2 0])
                          (assoc-in [:state :players 1 :robot :direction] :west)
                          (assoc-in [:state :players 1 :robot :position] [2 1])
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}]
                                             player2-id [{:type :move :value 2 :priority 100}]})))]
        (is (= 1 (player-damage base-game 1)))
        (is (zero? (player-damage base-game 2)))))

    (testing "Robot landing on a square with 3 lasers square gains 3 damage"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :north)
                          (assoc-in [:state :players 0 :robot :position] [3 4])
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}]})))]
        (is (= [3 2] (player-position base-game 1)))
        (is (= 3 (player-damage base-game 1)))))

    (testing "Robot landing on a belt in path of a laser, where belt takes him out of path of laser, incurs no damage"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :north)
                          (assoc-in [:state :players 0 :robot :position] [2 4])
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}]})))]
        (is (= [2 1] (player-position base-game 1)))
        (is (zero? (player-damage base-game 1)))))))

(deftest robot-laser-interaction
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] board-with-walls)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "Robot in immediate line of sight of other robot single damage point"
      (let [base-game (complete-turn base-game (t {player1-id [{:type :move :value 1 :priority 100}
                                                               {:type :rotate :value :right :priority 100}]
                                                   player2-id [{:type :move :value 1 :priority 100}
                                                               {:type :rotate :value :right :priority 100}]}))]
        (is (zero? (player-damage base-game 1)))
        (is (= 1 (player-damage base-game 2)))))

    (testing "Robot hiding behind a wall does not gain damage from a robot laser"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :west)
                          (assoc-in [:state :players 0 :robot :position] [2 2])
                          (complete-turn (t {player1-id [{:type :move :value 1 :priority 100}
                                                         {:type :rotate :value :left :priority 100}]
                                             player2-id [{:type :move :value 1 :priority 100}
                                                         {:type :move :value -1 :priority 100}]})))]
        (is (zero? (player-damage base-game 1)))
        (is (zero? (player-damage base-game 2)))))))

(def board-with-flags
  (->RRSeqBoard
    (concat
      [(concat [blank-square (with-flag blank-square 1)] (repeat 2 blank-square))]
      [(concat (repeat 3 blank-square) [(with-flag blank-square 3)])]
      [(concat (repeat 2 blank-square) [(with-flag blank-square 2) blank-square])]
      [(repeat 4 blank-square)]
      [(map docking-bay-square (range 1 5))])))

(deftest robots-and-flags
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] board-with-flags)
        player1-id (get-in base-game [:state :players 0 :id])]
    (testing "Robot landing on the first flag at the end of the turn has flag added to it, and it's archive marker placed on it"
      (let [base-game (complete-turn base-game (t {player1-id [{:type :move :value 3 :priority 100}
                                                               {:type :rotate :value :right :priority 100}
                                                               {:type :move :value 1 :priority 100}
                                                               {:type :rotate :value :left :priority 100}
                                                               {:type :move :value 1 :priority 100}]}))]
        (is (= #{1} (player-flags base-game 1)))
        (is (= [1 0] (player-archive-marker base-game 1)))))

    (testing "Robots must hit flags in order for a flag touch to be registered, but archive marker is set irrespective"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :flags] #{1})
                          (assoc-in [:state :players 0 :robot :archive-marker] [1 0])
                          (complete-turn (t {player1-id [{:type :move :value 3 :priority 100}
                                                         {:type :rotate :value :right :priority 100}
                                                         {:type :move :value 3 :priority 100}]})))]
        (is (= #{1} (player-flags base-game 1)))
        (is (= [3 1] (player-archive-marker base-game 1)))))

    (testing "A player has finished the game when their robot has touched each flag"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :flags] #{1 2})
                          (assoc-in [:state :players 0 :robot :position] [0 1])
                          (assoc-in [:state :players 0 :robot :direction] :east)
                          (complete-turn (t {player1-id [{:type :move :value 3 :priority 100}]})))]
        (is (= #{1 2 3} (player-flags base-game 1)))
        (is (= :finished ((player-state-fn (:state base-game)) (get-in base-game [:state :players 0]))))))))

(def board-with-pits
  (->RRSeqBoard
    (concat
      [(concat [blank-square (with-pit blank-square)] (repeat 2 blank-square))]
      [(concat (repeat 2 blank-square) [(with-belt blank-square :east) (with-pit blank-square)])]
      [(concat (repeat 2 blank-square) [(with-pit blank-square) blank-square])]
      [(repeat 4 blank-square)]
      [(map docking-bay-square (range 1 5))])))

(deftest robot-and-pit-squares
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] board-with-pits)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "Robot that falls into a pit is destroyed"
      (let [base-game (complete-turn base-game (t {player1-id [{:type :move :value 3 :priority 100}
                                                               {:type :rotate :value :right :priority 100}
                                                               {:type :move :value 1 :priority 100}
                                                               {:type :rotate :value :left :priority 100}
                                                               {:type :move :value 1 :priority 100}]}))]
        (is (= :destroyed (player-state base-game 1)))))

    (testing "Robot that is pushed into pit is destroyed"
      (let [base-game (complete-turn base-game (t {player1-id [{:type :move :value 3 :priority 100}
                                                               {:type :rotate :value :right :priority 100}
                                                               {:type :move :value 1 :priority 100}]
                                                   player2-id [{:type :move :value 1 :priority 300}
                                                               {:type :move :value 1 :priority 300}
                                                               {:type :move :value 1 :priority 100}]}))]
        (is (= :destroyed (player-state base-game 1)))
        (is (= :ready (player-state base-game 2)))))

    (testing "Robot dropped into a pit by a belt is destroyed"
      (let [base-game (complete-turn base-game (t {player1-id [{:type :move :value 3 :priority 100}
                                                               {:type :rotate :value :right :priority 100}
                                                               {:type :move :value 2 :priority 100}]}))]
        (is (= :destroyed (player-state base-game 1)))))))

(def board-with-repair-stations
  (->RRSeqBoard
    (concat
      [(concat [(with-repair blank-square)] (repeat 3 blank-square))]
      [(repeat 4 blank-square)]
      [(concat (repeat 2 blank-square) [(with-pit blank-square) blank-square])]
      [(repeat 4 blank-square)]
      [(map docking-bay-square (range 1 5))])))

(deftest robot-repair-station-interaction
  (let [base-game (new-game [{:name "player 1"}] board-with-repair-stations)
        player1-id (get-in base-game [:state :players 0 :id])]
    (testing "Robot landing on a repair site has their archive marker moved to this square, but no damage reduction"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :damage] 5)
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}
                                                         {:type :move :value 2 :priority 100}
                                                         {:type :rotate :value :right :priority 100}
                                                         {:type :move :value 1 :priority 100}]})))]
        (is (= [0 0] (player-archive-marker base-game 1)))
        (is (= 5 (player-damage base-game 1)))))

    (testing "Robot on repair square at clean-up loses a damage token"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :damage] 5)
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}
                                                         {:type :move :value 2 :priority 100}]}))
                          (clean-up {}))]
        (is (= 4 (player-damage base-game 1)))))

    (testing "Robot on repair square can't reduce their damage tokens below 0"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :damage] 0)
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}
                                                         {:type :move :value 2 :priority 100}]}))
                          (clean-up {}))]
        (is (zero? (player-damage base-game 1)))))))

(deftest destroyed-robots
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"} {:name "player 3"}] board-with-lasers)
        player1-id (get-in base-game [:state :players 0 :id])]
    (testing "Robot reaching 0 damage"
      (let [base-game (-> base-game
                          (update-in [:state :players 0 :robot] merge {:damage         9
                                                                       :direction      :west
                                                                       :position       [2 0]
                                                                       :archive-marker [0 4]
                                                                       :lives          4})
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100} ;; << destroyed here
                                                         {:type :rotate :value :left :priority 100}
                                                         {:type :move :value 2 :priority 100}]})))]
        (testing "Is destroyed and does not move for the rest of the turn"
          (is (= :destroyed (player-state base-game 1)))
          (is (nil? (player-position base-game 1)))
          (is (nil? (player-direction base-game 1))))

        (testing "Is returned to the game during the clean up phase at their archive marker, loses a life, and is reset to 2 damage"
          (let [base-game (clean-up base-game {})]
            (is (= [0 4] (player-position base-game 1)))
            (is (= 3 (player-lives base-game 1)))
            (is (= 2 (player-damage base-game 1)))))

        (testing "If another player is located on the player's archive marker, then the player is placed in a location adjacent
                  to the player's archive marker"
          (let [base-game (-> base-game
                              (assoc-in [:state :players 1 :robot :position] [0 4])
                              (clean-up {}))]
            (is (not= (player-position base-game 1) (player-position base-game 2)))
            (is (adjacent-to? [0 4] (player-position base-game 1)))))

        (testing "If another player is located on the player's archive marker, then the player is placed in a location adjacent
                  to the player's archive marker, but not on another robot"
          (let [base-game (-> base-game
                              (assoc-in [:state :players 1 :robot :position] [0 4])
                              (assoc-in [:state :players 2 :robot :position]
                                        (random-adjacent-square (:state base-game) [0 4]))
                              (clean-up {}))]
            (is (not= (player-position base-game 1) (player-position base-game 2)))
            (is (not= (player-position base-game 1) (player-position base-game 3))))))

      (testing "Robot can't respawn onto a pit"
        (is (= 1 2)))

      (testing "Multiple robots respawning"
        (let [base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 10 :state :destroyed :direction nil :position nil})
                            (update-in [:state :players 1 :robot] merge {:damage 10 :state :destroyed :direction nil :position nil}))]
          (testing "Respawning to the same archive marker does not meant the robots start on the same position"
            (let [cleaned-up-game (-> base-game
                                      (assoc-in [:state :players 0 :robot :archive-marker] [0 0])
                                      (assoc-in [:state :players 1 :robot :archive-marker] [0 0])
                                      (clean-up {}))]
              (is (not= (player-position cleaned-up-game 1) (player-position cleaned-up-game 2))))))))))

(deftest robots-with-locked-registers
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] blank-board)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "At the end of the turn, robot with"
      (testing "0 - 4 damage has no locked registers"
        (let [base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 0})
                            (update-in [:state :players 1 :robot] merge {:damage 4})
                            (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}]
                                               player2-id [{:type :move :value 2 :priority 110}]}))
                            (clean-up {}))]
          (is (empty? (player-locked-registers base-game 1)))
          (is (empty? (player-locked-registers base-game 2)))))

      (testing "5 damage has register 5 locked"
        (let [base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 0})
                            (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}
                                                           {:type :move :value 1 :priority 100}
                                                           {:type :move :value 3 :priority 100}
                                                           {:type :move :value 2 :priority 100}
                                                           {:type :move :value 1 :priority 500}]}))
                            (clean-up {}))]
          (is (= [{:type :move :value 1 :priority 500}] (player-locked-registers base-game 1)))))
      (testing "6 damage has registers 4,5 locked"

        )
      (testing "7 damage has registers 3,4,5 locked"

        )
      (testing "8 damage has registers 2,3,4,5 locked"

        )
      (testing "9 damage has registers 1,2,3,4,5 locked"

        )
      )

    (testing "The locked registers are applied to the list of registers for the robot")

    (testing "Robot that heals has locked registers removed")
    ))
