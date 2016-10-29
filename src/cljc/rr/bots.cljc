(ns rr.bots)

(defprotocol RRBot
  (new-game [bot game]) ; -> :ready or :fail
  (turn [bot game turn])                                    ; -> {:registers [], :powering-down bool}
  (turn-complete [bot game turn])                           ; -> :no-action|:power-down|:power-down-override
  (game-over [bot game results])                            ; no response expected
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

(defrecord RRRemoteBot []
  RRBot
  (new-game [bot game])
  (turn [bot game turn])
  (turn-complete [bot game turn])
  (game-over [bot game results])
  (profile [bot])
  )
