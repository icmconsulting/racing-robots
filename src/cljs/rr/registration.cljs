(ns rr.registration
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.session :as session]
            [taoensso.timbre :refer [debug info warn]]
            [cljs.core.async :as async]
            [ajax.core :refer [GET PUT]]
            [rr.bs :as bs]
            [rr.bots :as bots]
            [rr.game :as game]
            [rr.runner :as runner]
            [rr.utils :refer [ascii-title]])
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
  {:error error :id id})

(defn registration-loading
  []
  [bs/panel {:class "pulse"}
   [:h3 "Fetching registration..."]])

(defn registration-middle-section
  []
  [:div.registration-middle
   (cond
     (:waiting? @registration-state)
     [registration-loading]

     (:error @registration-state)
     [bs/alert {:bs-style "warning"}
      [:h3 [bs/glyph {:glyph "warning-sign"}] " Registration Fetch Error"]
      [:div [:p "Error while fetching registration " [:strong (:id @registration-state)]]]
      [:div [:p "Go and find Brendan to help you out..."]]]

     :else
     [bs/panel {:class "registration-entry-root"}
      [:pre ascii-title]
      [:h4 (clojure.string/join " and " (filter identity [(:player1 @registration-state) (:player2 @registration-state)]))]
      [:form
       [bs/button {:bs-size  :large
                   :bs-style :primary
                   :on-click #(dispatch! [:test!])} "Test your submission"]]]

     )])

(defn registration-root
  [registration-id]
  (fetch-registration! registration-id)
  (fn [_]
    [:section.registration-root
     [:section.left]
     [registration-middle-section]
     [:section.right]]))

