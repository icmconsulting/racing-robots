(ns rr.game-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer :all]
            [rr.game :refer :all]
            [rr.game :as game]))

(defn turn-with-registers
  ([registers] (turn-with-registers 0 registers))
  ([turn-number registers] (turn-with-registers turn-number registers #{}))
  ([turn-number registers powering-down]
   (reify RRGameTurn
     (turn-number [turn] turn-number)
     (deal-cards-to-players [turn])
     (player-timedout [turn player-id])
     (registers-for-turn [turn] registers)
     (players-powering-down-next-turn [turn] powering-down)
     (players-with-invalid-response [turn] nil))))

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
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] blank-board)
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

    (testing "Moving robot backward"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [0 6]))
            game-after-move (complete-turn base-game
                                           (t {player-id (vec (repeat 5 {:type :move :value -1 :priority 100}))}))]
        (is (= [0 1] (player-position game-after-move 1)))
        (is (= :south (player-direction game-after-move 1)))))

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
        player2-id (get-in base-game [:state :players 1 :id])
        player3-id (get-in base-game [:state :players 2 :id])]

    (testing "Robot on a non express belt after a turn is moved by 1 square in the direction of the belt"
      (let [base-single-player-games (after-each-register-for-turn base-game (t {player1-id [{:type :move :value 2 :priority 290}]}))]
        (is (= [1 2] (player-position (second base-single-player-games) 1)))))

    (testing "Robot on an express belt after a turn is moved by 2 squares in the direction of the belt"
      (let [base-single-player-games (-> (assoc-in base-game [:state :players 0 :robot :direction] :east)
                                        (assoc-in [:state :players 0 :robot :position] [0 0])
                                        (after-each-register-for-turn (t {player1-id [{:type :move :value 1 :priority 290}]})))]
        (is (= [1 2] (player-position (second base-single-player-games) 1)))))

    (testing "Robots being moved into a conflicting spot will not be moved by the belt"
      (let [two-player-game (complete-turn base-game (t {player1-id [{:type :move :value 1 :priority 290}]
                                                         player3-id [{:type :move :value 1 :priority 330}]}))]
        (is (= [0 3] (player-position two-player-game 1)))
        (is (= [2 3] (player-position two-player-game 3)))))

    (testing "Robot being moved off a belt onto a square with a conflicting spot will not be moved"
      (let [single-player-game (complete-turn base-game (t {player1-id [{:type :move :value 1 :priority 290}
                                                                        {:type :move :value 0 :priority 290}]}))] ;;not realistic, but only way on this map
        (is (= [1 3] (player-position single-player-game 1)))))

    (testing "Robots are not rotated by the same belt moving in a different direction that the robot is facing"
      (let [single-player-game (-> base-game
                                   (assoc-in [:state :players 0 :robot :direction] :south)
                                   (assoc-in [:state :players 0 :robot :position] [0 1])
                                   (after-each-register-for-turn (t {player1-id [{:type :move :value 1 :priority 290}]}))
                                   (second))]
        (is (= [1 2] (player-position single-player-game 1)))
        (is (= :south (player-direction single-player-game 1)))))

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
      (let [single-player-games (-> base-game
                                   (assoc-in [:state :players 0 :robot :direction] :west)
                                   (assoc-in [:state :players 0 :robot :position] [3 1])
                                   (after-each-register-for-turn (t {player1-id [{:type :move :value 1 :priority 290}]})))]
        (is (= [1 2] (player-position (second single-player-games) 1)))
        (is (= :east (player-direction (second single-player-games) 1)))))

    (testing "Robots are destroyed if a belt moves them off the board"
      (let [single-player-game (-> base-game
                                   (assoc-in [:state :players 0 :robot :position] [3 4])
                                   (complete-turn (t {player1-id [{:type :move :value 2 :priority 290}]})))]
        (is (= :destroyed (player-state single-player-game 1)))
        (is (nil? (player-position single-player-game 1)))))

    (testing "Robots that are powered down can still be thrown off the board by a belt"
      (let [base-single-player-game (-> base-game
                                        (assoc-in [:state :players 0 :robot :powered-down?] true)
                                        (assoc-in [:state :players 0 :robot :position] [0 2])
                                        (complete-turn (t {player1-id []
                                                           player3-id [{:type :move :value 2 :priority 290}]})))]
        (is (nil? (player-position base-single-player-game 1)))
        (is (= :destroyed (player-state base-single-player-game 1)))))

    (testing "Robots that are powered down can still be killed by being pushed off the board"
      (let [base-single-player-game (-> base-game
                                        (assoc-in [:state :players 0 :robot :powered-down?] true)
                                        (assoc-in [:state :players 0 :robot :position] [3 2])
                                        (complete-turn (t {player1-id []
                                                           player3-id [{:type :move :value 2 :priority 290}]})))]
        (is (= :destroyed (player-state base-single-player-game 1)))
        (is (nil? (player-position base-single-player-game 1)))))

    (testing "All players are powered down, belts still take effect"
      (let [base-single-player-game (-> base-game
                                        (assoc-in [:state :players 0 :robot :powered-down?] true)
                                        (assoc-in [:state :players 0 :robot :position] [0 2])
                                        (assoc-in [:state :players 1 :robot :powered-down?] true)
                                        (assoc-in [:state :players 2 :robot :powered-down?] true)
                                        (complete-turn (t {player1-id []
                                                           player2-id []
                                                           player3-id []})))]
        (is (= :destroyed (player-state base-single-player-game 1)))
        (is (nil? (player-position base-single-player-game 1)))))))

