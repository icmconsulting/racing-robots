(ns rr.game-viewer
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.session :as session]
            [cljs.core.async :as async]
            [rr.bs :as bs]
            [rr.bots :as bots]
            [rr.boards :as boards]
            [rr.board-viewer :refer [board-view board-parent-resize-props]]
            [rr.logger :as logger]
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

(def move-1-image (image-obj "/images/m1.gif"))
(def move-2-image (image-obj "/images/m2.gif"))
(def move-3-image (image-obj "/images/m3.gif"))
(def backup-image (image-obj "/images/bu.gif"))

(def turn-left-image (image-obj "/images/tl.gif"))
(def turn-right-image (image-obj "/images/tr.gif"))
(def u-turn-image (image-obj "/images/ut.gif"))

(def move-1-locked-image (image-obj "/images/m1_grey.gif"))
(def move-2-locked-image (image-obj "/images/m2_grey.gif"))
(def move-3-locked-image (image-obj "/images/m3_grey.gif"))
(def backup-locked-image (image-obj "/images/bu_grey.gif"))

(def turn-left-locked-image (image-obj "/images/tl_grey.gif"))
(def turn-right-locked-image (image-obj "/images/tr_grey.gif"))
(def u-turn-locked-image (image-obj "/images/ut_grey.gif"))

(def power-down-image (image-obj "/images/power-down.png"))
(def flags-touched-image (image-obj "/images/flag_blue.gif"))

(defn register-type->image
  [{:keys [type value locked?]}]
  (case type
    :move (case value
            1 (if locked? move-1-locked-image move-1-image)
            2 (if locked? move-2-locked-image move-2-image)
            3 (if locked? move-3-locked-image move-3-image)
            -1 (if locked? backup-locked-image backup-image))
    :rotate (case value
              :left (if locked? turn-left-locked-image turn-left-image)
              :right (if locked? turn-right-locked-image turn-right-image)
              :u-turn (if locked? u-turn-locked-image u-turn-image))))

(def all-bot-images #{bot0-image bot1-image bot2-image bot3-image bot4-image bot5-image bot6-image bot7-image})

(defmethod dispatch-event-type :abandon-game! [_ _] empty-game)

(def max-stack-size 3)

(defn push-game-state
  [game-state game]
  (update game-state :state-stack
          (comp vec (partial take-last max-stack-size) conj)
          game))

(defmethod dispatch-event-type :game-next-turn!
  [game-state _]
  (let [next-turn-chan (runner/next-turn (:game game-state))]
    (go (dispatch! [:game-turn-responses-received! (async/<! next-turn-chan)]))
    (-> (assoc game-state :waiting-for-players? true)
        (push-game-state (:game game-state)))))

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
        (push-game-state (:game game-state)))))

(defmethod dispatch-event-type :game-clean-up-responses-received!
  [game-state [_ [turn game-ready-for-next-turn]]]
  (assoc game-state
    :game game-ready-for-next-turn
    :current-turn nil
    :previous-turn turn
    :waiting-for-players? false))

(defmethod dispatch-event-type :game-try-again!
  [game-state _]
  (let [last-game-state (last (:state-stack game-state))
        current-turn (if (:current-turn game-state)
                       nil
                       (:previous-turn game-state))
        previous-turn (last (last (game/turns last-game-state)))]
    (assoc game-state
      :game last-game-state
      :current-turn current-turn
      :previous-turn previous-turn
      :state-stack (butlast (:state-stack game-state))
      :waiting-for-players? false)))

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

      [bs/button {:bs-size  "small"
                  :on-click #(dispatch! [:game-try-again!])
                  :disabled (or waiting? (empty? (:state-stack @game-state)))}
       [bs/glyph {:glyph "step-backward"}] "Try again"]

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

(defn game-root* []
  [:div#game-board
   [:div [board-view (cursor game-data-cursor [])]]
   [game-controller-panel]])

