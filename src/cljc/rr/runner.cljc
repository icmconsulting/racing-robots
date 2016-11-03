(ns rr.runner
  (:require [taoensso.timbre :as timbre :refer [info debug]]
            [rr.game :as game]
            [rr.bots :as bots]
    #?(:cljs [cljs.core.async :as async]
       :clj
            [clojure.core.async :as async :refer [go go-loop]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

;;TODO: get ready status for each bot

(defn send-and-collect-responses
  [players send-fn reduce-responses-fn]
  (let [players (mapv #(assoc % :resp-chan (async/merge [(async/timeout 5000)])) players)]
    (doseq [{:keys [resp-chan] :as player} players]
      (async/pipe (send-fn player) resp-chan))

    (go-loop [players-waiting players
              received-responses []]
      (let [[v ch] (async/alts! (mapv :resp-chan players-waiting))
            players-left (remove #(identical? ch (:resp-chan %)) players-waiting)]
        (debug (:id v) "=>" (:resp v))
        (debug "Waiting for [" (count players-left) "] players to respond")
        (if (seq players-left)
          (recur players-left (conj received-responses v))
          (reduce-responses-fn (conj received-responses v)))))))

(defn bot-response-for-new-game
  [game {:keys [bot-instance] :as player}]
  (async/map
    #(assoc player :resp %)
    [(bots/new-game bot-instance game)]))

(defn apply-bot-new-game-responses
  [player-with-response]
  (zipmap (map :id player-with-response)
          (map :resp player-with-response)))

(defn start-new-game
  [{:keys [players board]}]
  (let [new-game (game/new-game players board)]
    (send-and-collect-responses (game/players new-game)
                                (partial bot-response-for-new-game new-game)
                                (fn [responses] [new-game (apply-bot-new-game-responses responses)]))))

(defn bot-response-for-turn
  [game turn {:keys [bot-instance] :as player-turn}]
  (async/map
    #(assoc player-turn :resp %)
    [(bots/turn bot-instance game (assoc (select-keys player-turn [:dealt])
                                    :turn-number (game/turn-number turn)))]))

(defn apply-bot-responses
  [turn player-with-responses]
  (reduce (fn [turn {:keys [resp id]}]
            (game/player-enters-registers turn id (:registers resp) (true? (:powering-down resp))))
          turn player-with-responses))


(defn next-turn
  "Returns a chan which will eventually have the updated game placed onto after all players have submitted their turns"
  [game]
  (let [turn (game/start-next-turn game)]
    (send-and-collect-responses (game/deal-cards-to-players turn)
                                (partial bot-response-for-turn game turn)
                                (fn [responses]
                                  (let [completed-turn (apply-bot-responses turn responses)]
                                    [completed-turn (game/complete-turn game completed-turn)])))))

(defn bot-response-complete-turn
  [game turn turn-player]
  (async/map
    #(assoc turn-player :resp %)
    [(bots/turn-complete (:bot-instance turn-player) game turn)]))

(defn apply-bot-clean-up-responses
  [received-responses]
  (zipmap (map :id received-responses)
          (map :resp received-responses)))

(defn clean-up-turn
  [game turn]
  (let [turn-players (keys (game/registers-required-for-turn turn))]
    (send-and-collect-responses (keys (game/registers-required-for-turn turn))
                                (partial bot-response-complete-turn game turn)
                                (fn [responses]
                                  (let [player-responses (apply-bot-clean-up-responses responses)]
                                    [turn (game/clean-up game turn player-responses)])))))
