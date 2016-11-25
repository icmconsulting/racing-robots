(ns rr.registration
  (:require [compojure.core :refer [GET POST PUT defroutes context routes]]
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

;; TODO: generate player registrations from team entries
;; - Generates URLs - will slack manually to teams

(def default-other-bot (bots/player-bot {:player-type :zippy}))
(def default-board boards/proving-grounds)

(defn exec-bot-verification
  [{:keys [bot-instance]}]
  (go
    (merge {:test :verification :description "Verify that your bot is up and can be contacted."}
           (if (satisfies? bots/RRVerifiableBot bot-instance)
             (bots/verify bot-instance)
             {:result :pass}))))

(defn exec-new-game-verification
  [player]
  (go
    (let [new-game (game/new-game [player default-other-bot] default-board)
          new-game-response (bots/new-game (:bot-instance player) new-game)]
      (merge {:test :new-game :description "Initiate a new game with your bot, and fetch your profile"}
             (if-not (= :ready (:response new-game-response))
               {:result :fail :data new-game-response}
               {:result :pass})))))

(defn exec-player-bot-tests
  [player-bot]
  (let [player-bot (game/player-with-bot-instance player-bot)]
    (go
      [(async/<! (exec-bot-verification player-bot))
       (async/<! (exec-new-game-verification player-bot))])))

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
                        (:registration-id registration) (assoc registration :result :saved
                                                                            :test-results test-results))
          {:result :saved
           :registration registration
           :test-results test-results})
      {:result :failed
       :registration registration
       :test-results test-results})))

(defn registration-id-routes
  [registration-id]
  (if-let [registration (get @registrations registration-id)]
    (routes
      (GET "/" [] (resp/response {:registration (assoc registration :registration-id registration-id)}))

      (PUT "/" {:keys [body]}
           (resp/response (test-and-maybe-save! (:registration body)))))
    (resp/not-found {})))

(defroutes registration-routes*
           (context "/:registration-id" [registration-id]
             (registration-id-routes registration-id)))

(def registration-routes
  (-> registration-routes*
      (wrap-transit-body {:keywords? false})
      (wrap-transit-response {:encoding :json :opts {}})))