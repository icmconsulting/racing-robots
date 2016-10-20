(ns rr.logger
  (:require [reagent.core :as reagent]
            [rr.game :as game]))

(def robot-events-xf (mapcat (fn [player]
                               (map #(assoc % :player-name (:name player) :player-id (:id player))
                                    (:events (:robot player))))))

;;TODO: make this readable

(defn log-watcher
  [_ _ {old-game-state :game} {new-game-state :game}]
  (when (and old-game-state new-game-state)
    (let [last-old-robot-event-ts (->> (into [] robot-events-xf (game/players old-game-state))
                                       (sort-by :time >)
                                       (first)
                                       (:time))
          all-new-state-events (->> (into [] (comp robot-events-xf
                                                   (filter #(< last-old-robot-event-ts (:time %))))
                                          (game/players new-game-state))
                                    (sort-by :time <))]
      (doseq [event all-new-state-events]
        (println event)))))

(defn start-robot-event-logger
  [game-atom]
  (add-watch game-atom :logger log-watcher))

(defn stop-robot-event-logger
  [game-atom]
  (remove-watch game-atom :logger))
