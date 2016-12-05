(ns rr.registration
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [taoensso.timbre :refer [debug info warn error]]
            [ajax.core :refer [GET PUT]]
            [rr.bs :as bs]
            [rr.utils :refer [ascii-title csrf-token player-short-id truncate-name players-names]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defonce registration-state (atom {:waiting? true}))

(defmulti dispatch-event-type (fn [_ event] (first event)))

(defn dispatch!
  ([event]
   (dispatch! registration-state event))
  ([a event]
   (reagent/rswap! a dispatch-event-type event)))

(defn fetch-registration!
  [registration-id]
  (GET (str "/registrations/" registration-id)
       {:handler #(dispatch! [:registration registration-id (:registration %)])
        :error-handler #(dispatch! [:error-registration registration-id %])}))

(defmethod dispatch-event-type :registration
  [_ [_ _ registration]]
  registration)

(defmethod dispatch-event-type :error-registration
  [_ [_ id error]]
  {:error error :registration-id id})

(defn registration-loading
  []
  [bs/panel {:class "pulse"}
   [:h3 "Fetching registration..."]])

(defn docker-image-input
  []
  [bs/form-group
   [bs/control-label "docker image"]
   [bs/input-group
    [bs/form-control {:type        :text
                      :placeholder "image e.g. icm-consulting/my-hackathon-docker-image"
                      :value       (:image-id @registration-state "")
                      :on-change   #(dispatch! [:submission-image-id (-> % .-target .-value)])}]
    [bs/input-group-addon
     ":"]
    [bs/form-control {:type        :text
                      :placeholder "tag e.g. latest"
                      :value       (:tag @registration-state "")
                      :on-change   #(dispatch! [:submission-tag (-> % .-target .-value)])}]]])

(defmethod dispatch-event-type :submission-image-id
  [registration-state [_ function-name]]
  (assoc registration-state :image-id function-name))

(defmethod dispatch-event-type :submission-tag
  [registration-state [_ function-name]]
  (assoc registration-state :tag function-name))

(defmethod dispatch-event-type :submission-function-name
  [registration-state [_ function-name]]
  (assoc registration-state :lambda-function-name function-name))

(defn lambda-function-name-input
  []
  [bs/form-group
   [bs/control-label "function name"]
   [bs/input-group
    [bs/input-group-addon "arn:aws:lambda:ap-southeast-2:079429354053:function:"]
    [bs/form-control {:type        :text
                      :placeholder "function name"
                      :value       (:lambda-function-name @registration-state "")
                      :on-change   #(dispatch! [:submission-function-name (-> % .-target .-value)])}]]])

(defmethod dispatch-event-type :submission-change
  [registration-state [_ connection-type]]
  (assoc registration-state :connection-type connection-type))

(defn can-test?
  [registration-state]
  (cond
    (= :lambda (:connection-type registration-state))
    (not (clojure.string/blank? (:lambda-function-name registration-state)))

    (= :docker (:connection-type registration-state))
    (and (not (clojure.string/blank? (:image-id registration-state)))
         (not (clojure.string/blank? (:tag registration-state))))

    :else false))

(defn submission-selection
  []
  [:div.submission-selection
   [bs/form-group
    [bs/control-label "Connection type"]
    [bs/radio {:name      "connection"
               :checked   (= (:connection-type @registration-state) :docker)
               :on-change #(dispatch! [:submission-change :docker])}
     "JSON over HTTP via Docker image"]
    (when (= :docker (:connection-type @registration-state))
      [docker-image-input])
    [bs/radio {:name      "connection"
               :on-change #(dispatch! [:submission-change :lambda])
               :checked   (= (:connection-type @registration-state) :lambda)} "AWS Lambda"]
    (when (= :lambda (:connection-type @registration-state))
      [lambda-function-name-input])]])

(defmethod dispatch-event-type :reset!
  [registration-state _]
  (go (fetch-registration! (:registration-id registration-state)))
  (select-keys registration-state [:registration-id]))

(defmethod dispatch-event-type :test!
  [registration-state _]
  (PUT (str "/registrations/" (:registration-id registration-state))
       {:params {:registration (dissoc registration-state :testing? :logs?)}
        :headers {"x-csrf-token" (csrf-token)}
        :handler #(dispatch! [:test-results %])
        :error-handler #(dispatch! [:test-error %])})
  (assoc registration-state :testing? true))

(defmethod dispatch-event-type :test-results
  [registration-state [_ results]]
  (merge registration-state
         {:testing? false}
         (:registration results)
         (dissoc results :registration)))

(defmethod dispatch-event-type :test-error
  [registration-state [_ error]]
  (error error "Error while testing player registration")
  (assoc registration-state :testing? false))

(defmethod dispatch-event-type :toggle-logs!
  [registration-state _]
  (update registration-state :logs? not))

(defn profile
  [reg]
  (:profile (first (filter :profile (:test-results reg)))))

(defn profile-view
  [profile]
  [:div
   [:h3.player-name
    [:span.player-images
     [:span
      [:img {:src (:avatar profile) :alt (:name profile)}]]]
    [:span.full-name
     [:span.robot-name (:robot-name profile)]
     [:span.team-name (:name profile)]]]])

(defn registration-form-buttons
  []
  [:div.registration-buttons
   [bs/button {:bs-size  :large
               :bs-style :primary
               :class    (when (:testing? @registration-state) "pulse")
               :disabled (or (not (can-test? @registration-state))
                             (:testing? @registration-state))
               :on-click #(dispatch! [:test!])}
    (cond (:testing? @registration-state) "Verifying bot..."
          (= :saved (:result @registration-state)) "Resubmit"
          :else "Test your submission")]
   [bs/button {:bs-size  :large
               :bs-style :default
               :disabled (:testing? @registration-state)
               :on-click #(dispatch! [:reset!])} "Reset"]
   (when (#{:saved :failed} (:result @registration-state))
     [:a {:on-click #(dispatch! [:toggle-logs!])}
      (if (:logs? @registration-state) "Hide Logs" "Show Logs")])])

(def reason-descriptions
  {:lambda/function-failed-invocation "The Lambda function failed, returning a HTTP status other than 200"
   :lambda/function-not-found "The Lambda function could not be found. Did you enter it correctly?"
   :lambda/lambda-service-exception "Lambda threw an Exception during execution."
   :lambda/service-exception "An AWS Service Exception occurred. This could mean just about anything."

   :docker/pull-failed "The docker image specified could not be pulled from the AWS ECR. Does it exist?"
   :docker/start-container-failed "A container for the selected image could not be started."

   :exception "An exception was caught whilst executing the test."
   :not-ready "An invalid response was received whilst attempting to start a new game"})

(defn test-result-view
  [test]
  [:li (if (= :pass (:result test))
         [bs/glyph {:glyph :ok}]
         [bs/glyph {:glyph :remove}])
   " " [:abbr {:title (:description test)} [:strong (:test test)]]
   [:span.failure-result (get reason-descriptions (:reason test))]])

(defn log-view
  []
  [:div.logs
   [:pre.pre-scrollable
    (for [test (:test-results @registration-state)]
      (str
        "--------------------------------------------\n"
        "Logs for test \"" (name (:test test)) "\"\n"
           (clojure.string/join "\n" (map :message (:logs test)))
        "\n"))]])

(defn registration-form
  []
  [bs/panel {:class "registration-entry-root"}
   [:pre ascii-title]
   [:h3 (clojure.string/join " and " (filter identity [(:player1 @registration-state) (:player2 @registration-state)]))]
   [:form
    [submission-selection]

    (cond (= :failed (:result @registration-state))
          [bs/alert {:bs-style :danger}
           [:p [:strong "Verification of bot failed!"]]
           [:p "Tests attempted:"]
           [:ul.tests
            (for [test (:test-results @registration-state)]
              ^{:key (str "test-" (:test test))}
              [test-result-view test])]
           [:p "Your registration has " [:strong "not"] " been saved. Fix up the issue, and resubmit." [:br]]
           [registration-form-buttons]]

          (= :saved (:result @registration-state))
          [bs/alert {:bs-style :success}
           [:p [:strong "Registration successful!"]]
           [profile-view (profile @registration-state)]
           (case (:connection-type @registration-state)
             :docker
             [:p [:strong "Note! "] "You will need to" [:strong " resubmit your registration again "] "if/when you push a new version of your docker image."]
             :lambda
             [:p [:strong "Note! "] "There is no need to resubmit this registration unless your function name changes."])
           [registration-form-buttons]]

          :else [registration-form-buttons])

    (when (:logs? @registration-state)
      [log-view])]])

(defn registration-middle-section
  []
  [:div.registration-middle
   (cond
     (:waiting? @registration-state)
     [registration-loading]

     (:error @registration-state)
     [bs/alert {:bs-style "warning"}
      [:h3 [bs/glyph {:glyph "warning-sign"}] " Registration Fetch Error"]
      [:div [:p "Error while fetching registration " [:strong (:registration-id @registration-state)]]]
      [:div [:p "Go and find Brendan to help you out..."]]]

     :else
     [registration-form])])

(defn registration-view*
  [_]
  [:section.registration-root
   [:section.left]
   [registration-middle-section]
   [:section.right]])

(def registration-view
  (with-meta registration-view*
             {:component-did-mount (fn [this] (fetch-registration! (last (reagent/argv this))))}))

(defn registration-root
  [registration-id]
  [registration-view registration-id])


(defonce all-registrations-state (atom {:waiting? true}))

(defn fetch-all-registrations!
  []
  (GET (str "/registrations")
       {:handler #(dispatch! all-registrations-state [:all-registrations (:registrations %)])
        :error-handler #(dispatch! all-registrations-state [:error-all-registration %])}))

(defmethod dispatch-event-type :all-registrations
  [_ [_ registrations]]
  registrations)

(defmethod dispatch-event-type :error-all-registrations
  [_ [_ error]]
  {:error error})

(def unknown-registration-avatar "/public/images/unknown-registration.jpg")

(defmulti registrations-thumb (comp :connection-type val))



(defmethod registrations-thumb :docker
  [[id reg]]
  (let [profile (profile reg)]
    [bs/thumbnail {:src (:avatar profile) :alt (:name profile) :class "docker-registration registration" :key id}
     [:h3 (truncate-name 40 (:name profile)) [:small (players-names reg)]]
     [:dl
      [:dt "Robot name"] [:dd (:robot-name profile)]
      [:dt "Image"] [:dd (:image-id reg) ":" (:tag reg)]]]))

(defmethod registrations-thumb :lambda
  [[id reg]]
  (let [profile (profile reg)]
    [bs/thumbnail {:src (:avatar profile) :alt (:name profile) :class "lambda-registration registration" :key id}
     [:h3 (truncate-name 40 (:name profile)) [:small (players-names reg)]]
     [:dl
      [:dt "Robot name"] [:dd (:robot-name profile)]
      [:dt "Function name"] [:dd (:lambda-function-name reg)]]]))

(defmethod registrations-thumb :default
  [[id reg]]
  (let [profile-name (players-names reg)]
    [bs/thumbnail {:src unknown-registration-avatar :alt profile-name :class "pending-registration registration" :key id}
     [:h3 profile-name]
     [:p "Pending registration..."]]))

(defn registrations-gallery
  []
  [bs/panel {:class "all-registrations-gallery" :header "Hackathon Competitors"}
   (map registrations-thumb @all-registrations-state)])

(defn all-registration-loading
  []
  [bs/panel {:class "pulse"}
   [:h3 "Fetching registrations..."]])

(defn all-registrations-middle-section
  []
  [:div.all-registrations-middle
   (cond
     (:waiting? @all-registrations-state)
     [all-registration-loading]

     (:error @all-registrations-state)
     [bs/alert {:bs-style "warning"}
      [:h3 [bs/glyph {:glyph "warning-sign"}] " Registrations Fetch Error"]
      [:div [:p "Error while fetching registrations"]]]

     :else
     [registrations-gallery])])

(defn registrations-view*
  []
  [:section.all-registrations-root
   [:section.left]
   [all-registrations-middle-section]
   [:section.right]])

(def registrations-view
  (with-meta registrations-view* {:component-did-mount fetch-all-registrations!}))

(defn registrations-root [] [registrations-view])
