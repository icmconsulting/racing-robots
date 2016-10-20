(ns rr.runner
  (:require [rr.game :as game]
            [rr.bots :as bots]
            #?(:cljs [cljs.core.async :as async]
               :clj [clojure.core.async :as async :refer [go go-loop]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

(defn start-new-game
  [{:keys [players board]}]
  (game/new-game players board))

(defn bot-response-for-turn
  [game {:keys [bot-instance] :as player-turn}]
  (assoc player-turn :resp (bots/turn bot-instance game (select-keys player-turn [:dealt]))))

(defn apply-bot-responses
  [turn player-with-responses]
  (reduce (fn [turn {:keys [resp id]}]
            (game/player-enters-registers turn id (:registers resp) (true? (:powering-down resp))))
          turn player-with-responses))

(defn next-turn
  "Returns a chan which will eventually have the updated game placed onto after all players have submitted their turns"
  [game]
  (let [turn (game/start-next-turn game)
        turn-players (map #(assoc % :resp-chan (async/timeout 5000)) (game/deal-cards-to-players turn))]
    ;; for each player bot in turn, get response
    (doseq [{:keys [resp-chan] :as turn-player} turn-players]
      (go (async/>! resp-chan (bot-response-for-turn game turn-player))))

    (go-loop [players-waiting turn-players
              received-responses []]
      (let [[v] (async/alts! (map :resp-chan players-waiting))
            players-left (remove #(= (:id v) (:id %)) players-waiting)
            _ (println (:id v) "=>" (:resp v))]
        (if (seq players-left)
          (recur players-left (conj received-responses v))
          (let [completed-turn (apply-bot-responses turn (conj received-responses v))]
            [completed-turn (game/complete-turn game completed-turn)]))))))

(defn bot-response-complete-turn
  [game turn turn-player]
  (let [bot-response (bots/turn-complete (:bot-instance turn-player) game turn)]
    (assoc turn-player :resp bot-response)))

(defn apply-bot-clean-up-responses
  [received-responses]
  (zipmap (map :id received-responses)
          (map :resp received-responses)))

(defn clean-up-turn
  [game turn]
  (let [turn-players (keys (game/registers-required-for-turn turn))
        turn-players (map #(assoc % :resp-chan (async/timeout 5000)) turn-players)]

    (doseq [{:keys [resp-chan] :as turn-player} turn-players]
      (go (async/>! resp-chan (bot-response-complete-turn game turn turn-player))))

    (go-loop [players-waiting turn-players
              received-responses []]
      (let [[v] (async/alts! (map :resp-chan players-waiting))
            players-left (remove #(= (:id v) (:id %)) players-waiting)]
        (if (seq players-left)
          (recur players-left (conj received-responses v))
          (let [player-responses (apply-bot-clean-up-responses received-responses)]
            [turn (game/clean-up game turn player-responses)]))))))
