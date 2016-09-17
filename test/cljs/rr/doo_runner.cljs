(ns rr.doo-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [rr.core-test]))

(doo-tests 'rr.core-test)
