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
           [com.spotify.docker.client.messages AuthConfig ContainerConfig HostConfig PortBinding]
           [java.net Socket InetSocketAddress URL SocketTimeoutException]
           [java.io IOException FileNotFoundException]))

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
     :host            (.getHost docker-client)
     :current-info-fn #(container-info container-id)
     :state           (some-> container-info (.state) (.status) (keyword))
     :port            (some-> container-info (.networkSettings) (.ports) (get "8080/tcp") (first) (.hostPort))
     :container-info  container-info}))

(defn wait-until-port-ready
  [host port]
  (let [socket (doto (Socket.) (.setReuseAddress true))
        socket-addr (InetSocketAddress. host (Integer/parseInt port))
        url-connection (.openConnection (URL. (str "http://" host ":" port "/")))]
    (try
      (.connect socket socket-addr 500)
      (timbre/debug "Socket connect successful, testing HTTP")
      (.connect url-connection)
      (with-open [is (.getInputStream url-connection)]
        (.read is))
      :ready
      (catch FileNotFoundException _
             (timbre/debug "File not found exception while attempting http conn to server - safe to ignore...")
             :ready)
      (catch IOException e
        :failed)
      (catch SocketTimeoutException _
        :failed)
      (finally
        (try
          (when socket (.close socket))
          (when url-connection (.disconnect url-connection))
          (catch Throwable _))))))

(defn start-container
  [image]
  (try
    (let [host-config (-> (HostConfig/builder) (.portBindings {"8080/tcp" [(PortBinding/randomPort "0.0.0.0")]}) (.build))
          container-creation (-> (ContainerConfig/builder) (.image image) (.hostConfig host-config) (.build))
          _ (timbre/info "Creating container for image [" image "]...")
          container (.createContainer docker-client container-creation)
          container-id (.id container)]
      (timbre/info "Container created for [" image "] with ID [" container-id "]")

      (doseq [warning (.getWarnings container)]
        (timbre/warn warning))

      (timbre/info "Attempting to start container [" container-id "]...")
      (.startContainer docker-client container-id)

      ;; Wait until container is running
      (let [counter (atom 0)]
        (while (and (< @counter 100)
                    (not= :running (get (container-info container-id) :state)))
          (swap! counter inc)
          (Thread/sleep 100)))

      (timbre/info "Waiting until host port bound to 8080 is accepting connections for container [" container-id "]...")

      ;; Wait until the exposed port is accepting connections
      (let [counter (atom 0)
            container-info (container-info container-id)]
        (if (:port container-info)
          (do
            (while (and (< @counter 100)
                        (not= :ready (wait-until-port-ready (:host container-info) (:port container-info))))
              (swap! counter inc)
              (Thread/sleep 100))

            (if (= :ready (wait-until-port-ready (:host container-info) (:port container-info)))
              (do
                (timbre/info "Container [" container-id "] for image [" image "] successfully started and is ready for connections.")
                container-info)
              (do
                (timbre/error "Could not connect to docker host port.")
                {:state :fail :reason :could-not-connect})))
          (do
            (timbre/error "No guest port on 8080! Have you exposed it in your Dockerfile???")
            {:state :fail :reason :no-exposed-port}))))
    (catch Throwable e
      (timbre/error e "Failed to start container for image " image)
      {:state :fail :exception (.getMessage e)})))

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
        _ (timbre/info "Stopping all running containers for image [" image-uri "]")
        _ (-> (running-containers-for-image image-uri) (stop-all))
        _ (-> (aws-registry-auth-data aws-creds)
              (auth-config)
              (pull-image progress-monitor image-uri))]
    (if (seq (into [] docker-error-message @messages))
      (do
        (timbre/error "Pull of image [" image-uri  "] failed!")
        {:result :fail :reason :docker/pull-failed})
      (let [started-container (start-container image-uri)]
        (if (= (:state started-container) :running)
          {:result :pass}
          {:result :fail
           :reason :docker/start-container-failed
           :messages [{:error "Could not start container" :container started-container}]})))))

;(verify-docker-image {:endpoint "ap-southeast-2"} "icm-consulting/bb-test" "latest")

(defmacro http-bot-for-container
  [image-id tag player bot-binding & body]
  `(let [image-uri# (image-identifier ~image-id ~tag)
         running-containers# (running-containers-for-image image-uri#)
         container# (if-not (seq running-containers#)
                     (start-container image-uri#)
                     (first running-containers#))
         body-fn# (fn [~bot-binding] ~@body)]
     (if (not= :running (:state container#))
       (do
         (timbre/error "Container" (:container-id container#) "is in state " (:state container#) " - need it to be :running")
         :rr.connectors/error-connecting)
       (body-fn# (bots.http/->RRHttpBot [(:host container#) (:port container#)] ~player)))))

(defrecord RRDockerBot [aws-creds image-id tag player]
  bots/RRBot
  (new-game [_ game] (http-bot-for-container image-id tag player http-bot
                                             (bots/new-game http-bot game)))
  (turn [_ game turn] (http-bot-for-container image-id tag player http-bot
                                              (bots/turn http-bot game turn)))
  (turn-complete [_ game turn] (http-bot-for-container image-id tag player http-bot
                                                       (bots/turn-complete http-bot game turn)))
  (game-over [_ game results] (http-bot-for-container image-id tag player http-bot
                                                      (bots/game-over http-bot game results)))
  bots/RRVerifiableBot
  (verify [_] (verify-docker-image aws-creds image-id tag)))

(defmethod bots/player-bot-instance :docker
  [player]
  (->RRDockerBot {:endpoint "ap-southeast-2"} (:image-id player) (:tag player) player))
