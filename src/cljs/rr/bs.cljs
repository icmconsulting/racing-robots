(ns rr.bs
  (:require [cljsjs.react-bootstrap]
            [reagent.core :refer [adapt-react-class]]))

;; nav
(def navbar (adapt-react-class js/ReactBootstrap.Navbar))
(def nav (adapt-react-class js/ReactBootstrap.Nav))
(def navbar-header (adapt-react-class js/ReactBootstrap.Navbar.Header))
(def navbar-brand (adapt-react-class js/ReactBootstrap.Navbar.Brand))
(def navbar-item (adapt-react-class js/ReactBootstrap.NavItem))
(def navbar-collapse (adapt-react-class js/ReactBootstrap.Navbar.Collapse))
(def navbar-toggle (adapt-react-class js/ReactBootstrap.Navbar.Toggle))

;; list groups
(def list-group (adapt-react-class js/ReactBootstrap.ListGroup))
(def list-group-item (adapt-react-class js/ReactBootstrap.ListGroupItem))

;; panels
(def panel (adapt-react-class js/ReactBootstrap.Panel))

;; forms
(def form-group (adapt-react-class js/ReactBootstrap.FormGroup))
(def form-control (adapt-react-class js/ReactBootstrap.FormControl))
(def control-label (adapt-react-class js/ReactBootstrap.ControlLabel))
(def help-block (adapt-react-class js/ReactBootstrap.HelpBlock))
(def button (adapt-react-class js/ReactBootstrap.Button))
(def radio (adapt-react-class js/ReactBootstrap.Radio))

;; other layouts
(def well (adapt-react-class js/ReactBootstrap.Well))


