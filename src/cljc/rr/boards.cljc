(ns rr.boards
  (:require [rr.game :as game]))

(def blank game/blank-square)
(defn rep [] (game/with-repair blank))
(defn pit [] (game/with-pit blank))
(defn walls [& walls] (apply game/with-walls blank walls))
(defn belt [dir] (game/with-belt blank dir))
(defn exp-belt [dir] (game/with-belt blank dir true))
(defn flag [num] (game/with-flag blank num))
(defn rot [dir] (game/with-rotator blank dir))
(defn laser [dir num] (game/with-lasers (walls dir) dir num))
(defn dock [num] (game/docking-bay-square num))

(def easy-docking-bay-board
  (concat [[blank blank (walls :north) blank (walls :west :north) blank blank (walls :north :east) blank (walls :north) blank blank]]
          [(concat [blank (walls :west :east)] (repeat 8 blank) [(walls :east :west) blank])]
          [[(belt :east) (belt :east) (belt :south) (dock 3) blank blank (walls :west) blank (dock 4) (belt :south) (belt :west) (belt :west)]]
          [(concat [blank blank] (repeat 3 (belt :east)) [(dock 1) (-> (dock 2) (game/with-walls :west))] (repeat 3 (belt :west)) [blank blank])]))

(def risky-exchange
  (game/->RRSeqBoard
    (concat
      [[(rep) blank (walls :north) (belt :north) (walls :north) (belt :south) (belt :north) (walls :north) (belt :south) (walls :north) (belt :north) blank]]
      [[(belt :west) blank (pit) (belt :north) blank (belt :south) (belt :north) (flag 1) (belt :south) blank (rot :right) (belt :west)]]
      [[(walls :west) blank blank (belt :north) blank (belt :south) (belt :north) blank (belt :south) (walls :west) blank (laser :east 1)]]
      [(concat [(belt :east) (belt :east) (belt :east) (rot :left) blank (belt :south) (belt :north) blank (belt :south)] (repeat 3 (exp-belt :east)))]
      [[(walls :west) (flag 3) blank blank (walls :south :east) (belt :south) (belt :north) (walls :west :south) blank blank blank (walls :east)]]
      [(concat (repeat 5 (belt :west)) [blank blank] (repeat 5 (belt :west)))]
      [(concat [blank] (repeat 4 (belt :east)) [blank blank] (repeat 5 (exp-belt :east)))]
      [[(walls :west) blank blank blank (walls :north :east) (exp-belt :south) (belt :north) (-> (walls :west :north) (game/with-repair)) blank (flag 2) blank (walls :east)]]
      [(concat (repeat 3 (belt :west)) [(rot :left) blank (exp-belt :south) (belt :north) blank (rot :left)] (repeat 3 (belt :west)))]
      [[(walls :west) blank blank (belt :north) blank (exp-belt :south) (belt :north) blank (belt :south) blank (walls :north) (walls :east)]]
      [[(pit) (walls :south) blank (belt :north) blank (exp-belt :south) (belt :north) blank (belt :south) blank (rot :right) (belt :east)]]
      [[blank blank (walls :south) (belt :north) (walls :south) (exp-belt :south) blank (walls :south) (belt :south) (walls :south) (belt :north) (rep)]]
      easy-docking-bay-board)))

(def dizzy-dash
  (game/->RRSeqBoard
    (concat
      [[blank blank (walls :north) blank (walls :north) blank blank (walls :north) blank (walls :north) blank blank]]
      [(concat [blank] (repeat 3 (exp-belt :east)) [(exp-belt :south) blank blank] (repeat 3 (exp-belt :east)) [(exp-belt :south) blank])]
      [[(walls :west) (exp-belt :north) (rot :right) blank (exp-belt :south) (rot :left) blank (exp-belt :north) (rot :right) blank (exp-belt :south) (walls :east)]]
      [[blank (exp-belt :north) (rep) (-> (walls :north) (game/with-rotator :right)) (exp-belt :south) (walls :west) (laser :east 1) (exp-belt :north) (rep) (rot :right) (exp-belt :south) blank]]
      [(concat [(walls :west) (exp-belt :north)] (repeat 3 (exp-belt :west)) [(flag 1) (rot :left) (exp-belt :north)] (repeat 3 (exp-belt :west)) [(walls :east)])]
      [(concat (repeat 4 blank) [(rot :left)] (repeat 3 blank) [(laser :north 1) (rot :left)] (repeat 2 blank))]
      [(concat [blank (flag 3) (rot :left) (laser :south 1) blank blank blank (rot :left)] (repeat 4 blank))]
      [(concat [(walls :west)] (repeat 3 (exp-belt :east)) [(exp-belt :south) (rot :left) blank] (repeat 3 (exp-belt :east)) [(exp-belt :south) (walls :east)])]
      [[blank (exp-belt :north) (rot :right) (rep) (exp-belt :south) (laser :west 1) (walls :east) (exp-belt :north) (-> (walls :south) (game/with-rotator :right)) (rep) (exp-belt :south) blank]]
      [[(walls :west) (exp-belt :north) blank (rot :right) (exp-belt :south) blank (rot :left) (exp-belt :north) blank (rot :right) (exp-belt :south) (walls :east)]]
      [(concat [blank (exp-belt :north)] (repeat 3 (exp-belt :west)) [blank blank (exp-belt :north)] (repeat 3 (exp-belt :west)) [blank])]
      [[blank blank (walls :south) blank (walls :south) blank blank (walls :south) blank (walls :south) (flag 2) blank]]
      easy-docking-bay-board)))

