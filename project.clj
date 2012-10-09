(defproject bacnet-logger "1.0.2"
  :description "Automatic logger for a BACnet network"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [clj-time "0.4.2"]
                 [bacnet-scan-utils "1.0.5"]
                 [org.clojars.frozenlock/gzip64 "1.0.0"]
                 [clj-http "0.4.1"]
                 [seesaw "1.4.2"]
                 [overtone/at-at "1.0.0"]]
  :main bacnet-logger.core)