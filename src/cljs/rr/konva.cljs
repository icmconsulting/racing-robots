(ns rr.konva
  (:require [react-konva.core]
            [reagent.core :refer [adapt-react-class]]))


(def stage (adapt-react-class js/ReactKonva.Stage))
(def group (adapt-react-class js/ReactKonva.Group))
(def layer (adapt-react-class js/ReactKonva.Layer))
(def label (adapt-react-class js/ReactKonva.Label))

;; shapes
(def rect (adapt-react-class js/ReactKonva.Rect))
(def circle (adapt-react-class js/ReactKonva.Circle))
(def ellipse (adapt-react-class js/ReactKonva.Ellipse))
(def wedge (adapt-react-class js/ReactKonva.Wedge))
(def line (adapt-react-class js/ReactKonva.Line))
(def sprite (adapt-react-class js/ReactKonva.Sprite))
(def image (adapt-react-class js/ReactKonva.Image))
(def text (adapt-react-class js/ReactKonva.Text))
(def text-path (adapt-react-class js/ReactKonva.TextPath))
(def star (adapt-react-class js/ReactKonva.Star))
(def ring (adapt-react-class js/ReactKonva.Ring))
(def arc (adapt-react-class js/ReactKonva.Arc))
(def tag (adapt-react-class js/ReactKonva.Tag))
(def path (adapt-react-class js/ReactKonva.Path))
(def regular-polygon (adapt-react-class js/ReactKonva.RegularPolygon))
(def arrow (adapt-react-class js/ReactKonva.Arrow))
(def shape (adapt-react-class js/ReactKonva.Shape))
