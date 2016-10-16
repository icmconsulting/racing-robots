(ns rr.bots)

;;TODO....

(defprotocol RRBot
  (new-game [bot game])
  (turn [bot game turn])
  (turn-complete [bot game turn])
  (game-over [bot game results])
  (profile [bot]))

(defrecord RRLocalBot [state-atom card-selection-fn]
  RRBot
  (new-game [bot game])
  (turn [bot game turn])
  (turn-complete [bot game turn])
  (game-over [bot game results])
  (profile [bot]))

(defn select-random-cards
  []

  )


(def local-bots
  {:zippy {:name "Zippy the Idiotbot"
           :bot-instance #(->RRLocalBot (atom {}) select-random-cards)}})

(defrecord RRRemoteBot []
  RRBot
  (new-game [bot game])
  (turn [bot game turn])
  (turn-complete [bot game turn])
  (game-over [bot game results])
  (profile [bot])
  )
