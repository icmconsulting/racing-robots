(ns rr.registration
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.session :as session]
            [taoensso.timbre :refer [debug info warn error]]
            [cljs.core.async :as async]
            [ajax.core :refer [GET PUT]]
            [rr.bs :as bs]
            [rr.bots :as bots]
            [rr.game :as game]
            [rr.runner :as runner]
            [rr.utils :refer [ascii-title csrf-token]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defonce registration-state (atom {:waiting? true}))

(defmulti dispatch-event-type (fn [_ event] (first event)))

(defn dispatch!
  [event]
  (swap! registration-state dispatch-event-type event))

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
  )

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
  [_ _]
  ;;TODO: clear on backend, too
  {})

(defmethod dispatch-event-type :test!
  [registration-state _]
  (PUT (str "/registrations/" (:registration-id registration-state))
       {:params {:registration (dissoc registration-state :testing?)}
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

(defn registration-form-buttons
  []
  [:div
   [bs/button {:bs-size  :large
               :bs-style :primary
               :class    (when (:testing? @registration-state) "pulse")
               :disabled (or (not (can-test? @registration-state))
                             (:testing? @registration-state))
               :on-click #(dispatch! [:test!])}
    (if (:testing? @registration-state) "Verifying bot..." "Test your submission")]
   [bs/button {:bs-size  :large
               :bs-style :default
               :disabled (:testing? @registration-state)
               :on-click #(dispatch! [:reset!])} "Reset"]])

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
              [:li (if (= :pass (:result test))
                     [bs/glyph {:glyph :ok}]
                     [bs/glyph {:glyph :remove}])
               " " [:strong (:test test)] " - " (:description test)])]
           [:p "Your registration has " [:strong "not"] " been saved. Fix up the issue, and resubmit." [:br]]
           [registration-form-buttons]]

          (= :saved (:result @registration-state))
          [bs/alert {:bs-style :success}
           [:p [:strong "Registration successful!"]]
           [:p "TODO: profile here..."]
           [:p "TODO: note for docker players here RE: if image changes, they will need to resubmit..."]
           [:p "TODO: note for lambda players here RE: no need to resubmit unless their function name changes..."]
           [registration-form-buttons]]

          :else [registration-form-buttons])]])

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

(defn registration-root
  [registration-id]
  (fetch-registration! registration-id)
  (fn [_]
    [:section.registration-root
     [:section.left]
     [registration-middle-section]
     [:section.right]]))