(def board-with-rotators
  (->RRSeqBoard
    (concat
      [(concat [(with-rotator blank-square :left)] (repeat 3 blank-square))]
      [(repeat 4 blank-square)]
      [(concat (repeat 3 blank-square) [(with-rotator blank-square :right)])]
      [(concat (repeat 3 blank-square) [(with-rotator blank-square :u-turn)])]
      [(map docking-bay-square (range 1 5))])))

(deftest rotator-interaction
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] board-with-rotators)
        player1-id (get-in base-game [:state :players 0 :id])]
    (testing "Robot landing on a rotate-left square is rotated left"
      (let [base-single-player-games (after-each-register-for-turn base-game (t {player1-id [{:type :move :value 2 :priority 290}
                                                                                             {:type :move :value 2 :priority 290}]}))]
        (is (= :west (player-direction (second (rest base-single-player-games)) 1)))))

    (testing "Robot landing on a rotator right square is rotated right"
      (let [base-single-player-games (after-each-register-for-turn base-game (t {player1-id [{:type :move :value 2 :priority 290}
                                                                                             {:type :rotate :value :right :priority 100}
                                                                                             {:type :move :value 3 :priority 290}]}))]
        (is (= :south (player-direction (nth base-single-player-games 3) 1)))))

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
      [(concat [(with-pit blank-square)] (repeat 3 blank-square))]
      [(map docking-bay-square (range 1 5))])))

