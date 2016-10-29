(ns rr.ajax-bot
  (:require [ajax.core :as ajax]
            [rr.bots :as bots]))

(defrecord RRAjaxBot []
  bots/RRBot
  (new-game [bot game]

    )
  (turn [bot game turn] )
  (turn-complete [bot game turn] )
  (game-over [bot game results])
  (profile [bot]))

