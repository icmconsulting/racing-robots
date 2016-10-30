(ns rr.connectors
  (:require [compojure.core :refer [GET POST defroutes]]
            [taoensso.timbre :as timbre]
            [ring.util.response :as resp]
            [clojure.data.json :as json]
            [cognitect.transit :as transit]
            [org.httpkit.client :as http]
            [clojure.core.async :as async :refer [go go-loop <! >!]]
            [ring.middleware.transit :refer [wrap-transit-response wrap-transit-body]]
            [rr.middleware :refer [wrap-middleware]]
            [rr.bots :as bots]
            [rr.boards :as boards]
            [rr.game :as game]))

(timbre/refer-timbre)

(defn other-players
  [player]
  (comp (remove #(= (:id %) (:id player)))
        (map (fn [p]
               (->
                 p
                 (select-keys [:robot :name :id :state])
                 (update :robot dissoc :events))))))

(defn game-data-for-bot
  [game player]
  (let [board (game/board game)]
    {:id    (game/id game)
     :board {:name    (boards/board-from-board board)
             :squares (game/rows board)}
     :player-robot (:robot player)
     :other-players (into [] (other-players player) (game/players game))}))

(defn local-address
  [port & path-parts]
  (clojure.string/join "/" (cons (str "http://localhost:" port) path-parts)))

(defn http-new-game
  [port game player]
  (let [game-data (game-data-for-bot game player)
        url (local-address port "game" (game/id game))
        {:keys [error body status] :as resp} @(http/post url
                                                   {:body    (json/write-str game-data)
                                                    :headers {"Content-Type" "application/json"}})]
    (cond
      error (do
              (timbre/error error "Failed to connect to server on port " port)
              ::error-connecting)
      (and (= 200 status) (= "ready" (:response (json/read-str body :key-fn keyword))))
      :ready
      :else
      (do
        (warn "Invalid response from bot at" url
              "\nExpected body to be JSON, and '{\"response\": \"ready\"}', but received response: " (select-keys resp [:status :body]))
        ::invalid-response))))

(defrecord RRHttpBot [port player]
  bots/RRBot
  (new-game [bot game] (http-new-game port game player))
  (turn [bot game turn] )
  (turn-complete [bot game turn] )
  (game-over [bot game results])
  (profile [bot]))

(defmulti player-bot :connection-type)

(defmethod player-bot :http
  [player]
  (->RRHttpBot (:port player) player))

(defn wrap-game
  [app]
  (fn [req]
    (let [body (:body req)
          board (game/map->RRSeqBoard (get-in body [:game :state :board]))]
      (app (assoc req :game (game/map->RRGameState (assoc-in (:game body) [:state :board] board)))))))

(defn player-with-bot
  [game player-id]
  (let [player (filter #(= (:id %) player-id) (game/players game))]
    (assoc player :bot-connector (player-bot player))))

(comment
  (let [game (game/new-game [{:name "Tester" :port 9000 :connection-type :http}]
                            boards/dizzy-dash)]
    (bots/new-game (player-bot (first (game/players game))) game)
    ))

(defn player-ready
  [game player-id]
  (let [player (player-with-bot game player-id)]
    (if-let [bot-connector (:bot-connector player)]
      (game/new-game bot-connector game)
      ::no-available-connector)))

;;TODO: 1 ajax call == 1 bot command
;; i.e. the RemoteBot impl makes the ajax call once for each player bot - the runner collects all responses...

(defroutes bot-routes*

           (POST "/new-game/:player-id" {:keys [game params]}
             (resp/response (player-ready game (:player-id params))))

           (POST "/turn/:player-id" {:keys [game]}

             )

           )

(def bot-routes
  (-> bot-routes*
      (wrap-game)
      (wrap-transit-body {:keywords? true :opts {}})
      (wrap-transit-response {:encoding :json :opts {}})))