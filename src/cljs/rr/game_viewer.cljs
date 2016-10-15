(ns rr.game-viewer
  (:require [reagent.core :as reagent :refer [atom]]
            [reagent.session :as session]
            [rr.bs :as bs]))

(def empty-game {:new-game {:players [{} {} {} {}] :state :not-started}})
(defonce game-state (atom empty-game))

(defn game-root
  []

  )

(defmulti dispatch-event-type (fn [game-state-val event] (first event)))

(defmethod dispatch-event-type :player-type-change
  [game-state [_ player-num selected-player-type]]
  (assoc-in game-state [:new-game :players player-num :player-type] (keyword selected-player-type)))

(defmethod dispatch-event-type :player-connection-change
  [game-state [_ player-num connection-type]]
  (assoc-in game-state [:new-game :players player-num :connection-type] (keyword connection-type)))

(defmethod dispatch-event-type :player-connection-port-number
  [game-state [_ player-num port-number]]
  (if-not (clojure.string/blank? port-number)
    (assoc-in game-state [:new-game :players player-num :port] port-number)
    (update-in game-state [:new-game :players player-num] dissoc :port)))

(defn dispatch!
  [event]
  (swap! game-state dispatch-event-type event))

(defn new-game-player-by-number
  [game-state player-num]
  (get-in game-state [:new-game :players player-num]))

(defn port-number-input
  [player-num]
  [bs/form-group
   [bs/control-label "Port number"]
   [bs/form-control {:type :number
                     :placeholder "Localhost bound port number of your RR AI Bot application"
                     :min 1
                     :max 65536
                     :value (:port (new-game-player-by-number @game-state player-num) "")
                     :on-change #(dispatch! [:player-connection-port-number player-num (-> % .-target .-value)])}]
   [bs/help-block "Obviously, should be between 0 and 65536"]])

(defn player-type-selection
  [player-num]
  [:div
   [bs/form-group
    [bs/control-label (str "player " (inc player-num) " type")]
    [bs/form-control {:component-class "select"
                      :placeholder     "select player type"
                      :default-value   (name (:player-type (new-game-player-by-number @game-state player-num) ""))
                      :on-change       #(dispatch! [:player-type-change player-num (-> % .-target .-value)])}
     [:option {:disabled true :value ""} "select a player type"]
     [:option {:value "player"} "rr bot competitor (you)"]
     ;;TODO: read the available bots from the bot list
     [:optgroup {:label "CPU Bots"}
      [:option {:value "zippy"} "Zippy the idiot"]
      [:option {:value "kevin"} "Kevin the wrecker"]
      [:option {:value "phillis"} "Phillis the phoney"]]]]
   (let [new-game-player (new-game-player-by-number @game-state player-num)]
     (when (= (:player-type new-game-player) :player)
       [bs/well
        [bs/form-group
         [bs/control-label "Connection type"]
         [bs/radio {:name "connection" :on-change #(dispatch! [:player-connection-change player-num :http])} "Json over HTTP/REST"]
         (when (= :http (:connection-type new-game-player)) [port-number-input player-num])
         [bs/radio {:name "connection" :on-change #(dispatch! [:player-connection-change player-num :socket])} "Socket"]
         (when (= :socket (:connection-type new-game-player)) [port-number-input player-num])
         [bs/radio {:name "connection" :on-change #(dispatch! [:player-connection-change player-num :lambda])} "Json over AWS Lambda"]]]))])

(defn new-game-ready-to-start?
  [{:keys [new-game]}]
  (println new-game)
  (let [{:keys [players]} new-game
        player-players (filter #(= :player (:player-type %)) players)
        http-players (filter #(= :http (:connection-type %)) players)
        socket-players (filter #(= :socket (:connection-type %)) players)]
    (and
      (every? :player-type players)
      (every? :connection-type player-players)
      (every? :port http-players)
      (every? :port socket-players))))

(defn start-new-game-root
  []
  [bs/panel {:header "select robotic parameters"}
   [:form
    (for [player-num (range 0 4)]
      ^{:key player-num}
      [player-type-selection player-num])

    [bs/button {:bs-size :large :bs-style :primary :disabled (not (new-game-ready-to-start? @game-state))} "Get Racin'"]]])

(defn game-not-started?
  [game]
  (= :not-started (get-in game [:new-game :state])))

(defn game-viewer-middle-section
  []
  [:section.middle
    (if (game-not-started? @game-state)
      [start-new-game-root]
      [game-root])])

(defn game-viewer-root []
  [:section.game-viewer-root
   [:section.left

    ]
   [game-viewer-middle-section]
   [:section.right

    ]])

;;TODO: start a game between bots!