(ns rr.utils)

(defn image-obj
  [src]
  (let [obj (js/Image.)]
    (set! obj -src src)
    obj))

(defn player-short-id
  [player]
  (second (re-find #"^(\w+)-" (:id player))))