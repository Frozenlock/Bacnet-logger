(defproject bacnet-logger "1.0.6"
  :description "Automatic logger for a BACnet network"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [clj-time "0.4.2"]
                 [bacnet-scan-utils "1.0.8"]
                 [org.clojars.frozenlock/gzip64 "1.0.0"]
                 [clj-http "0.4.1"]
                 [seesaw "1.4.2"]
                 [overtone/at-at "1.0.0"]]
  :plugins [[lein-getdown "0.0.1"]]
  :getdown {:appbase "https://bacnethelp.com:8443/getdown/logger/"
            :allow-offline true}
  :main bacnet-logger.core)