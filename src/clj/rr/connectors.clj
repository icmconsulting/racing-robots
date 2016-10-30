(ns rr.connectors
  (:require [compojure.core :refer [GET POST defroutes]]
            [taoensso.timbre :as timbre]
            [ring.util.response :as resp]
            [clojure.data.json :as json]
            [cognitect.transit :as transit]
            [org.httpkit.client :as http]
            [scjsv.core :as validator]
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

(defn new-game-data-for-bot
  [game player]
  (let [board (game/board game)]
    {:id    (game/id game)
     :board {:name    (boards/board-from-board board)
             :squares (game/rows board)}
     :player-robot (:robot player)
     :other-players (into [] (other-players player) (game/players game))}))

(defn turn-game-data-for-bot
  [game player turn]
  (let [{:keys [dealt]} (first (filter #(= (:id player) (:id %)) (game/deal-cards-to-players turn)))]
    (merge (new-game-data-for-bot game player)
           {:cards dealt})))

(defn local-address
  [port & path-parts]
  (clojure.string/join "/" (cons (str "http://localhost:" port) path-parts)))

(def ready-schema
  {:$schema "http://json-schema.org/draft-04/schema#"
   :type "object"
   :properties {:response {:enum ["ready"]}}
   :required [:response]})

(def ready-validator (validator/validator ready-schema))

(defn- json-response? [resp]
  (when-let [type (get-in resp [:headers :content-type])]
    (not (empty? (re-find #"^application/(.+\+)?json" type)))))

(defn do-http-request
  [method url game-data response-validator]
  (let [{:keys [error body status] :as resp} @(http/request {:url     url
                                                             :method  method
                                                             :body    (json/write-str game-data)
                                                             :headers {"Content-Type" "application/json"}})
        body (when (and (json-response? resp) body) (json/read-str body :key-fn keyword))
        validation-result (when body (response-validator body))]
    (cond
      error (do
              (timbre/error error "Failed to connect to server to url " url)
              ::error-connecting)
      (and (= 200 status) (nil? validation-result))
      body
      :else
      (do
        (warn "Invalid response received for" (name method) "url ->" url "\n"
              (if-not validation-result
                (str "HTTP Status code: " status)
                (:message (first validation-result))))
        ::invalid-response))))

(defn http-new-game
  [port game player]
  (let [game-data (new-game-data-for-bot game player)
        url (local-address port "game" (game/id game))
        response (do-http-request :post url game-data ready-validator)]
    (if (keyword? response) response :ready)))

(def turn-schema
  {:$schema "http://json-schema.org/draft-04/schema#"
   :type "object"
   :properties {:registers {:$ref "#/definitions/registers"}
                :powering-down {:type "boolean"}}
   :definitions {:registers {:type "array" :items {:$ref "#/definitions/register"}}
                 :register {:type "object"
                            :properties {:type {:$ref "#/definitions/type"}
                                         :value {:$ref "#/definitions/value"}
                                         :priority {:$ref "#/definitions/priority"}}
                            :required [:type :value :priority]}
                 :type {:enum ["move" "rotate"]}
                 :value {:enum [1 2 3 -1 "right" "left" "u-turn"]}
                 :priority {:type "integer"}}
   :required [:registers :powering-down]})

(def turn-validator (validator/validator turn-schema))

(defn http-turn [port game player turn]
  (let [game-data (turn-game-data-for-bot game player turn)
        url (local-address port "game" (game/id game) (game/turn-number turn))]
    (do-http-request :post url game-data turn-validator)))

(defrecord RRHttpBot [port player]
  bots/RRBot
  (new-game [bot game] (http-new-game port game player))
  (turn [bot game turn] (http-turn port game player turn))
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
  (let [game (game/new-game [{:name "Tester 1" :port 9000 :connection-type :http}
                             {:name "Tester 2" :connection-type :bot}]
                            boards/dizzy-dash)]
    (bots/turn (player-bot (first (filter :port (game/players game)))) game (game/start-next-turn game))
    ))

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
    (game/new-game (:bot-connector player) game)))

;;TODO: 1 ajax call == 1 bot command
;; i.e. the RemoteBot impl makes the ajax call once for each player bot - the runner collects all responses...

(defn player-turn [game player-id]
  (with-player-connector
    game player player-id
    (game/new-game (:bot-connector player) game)))

(defroutes bot-routes*

           (POST "/new-game/:player-id" {:keys [game params]}
             (resp/response (player-ready game (:player-id params))))

           (POST "/turn/:player-id" {:keys [game params]}
              (resp/response (player-turn game (:player-id params))))



           )

(def bot-routes
  (-> bot-routes*
      (wrap-game)
      (wrap-transit-body {:keywords? true :opts {}})
      (wrap-transit-response {:encoding :json :opts {}})))