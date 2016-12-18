(ns rr.connectors
  (:require [compojure.core :refer [GET POST defroutes]]
            [taoensso.timbre :as timbre]
            [ring.util.response :as resp]
            [cognitect.transit :as transit]
            [camel-snake-kebab.core :refer :all]
            [clojure.core.async :as async :refer [go go-loop <! >!]]
            [ring.middleware.transit :refer [wrap-transit-response wrap-transit-body]]
            [rr.middleware :refer [wrap-middleware]]
            [config.core :refer [env]]
            [rr.bots :as bots]
            [rr.game :as game]
            [rr.boards :as boards]
            [rr.bots.http]
            [rr.bots.lambda]
            [rr.bots.docker]))

(timbre/refer-timbre)

(defn wrap-game
  [app]
  (fn [req]
    (let [body (:body req)]
      (app (assoc req :game (:game body))))))

(defn wrap-turn
  [app]
  (fn [req]
    (let [body (:body req)]
      (app (assoc req :turn (:turn body))))))

(defn player-with-bot
  [game player-id]
  (let [player (first (filter #(= (:id %) player-id) (game/players game)))]
    (assoc player :bot-connector (bots/player-bot-instance player))))

(defmacro with-player-connector
  [game player-sym player-id & body]
  `(let [~player-sym (player-with-bot ~game ~player-id)]
     (if (:bot-connector ~player-sym)
       ~@body
       ::no-available-connector)))

(defn player-ready
  [game player-id]
  (with-player-connector
    game player player-id
    (bots/new-game (:bot-connector player) game)))

(defn player-turn [game player-id turn]
  (with-player-connector
    game player player-id
    (bots/turn (:bot-connector player) game turn)))

(defn complete-player-turn [game player-id turn]
  (with-player-connector
    game player player-id
    (bots/turn-complete (:bot-connector player) game turn)))

(defn player-game-over
  [game player-id]
  (with-player-connector
    game player player-id
    (bots/game-over (:bot-connector player) game [])))

(defroutes bot-routes*
           (POST "/new-game/:player-id" {:keys [game params]}
             (resp/response {:response (or (player-ready game (:player-id params)) ::no-response)}))

           (POST "/turn/:player-id" {:keys [game params turn]}
              (resp/response {:response (or (player-turn game (:player-id params) turn) ::no-response)}))

           (POST "/complete-turn/:player-id" {:keys [game params turn]}
             (resp/response {:response (or (complete-player-turn game (:player-id params) turn) ::no-response)}))

           (POST "/game-over/:player-id" {:keys [game params]}
             (resp/response {:response (or (player-game-over game (:player-id params)) ::no-response)})))

(def transit-handler
  {"game"  (transit/read-handler game/map->RRGameState)
   "board" (transit/read-handler (fn [{:keys [name]}] (get-in boards/all-available-boards [name :board])))
   "turn"  (transit/read-handler game/map->RRGameTurnState)})

(def bot-routes
  (-> bot-routes*
      (wrap-turn)
      (wrap-game)
      (wrap-transit-body {:keywords? false
                          :opts {:handlers transit-handler}})
      (wrap-transit-response {:encoding :json :opts {}})))