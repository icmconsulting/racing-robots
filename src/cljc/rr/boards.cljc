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
      [[blank blank (walls :north) blank (walls :west :north) blank blank (walls :north :east) blank (walls :north) blank blank]]
      [(concat [blank (walls :west :east)] (repeat 8 blank) [(walls :east :west) blank])]
      [[(belt :east) (belt :east) (belt :south) (dock 3) blank blank (walls :west) blank (dock 4) (belt :south) (belt :west) (belt :west)]]
      [(concat [blank blank] (repeat 3 (belt :east)) [(dock 1) (-> (dock 2) (game/with-walls :west))] (repeat 3 (belt :west)) [blank blank])])))


(def all-available-boards
  {:risky-exchange {:board risky-exchange
                    :description "An easy course to start on, but don’t fall off the edge!"}})