(deftest wall-laser-interaction
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] board-with-lasers)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "Robot landing on a single laser square gains 1 damage"
      (let [base-games (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :west)
                          (assoc-in [:state :players 0 :robot :position] [2 0])
                          (after-each-register-for-turn (t {player1-id [{:type :move :value 2 :priority 100}]})))]
        (is (= 1 (player-damage (second base-games) 1)))))

    (testing "Robot hiding behind a wall does not gain damage from a laser"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :north)
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}]})))]
        (is (zero? (player-damage base-game 1)))))

    (testing "Lasers don't pass through robots - only first one takes the damage"
      (let [base-games (-> base-game
                           (assoc-in [:state :players 0 :robot :direction] :west)
                           (assoc-in [:state :players 0 :robot :position] [2 0])
                           (assoc-in [:state :players 1 :robot :direction] :west)
                           (assoc-in [:state :players 1 :robot :position] [2 1])
                           (after-each-register-for-turn (t {player1-id [{:type :move :value 2 :priority 100}]
                                                             player2-id [{:type :move :value 2 :priority 100}]})))]
        (is (= 1 (player-damage (second base-games) 1)))
        (is (zero? (player-damage (second base-games) 2)))))

    (testing "Robot landing on a square with 3 lasers square gains 3 damage"
      (let [base-games (-> base-game
                          (assoc-in [:state :players 0 :robot :direction] :north)
                          (assoc-in [:state :players 0 :robot :position] [3 4])
                          (after-each-register-for-turn (t {player1-id [{:type :move :value 2 :priority 100}]})))]
        (is (= [3 2] (player-position (second base-games) 1)))
        (is (= 3 (player-damage (second base-games) 1)))))

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
      (let [base-games (after-each-register-for-turn base-game (t {player1-id [{:type :move :value 1 :priority 100}
                                                                              {:type :rotate :value :right :priority 100}]
                                                                  player2-id [{:type :move :value 1 :priority 100}
                                                                              {:type :rotate :value :right :priority 100}]}))]
        (is (zero? (player-damage (nth base-games 2) 1)))
        (is (= 1 (player-damage (nth base-games 2) 2)))))

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

    (testing "Robots with no prior flags will not have flag registered if previous flag has not been touched"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :flags] #{})
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}
                                                         {:type :rotate :value :right :priority 100}
                                                         {:type :move :value 2 :priority 100}]})))]
        (is (= #{} (player-flags base-game 1)))))

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
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] board-with-repair-stations)
        player1-id (get-in base-game [:state :players 0 :id])]
    (testing "Robot landing on a repair site has their archive marker moved to this square, but no damage reduction"
      (let [base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :damage] 5)
                          (assoc-in [:state :players 1 :robot :position] [3 4])
                          (complete-turn (t {player1-id [{:type :move :value 2 :priority 100}
                                                         {:type :move :value 2 :priority 100}
                                                         {:type :rotate :value :right :priority 100}
                                                         {:type :move :value 1 :priority 100}]})))]
        (is (= [0 0] (player-archive-marker base-game 1)))
        (is (= 5 (player-damage base-game 1)))))

    (testing "Robot on repair square at clean-up loses a damage token"
      (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                 {:type :move :value 2 :priority 100}]})
            base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :damage] 5)
                          (complete-turn turn)
                          (clean-up turn {}))]
        (is (= 4 (player-damage base-game 1)))))

    (testing "Robot on repair square can't reduce their damage tokens below 0"
      (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                 {:type :move :value 2 :priority 100}]})
            base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :damage] 0)
                          (complete-turn turn)
                          (clean-up turn {}))]
        (is (zero? (player-damage base-game 1)))))))

(defn cards-dealt-to-player
  [player-id turn]
  (:dealt (first
            (filter #(= player-id (:id %))
                    (deal-cards-to-players turn)))))

