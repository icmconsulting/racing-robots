(ns rr.bots.lambda
  (:require [rr.bots :as bots]
            [rr.bots.common :as common]
            [amazonica.aws.lambda :as lambda]
            [amazonica.core :refer [ex->map]]
            [taoensso.timbre :as timbre]
            [rr.game :as game]))

(timbre/refer-timbre)

(defn invoke-lambda-function
  [aws-creds function-name message-type data validator]
  (let [message {:message-type message-type
                 :data         data}
        payload (common/write-json-request message)]
    (try
      (let [response (some->
                       (lambda/invoke aws-creds :function-name function-name :payload payload)
                       (:payload)
                       (.array)
                       (String. "UTF-8")
                       (common/read-json-response))
            validation-result (validator response)]
        (if validation-result
          (do
            (warn "Invalid response received when invoking function" function-name)
            (warn (:message (first validation-result)))
            :rr.connectors/invalid-response)
          response))
      (catch com.amazonaws.AmazonServiceException e
        (error e "Error while invoking lambda function" function-name)
        :rr.connectors/error-connecting))))

(defn lambda-new-game
  [aws-creds function-name game player]
  (let [game-data (assoc (common/new-game-data-for-bot game player) :game-id (game/id game))
        response (invoke-lambda-function aws-creds function-name "new-game" game-data common/ready-validator)]
    (if (keyword? response)
      response
      (update response :response keyword))))

(defn lambda-turn
  [aws-creds function-name game player turn]
  (let [game-data (assoc (common/turn-game-data-for-bot game player turn)
                    :game-id (game/id game)
                    :turn-number (:turn-number turn))
        response (invoke-lambda-function aws-creds function-name "turn" game-data (partial common/turn-validator player))]
    (if (keyword? response)
      response
      (common/adapt-turn-response response))))

(defn lambda-turn-complete
  [aws-creds function-name game player turn]
  (let [game-data (assoc (common/completed-turn-game-data-for-bot game player turn)
                    :game-id (game/id game)
                    :turn-number (game/turn-number turn))
        response (invoke-lambda-function aws-creds function-name "turn-complete" game-data common/turn-completed-validator)]
    (if (keyword? response) response (keyword (:response response)))))

(defn lambda-game-over [aws-creds function-name game player]
  (let [game-data (assoc (common/game-over-data-for-bot game player) :game-id (game/id game))
        response (invoke-lambda-function aws-creds function-name "game-over" game-data (constantly nil))]
    (when (keyword? response) (warn "You don't seem to care about the results. That's fine. Good luck to you."))
    :ok))

(defn verify-lambda-function
  [aws-creds function-name]
  (try
    ;; Test get, then invocation
    (timbre/info "Testing whether function [" function-name "] exists...")
    (lambda/get-function aws-creds :function-name function-name)
    (timbre/info "Function [" function-name "] exists. Testing invocation....")

    (if-not (= 200 (:status-code (lambda/invoke aws-creds :function-name function-name :payload {})))
      (do
        (timbre/error "Invocation of function [" function-name "] failed!")
        {:result :fail :reason :lambda/function-failed-invocation})
      {:result :pass})

    (catch com.amazonaws.services.lambda.model.ResourceNotFoundException e
      (timbre/error e "Resource not found exception for function [" function-name "]")
      {:result :fail :reason :lambda/function-not-found})
    (catch com.amazonaws.services.lambda.model.AWSLambdaException e
      (timbre/error e "AWS Lambda Exception while testing function [" function-name "]")
      {:result :fail :reason (case (:error-code (ex->map e))
                               "AccessDeniedException" :lambda/access-denied
                               :lambda/lambda-service-exception)})
    (catch com.amazonaws.AmazonServiceException e
      (timbre/error e "General exception while executing [" function-name "]")
      {:result :fail :reason :lambda/service-exception})))

(defrecord RRLambdaBot [aws-creds function-name player]
  bots/RRBot
  (new-game [_ game] (lambda-new-game aws-creds function-name game player))
  (turn [_ game turn] (lambda-turn aws-creds function-name game player turn))
  (turn-complete [_ game turn] (lambda-turn-complete aws-creds function-name game player turn))
  (game-over [_ game _] (lambda-game-over aws-creds function-name game player))
  bots/RRVerifiableBot
  (verify [_] (verify-lambda-function aws-creds function-name)))

(defmethod bots/player-bot-instance :lambda
  [player]
  (->RRLambdaBot {:endpoint "ap-southeast-2"} (:lambda-function-name player) player))
