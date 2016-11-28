(ns rr.game-viewer
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.session :as session]
            [taoensso.timbre :refer [debug info warn]]
            [cljs.core.async :as async]
            [rr.ajax-bot :as ajax-bot]
            [rr.bs :as bs]
            [rr.bots :as bots]
            [rr.boards :as boards]
            [rr.board-viewer :refer [board-view board-parent-resize-props]]
            [rr.logger :as logger]
            [rr.game :as game]
            [rr.runner :as runner]
            [rr.utils :refer [image-obj player-short-id]]
            [taoensso.timbre :as timbre])
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

(def register-tick-ms 2000)

(defmethod dispatch-event-type :game-turn-responses-received!
  [game-state [_ [turn game-with-turn-changes]]]
  (if (:show-each-register? game-state)
    (do
      (go-loop [registers (rest game-with-turn-changes)
                num 0]
               (if (first registers)
                 (do
                   (dispatch! [:next-register! num turn (first registers)])
                   (async/<! (async/timeout register-tick-ms))
                   (recur (rest registers) (inc num)))
                 (dispatch! [:no-more-registers!])))
      (assoc game-state :current-state turn))
    (assoc game-state :game (last game-with-turn-changes)
                      :players-after-each-register (map game/players game-with-turn-changes)
                      :current-turn turn
                      :waiting-for-players? false)))

(defmethod dispatch-event-type :next-register!
  [game-state [_ number turn after-register-game-state]]
  (let [new-attrs {:game            after-register-game-state
                   :active-register number
                   :current-turn turn}]
    (merge game-state new-attrs)))

(defmethod dispatch-event-type :no-more-registers!
  [game-state _]
  (-> game-state
      (dissoc :active-register)
      (assoc :waiting-for-players? false)))

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
        all-turns (game/turns last-game-state)
        previous-turn (get all-turns (apply max (keys all-turns)))]
    (assoc game-state
      :game last-game-state
      :current-turn current-turn
      :previous-turn previous-turn
      :state-stack (butlast (:state-stack game-state))
      :waiting-for-players? false)))

(def autoplay-tick-ms 2000)

(defn game-finished?
  [{:keys [game]}]
  (= :game-over (first (game/victory-status game))))

(defn autoplay-watch
  [_ _ old-game-state new-game-state]
  (when-not (:waiting-for-players? new-game-state)
    (let [next-dispatch
          (cond
            (game-finished? new-game-state)
            (do
              (remove-watch game-state :autoplay)
              (runner/game-over (:game new-game-state))
              nil)

            (not= (:autoplay? old-game-state) (:autoplay? new-game-state))
            (if (:current-turn new-game-state)
              [:game-clean-up-turn!]
              [:game-next-turn!])

            (and (:current-turn old-game-state) (nil? (:current-turn new-game-state)))
            [:game-next-turn!]

            (and (nil? (:current-turn old-game-state)) (:current-turn new-game-state))
            [:game-clean-up-turn!]

            ;; register running just finished
            (and (nil? (:active-register new-game-state)) (some? (:active-register old-game-state)))
            [:game-clean-up-turn!])]
      (when next-dispatch
        (go (async/<! (async/timeout autoplay-tick-ms))
            (when (:autoplay? @game-state)
              (dispatch! next-dispatch)))))))

(defmethod dispatch-event-type :start-autoplay!
  [state _]
  (add-watch game-state :autoplay autoplay-watch)
  (assoc state :autoplay? true))

(defmethod dispatch-event-type :pause-autoplay!
  [state _]
  (remove-watch game-state :autoplay)
  (assoc state :autoplay? false))

(defmethod dispatch-event-type :toggle-each-register!
  [state _]
  (update state :show-each-register? not))

