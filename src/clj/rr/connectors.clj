(ns rr.connectors
  (:require [compojure.core :refer [GET POST defroutes]]
            [taoensso.timbre :as timbre]
            [ring.util.response :as resp]
            [clojure.data.json :as json]
            [cognitect.transit :as transit]
            [org.httpkit.client :as http]
            [scjsv.core :as validator]
            [amazonica.aws.lambda :as lambda]
            [clojure.core.async :as async :refer [go go-loop <! >!]]
            [ring.middleware.transit :refer [wrap-transit-response wrap-transit-body]]
            [rr.middleware :refer [wrap-middleware]]
            [rr.bots :as bots]
            [rr.boards :as boards]
            [rr.game :as game])
  (:import [com.amazonaws AmazonServiceException]))

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
     :player-id (:id player)
     :board {:name    (:name board)                         ;; bit of a hack - this assumes that :name is within the map of the board that is sent
             :squares (game/rows board)}
     :player-robot (:robot player)
     :other-players (into [] (other-players player) (game/players game))}))

(defn turn-game-data-for-bot
  [game player {:keys [dealt]}]
  (merge (new-game-data-for-bot game player)
         {:cards dealt}))

(defn completed-turn-game-data-for-bot
  [game player turn]
  (let [turn-players (game/deal-cards-to-players turn)
        registers-for-players (game/registers-for-turn turn)]
    (merge (new-game-data-for-bot game player)
           {:other-players (into [] (comp (other-players player)
                                          (map #(assoc % :last-turn (get registers-for-players (:id %)))))
                                 turn-players)
            :available-responses (game/allowable-clean-up-commands player turn)})))

(defn game-over-data-for-bot
  [game player]
  (let [[_ players] (game/victory-status game)]
    {:winners (map #(dissoc % :robot) (filter #(= :winner (:victory-state %)) players))
     :all-players players}))

(defn local-address
  [port & path-parts]
  (clojure.string/join "/" (cons (str "http://localhost:" port) path-parts)))

(def ready-schema
  {:$schema "http://json-schema.org/draft-04/schema#"
   :type "object"
   :properties {:response {:enum ["ready"]}
                :profile {:type "object"
                          :properties {:name {:type "string"}
                                       :robot-name {:type "string"}
                                       :avatar {:type "string"}}
                          :required [:name :robot-name :avatar]}}
   :required [:response :profile]})

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
        (warn "Invalid response received for" (name method) "url ->" url "\n")
        (if (nil? validation-result)
          (warn "HTTP Status code: " status)
          (warn (:message (first validation-result))))
        ::invalid-response))))

(defn http-new-game
  [port game player]
  (let [game-data (new-game-data-for-bot game player)
        url (local-address port "game" (game/id game))
        response (do-http-request :post url game-data ready-validator)]
    (if (keyword? response)
      response
      (update response :response keyword))))

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

