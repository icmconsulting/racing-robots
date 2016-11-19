(ns rr.bots
  (:require [rr.game :as game]
    #?(:cljs [cljs.core.async :as async]
       :clj [clojure.core.async :as async :refer [go go-loop]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

;; All bot funcs return a chan

(defprotocol RRBot
  (new-game [bot game]) ; -> :ready or :fail
  (turn [bot game player-turn])                                    ; -> {:registers [], :powering-down bool}
  (turn-complete [bot game game-turn])                           ; -> :no-action|:power-down|:power-down-override
  (game-over [bot game results])                            ; no response expected
  )

(defrecord RRLocalBot [state-atom profile card-selection-fn clean-up-fn]
  RRBot
  (new-game [bot game] (go {:response :ready :profile profile}))
  (turn [bot game player-turn] (go (card-selection-fn game (:dealt player-turn))))
  (turn-complete [bot game turn] (go (clean-up-fn game turn)))
  (game-over [bot game results] (go :ok)))

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

(defn maybe-invalid-response
  [& args]
  (if (< (rand-int 10) 8)
    :rr.connectors/invalid-response
    (apply select-random-cards-or-power-down args)))

(defn maybe-power-down
  [_ _]
  :no-action ;; TODO - choose to continue powering down or not
  )

(defn maybe-invalid-complete-turn-response
  [_ _]
  (if (< (rand-int 10) 8)
    :rr.connectors/invalid-response
    :no-action))

(defn maybe-cheat-response
  [_ dealt-cards]
  (if (< (rand-int 10) 8)
    ;; cheat
    {:registers     (take 5 (shuffle game/program-card-deck))
     :powering-down (rand-nth (conj (repeat 9 true) [false]))}
    (select-random-cards nil dealt-cards)))

(def local-bots
  {:zippy #(->RRLocalBot (atom {})
                         {:name "Team zippy"
                          :robot-name "Zippy"
                          :avatar "/images/zippy-avatar.png"}
                         select-random-cards (constantly :no-action))
   :sleepy #(->RRLocalBot (atom {})
                          {:name "Sleepy bots inc."
                           :robot-name "Sleepy"
                           :avatar "/images/sleepy-avatar.jpg"}
                          select-random-cards-or-power-down maybe-power-down)
   :fumbly #(->RRLocalBot (atom {})
                          {:name "Incompotent Business Machines"
                           :robot-name "Fumbly"
                           :avatar "/images/fumbly-avatar.jpg"}
                          maybe-invalid-response maybe-invalid-complete-turn-response)
   :sneaky #(->RRLocalBot (atom {})
                          {:name "Pyramids Engineering"
                           :robot-name "Sneaky"
                           :avatar "/images/sneaky-avatar.png"}
                          maybe-cheat-response maybe-power-down)})

(defmulti player-bot-instance :connection-type)

(defmulti player-bot (juxt :player-type :connection-type))

(defmethod player-bot [:player :http]
  [player]
  (let [player-data {:name            "Unloaded Human HTTP Bot"
                     :connection-type :http
                     :port            (:port player)}]
    (assoc player-data :bot-instance-fn player-bot-instance)))

(defmethod player-bot [:player :lambda]
  [player]
  (let [player-data {:name                 "Unloaded Human Lambda Bot"
                     :connection-type      :lambda
                     :lambda-function-name (:lambda-function-name player)
                     :bonus-modifier       game/bonus-2-damage-points-modifier}]
    (assoc player-data :bot-instance-fn player-bot-instance)))

(defmethod player-bot :default
  [{:keys [player-type]}]
  (when-let [cpu-bot-fn (local-bots player-type)]
    {:name "Loading CPU Bot"
     :bot-instance (cpu-bot-fn)}))