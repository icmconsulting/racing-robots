(ns rr.game-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer :all]
            [rr.game :refer :all]))

(comment
  ;; Probably won't be needed..
  (def board-generator (gen/return blank-board))

  (def player-generator (gen/fmap #(hash-map :name %) (gen/not-empty gen/string-alphanumeric)))

  (def game-state-generator
    (gen/fmap (fn [[players board]]
                (new-game players board))
              (gen/tuple (gen/let [num-players (gen/such-that #(and (< 1 %) (> 9 %)) gen/nat)]
                                  (gen/vector player-generator num-players))
                         board-generator))))

;; test robot movement registers
(defn player-state
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :state]))

(defn player-position
  [game player-num]
  (get-in game [:state :players (dec player-num) :robot :position]))

(deftest moving-robot
  (let [base-game (new-game [{:name "player 1"}] blank-board)
        player-id (get-in base-game [:state :players 0 :id])]
    (testing "Moving robot north"
      (is (= [4 10]
             (player-position
               (complete-registers base-game
                                   {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))})
               1))))
    (testing "Moving robot east"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :east)
                          (assoc-in [:state :players 0 :robot :position] [0 0]))]
        (is (= [5 0]
               (player-position
                 (complete-registers base-game
                                     {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))})
                 1)))))
    (testing "Moving robot west"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :west)
                          (assoc-in [:state :players 0 :robot :position] [5 0]))]
        (is (= [0 0]
               (player-position
                 (complete-registers base-game
                                     {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))})
                 1)))))
    (testing "Moving robot south"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [0 0]))]
        (is (= [0 5]
               (player-position
                 (complete-registers base-game
                                     {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))})
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
          (is (= :destroyed
                 (player-state
                   (complete-registers game
                                       {player-id (vec (repeat 5 {:type :move :value 1 :priority 100}))})
                   1))))))))

(deftest robot-interactions
  (let [base-game (new-game [{:name "player 1"} {:name "player 2"}] blank-board)
        player1-id (get-in base-game [:state :players 0 :id])
        player2-id (get-in base-game [:state :players 1 :id])]
    (testing "Example from the game instructions"
      (let [base-game (-> (assoc-in base-game [:state :players 0 :robot :direction] :south)
                          (assoc-in [:state :players 0 :robot :position] [0 0])
                          (assoc-in [:state :players 1 :robot :position] [0 1])
                          (assoc-in [:state :players 1 :robot :direction] :east)
                          (complete-registers {player1-id [{:type :move :value 1 :priority 330}]
                                               player2-id [{:type :move :value 1 :priority 290}]}))]
        (is (= [0 1] (player-position base-game 1)))
        (is (= [1 2] (player-position base-game 2)))))))


