(ns rr.runner
  (:require [rr.game :as game]
            [rr.bots :as bots]))

(defn start-new-game
  [{:keys [players board]}]
  (game/new-game players board))

