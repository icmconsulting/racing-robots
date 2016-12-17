(ns rr.bots.http
  (:require [rr.bots.common :as common]
            [org.httpkit.client :as http]
            [taoensso.timbre :as timbre]
            [config.core :refer [env]]
            [rr.game :as game]
            [rr.bots :as bots]))

(timbre/refer-timbre)

(defn local-address
  [[host port] & path-parts]
  (clojure.string/join "/" (cons (str "http://" host ":" port) path-parts)))

(defn- json-response? [resp]
  (when-let [type (get-in resp [:headers :content-type])]
    (not (empty? (re-find #"^application/(.+\+)?json" type)))))


(defn do-http-request
  [method url game-data response-validator]
  (let [{:keys [error body status] :as resp} @(http/request {:url     url
                                                             :method  method
                                                             :body    (common/write-json-request game-data)
                                                             :headers {"Content-Type" "application/json"}})
        body (when (and (json-response? resp) body) (common/read-json-response body))
        validation-result (when body (response-validator body))]
    (cond
      error (do
              (timbre/error error "Failed to connect to server at url " url)
              (println resp)
              :rr.connectors/error-connecting)
      (and (= 200 status) (nil? validation-result))
      body
      :else
      (do
        (warn "Invalid response received for" (name method) "url ->" url "\n")
        (if (nil? validation-result)
          (warn "HTTP Status code: " status)
          (warn (:message (first validation-result))))
        :rr.connectors/invalid-response))))

(defn http-new-game
  [port game player]
  (let [game-data (common/new-game-data-for-bot game player)
        url (local-address port "game" (game/id game))
        response (do-http-request :post url game-data common/ready-validator)]
    (if (keyword? response)
      response
      (update response :response keyword))))

(defn http-turn [port game player turn]
  (let [game-data (common/turn-game-data-for-bot game player turn)
        url (local-address port "game" (game/id game) (:turn-number turn))
        turn-response (do-http-request :post url game-data (partial common/turn-validator player))]
    (if (keyword? turn-response)
      turn-response
      (common/adapt-turn-response turn-response))))


(defn http-turn-complete
  [port game player turn]
  (let [game-data (common/completed-turn-game-data-for-bot game player turn)
        url (local-address port "game" (game/id game) (:turn-number turn))
        response (do-http-request :put url game-data common/turn-completed-validator)]
    (if (keyword? response) response (keyword (:response response)))))

(defn http-game-over [port game player]
  (let [game-data (common/game-over-data-for-bot game player)
        url (local-address port "game" (game/id game))
        resp (do-http-request :delete url game-data (constantly nil))]
    (when (keyword? resp) (warn "You don't seem to care about the results. That's fine. Good luck to you."))
    :ok))

(defrecord RRHttpBot [server player]
  bots/RRBot
  (new-game [_ game] (http-new-game server game player))
  (turn [_ game turn] (http-turn server game player turn))
  (turn-complete [_ game turn] (http-turn-complete server game player turn))
  (game-over [_ game results] (http-game-over server game player)))

(defmethod bots/player-bot-instance :http
  [player]
  (->RRHttpBot ["localhost" (:port player)] player))
