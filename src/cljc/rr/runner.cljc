(ns rr.runner
  (:require [rr.game :as game]
            [rr.bots :as bots]
            #?(:cljs [cljs.core.async :as async]
               :clj [clojure.core.async :as async :refer [go go-loop]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

;;TODO: get ready status for each bot

(defn start-new-game
  [{:keys [players board]}]
  (game/new-game players board))

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
  (let [turn (game/start-next-turn game)
        turn-players (mapv #(assoc % :resp-chan (async/merge [(async/timeout 5000)])) (game/deal-cards-to-players turn))]
    ;; for each player bot in turn, get response
    (doseq [{:keys [resp-chan] :as turn-player} turn-players]
      (async/pipe (bot-response-for-turn game turn turn-player) resp-chan))

    (go-loop [players-waiting turn-players
              received-responses []]
      (let [_ (println ">" (map :id players-waiting))
            [v ch] (async/alts! (mapv :resp-chan players-waiting))
            _ (println (nil? ch) (nil? v))
            players-left (remove #(identical? ch (:resp-chan %)) players-waiting)
            _ (println (map :id (filter #(identical? ch (:resp-chan %)) players-waiting)))
            _ (println (:id v) "=>" (:resp v) ":" (map :id players-left))]
        (if (seq players-left)
          (recur players-left (conj received-responses v))
          (let [completed-turn (apply-bot-responses turn (conj received-responses v))]
            [completed-turn (game/complete-turn game completed-turn)]))))))

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
  (let [turn-players (keys (game/registers-required-for-turn turn))
        turn-players (map #(assoc % :resp-chan (async/merge [(async/timeout 5000)])) turn-players)]

    (doseq [{:keys [resp-chan] :as turn-player} turn-players]
      (async/pipe (bot-response-complete-turn game turn turn-player) resp-chan))

    (go-loop [players-waiting turn-players
              received-responses []]
      (let [[v ch] (async/alts! (map :resp-chan players-waiting))
            players-left (remove #(identical? ch (:resp-chan %)) players-waiting)
            _ (println (:id v) "=>" (:resp v))]
        (if (seq players-left)
          (recur players-left (conj received-responses v))
          (let [player-responses (apply-bot-clean-up-responses received-responses)]
            [turn (game/clean-up game turn player-responses)]))))))
