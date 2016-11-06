(ns rr.ajax-bot
  (:require [ajax.core :refer [ajax-request GET POST transit-request-format transit-response-format]]
            [cljs.core.async :as async]
            [rr.boards :as boards]
            [rr.bots :as bots]
            [rr.game :as game])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn csrf-token
  []
  (-> (.getElementsByTagName js/document "body")
      (aget 0)
      (.-dataset)
      (.-csrf)))

(defn response-handler
  [ch [ok response]]
  (go (async/>! ch (if ok (:response response) {:error response}))))

(deftype BoardHandler []
  Object
  (tag [_ _] "board")
  (rep [_ v] {:name (boards/board-from-board v) :board-squares (:board-squares v)}))

(defn fix-player-for-send
  [p]
  (-> p
      (dissoc :bot-instance :bot-instance-fn :robot-image)
      (update :robot dissoc :events)))

(deftype GameHandler []
  Object
  (tag [_ _] "game")
  (rep [_ v] {:state (update-in (:state v) [:players]
                                (partial mapv fix-player-for-send))}))

(deftype TurnHandler []
  Object
  (tag [_ _] "turn")
  (rep [_ v] (assoc (zipmap (keys v) (vals v))
               :players (mapv fix-player-for-send (:players v)))))

(def transit-handlers
  {game/RRSeqBoard      (BoardHandler.)
   game/RRGameState     (GameHandler.)
   game/RRGameTurnState (TurnHandler.)})

(defn base-request
  []
  {:method          :post
   :headers         {"x-csrf-token" (csrf-token)}
   :format          (transit-request-format {:handlers transit-handlers})
   :response-format (transit-response-format)})

(defn send-new-game [game player-id]
  (let [ch (async/chan)]
    (ajax-request
      (merge (base-request)
             {:uri     (str "/bot/new-game/" player-id)
              :params  {:game game}
              :handler (partial response-handler ch)}))
    ch))

(defn send-turn [game player-id turn]
  (let [ch (async/chan)]
    (ajax-request (merge (base-request)
                         {:uri     (str "/bot/turn/" player-id)
                          :params  {:turn turn :game game}
                          :handler (partial response-handler ch)}))
    ch))

(defn send-turn-complete [game player-id turn]
  (let [ch (async/chan)]
    (ajax-request
      (merge (base-request)
             {:uri     (str "/bot/complete-turn/" player-id)
              :params  {:game game :turn turn}
              :handler (partial response-handler ch)}))
    ch))

(defn send-game-over [game player-id]
  (let [ch (async/chan)]
    (ajax-request
      (merge (base-request)
             {:uri     (str "/bot/game-over/" player-id)
              :params  {:game game}
              :handler (partial response-handler ch)}))
    ch))

(defrecord RRAjaxBot [player-id]
  bots/RRBot
  (new-game [bot game] (send-new-game game player-id))
  (turn [bot game turn] (send-turn game player-id turn))
  (turn-complete [bot game turn] (send-turn-complete game player-id turn))
  (game-over [bot game results] (send-game-over game player-id)))
