(ns rr.bots.docker
  (:require [rr.bots :as bots]
            [rr.bots.common :as common]
            [rr.game :as game]
            [rr.bots.http :as bots.http]
            [config.core :refer [env]]
            [base64-clj.core :as base64]
            [taoensso.timbre :as timbre]
            [amazonica.aws.ecr :as ecr]
            [amazonica.core :refer [with-credential]]
            [clojure.core.async :as async])
  (:import [com.spotify.docker.client DefaultDockerClient ProgressHandler
                                      DockerClient$ListContainersParam DockerClient$ListContainersFilterParam]
           [com.spotify.docker.client.messages AuthConfig ContainerConfig HostConfig PortBinding]))

(def docker-client (-> (DefaultDockerClient/fromEnv)
                       (.build)))

(def aws-account-id (:aws-account-id env "079429354053"))
(def icm-aws-registry-uri "079429354053.dkr.ecr.ap-southeast-2.amazonaws.com")

(defn aws-registry-auth-data
  [aws-creds]
  (let [{:keys [authorization-data]} (with-credential (merge {:endpoint "ap-southeast-2"} aws-creds)
                                                      (ecr/get-authorization-token {:registry-ids [aws-account-id]}))
        auth-data (first authorization-data)
        [user password] (clojure.string/split (base64/decode (:authorization-token auth-data "")) #":")]
    (when auth-data
      (assoc auth-data
        :password password
        :user user))))

(defn auth-config
  [auth-data]
  (let [{:keys [proxy-endpoint password user]} auth-data]
    (-> (AuthConfig/builder)
        (.username user)
        (.password password)
        (.serverAddress proxy-endpoint)
        (.build))))

(defn progress-monitor
  []
  (let [messages (atom [])]
    [messages
     (reify
       ProgressHandler
       (progress [_ msg]
         (let [message-data {:status   (.status msg)
                             :error    (.error msg)
                             :progress (.progress msg)}]
           (swap! messages conj message-data)
           (if (.error msg)
             (timbre/error (.error msg))
             (timbre/info (.status msg))))))]))

(defn image-identifier
  [image-id tag]
  (str icm-aws-registry-uri "/" image-id ":" tag))

(defn pull-image
  [auth progress-monitor image]
  (timbre/info "Starting image pull for " image)
  (.pull docker-client image auth progress-monitor))

(defn container-info
  [container-id]
  (when-let [container-info (.inspectContainer docker-client container-id)]
    {:container-id    container-id
     :image           (.image container-info)
     :ip-address      (.getHost docker-client)
     :current-info-fn #(container-info container-id)
     :state           (some-> container-info (.state) (.status) (keyword))
     :port            (some-> container-info (.networkSettings) (.ports) (get "8080/tcp") (first) (.hostPort))
     :container-info  container-info}))

(defn start-container
  [image]
  (try
    (let [host-config (-> (HostConfig/builder) (.portBindings {"8080/tcp" [(PortBinding/randomPort "0.0.0.0")]}) (.build))
          container-creation (-> (ContainerConfig/builder) (.image image) (.hostConfig host-config) (.build))
          container (.createContainer docker-client container-creation)
          container-id (.id container)]
      (doseq [warning (.getWarnings container)]
        (timbre/warn warning))
      (.startContainer docker-client container-id)
      (let [counter (atom 0)]
        (while (and (< @counter 300)
                    (not= :running (get (container-info container-id) :state)))
          (swap! counter inc)
          (Thread/sleep 100)))
      (container-info container-id))
    (catch Throwable e
      (timbre/error e "Failed to start container for image " image)
      {:state :fail :exception e})))

(def docker-error-message (filter :error))

(defn stop-all
  [containers]
  (doseq [{:keys [container-id]} containers]
    (timbre/debug "Stopping container" container-id)
    (.stopContainer docker-client container-id 5000)
    (.waitContainer docker-client container-id)))

(defn running-containers-for-image
  [image]
  (when-let [running-containers (seq (.listContainers docker-client
                                                      (into-array DockerClient$ListContainersParam
                                                                  [(DockerClient$ListContainersParam/withStatusRunning)])))]
    (->> (filter #(= image (.image %)) running-containers)
         (map (comp container-info #(.id %))))))

(defn verify-docker-image
  [aws-creds image-id tag]
  (let [image-uri (image-identifier image-id tag)
        [messages progress-monitor] (progress-monitor)
        _ (-> (running-containers-for-image image-uri) (stop-all))
        _ (-> (aws-registry-auth-data aws-creds)
              (auth-config)
              (pull-image progress-monitor image-uri))]
    (if (seq (into [] docker-error-message @messages))
      {:result :fail :reason :docker/pull-failed :messages @messages}
      (let [started-container (start-container image-uri)]
        (if (= (:state started-container) :running)
          {:result :pass}
          {:result :fail
           :reason :docker/start-container-failed
           :messages [{:error "Could not start container" :container started-container}]})))))

;(verify-docker-image {} "icm-consulting/lein-clojure-build" "latest")

(defrecord RRDockerBot [aws-creds image-id tag player]
  bots/RRBot
  (new-game [_ game] )
  (turn [_ game turn] )
  (turn-complete [_ game turn] )
  (game-over [_ game _] )
  bots/RRVerifiableBot
  (verify [_] (verify-docker-image aws-creds image-id tag)))