(deftest destruction-of-robots
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"} {:name "player 3"}] board-with-lasers)
        player1-id (get-in base-game [:state :players 0 :id])]
    (testing "Robot reaching 0 damage"
      (let [turn (t {player1-id [{:type :move :value 2 :priority 100} ;; << destroyed here
                                 {:type :rotate :value :left :priority 100}
                                 {:type :move :value 2 :priority 100}]})
            base-game (-> base-game
                          (update-in [:state :players 0 :robot] merge {:damage         9
                                                                       :direction      :west
                                                                       :position       [2 0]
                                                                       :archive-marker [0 4]
                                                                       :lives          4})
                          (complete-turn turn))]
        (testing "Is destroyed and does not move for the rest of the turn"
          (is (= :destroyed (player-state base-game 1)))
          (is (nil? (player-position base-game 1)))
          (is (nil? (player-direction base-game 1))))

        (testing "Is returned to the game during the clean up phase at their archive marker, loses a life, and is reset to 2 damage"
          (let [base-game (clean-up base-game (start-next-turn base-game) {})]
            (is (= [0 4] (player-position base-game 1)))
            (is (= 3 (player-lives base-game 1)))
            (is (= 2 (player-damage base-game 1)))
            (is (= :ready (player-state base-game 1)))))

        (testing "If another player is located on the player's archive marker, then the player is placed in a location adjacent
                  to the player's archive marker"
          (let [base-game (-> base-game
                              (assoc-in [:state :players 1 :robot :position] [0 4])
                              (clean-up (start-next-turn base-game) {}))]
            (is (not= (player-position base-game 1) (player-position base-game 2)))
            (is (adjacent-to? [0 4] (player-position base-game 1)))))

        (testing "If another player is located on the player's archive marker, then the player is placed in a location adjacent
                  to the player's archive marker, but not on another robot"
          (let [base-game (-> base-game
                              (assoc-in [:state :players 1 :robot :position] [0 4])
                              (assoc-in [:state :players 2 :robot :position]
                                        (random-adjacent-square (:state base-game) [0 4]))
                              (clean-up (start-next-turn base-game) {}))]
            (is (not= (player-position base-game 1) (player-position base-game 2)))
            (is (not= (player-position base-game 1) (player-position base-game 3)))))

        (testing "Robot can't respawn onto a pit"
          (is (nil? ((set (for [_ (range 0 10)]
                            (let [base-game (-> base-game
                                                (assoc-in [:state :players 1 :robot :position] [0 4])
                                                (assoc-in [:state :players 2 :robot :position] [1 4])
                                                (clean-up turn {}))]
                              (player-position base-game 1))))
                      [0 3])))))

      (testing "Multiple robots respawning"
        (let [base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 10 :state :destroyed :direction nil :position nil})
                            (update-in [:state :players 1 :robot] merge {:damage 10 :state :destroyed :direction nil :position nil}))]
          (testing "Respawning to the same archive marker does not mean the robots start on the same position"
            (let [cleaned-up-game (-> base-game
                                      (assoc-in [:state :players 0 :robot :archive-marker] [0 0])
                                      (assoc-in [:state :players 1 :robot :archive-marker] [0 0])
                                      (clean-up (start-next-turn base-game) {}))]
              (is (not= (player-position cleaned-up-game 1) (player-position cleaned-up-game 2)))))))

      (testing "Dead players receive no cards"
        (let [turn (t {player1-id [{:type :move :value 2 :priority 100}]})
              base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 9 :lives  0})
                            (complete-turn turn)
                            (clean-up turn {}))]
          (is (= :destroyed (player-state base-game 1)))
          (is (empty? (cards-dealt-to-player player1-id (start-next-turn base-game)))))))))

(deftest players-invalid-response
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"} {:name "player 3"}] blank-board)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])
        player3-id (get-in base-game [:state :players 2 :id])
        next-turn (game/start-next-turn base-game)]
    (testing "Player that responds with invalid response has 5 damage applied"
      (let [next-turn (game/player-invalid-response next-turn player1-id)
            game (game/complete-turn base-game next-turn)]
        (is (= 5 (player-damage game 1)))))

    (testing "Player that receives a penalty that takes robot over 10 damage has robot destroyed"
      (let [next-turn (game/player-invalid-response next-turn player1-id)
            game (-> base-game
                     (update-in [:state :players 0 :robot] merge {:damage 9})
                     (game/complete-turn  next-turn))]
        (is (= :destroyed (player-state game 1)))))))

