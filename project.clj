(defproject bacnet-logger "1.0.1"
  :description "Automatic logger for a BACnet network"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [clj-time "0.4.2"]
                 [bacnet-scan-utils "1.0.3"]
                 [org.clojars.frozenlock/gzip64 "1.0.0"]
                 [clj-http "0.4.1"]
                 [overtone/at-at "1.0.0"]]
  :main bacnet-logger.core)