(defn adapt-register-response
  [register]
  (-> register
      (update :type keyword)
      (update :value #(if (integer? %) % (keyword %)))))

(defn adapt-turn-response
  [turn-response]
  (update turn-response :registers #(map adapt-register-response %)))

(defn http-turn [port game player turn]
  (let [game-data (turn-game-data-for-bot game player turn)
        url (local-address port "game" (game/id game) (:turn-number turn))
        turn-response (do-http-request :post url game-data turn-validator)]
    (if (keyword? turn-response)
      turn-response
      (adapt-turn-response turn-response))))

(def turn-completed-schema
  {:$schema "http://json-schema.org/draft-04/schema#"
   :type "object"
   :properties {:response {:enum ["power-down" "power-down-override" "no-action"]}}
   :required [:response]})

(def turn-completed-validator (validator/validator turn-completed-schema))

(defn http-turn-complete
  [port game player turn]
  (let [game-data (completed-turn-game-data-for-bot game player turn)
        url (local-address port "game" (game/id game) (:turn-number turn))
        response (do-http-request :put url game-data turn-completed-validator)]
    (if (keyword? response) response (keyword (:response response)))))

(defn http-game-over [port game player]
  (let [game-data (game-over-data-for-bot game player)
        url (local-address port "game" (game/id game))
        resp (do-http-request :delete url game-data (constantly nil))]
    (when (keyword? resp) (warn "You don't seem to care about the results. That's fine. Good luck to you."))
    :ok))

(defrecord RRHttpBot [port player]
  bots/RRBot
  (new-game [_ game] (http-new-game port game player))
  (turn [_ game turn] (http-turn port game player turn))
  (turn-complete [_ game turn] (http-turn-complete port game player turn))
  (game-over [bot game results] (http-game-over port game player)))

(defn invoke-lambda-function
  [aws-creds function-name message-type data validator]
  (let [message {:message-type message-type
                 :data         data}
        payload (json/write-str message)]
    (try
      (let [response (some->
                       (lambda/invoke aws-creds :function-name function-name :payload payload)
                       (:payload)
                       (.array)
                       (String. "UTF-8")
                       (json/read-str :key-fn keyword))
            validation-result (validator response)]
        (if validation-result
          (do
            (warn "Invalid response received when invoking function" function-name)
            (warn (:message (first validation-result)))
            ::invalid-response)
          response))
      (catch com.amazonaws.AmazonServiceException e
        (error e "Error while invoking lambda function" function-name)
        ::error-connecting))))

(defn lambda-new-game
  [aws-creds function-name game player]
  (let [game-data (assoc (new-game-data-for-bot game player) :game-id (game/id game))
        response (invoke-lambda-function aws-creds function-name "new-game" game-data ready-validator)]
    (if (keyword? response)
      response
      (update response :response keyword))))

(defn lambda-turn
  [aws-creds function-name game player turn]
  (let [game-data (assoc (turn-game-data-for-bot game player turn)
                    :game-id (game/id game)
                    :turn-number (:turn-number turn))
        response (invoke-lambda-function aws-creds function-name "turn" game-data turn-validator)]
    (if (keyword? response)
      response
      (adapt-turn-response response))))

(defn lambda-turn-complete
  [aws-creds function-name game player turn]
  (let [game-data (assoc (completed-turn-game-data-for-bot game player turn)
                    :game-id (game/id game)
                    :turn-number (game/turn-number turn))
        response (invoke-lambda-function aws-creds function-name "turn-complete" game-data turn-completed-validator)]
    (if (keyword? response) response (keyword (:response response)))))

(defn lambda-game-over [aws-creds function-name game player]
  (let [game-data (assoc (game-over-data-for-bot game player) :game-id (game/id game))
        response (invoke-lambda-function aws-creds function-name "game-over" game-data (constantly nil))]
    (when (keyword? response) (warn "You don't seem to care about the results. That's fine. Good luck to you."))
    :ok))

(defrecord RRLambdaBot [aws-creds function-name player]
  bots/RRBot
  (new-game [_ game] (lambda-new-game aws-creds function-name game player))
  (turn [_ game turn] (lambda-turn aws-creds function-name game player turn))
  (turn-complete [_ game turn] (lambda-turn-complete aws-creds function-name game player turn))
  (game-over [_ game _] (lambda-game-over aws-creds function-name game player)))

(defmulti player-bot :connection-type)

(defmethod player-bot :http
  [player]
  (->RRHttpBot (:port player) player))

(defmethod player-bot :lambda
  [player]
  (->RRLambdaBot {:endpoint "ap-southeast-2"} (:lambda-function-name player) player))

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
    (assoc player :bot-connector (player-bot player))))

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
   "board" (transit/read-handler game/map->RRSeqBoard)
   "turn"  (transit/read-handler game/map->RRGameTurnState)})

(def bot-routes
  (-> bot-routes*
      (wrap-turn)
      (wrap-game)
      (wrap-transit-body {:keywords? false
                          :opts {:handlers transit-handler}})
      (wrap-transit-response {:encoding :json :opts {}})))