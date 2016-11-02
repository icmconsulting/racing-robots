(ns rr.bots
  (:require #?(:cljs [cljs.core.async :as async]
               :clj [clojure.core.async :as async :refer [go go-loop]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

;; All bot funcs return a chan

(defprotocol RRBot
  (new-game [bot game]) ; -> :ready or :fail
  (turn [bot game player-turn])                                    ; -> {:registers [], :powering-down bool}
  (turn-complete [bot game game-turn])                           ; -> :no-action|:power-down|:power-down-override
  (game-over [bot game results])                            ; no response expected
  (profile [bot]))

(defrecord RRLocalBot [state-atom card-selection-fn clean-up-fn]
  RRBot
  (new-game [bot game] (go :ready))
  (turn [bot game player-turn] (go (card-selection-fn game (:dealt player-turn))))
  (turn-complete [bot game turn] (go (clean-up-fn game turn)))
  (game-over [bot game results] (go :ok))
  (profile [bot]))

(defn select-random-cards
  [_ dealt-cards]
  {:registers     (->> dealt-cards
                       (shuffle)
                       (take 5))
   :powering-down false})

(defn select-random-cards-or-power-down
  [_ dealt-cards]
  (let [i (rand-int 10)]
    (merge
      (select-random-cards nil dealt-cards)
      (when (< i 7) {:powering-down true}))))

(defn maybe-power-down
  [_ _]
  :no-action ;; TODO - choose to continue powering down or not
  )

(def local-bots
  {:zippy {:name "Zippy"
           :bot-instance #(->RRLocalBot (atom {}) select-random-cards (constantly :no-action))
           :avatar "/images/zippy-avatar.png"}
   :sleepy {:name "Sleepy"
            :bot-instance #(->RRLocalBot (atom {}) select-random-cards-or-power-down maybe-power-down)
            :avatar "/images/sleepy-avatar.jpg"}})
