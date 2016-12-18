(ns rr.logger
  (:require [goog.string :refer [format]]
            [taoensso.timbre :as timbre :refer [info]]
            [rr.game :as game]
            [rr.utils :refer [player-short-id ascii-title]]))

(def robot-events-xf (mapcat (fn [player]
                               (map #(assoc % :player-name (:name player)
                                              :player-id (:id player)
                                              :robot-name (:robot-name player))
                                    (:events (:robot player))))))

(defn fmt-ev
  [message & arg-patterns]
  (fn [ev] (apply format message (map (partial get-in ev) arg-patterns))))

(def event-patterns
  {:rotate/left (fmt-ev "Rotated left")
   :rotate/right (fmt-ev "Rotated right")
   :rotate/u-turn (fmt-ev "Performed a u-turn")

   :move/east (fmt-ev "Moved east")
   :move/west (fmt-ev "Moved west")
   :move/north (fmt-ev "Moved north")
   :move/south (fmt-ev "Moved south")
   :move/blocked (fmt-ev "Blocked by an immovable object, and could not move")

   :destroyed/fell-off-board (fmt-ev "Fell off the side of the board!")
   :destroyed/belt-pushed-off-board (fmt-ev "Pushed off the board after riding a conveyer belt!")
   :belt/moved-by-belt (fmt-ev "Moved along a square by a conveyer belt")

   :rotator/left (fmt-ev "Was rotated left by a rotator gear")
   :rotator/right (fmt-ev "Was rotated right by a rotator gear")

   :destroyed/fell-into-pit (fmt-ev "Fell into a pit!")

   :damage/by-robot-laser (fmt-ev "Damaged by laser from robot \"%s\"" [:args 0 :name])
   :damage/by-wall-laser (fmt-ev "Damaged by wall laser")

   :victory/touched-flag (fmt-ev "Reached flag %s!!" [:args 0])

   :archive-marker/moved-to (fmt-ev "Moved archive marker to [%s, %s]" [:args 0 0] [:args 0 1])

   :damage/repair-powered-down (fmt-ev "Fully healed after powering down")
   :damage/invalid-response (fmt-ev "Sustained damage due to controlling player's ineptitude")
   :damage/repair (fmt-ev "Repaired at repair station")

   :player/bonus-applied (fmt-ev "Tech bonus applied")

   :player/died (fmt-ev "Player died - game over")

   :player/lost-life (fmt-ev "Lost life")

   :registers/locked-register-change (fmt-ev "Locked registers changed")

   :power-down/overridden (fmt-ev "Previous power-down command overridden")
   :power-down/start (fmt-ev "Power-down commencing")})

(defn log-robot-event
  [event]
  (if-let [f (-> event :type event-patterns)]
    (info (format "%s: [%s (%s, %s)]"
                  (:turn event)
                  (:robot-name event)
                  (:player-name event)
                  (player-short-id (assoc event :id (:player-id event))))
          (f event))
    (info (dissoc event :args))))

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
        (log-robot-event event)))))

(defn start-robot-event-logger
  [game-atom]
  (add-watch game-atom :logger log-watcher))

(defn stop-robot-event-logger
  [game-atom]
  (remove-watch game-atom :logger))

(defn print-log-header
  []
  (timbre/info ascii-title))
