(ns rr.bots)

;;TODO....

(defprotocol RRBot
  (new-game [bot game])
  (turn [bot game turn])
  (turn-complete [bot game turn])
  (game-over [bot game results])
  (profile [bot]))

(defrecord RRLocalBot [state-atom card-selection-fn clean-up-fn]
  RRBot
  (new-game [bot game])
  (turn [bot game turn] (card-selection-fn game (:dealt turn)))
  (turn-complete [bot game turn] (clean-up-fn game turn))
  (game-over [bot game results])
  (profile [bot]))

(defn select-random-cards
  [_ dealt-cards]
  {:registers     (->> dealt-cards
                       (shuffle)
                       (take 5))
   :powering-down false})

(def local-bots
  {:zippy {:name "Zippy the Idiotbot"
           :bot-instance #(->RRLocalBot (atom {}) select-random-cards (constantly :no-action))
           :avatar "/images/zippy-avatar.png"}})

(defrecord RRRemoteBot []
  RRBot
  (new-game [bot game])
  (turn [bot game turn])
  (turn-complete [bot game turn])
  (game-over [bot game results])
  (profile [bot])
  )
