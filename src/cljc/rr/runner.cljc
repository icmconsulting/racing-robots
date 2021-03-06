(ns rr.runner
  (:require [taoensso.timbre :as timbre :refer [info debug warn]]
            [rr.game :as game]
            [rr.bots :as bots]
    #?(:cljs [cljs.core.async :as async]
       :clj
            [clojure.core.async :as async :refer [go go-loop]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

(defn send-and-collect-responses
  [players send-fn reduce-responses-fn]
  (let [players (mapv #(assoc % :resp-chan (async/merge [(async/timeout 12000)])) players)]
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
                                (fn [responses]
                                  (let [player-responses (apply-bot-new-game-responses responses)
                                        game-with-player-responses (reduce-kv (fn [game player-id resp]
                                                                                (if-let [profile (:profile resp)]
                                                                                  (game/update-player-profile game player-id profile)
                                                                                  game))
                                                                              new-game player-responses)]
                                    [game-with-player-responses player-responses])))))

(defn bot-response-for-turn
  [game turn {:keys [bot-instance] :as player-turn}]
  (async/map
    #(assoc player-turn :resp %)
    [(bots/turn bot-instance game (assoc (select-keys player-turn [:dealt])
                                    :turn-number (game/turn-number turn)))]))

(defn enter-player-registers
  [turn {:keys [dealt id name] :as player} {:keys [registers powering-down]}]
  (let [extra-cards (clojure.set/difference (set registers) (set dealt))
        number-registers-expected (game/num-registers-for-this-turn player)]
    (cond
      ;; can't play cards you weren't dealt!
      (seq extra-cards)
      (do
        (warn "Cheater! Player [" name "] tried to play register cards that weren't dealt to them!" extra-cards)
        (game/player-invalid-response turn id))

      (not= number-registers-expected (count registers))
      (do
        (warn "Cheater! Player [" name "] did not play the correct number of registers! Expected: " number-registers-expected ", Actual: " (count registers))
        (game/player-invalid-response turn id))

      :else (game/player-enters-registers turn id registers (true? powering-down)))))

(defn apply-bot-responses
  [turn player-with-responses]
  (reduce (fn [turn {:keys [resp id] :as player}]
            (if (#{:rr.connectors/invalid-response :rr.connectors/no-response} resp)
              (game/player-invalid-response turn id)
              (enter-player-registers turn player resp)))
          turn player-with-responses))

(defn next-turn
  "Returns a chan which will eventually have the updated game placed onto after all players have submitted their turns"
  [game]
  (let [turn (game/start-next-turn game)]
    (send-and-collect-responses (game/deal-cards-to-players turn)
                                (partial bot-response-for-turn game turn)
                                (fn [responses]
                                  (let [completed-turn (apply-bot-responses turn responses)]
                                    [completed-turn (game/after-each-register-for-turn game completed-turn) turn])))))

(defn repeat-turn
  "Like next-turn, but repeats an already completed turn"
  [game turn]
  (send-and-collect-responses (game/deal-cards-to-players turn)
                              (partial bot-response-for-turn game turn)
                              (fn [responses]
                                (let [completed-turn (apply-bot-responses turn responses)]
                                  [completed-turn (game/after-each-register-for-turn game completed-turn) turn]))))

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
    (send-and-collect-responses turn-players
                                (partial bot-response-complete-turn game turn)
                                (fn [responses]
                                  (let [player-responses (apply-bot-clean-up-responses responses)]
                                    [turn (game/clean-up game turn player-responses)])))))

(defn bot-response-game-over
  [game turn-player]
  (async/map
    #(assoc turn-player :resp %)
    [(bots/game-over (:bot-instance turn-player) game [])]))

(defn apply-bot-game-over-responses
  [received-responses]
  (zipmap (map :id received-responses)
          (map :resp received-responses)))

(defn game-over
  [game]
  (send-and-collect-responses (game/players game)
                              (partial bot-response-game-over game)
                              apply-bot-game-over-responses))