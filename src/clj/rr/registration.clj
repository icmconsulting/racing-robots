(ns rr.registration
  (:require [compojure.core :refer [GET POST PUT DELETE defroutes context routes]]
            [taoensso.timbre :as timbre]
            [ring.util.response :as resp]
            [config.core :refer [env]]
            [alandipert.enduro :as enduro]
            [camel-snake-kebab.core :refer :all]
            [clojure.core.async :as async :refer [go go-loop <! >!]]
            [ring.middleware.transit :refer [wrap-transit-response wrap-transit-body]]
            [rr.middleware :refer [wrap-middleware]]
            [rr.bots :as bots]
            [rr.boards :as boards]
            [rr.game :as game])
  (:import [java.util UUID]))

(def empty-registrations {:players {}})

(defonce registrations
  (if-let [reg-file (env :reg-file)]
    (enduro/file-atom empty-registrations reg-file)
    (enduro/mem-atom empty-registrations)))

(defn generate-registrations!
  [teams]
  (enduro/reset! registrations (zipmap (repeatedly (comp str #(UUID/randomUUID))) teams)))

(comment
  (generate-registrations! [{:player1 "Raj" :player2 "Chaitanya"}
                            {:player1 "Emile" :player2 "Ajeethan"}
                            {:player1 "Helder" :player2 "Phil"}
                            {:player1 "Yair" :player2 "Bernhard"}
                            {:player1 "Abhijeet" :player2 "Nelson"}
                            {:player1 "Robert" :player2 "John"}
                            {:player1 "Conrad" :player2 "?"}]))

;; TODO: generate player registrations from team entries
;; - Generates URLs - will slack manually to teams

(def default-other-bot (bots/player-bot {:player-type :zippy}))
(def default-board boards/proving-grounds)

(defn format-log
  [data]
  {:message (force (:output_ data))})

(defmacro with-captured-logs
  [& body]
  `(let [logs# (atom [])]
     (timbre/with-merged-config
       {:appenders
        {:captured {:enabled?  true :async? false
                    :min-level nil
                    :output-fn (partial timbre/default-output-fn {:no-stacktrace? true})
                    :fn        #(swap! logs# conj (format-log %))}}}
       (let [res# ~@body]
         (if (map? res#)
           (assoc res# :logs @logs#)
           res#)))))

(defn exec-bot-verification
  [{:keys [bot-instance]}]
  (go
    (with-captured-logs
      (merge {:test :verification :description "Verify that your bot is up and can be contacted."}
             (try (if (satisfies? bots/RRVerifiableBot bot-instance)
                    (bots/verify bot-instance)
                    {:result :pass})
                  (catch Throwable e
                    (timbre/error e "Exception while verifying docker image.")
                    {:result :fail :reason :exception :messages [(.getMessage e)]}))))))

(defn exec-new-game-verification
  [player]
  (go
    (with-captured-logs
      (let [new-game (game/new-game [player default-other-bot] default-board)
            _ (timbre/info "Sending new game request to bot...")
            new-game-response (try (bots/new-game (:bot-instance player) new-game)
                                   (catch Throwable e
                                     (timbre/error e "Exception caught while sending new game request to bot!")
                                     {:exception (.getMessage e)}))]
        (merge {:test :new-game :description "Initiate a new game with your bot, and fetch your profile"}
               (if-not (= :ready (:response new-game-response))
                 (do
                   (timbre/error "New game test failed as an invalid response was received from the bot!")
                   {:result :fail :data new-game-response :reason (if (:exception new-game-response) :exception :not-ready)})
                 (do
                   (timbre/info "New game test passed!")
                   {:result :pass :profile (:profile new-game-response)})))))))

(defn exec-player-bot-tests
  [player-bot]
  (let [player-bot (game/player-with-bot-instance player-bot)]
    (go
      (let [bot-verification (async/<! (exec-bot-verification player-bot))
            new-game-verification (async/<! (exec-new-game-verification player-bot))]
        [bot-verification new-game-verification]))))

(defn execute-tests
  [player]
  (let [player-bot (merge (bots/player-bot player) (select-keys player [:registration-id]))
        result-chan (async/pipe (exec-player-bot-tests player-bot) (async/timeout 30000))]
    (async/<!! result-chan)))

(defn test-and-maybe-save!
  [registration]
  (let [test-results (execute-tests (assoc registration :player-type :player))]
    (if (and (seq test-results)
             (every? #(= :pass (:result %)) test-results))
      (do (enduro/swap! registrations assoc
                        (:registration-id registration)
                        (assoc registration :result :saved
                                            :test-results test-results))
          {:result :saved
           :registration registration
           :test-results test-results})
      {:result :failed
       :registration registration
       :test-results test-results})))

(defn reset-registration!
  [{:keys [registration-id]}]
  (enduro/swap! registrations update registration-id select-keys [:player1 :player2])
  {:registration (assoc (get @registrations registration-id) :registration-id registration-id)})

(defn registration-id-routes
  [registration-id]
  (if-let [registration (get @registrations registration-id)]
    (routes
      (GET "/" [] (resp/response {:registration (assoc registration :registration-id registration-id)}))

      (PUT "/" {:keys [body]}
           (resp/response (test-and-maybe-save! (:registration body))))

      (DELETE "/" _
        (resp/response (reset-registration! registration))))
    (resp/not-found {})))

(defroutes registration-routes*
           (GET "/" [] (resp/response {:registrations @registrations}))
           (context "/:registration-id" [registration-id]
             (registration-id-routes registration-id)))

(def registration-routes
  (-> registration-routes*
      (wrap-transit-body {:keywords? false})
      (wrap-transit-response {:encoding :json :opts {}})))