(deftest robots-with-locked-registers
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] blank-board)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "At the end of the turn, robot with"
      (testing "0 - 4 damage has no locked registers"
        (let [turn (t {player1-id [{:type :move :value 2 :priority 100}]
                       player2-id [{:type :move :value 2 :priority 110}]})
              base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 0})
                            (update-in [:state :players 1 :robot] merge {:damage 4})
                            (complete-turn turn)
                            (clean-up turn {}))]
          (is (empty? (player-locked-registers base-game 1)))
          (is (empty? (player-locked-registers base-game 2)))))


      (testing "5 damage has register 5 locked"
        (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                   {:type :move :value 1 :priority 100}
                                   {:type :move :value 3 :priority 100}
                                   {:type :move :value 2 :priority 100}
                                   {:type :move :value 1 :priority 500}]})
              base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 5})
                            (complete-turn turn)
                            (clean-up turn {}))]
          (is (= [{:type :move :value 1 :priority 500}] (player-locked-registers base-game 1)))))

      (testing "6 damage has registers 4,5 locked"
        (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                   {:type :move :value 1 :priority 100}
                                   {:type :move :value 3 :priority 100}
                                   {:type :move :value 2 :priority 400}
                                   {:type :move :value 1 :priority 500}]})
              base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 6})
                            (complete-turn turn)
                            (clean-up turn {}))]
          (is (= [{:type :move :value 1 :priority 500}
                  {:type :move :value 2 :priority 400}]
                 (player-locked-registers base-game 1)))))

      (testing "7 damage has registers 3,4,5 locked"
        (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                   {:type :move :value 1 :priority 100}
                                   {:type :move :value 3 :priority 300}
                                   {:type :move :value 2 :priority 400}
                                   {:type :move :value 1 :priority 500}]})
              base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 7})
                            (complete-turn turn)
                            (clean-up turn {}))]
          (is (= [{:type :move :value 1 :priority 500}
                  {:type :move :value 2 :priority 400}
                  {:type :move :value 3 :priority 300}]
                 (player-locked-registers base-game 1)))))

      (testing "8 damage has registers 2,3,4,5 locked"
        (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                   {:type :move :value 1 :priority 200}
                                   {:type :move :value 3 :priority 300}
                                   {:type :move :value 2 :priority 400}
                                   {:type :move :value 1 :priority 500}]})
              base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 8})
                            (complete-turn turn)
                            (clean-up turn {}))]
          (is (= [{:type :move :value 1 :priority 500}
                  {:type :move :value 2 :priority 400}
                  {:type :move :value 3 :priority 300}
                  {:type :move :value 1 :priority 200}]
                 (player-locked-registers base-game 1)))))

      (testing "9 damage has registers 1,2,3,4,5 locked"
        (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                   {:type :move :value 1 :priority 200}
                                   {:type :move :value 3 :priority 300}
                                   {:type :move :value 2 :priority 400}
                                   {:type :move :value 1 :priority 500}]})
              base-game (-> base-game
                            (update-in [:state :players 0 :robot] merge {:damage 9})
                            (complete-turn turn)
                            (clean-up turn {}))]
          (is (= [{:type :move :value 1 :priority 500}
                  {:type :move :value 2 :priority 400}
                  {:type :move :value 3 :priority 300}
                  {:type :move :value 1 :priority 200}
                  {:type :move :value 2 :priority 100}]
                 (player-locked-registers base-game 1))))))

    (testing "Locked registers are additive"
      (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                 {:type :move :value 1 :priority 200}
                                 {:type :move :value 3 :priority 300}]})
            base-game (-> base-game
                          (update-in [:state :players 0 :robot] merge {:damage           7 ;; robot got damaged this turn
                                                                       :locked-registers [{:type :move :value 1 :priority 500}
                                                                                          {:type :move :value 2 :priority 400}]})
                          (complete-turn turn)
                          (clean-up turn {}))]
        (is (= [{:type :move :value 1 :priority 500}
                {:type :move :value 2 :priority 400}
                {:type :move :value 3 :priority 300}]
               (player-locked-registers base-game 1)))))

    (testing "Robot that heals has locked registers removed"
      (let [turn (t {player1-id [{:type :move :value 2 :priority 100}
                                 {:type :move :value 1 :priority 200}
                                 {:type :move :value 3 :priority 300}]})
            base-game (-> base-game
                          (update-in [:state :players 0 :robot] merge {:damage           5
                                                                       :locked-registers [{:type :move :value 1 :priority 500}
                                                                                          {:type :move :value 2 :priority 400}]})
                          (complete-turn turn)
                          (clean-up turn {}))]
        (is (= [{:type :move :value 1 :priority 500}]
               (player-locked-registers base-game 1)))))

    (testing "The locked registers are applied to the list of registers for the robot"
      (let [turn (t {player1-id [{:type :move :value 1 :priority 100}
                                 {:type :move :value 1 :priority 200}
                                 {:type :move :value 1 :priority 300}]})
            base-game (-> base-game
                          (assoc-in [:state :players 0 :robot :locked-registers] [{:type :rotate :value :right :priority 500}
                                                                                  {:type :move :value 1 :priority 400}])
                          (complete-turn turn))]
        (is (= [4 11] (player-position base-game 1)))
        (is (= :east (player-direction base-game 1)))))))

