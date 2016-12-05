(ns rr.utils)

(defn image-obj
  [src]
  (let [obj (js/Image.)]
    (set! obj -src src)
    obj))

(defn player-short-id
  [player]
  (second (re-find #"^(\w+)-" (:id player))))

(def ascii-title
  "\n\n   ____  _____   __  ___        __ __              __          __    __                    ___   ___   ___  ____\n  /  _/ / ___/  /  |/  /       / // / ___ _ ____  / /__ ___ _ / /_  / /  ___   ___        |_  | / _ \\ <  / / __/\n _/ /  / /__   / /|_/ /       / _  / / _ `// __/ /  '_// _ `// __/ / _ \\/ _ \\ / _ \\      / __/ / // / / / / _ \\ \n/___/  \\___/  /_/  /_/       /_//_/  \\_,_/ \\__/ /_/\\_\\ \\_,_/ \\__/ /_//_/\\___//_//_/     /____/ \\___/ /_/  \\___/ \n   ___               _                      ___         __        __         __                                 \n  / _ \\ ___ _ ____  (_)  ___   ___ _       / _ \\ ___   / /  ___  / /_  ___  / /                                 \n / , _// _ `// __/ / /  / _ \\ / _ `/      / , _// _ \\ / _ \\/ _ \\/ __/ (_-< /_/                                  \n/_/|_| \\_,_/ \\__/ /_/  /_//_/ \\_, /      /_/|_| \\___//_.__/\\___/\\__/ /___/(_)                                   \n                             /___/                                                                              \n\n")

(defn csrf-token
  []
  (-> (.getElementsByTagName js/document "body")
      (aget 0)
      (.-dataset)
      (.-csrf)))

(defn truncate-name
  [max-size name]
  (if (< max-size (count name))
    (str (subs name 0 (- max-size 3)) "...")
    name))

(defn players-names
  [reg]
  (clojure.string/join " and " (filter identity [(:player1 reg) (:player2 reg)])))