(def chop-shop-challenge
  (game/->RRSeqBoard
    (concat
      [[blank blank (walls :north) blank (walls :north) (belt :south) (belt :north) (walls :north) blank (walls :north) blank (rep)]]
      [(concat [(belt :west) (-> (walls :south :north) (game/with-lasers :south 3))] (repeat 4 (belt :west)) [(belt :north)] (repeat 3 blank) [(pit) blank])]
      [[(walls :west) blank (rep) blank (walls :west) (rot :left) (rot :right) blank blank (laser :east 1) blank (walls :east)]]
      [(concat (repeat 3 (belt :east)) [(walls :north)] (repeat 6 (exp-belt :east)) [(-> (walls :north) (game/with-belt :east true)) (exp-belt :east)])]
      [(concat [(walls :west) blank blank (rot :right)] (repeat 5 blank) [(exp-belt :north) blank (walls :east)])]
      [[blank blank (pit) (rot :left) blank blank (rep) (belt :south) (walls :north :west) (exp-belt :north) (-> (laser :south 1) (game/with-belt :west true)) (exp-belt :west)]]
      [(concat [blank blank blank (laser :south 2) (belt :west) (belt :west) (-> (belt :west) (game/with-walls :north)) (rot :left)] (repeat 4 (belt :east)))]
      [(concat [(walls :west) (laser :north 1)] (repeat 3 blank) [(pit) (belt :north)] (repeat 4 blank) [(flag 4)])]
      [(concat (repeat 3 (belt :west)) [(rot :left) blank (laser :west 1) (rot :right) (walls :east) blank] (repeat 3 (belt :west)))]
      [[(walls :west) (walls :south) (pit) (belt :north) (flag 1) blank (belt :north) (rep) (pit) (belt :west) (belt :west) (walls :east)]]
      [[blank (flag 3) blank (belt :north) blank (walls :east) (belt :north) blank blank blank (belt :north) blank]]
      [[(rep) blank (walls :south) (belt :north) (walls :south) blank (belt :north) (walls :south) blank (flag 2) (belt :north) blank]]
      [(concat (repeat 12 blank))]
      [(concat (repeat 12 blank))]
      [[blank (walls :west) (walls :east) (dock 3) blank (-> (dock 1) (game/with-walls :west)) (-> (dock 2) (game/with-walls :east)) (walls :east) (dock 4) (walls :east) blank (walls :west)]]
      [[blank blank (walls :south) blank (walls :south) blank blank (walls :south) blank (walls :south) blank blank]])))

(def proving-grounds
  (game/->RRSeqBoard
    (concat
      [(concat (repeat 3 blank) [(flag 1)] (repeat 4 blank))]
      [(concat (repeat 3 blank) [(flag 2)] (repeat 4 blank))]
      [(concat (repeat 3 blank) [(flag 3)] (repeat 4 blank))]
      [(repeat 8 blank)]
      [[(rep) blank (walls :south) blank (walls :south) blank (walls :south) blank]]
      [(repeat 8 blank)]
      [(repeat 8 blank)]
      [[(dock 3) blank (-> (dock 1) (game/with-walls :west)) blank blank (-> (dock 2) (game/with-walls :east)) blank (dock 4)]])))


(def all-available-boards
  {;; test harness only boards
   :proving-grounds     {:board       proving-grounds
                         :key         :proving-grounds
                         :description "For testing basic mechanics of your bot. Won't be used in the tournament"
                         :test-only?  true}

   ;; tournament boards
   :risky-exchange      {:board       risky-exchange
                         :key         :risky-exchange
                         :description "An easy course to start on, but don’t fall off the edge!"}
   :dizzy-dash          {:board       dizzy-dash
                         :key         :dizzy-dash
                         :description "Whoops, was that the flag over there?"}
   :chop-shop-challenge {:board       chop-shop-challenge
                         :key         :chop-shop-challenge
                         :description "Great risk, great reward"}

   })

(defn board-from-board
  [board]
  (some->> (filter #(identical? (:board (val %)) board) all-available-boards)
           (first)
           (key)))
