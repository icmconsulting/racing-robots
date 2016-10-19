(ns rr.game-viewer
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.session :as session]
            [cljs.core.async :as async]
            [rr.bs :as bs]
            [rr.bots :as bots]
            [rr.boards :as boards]
            [rr.board-viewer :refer [board-view board-parent-resize-props]]
            [rr.game :as game]
            [rr.runner :as runner]
            [rr.utils :refer [image-obj]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def empty-game {:new-game {:players [{} {} {} {}] :state :not-started :board :random}})
(defonce game-state (atom empty-game))

(defmulti dispatch-event-type (fn [_ event] (first event)))

(defn dispatch!
  [event]
  (swap! game-state dispatch-event-type event))

(def bot0-image (image-obj "/images/bot-0.gif"))
(def bot1-image (image-obj "/images/bot-1.gif"))
(def bot2-image (image-obj "/images/bot-2.gif"))
(def bot3-image (image-obj "/images/bot-3.gif"))
(def bot4-image (image-obj "/images/bot-4.gif"))
(def bot5-image (image-obj "/images/bot-5.gif"))
(def bot6-image (image-obj "/images/bot-6.gif"))

(def bot7-image (image-obj "/images/bot-7.gif"))

(def all-bot-images #{bot0-image bot1-image bot2-image bot3-image bot4-image bot5-image bot6-image bot7-image})

(defmethod dispatch-event-type :abandon-game! [_ _] empty-game)

(defmethod dispatch-event-type :game-next-turn!
  [game-state _]
  (let [next-turn-chan (runner/next-turn (:game game-state))]
    (go (dispatch! [:game-turn-responses-received! (async/<! next-turn-chan)]))
    (-> (assoc game-state :waiting-for-players? true)
        (update :state-stack conj (:game game-state)))))

(defmethod dispatch-event-type :game-turn-responses-received!
  [game-state [_ [turn game-with-turn-changes]]]
  (assoc game-state :game game-with-turn-changes
                    :current-turn turn
                    :waiting-for-players? false))

(defmethod dispatch-event-type :game-clean-up-turn!
  [game-state _]
  (let [next-turn-chan (runner/clean-up-turn (:game game-state) (:current-turn game-state))]
    (go (dispatch! [:game-clean-up-responses-received! (async/<! next-turn-chan)]))
    (-> (assoc game-state :waiting-for-players? true)
        (update :state-stack conj (:game game-state)))))

(defmethod dispatch-event-type :game-clean-up-responses-received!
  [game-state [_ [turn game-ready-for-next-turn]]]
  (assoc game-state
    :game game-ready-for-next-turn
    :current-turn nil
    :previous-turn turn
    :waiting-for-players? false))

(defn game-controller-panel
  []
  (let [waiting? (:waiting-for-players? @game-state)]
    [:div.game-controller-panel
     [bs/button-group {:vertical true}
      (when-not (:current-turn @game-state)
        [bs/button {:bs-size  "small"
                    :on-click #(dispatch! [:game-next-turn!])
                    :disabled waiting?}
         [bs/glyph {:glyph "step-forward"}] "Next turn"])
      (when (:current-turn @game-state)
        [bs/button {:bs-size  "small"
                    :on-click #(dispatch! [:game-clean-up-turn!])
                    :disabled waiting?}
         [bs/glyph {:glyph "step-forward"}] "Clean-up turn"])
      [bs/button {:bs-size "small" :disabled waiting?}
       [bs/glyph {:glyph "play-circle"}] "Autoplay"]]

     [bs/button-group {:vertical true}
      [bs/button {:bs-size  "small"
                  :bs-style :danger
                  :on-click #(dispatch! [:abandon-game!])
                  :disabled waiting?}
       [bs/glyph {:glyph "remove"}] "Abandon!"]]]))

(defn game-data-cursor
  ([k]
   (let [curr-game (:game @game-state)
         data {:board   (game/board curr-game)
               :players (game/players curr-game)
               :container-id "game-board"}]
     (get-in data k)))
  ([k v] (throw (ex-info "Don't change the game cursor, goddamit!" {:tried-to-change [k v]}))))

(defn game-root []
  [:div#game-board
   [:div [board-view (cursor game-data-cursor [])]]
   [game-controller-panel]])

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
    (assoc cpu-bot
      :robot-image (rand-nth (seq all-bot-images))
      :bot-instance ((:bot-instance cpu-bot)))))

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
    {:game (runner/start-new-game {:players players :board (:board board)})
     :state-stack []}))

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

(defn game-viewer-middle-section*
  []
  [:section.middle
    (if (game-not-started? @game-state)
      [start-new-game-root]
      [game-root])])

(def game-viewer-middle-section (with-meta game-viewer-middle-section* board-parent-resize-props))

(def player-colours ["red" "green" "blue" "yellow"])

(defn player-score-sheet
  [player-num position]
  (let [{:keys [name avatar robot robot-image] :as player} (nth (game/players (:game @game-state)) player-num)]
    [:div.player-score-sheet {:class (str (clojure.core/name position) " " (get player-colours player-num))}
     (into [:div
            [:h3.player-name name
             [:img {:src avatar :alt name}]
             [:img {:src (.-src robot-image) :alt name}]]]
           (if-not (= :dead (:state player))
             [(if-not (= :destroyed (:state robot))
                [:div.scores
                 [:span.damage (:damage robot)]
                 [:span.lives (:lives robot)]]
                [:div.scores
                 [:span.destroyed "Robot destroyed - awaiting respawn"]])
              (when-not (= :destroyed (:state robot))
                [:div.registers-this-turn
                 [:span "Registers this turn..."]])]
             [:div.player-dead
              [:h4 "RIP"]]))]))

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