(ns rr.game-viewer
  (:require [reagent.core :as reagent :refer [atom]]
            [reagent.session :as session]
            [rr.bs :as bs]
            [rr.bots :as bots]
            [rr.boards :as boards]
            [rr.game :as game]
            [rr.runner :as runner]))

(def empty-game {:new-game {:players [{} {} {} {}] :state :not-started :board :random}})
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

(defmethod dispatch-event-type :board-change
  [game-state [_ board]]
  (assoc-in game-state [:new-game :board] (keyword board)))

(defmethod dispatch-event-type :player-connection-port-number
  [game-state [_ player-num port-number]]
  (if-not (clojure.string/blank? port-number)
    (assoc-in game-state [:new-game :players player-num :port] port-number)
    (update-in game-state [:new-game :players player-num] dissoc :port)))

(defn apply-player-bot
  [{:keys [player-type] :as player}]
  ;; TODO: create player ai bots
  (when-let [cpu-bot (bots/local-bots player-type)]
    cpu-bot))

(defn kw->board
  [board]
  (get boards/all-available-boards
       (if (= :random board)
         (rand-nth (keys boards/all-available-boards))
         board)))

(defmethod dispatch-event-type :start-game!
  [game-state _]
  (let [players (map apply-player-bot (get-in game-state [:new-game :players]))
        board (kw->board (get-in game-state [:new-game :board]))]
    {:game (runner/start-new-game {:players players :board (:board board)})}))

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
     [:optgroup {:label "CPU Bots"}
      (for [[bot-key {:keys [name]}] bots/local-bots]
        ^{:key bot-key}
        [:option {:value bot-key} name])]]]
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

(defn board-selection
  []
  [bs/form-group
   [bs/control-label "board"]
   [bs/form-control {:component-class "select"
                     :placeholder     "select board"
                     :default-value   (name (get-in @game-state [:new-game :board] ""))
                     :on-change       #(dispatch! [:board-change (-> % .-target .-value)])}
    [:option {:value "random"} "give me a random one"]
    (for [[board-key _] boards/all-available-boards]
      ^{:key (name board-key)}
      [:option {:value board-key} (name board-key)])]])

(defn start-new-game-root
  []
  [bs/panel {:header "select robotic parameters"}
   [:form
    (for [player-num (range 0 4)]
      ^{:key player-num}
      [player-type-selection player-num])

    [board-selection]

    [bs/button {:bs-size :large
                :bs-style :primary
                :disabled (not (new-game-ready-to-start? @game-state))
                :on-click #(dispatch! [:start-game!])} "Get Racin'"]]])

(defn game-not-started?
  [game]
  (= :not-started (get-in game [:new-game :state])))

(defn game-viewer-middle-section
  []
  [:section.middle
    (if (game-not-started? @game-state)
      [start-new-game-root]
      [game-root])])

(defn player-score-sheet
  [player-num position]
  (let [player (nth (game/players (:game @game-state)) player-num)]
    [:div.player-score-sheet {:class position}
     [:span (:name player)]
     ])
  )

(defn right-player-score-board
  []
  [:div.score-sheet
   [player-score-sheet 2 :top-right]
   [player-score-sheet 3 :bottom-right]])

(defn left-player-score-board
  []
  [:div.score-sheet
   [player-score-sheet 0 :top-left]
   [player-score-sheet 1 :bottom-left]])

(defn game-viewer-left-section
  []
  [:section.left
    (when (:game @game-state)
      [left-player-score-board])])

(defn game-viewer-right-section
  []
  [:section.right
   (when (:game @game-state)
     [right-player-score-board])])

(defn game-viewer-root []
  [:section.game-viewer-root
   [game-viewer-left-section]
   [game-viewer-middle-section]
   [game-viewer-right-section]])

;;TODO: start a game between bots!