(ns rr.game-viewer
  (:require [reagent.core :as reagent :refer [atom]]
            [reagent.session :as session]))

(def game-state (atom nil))

(defn game-title
  []

  )


(defn start-new-game-root
  []


  )

(defn game-viewer-root []
  [:section.game-viewer-root
   [:section.left

    ]
   [:section.middle
    [game-title]
    ]
   [:section.right

    ]])
;
;<body class="vbox viewport">
;<header>Header</header>
;<section class="main hbox space-between">
;<nav>Nav</nav>
;<article>Article</article>
;<aside>Aside</aside>
;</section>
;<footer>Footer</footer>
;</body>