(deftest robot-powers-down
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] blank-board)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]

    (testing "Player that commands that they will power down in the next turn"
      (let [turn (t 0 {player1-id [{:type :move :value 1 :priority 100}]
                       player2-id [{:type :move :value 1 :priority 100}]}
                    #{(player-by-id (:state base-game) player1-id)})
            game (-> base-game
                     (assoc-in [:state :players 0 :robot :damage] 4)
                     (complete-turn turn)
                     (clean-up turn {}))
            next-turn (start-next-turn game)]

        (testing "will not be dealt cards in that turn"
          (is (empty? (cards-dealt-to-player player1-id next-turn)))
          (is (seq (cards-dealt-to-player player2-id next-turn))))

        (testing "locked registers will not be applied while powered down"
          (let [game (-> base-game
                         (update-in [:state :players 0 :robot] merge {:damage           5
                                                                      :locked-registers [{:type :rotate :value :left :priority 101}]})
                         (complete-turn turn)
                         (clean-up turn {}))
                next-turn (-> (start-next-turn game)
                              (player-enters-registers player1-id [] false)
                              (player-enters-registers player2-id [{:type :move :value 1 :priority 100}] false))
                game-after-turn (complete-turn game next-turn)]
            (is (= :west (player-direction game-after-turn 1)))

            (testing "and will be cleared after powering back up"
              (is (zero? (player-damage game-after-turn 1)))
              (is (empty? (player-locked-registers (clean-up game-after-turn next-turn {}) 1))))))

        (testing "will have damage reset back to 0 at the beginning of the turn"
          (is (zero? (player-damage (complete-turn game next-turn) 1))))

        (testing "will not be powered down after the clean up phase"
          (let [game (-> (complete-turn game next-turn)
                         (clean-up next-turn {}))]
            (is (false? (get-in game [:state :players 0 :robot :powered-down?])))))

        (testing "during the clean up phase the player may select to power down again for the next turn"
          (let [game (-> (complete-turn game next-turn)
                         (clean-up next-turn {player1-id :power-down}))]
            (is (true? (get-in game [:state :players 0 :robot :powered-down?])))))

        (testing "then are destroyed, the player can override that decision"
          (let [game (-> base-game
                         (complete-turn turn)
                         (update-in [:state :players 0 :robot] merge {:damage 10 :state :destroyed}))]

            (testing "by selecting power-down-override"
              (let [game (clean-up game turn {player1-id :power-down-override})]
                (is (false? (get-in game [:state :players 0 :robot :powered-down?])))
                (is (= 2 (player-damage game 1)))))

            (testing "or ignore it and they will continue the next turn powered down"
              (let [game (clean-up game turn {})]
                (is (true? (get-in game [:state :players 0 :robot :powered-down?])))
                (is (= 2 (player-damage game 1)))))

            (testing "but not if they aren't destroyed"
              (let [game (-> base-game
                             (complete-turn turn)
                             (clean-up turn {player1-id :power-down-override}))]
                (is (true? (get-in game [:state :players 0 :robot :powered-down?])))))))))))