(def game-root
  (with-meta game-root*
             {:component-will-mount #(logger/start-robot-event-logger game-state)
              :component-will-unmount #(logger/stop-robot-event-logger game-state)}))

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
  [{:keys [player-type]} bot-image]
  ;; TODO: create player ai bots
  (when-let [cpu-bot (bots/local-bots player-type)]
    (assoc cpu-bot
      :robot-image bot-image
      :bot-instance ((:bot-instance cpu-bot)))))

(defn kw->board
  [board]
  (get boards/all-available-boards
       (if (= :random board)
         (rand-nth (keys boards/all-available-boards))
         board)))

(defmethod dispatch-event-type :start-game!
  [game-state _]
  (let [players (map apply-player-bot (get-in game-state [:new-game :players]) (shuffle all-bot-images))
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

(defn player-short-id
  [player]
  (second (re-find #"^(\w+)-" (:id player))))

(defn single-winner-game-over
  [winner]
  [[bs/row [bs/col {:xs 12}
            [:h2.text-center [:span "Winner: " [:span.winner (:name winner) " (" (player-short-id winner) ")"]]]]]
   [bs/row
    [bs/col {:xs 12 :class-name "text-center"}
     [:img {:src (:avatar winner)}]
     [:img {:src (.-src (:robot-image winner))}]]]])

(defn tie-game-over
  [winners]
  [[bs/row
    [bs/col {:xs 12}
     [:h2.text-center [:span "Tie: "
                       [:span.winner (clojure.string/join ", " (map :name winners))]]]]]
   [bs/row
    (let [col-size (/ 12 (count winners))]
      (for [winner winners]
        ^{:key (:id winner)}
        [bs/col {:xs col-size :class-name "text-center"}
         [:img {:src (:avatar winner)}]
         [:img {:src (.-src (:robot-image winner))}]]))]])

(defn player-results-table
  [players]
  [bs/table {:striped true :condensed true :fill true}
    [:thead
     [:tr
      [:th "Player"]
      [:th "Flags touched"]
      [:th "Lives lost"]
      [:th "Hit by laser"]
      [:th "Fell off board"]
      [:th "Fell into pit"]
      [:th "Rode belt"]
      [:th "Powered down"]]]
   [:tbody
    (for [player players]
      (let [robot (:robot player)]
        ^{:key (:id player)}
        [:tr
         [:td (:name player) " (" (player-short-id player) ")"]
         [:td (count (:flags robot))]
         [:td (- (:lives game/blank-robot) (:lives robot))]
         [:td (count (filter (comp #{:damage/by-robot-laser :damage/by-wall-laser} :type) (:events robot))) " times"]
         [:td (count (filter (comp #{:destroyed/fell-off-board :destroyed/belt-pushed-off-board} :type) (:events robot))) " times"]
         [:td (count (filter (comp #{:destroyed/fell-into-pit} :type) (:events robot))) " times"]
         [:td (count (filter (comp #{:belt/moved-by-belt} :type) (:events robot))) " squares"]
         [:td (count (filter (comp #{:power-down/start} :type) (:events robot))) " times"]]))]])

(defn game-over-root
  []
  (let [[_ players] (game/victory-status (:game @game-state))
        winners (filter #(= :winner (:victory-state %)) players)]
    [bs/panel
     (into [bs/grid
            [bs/row [bs/col {:xs 12} [:h1.text-center "Game over!"]]]]
           (concat
             (if (= 1 (count winners))
               (single-winner-game-over (first winners))
               (tie-game-over winners))
             [[player-results-table players]
              [bs/row [bs/col {:xs 12 :class-name "text-center"}
                       [bs/button {:on-click #(dispatch! [:abandon-game!]) :bs-style :primary} "go again!"]]]]))]))

(defn game-finished?
  [{:keys [game]}]
  (= :game-over (first (game/victory-status game))))

(defn game-viewer-middle-section*
  []
  [:section.middle
    (cond
      (game-not-started? @game-state) [start-new-game-root]
      (game-finished? @game-state) [game-over-root]
      :else [game-root])])

(def game-viewer-middle-section (with-meta game-viewer-middle-section* board-parent-resize-props))

(defn register-image
  [register]
  (let [image (register-type->image register)]
    [:img {:src (.-src image)}]))

(defn power-down-image-view
  []
  [:img.power-down {:src (.-src power-down-image)}])

(defn registers-view
  [{:keys [id robot]}]
  (if-let [turn-registers (:current-turn @game-state)]
    (let [player-registers-this-turn (get (game/registers-for-turn turn-registers) id)
          powering-down-next? (seq (filter #(= id (:id %)) (game/players-powering-down-next-turn turn-registers)))]
      [:div.registers-this-turn
       [:ul
        (map-indexed (fn [idx register]
                       ^{:key (str id "-" idx)}
                       [:li [register-image register]])
                     (concat player-registers-this-turn
                             (map #(assoc % :locked? true)  (:locked-registers robot))))
        (when powering-down-next? [:li [power-down-image-view]])]])
    [:div.register-this-turn]))


(def player-colours ["red" "green" "blue" "yellow"])

(defn player-score-sheet
  [player-num position]
  (let [{:keys [name avatar robot robot-image id] :as player} (nth (game/players (:game @game-state)) player-num)]
    [:div.player-score-sheet {:class (str (clojure.core/name position) " " (get player-colours player-num))}
     (into [:div
            [:h3.player-name name " (id: " (player-short-id player) ")"
             [:img {:src avatar :alt name}]
             [:img {:src (.-src robot-image) :alt name}]]]
           (if-not (= :dead (:state player))
             [(if-not (= :destroyed (:state robot))
                [:div.scores
                 [:span.damage (:damage robot)]
                 [:span.lives (:lives robot)]
                 (when (:powered-down? robot)
                   [:span.powered-down [power-down-image-view]])
                 [:span.flags-touched
                   (for [i (range 0 (count (:flags robot)))]
                     ^{:key i}
                     [:img {:src (.-src flags-touched-image)}])]]

                [:div.scores
                 [:span.destroyed "Robot destroyed - awaiting respawn"]])

              [registers-view player]]

             [[:div.player-dead
               [:h4 "RIP"]]]))]))

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