(defn game-controller-panel
  []
  (let [waiting? (:waiting-for-players? @game-state)
        autoplaying? (:autoplay? @game-state)
        show-each-register? (true? (:show-each-register? @game-state))]
    [:div.game-controller-panel
     [bs/button-group {:vertical true :class-name (when autoplaying? :pulse)}
      (when-not (:current-turn @game-state)
        [bs/button {:bs-size  "small"
                    :on-click #(dispatch! [:game-next-turn!])
                    :disabled (or waiting? autoplaying?)}
         [bs/glyph {:glyph "step-forward"}] "Next turn"])
      (when (:current-turn @game-state)
        [bs/button {:bs-size  "small"
                    :on-click #(dispatch! [:game-clean-up-turn!])
                    :disabled (or waiting? autoplaying?)}
         [bs/glyph {:glyph "step-forward"}] "Clean-up turn"])
      [bs/button {:bs-size  "small"
                  :on-click #(dispatch! [:game-try-again!])
                  :disabled (or waiting? autoplaying?
                                (empty? (:state-stack @game-state)))}
       [bs/glyph {:glyph "step-backward"}] "Try again"]

      (if autoplaying?
        [bs/button {:bs-size "small"
                    :disabled waiting?
                    :on-click #(dispatch! [:pause-autoplay!])}
         [bs/glyph {:glyph "pause"}] "Pause"]
        [bs/button {:bs-size "small"
                    :disabled waiting?
                    :on-click #(dispatch! [:start-autoplay!])}
         [bs/glyph {:glyph "play"}] "Autoplay"])

      [bs/button {:bs-size  "small"
                  :on-click #(dispatch! [:toggle-each-register!])
                  :active show-each-register?
                  :disabled (or waiting? autoplaying?)}
       [bs/glyph {:glyph "resize-full"}] "View each register"]]

     [bs/button-group {:vertical true}
      [bs/button {:bs-size  "small"
                  :bs-style :warning
                  :on-click #(dispatch! [:rematch!])
                  :disabled (or waiting? autoplaying?)}
       [bs/glyph {:glyph "repeat"}] "Restart game"]
      [bs/button {:bs-size  "small"
                  :bs-style :danger
                  :on-click #(dispatch! [:abandon-game!])
                  :disabled (or waiting? autoplaying?)}
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
  (-> game-state
      (assoc-in [:new-game :players player-num :player-type] (keyword selected-player-type))
      (update-in [:new-game :players player-num] dissoc :connection-type :port :lambda-function-name)))

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

(defmethod dispatch-event-type :player-connection-function-name
  [game-state [_ player-num function-name]]
  (if-not (clojure.string/blank? function-name)
    (assoc-in game-state [:new-game :players player-num :lambda-function-name] function-name)
    (update-in game-state [:new-game :players player-num] dissoc :lambda-function-name)))

(defn apply-player-bot
  [player bot-image]
  (assoc (bots/player-bot player) :robot-image bot-image))

(defn kw->board
  [board]
  (get boards/all-available-boards
       (if (= :random board)
         (rand-nth (keys boards/all-available-boards))
         board)))

(defmethod dispatch-event-type :start-game!
  [game-state _]
  (let [players (map apply-player-bot (get-in game-state [:new-game :players]) (shuffle all-bot-images))
        board (kw->board (get-in game-state [:new-game :board]))
        game-ch (runner/start-new-game {:players players :board (:board board)})
        game-params (select-keys (:new-game game-state) [:players :board])]

    (go (dispatch! (vec (concat [:game-started!] (async/<! game-ch)))))

    {:state-stack []
     :new-game game-params   ;; for trying again if something goes wrong
     :rematch-game game-params
     :autoplay? false
     :waiting-for-ready? true}))

(defmethod dispatch-event-type :game-started!
  [game-state [_ game player-readiness]]
  (let [all-players (game/players game)
        not-ready-players (remove #(= :ready (get-in player-readiness [(:id %) :response])) all-players)]
    (doseq [p not-ready-players] (warn "Player bot not ready: [" (:id p) "," (:name p) "]"))

    (if (seq not-ready-players)
      (assoc game-state :not-ready-players not-ready-players)
      (-> game-state
          (dissoc :not-ready-players :new-game :waiting-for-ready?)
          (assoc :game game)))))

(defn new-game-player-by-number
  [game-state player-num]
  (get-in game-state [:new-game :players player-num]))

(defn port-number-input
  [player-num]
  [bs/form-group
   [bs/control-label "Port number"]
   [bs/form-control {:type :number
                     :placeholder "Localhost bound port"
                     :min 1
                     :max 65536
                     :value (:port (new-game-player-by-number @game-state player-num) "")
                     :on-change #(dispatch! [:player-connection-port-number player-num (-> % .-target .-value)])}]
   [bs/help-block "between 0 and 65536"]])

(defn lambda-function-name-input
  [player-num]
  [bs/form-group
   [bs/control-label "function name"]
   [bs/form-control {:type :text
                     :placeholder "function name"
                     :value (:lambda-function-name (new-game-player-by-number @game-state player-num) "")
                     :on-change #(dispatch! [:player-connection-function-name player-num (-> % .-target .-value)])}]
   [bs/help-block "NOT the fully qualified ARN - just the function name"]])

(defn player-type-selection
  [player-num]
  [:div.player-selection
   [bs/form-group
    [bs/control-label (str "player " (inc player-num))]
    [bs/form-control {:component-class "select"
                      :placeholder     "select player type"
                      :default-value   (name (:player-type (new-game-player-by-number @game-state player-num) ""))
                      :on-change       #(dispatch! [:player-type-change player-num (-> % .-target .-value)])}
     [:option {:disabled true :value ""} "select a player type"]
     [:option {:value "player"} "rr bot competitor (you)"]
     [:optgroup {:label "CPU Bots"}
      (for [[bot-key] bots/local-bots]
        ^{:key bot-key}
        [:option {:value bot-key} (name bot-key)])]]]
   (let [new-game-player (new-game-player-by-number @game-state player-num)]
     (cond
       (= (:player-type new-game-player) :player)
       [bs/well
        [bs/form-group
         [bs/control-label "Connection type"]
         [bs/radio {:name "connection" :on-change #(dispatch! [:player-connection-change player-num :http])} "HTTP/REST"]
         (when (= :http (:connection-type new-game-player)) [port-number-input player-num])
         [bs/radio {:name "connection" :on-change #(dispatch! [:player-connection-change player-num :lambda])} "AWS Lambda"]]
        (when (= :lambda (:connection-type new-game-player)) [lambda-function-name-input player-num])]

       ((set (keys bots/local-bots)) (:player-type new-game-player))
       (let [bot ((bots/local-bots (:player-type new-game-player)))]
         [bs/thumbnail {:src (get-in bot [:profile :avatar]) :alt (name (:player-type new-game-player))}])))])

(defn new-game-ready-to-start?
  [{:keys [new-game]}]
  (let [{:keys [players]} new-game
        player-players (filter #(= :player (:player-type %)) players)
        http-players (filter #(= :http (:connection-type %)) players)
        lambda-players (filter #(= :lambda (:connection-type %)) players)]
    (and
      (every? :player-type players)
      (every? :connection-type player-players)
      (every? :port http-players)
      (every? :lambda-function-name lambda-players))))

(defn board-selection
  []
  [bs/form-group {:class "board-selection"}
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
  [bs/panel {:class "new-game-root" :header "select robotic parameters"}
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

(defn single-winner-game-over
  [winner]
  [[bs/row [bs/col {:xs 12}
            [:h2.text-center [:span "Winner: " [:span.winner (:name winner) " (" (player-short-id winner) ")"]]]]]
   [bs/row {:class "avatars"}
    [bs/col {:xs 12 :class-name "text-center"}
     [:img {:src (:avatar winner)}]
     [:img {:src (.-src (:robot-image winner))}]]]])

(defn tie-game-over
  [winners]
  [[bs/row
    [bs/col {:xs 12}
     [:h2.text-center [:span "Tie: "
                       [:span.winner (clojure.string/join ", " (map :name winners))]]]]]
   [bs/row {:class "avatars"}
    (let [col-size (/ 12 (count winners))]
      (for [winner winners]
        ^{:key (:id winner)}
        [bs/col {:xs col-size :class-name "text-center"}
         [:img {:src (:avatar winner)}]
         [:img {:src (.-src (:robot-image winner))}]]))]])

(defn player-results-table
  [players]
  [bs/table {:striped true :condensed true :fill true :class "results"}
    [:thead
     [:tr
      [:th]
      [:th "Player team"]
      [:th "Robot name"]
      [:th "Tournament score"]
      [:th "End status"]
      [:th "Flags"]
      [:th "Turns"]
      [:th "Squares moved"]
      [:th "Lives lost"]
      [:th "Hit by laser"]
      [:th "Fell off board"]
      [:th "Fell into pit"]
      [:th "Rode belt"]
      [:th "Powered down"]]]
   [:tbody
    (for [player players]
      (let [robot (:robot player)
            dead? (= :dead (:state player))
            winner? (= :winner (:victory-state player))]
        ^{:key (:id player)}
        [:tr {:class (when winner? "winner")}
         [:td (when winner? [bs/glyph {:glyph "star"}])]
         [:td (:name player) " (" (player-short-id player) ")"]
         [:td (:robot-name player)]
         [:td (game/player-score player)]
         [:td (if dead? "Dead" "active")]
         [:td (count (:flags robot))]
         [:td (game/num-player-turns player)]
         [:td (count (filter (comp #{:belt/moved-by-belt :move/north :move/east :move/south :move/west} :type)
                             (:events robot)))]
         [:td (if dead? 5 (- (:lives game/blank-robot) (:lives robot)))]
         [:td (count (filter (comp #{:damage/by-robot-laser :damage/by-wall-laser} :type) (:events robot))) " times"]
         [:td (count (filter (comp #{:destroyed/fell-off-board :destroyed/belt-pushed-off-board} :type) (:events robot))) " times"]
         [:td (count (filter (comp #{:destroyed/fell-into-pit} :type) (:events robot))) " times"]
         [:td (count (filter (comp #{:belt/moved-by-belt} :type) (:events robot))) " sq"]
         [:td (game/num-times-powered-down player) " times"]]))]])

(defmethod dispatch-event-type :rematch!
  [game-state _]
  (dispatch-event-type (assoc game-state :new-game (:rematch-game game-state))
                       [:start-game!]))

(defn game-over-root
  []
  (let [[_ players] (game/victory-status (:game @game-state))
        winners (filter #(= :winner (:victory-state %)) players)]
    [bs/panel {:class "game-over"}
     (into [bs/grid
            [bs/row [bs/col {:xs 12} [:h1.text-center "Game over!"]]]]
           (concat
             (if (= 1 (count winners))
               (single-winner-game-over (first winners))
               (tie-game-over winners))
             [[player-results-table players]
              [bs/row [bs/col {:xs 12 :class-name "text-center"}
                       [bs/button {:on-click #(dispatch! [:abandon-game!]) :bs-style :primary} "start new game"]
                       [bs/button {:on-click #(dispatch! [:rematch!]) :bs-style :success} "demand rematch!"]]]]))]))

(defn game-waiting-for-ready?
  [game-state]
  (:waiting-for-ready? game-state))

(defn waiting-for-ready-root
  [game-state]
  (let [not-ready-players (:not-ready-players game-state)]
    [bs/panel {:class (when-not not-ready-players "pulse")}
     (if not-ready-players
       [bs/alert {:bs-style "warning"}
        [:h3 [bs/glyph {:glyph "warning-sign"}] " Connection problems"]
        [:div [:p "Could not contact or invalid ready responses were received for the following players:"]]
        [:div [:ul
               (for [p not-ready-players]
                 ^{:key (:id p)}
                 [:li (:name p) "(" (:id p) ")"])]]
        [:div [:p "Check the developer console and the system log for more information."]]
        [:div [:p [bs/button {:bs-size  :large
                              :bs-style :primary
                              :on-click #(dispatch! [:start-game!])} "Try again"]
               [bs/button {:bs-size  :large
                           :on-click #(dispatch! [:abandon-game!])} "Abandon!"]]]]

       [:h3 "Waiting for players..."])]))

(defn game-viewer-middle-section*
  []
  [:section.middle
    (cond
      (game-waiting-for-ready? @game-state) [waiting-for-ready-root @game-state]
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
  [:div.registers-this-turn
   (cond
     (:powered-down? robot)
     [:span.powered-down [power-down-image-view]]

     (:current-turn @game-state)
     (let [current-turn (:current-turn @game-state)
           player-registers-this-turn (get (game/registers-for-turn current-turn) id)
           powering-down-next? (seq (filter #(= id (:id %)) (game/players-powering-down-next-turn current-turn)))]
       (let [active-register (:active-register @game-state)]
         [:ul
          (map-indexed (fn [idx register]
                         ^{:key (str id "-" idx)}
                         [:li.register-card
                          {:class (when (= idx active-register) "active")}
                          [register-image register]])
                       (concat player-registers-this-turn
                               (map #(assoc % :locked? true) (reverse (:locked-registers robot)))))
          (when powering-down-next? [:li.powered-down [power-down-image-view]])])))])


(def player-colours ["red" "green" "blue" "yellow"])

(defn robot-flags-view
  [robot]
  (when (seq (:flags robot))
    [:span.flags-touched
     (for [i (range 0 (count (:flags robot)))]
       ^{:key i}
       [:img {:src (.-src flags-touched-image)}])]))

(defn robot-active-scores
  [robot]
  [:div.scores
   (if-not (= :destroyed (:state robot))
     [:span.damage (:damage robot)]
     [:span.destroyed])
   [:span.lives (:lives robot)]
   [robot-flags-view robot]])

(def max-robot-name-size 18)
(def max-player-team-name-size 40)

(defn truncate-name
  [max-size name]
  (if (< max-size (count name))
    (str (subs name 0 (- max-size 3)) "...")
    name))

(defn player-score-sheet
  [player-num position]
  (let [{:keys [name avatar robot robot-image id] :as player} (nth (game/players (:game @game-state)) player-num)
        powered-down? (:powered-down? robot)
        dead? (= :dead (:state player))]
    [:div.player-score-sheet {:class (clojure.string/join " " [(clojure.core/name position)
                                                               (get player-colours player-num)
                                                               (when dead? "player-dead")])}
     (into [:div
            [:h3.player-name
             [:span.player-images
              [:span
               [:img {:src avatar :alt name}]]
              [:span
               [:img {:src (.-src robot-image) :alt name}]]]
             [:span.full-name
              [:span.robot-name (truncate-name max-robot-name-size (:robot-name player))]
              [:span.team-name (truncate-name max-player-team-name-size name)]
              [:span.player-id (player-short-id player)]]]]
           (if-not dead?
             [[robot-active-scores robot]
              [registers-view player]]
             [[:div
               [:h4 "game over"]]]))]))

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
  (when (or (nil? (:game @game-state))
            (not (game-finished? @game-state)))
    [:section.left
     (when (:game @game-state)
       [left-player-score-board])]))

(defn game-viewer-right-section
  []
  (when (or (nil? (:game @game-state))
            (not (game-finished? @game-state)))
    [:section.right
     (when (:game @game-state)
       [right-player-score-board])]))

(defn game-viewer-root []
  [:section.game-viewer-root
   [game-viewer-left-section]
   [game-viewer-middle-section]
   [game-viewer-right-section]])
