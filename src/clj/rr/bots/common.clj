(ns rr.bots.common
  (:require [clojure.data.json :as json]
            [camel-snake-kebab.core :refer :all]
            [scjsv.core :as validator]
            [rr.game :as game]))

(defn other-players
  [player]
  (comp (remove #(= (:id %) (:id player)))
        (map (fn [p]
               (->
                 p
                 (select-keys [:robot :name :id :state])
                 (update :robot dissoc :events))))))

(defn new-game-data-for-bot
  [game player]
  (let [board (game/board game)]
    {:id    (game/id game)
     :player-id (:id player)
     :board {:name    (:name board)                         ;; bit of a hack - this assumes that :name is within the map of the board that is sent
             :squares (game/rows board)}
     :player-robot (:robot player)
     :other-players (into [] (other-players player) (game/players game))}))

(defn turn-game-data-for-bot
  [game player {:keys [dealt]}]
  (merge (new-game-data-for-bot game player)
         {:cards dealt
          :num-registers (game/num-registers-for-this-turn player)}))

(defn completed-turn-game-data-for-bot
  [game player turn]
  (let [turn-players (game/deal-cards-to-players turn)
        registers-for-players (game/registers-for-turn turn)]
    (merge (new-game-data-for-bot game player)
           {:other-players (into [] (comp (other-players player)
                                          (map #(assoc % :last-turn (get registers-for-players (:id %)))))
                                 turn-players)
            :available-responses (game/allowable-clean-up-commands player turn)})))

(defn game-over-data-for-bot
  [game player]
  (let [[_ players] (game/victory-status game)]
    {:winners (map #(dissoc % :robot) (filter #(= :winner (:victory-state %)) players))
     :all-players players}))


(defn write-json-request
  [data]
  (json/write-str data :key-fn (comp name ->camelCase)))

(defn read-json-response
  [data]
  (json/read-str data :key-fn (comp keyword ->kebab-case)))

(def ready-schema
  {:$schema "http://json-schema.org/draft-04/schema#"
   :type "object"
   :properties {:response {:enum ["ready"]}
                :profile {:type "object"
                          :properties {:name {:type "string"}
                                       :robot-name {:type "string"}
                                       :avatar {:type "string"}}
                          :required [:name :robot-name :avatar]}}
   :required [:response :profile]})

(def ready-validator (validator/validator ready-schema))

(def turn-schema
  {:$schema "http://json-schema.org/draft-04/schema#"
   :type "object"
   :properties {:registers {:$ref "#/definitions/registers"}
                :powering-down {:type "boolean"}}
   :definitions {:registers {:type "array" :items {:$ref "#/definitions/register"}}
                 :register {:type "object"
                            :properties {:type {:$ref "#/definitions/type"}
                                         :value {:$ref "#/definitions/value"}
                                         :priority {:$ref "#/definitions/priority"}}
                            :required [:type :value :priority]}
                 :type {:enum ["move" "rotate"]}
                 :value {:enum [1 2 3 -1 "right" "left" "u-turn"]}
                 :priority {:type "integer"}}
   :required [:registers :powering-down]})

(defn turn-validator
  [player turn-data]
  (or
    ((validator/validator turn-schema) turn-data)
    (when-not (= (count (:registers turn-data)) (game/num-registers-for-this-turn player))
      [{:message (format "Invalid number of registers received. Expected %s, received %s."
                         (game/num-registers-for-this-turn player) (count (:registers turn-data)))}])))

(defn adapt-register-response
  [register]
  (-> register
      (update :type keyword)
      (update :value #(if (integer? %) % (keyword %)))))

(defn adapt-turn-response
  [turn-response]
  (update turn-response :registers #(map adapt-register-response %)))

(def turn-completed-schema
  {:$schema "http://json-schema.org/draft-04/schema#"
   :type "object"
   :properties {:response {:enum ["power-down" "power-down-override" "no-action"]}}
   :required [:response]})

(def turn-completed-validator (validator/validator turn-completed-schema))
