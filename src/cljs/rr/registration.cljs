(ns rr.registration
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.session :as session]
            [taoensso.timbre :refer [debug info warn]]
            [cljs.core.async :as async]
            [rr.bs :as bs]
            [rr.bots :as bots]
            [rr.game :as game]
            [rr.runner :as runner])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))