(deftest end-of-game
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"} {:name "player 3"} {:name "player 4"}] board-with-flags)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "Game is over when"
      (testing "only one player is left, and that player is the winner"
        (let [game (-> base-game
                       (assoc-in [:state :players 0 :state] :dead)
                       (assoc-in [:state :players 2 :state] :dead)
                       (assoc-in [:state :players 3 :state] :dead))
              [game-state players] (victory-status game)]
          (is (= :game-over game-state))
          (is (= [:loser :winner :loser :loser] (mapv :victory-state players)))))

      (testing "no players are left, but the winner is the robot who has captured the most flags"
        (let [game (-> base-game
                       (assoc-in [:state :players 0 :state] :dead)
                       (update-in [:state :players 0 :robot] merge {:flags #{1}})
                       (assoc-in [:state :players 1 :state] :dead)
                       (update-in [:state :players 1 :robot] merge {:flags #{1}})
                       (assoc-in [:state :players 2 :state] :dead)
                       (update-in [:state :players 2 :robot] merge {:flags #{1 2}})
                       (assoc-in [:state :players 3 :state] :dead)
                       (update-in [:state :players 3 :robot] merge {:flags #{}}))
              [game-state players] (victory-status game)]
          (is (= :game-over game-state))
          (is (= [:loser :loser :winner :loser] (mapv :victory-state players)))))

      (testing "no players are left, but more than 1 players have captured the same amount of flags, so a tie"
        (let [game (-> base-game
                       (assoc-in [:state :players 0 :state] :dead)
                       (update-in [:state :players 0 :robot] merge {:flags #{1 2}})
                       (assoc-in [:state :players 1 :state] :dead)
                       (update-in [:state :players 1 :robot] merge {:flags #{1 2}})
                       (assoc-in [:state :players 2 :state] :dead)
                       (update-in [:state :players 2 :robot] merge {:flags #{1}})
                       (assoc-in [:state :players 3 :state] :dead)
                       (update-in [:state :players 3 :robot] merge {:flags #{}}))
              [game-state players] (victory-status game)]
          (is (= :game-over game-state))
          (is (= [:winner :winner :loser :loser] (mapv :victory-state players)))))

      (testing "a single player has captured all the flags"
        (let [game (-> base-game
                       (update-in [:state :players 0 :robot] merge {:flags #{1 2}})
                       (update-in [:state :players 1 :robot] merge {:flags #{1 2}})
                       (update-in [:state :players 2 :robot] merge {:flags #{}})
                       (update-in [:state :players 3 :robot] merge {:flags #{1 2 3}}))
              [game-state players] (victory-status game)]
          (is (= :game-over game-state))
          (is (= [:loser :loser :loser :winner] (mapv :victory-state players)))))

      (testing "the maximum number of turns has been reached"
        ;;TODO - whats the maximum number of turns? how do we determine a winner in this condition?
        )
      )

    (testing "Game is still active when no one has won yet"
      (let [game (-> base-game
                     (update-in [:state :players 0 :robot] merge {:flags #{1 2}})
                     (update-in [:state :players 1 :robot] merge {:flags #{1 2}})
                     (update-in [:state :players 2 :robot] merge {:flags #{}})
                     (update-in [:state :players 3 :robot] merge {:flags #{1 2 }}))
            [game-state players] (victory-status game)]
        (is (= :active game-state))))

    (testing "Can't start a new turn if the game is over"
      (is (nil? (-> base-game
                    (update-in [:state :players 0 :robot] merge {:flags #{1 2}})
                    (update-in [:state :players 1 :robot] merge {:flags #{1 2}})
                    (update-in [:state :players 2 :robot] merge {:flags #{}})
                    (update-in [:state :players 3 :robot] merge {:flags #{1 2 3}})
                    (start-next-turn)))))

    (testing "If two robots can touch final flag on same turn, the first one to reach flag wins"
      (let [game (-> base-game
                     (update-in [:state :players 0 :robot] merge {:flags #{1 2} :position [3 2] :direction :west})
                     (update-in [:state :players 1 :robot] merge {:flags #{1 2} :position [2 1] :direction :east}))
            next-turn (t {player1-id [{:type :rotate :value :right :priority 100}
                                      {:type :move :value 1 :priority 100}]
                          player2-id [{:type :move :value 1 :priority 100}
                                      {:type :rotate :value :right :priority 100}]})
            game (-> (complete-turn game next-turn) (clean-up next-turn {}))
            [game-state players] (victory-status game)]
        (is (= :game-over game-state))
        (is (= [:loser :winner :loser :loser] (mapv :victory-state players)))))))
