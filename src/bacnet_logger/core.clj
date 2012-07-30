(ns bacnet-logger.core
  (:gen-class :main true)
  (:require [bacnet-scan-utils.bacnet :as b]
            [bacnet-scan-utils.export :as be]
            [gzip64.core :as g]
            [overtone.at-at :as ot]
            [clj-http.client :as client]))

(def posting-address "https://bacnethelp.com/logger/post-to-project")
;  "https://bacnethelp.com:8443/logger/post-to-project")

(defmacro get-logger-version []
  (let [x# (System/getProperty "bacnet-logger.version")]
    `~x#))

(defn mapply [f & args] (apply f (apply concat (butlast args) (last args))))

(defn get-configs []
  (try (read-string (slurp "config.cjm"))
       (catch Exception e)))


(defn scan-and-spit []
  (let [configs (get-configs)
        data (b/with-local-device (mapply b/new-local-device configs)
               (let [rds (mapply b/get-remote-devices-and-info configs)]
                 (b/remote-devices-object-and-properties rds
                                                         :get-trend-log false
                                                         :get-backup false)))]
    (when (map? data)
      (be/spit-to-log "BH" (g/gz64 (str data))))))


(defn find-unsent-logs []
  (let [filename-list (map #(.getName %)
                           (seq (.listFiles  (clojure.java.io/file "."))))]
    (filter #(re-find #"BH.*\.log" %) filename-list)))


(defn send-to-remote-server [data]
  (let [{:keys [logger-password project-id]} (get-configs)]
    (try (client/post posting-address
                      {:form-params {:data data
                                     :logger-version (get-logger-version)
                                     :logger-password logger-password
                                     :project-id project-id}
                       :content-type "application/x-www-form-urlencoded"})
         (catch Exception e))))

(defn send-logs
  "Check in the current folder for any unsent logs" []
  (doseq [file (find-unsent-logs)]
    (when (= 200 (:status (send-to-remote-server (slurp file))))
                                        ;if there's an error, keep the files for the next round
           (clojure.java.io/delete-file file))))

(defn repetitive-logging
  "Scan the network every 10 min and send back to server every 60 min" []
  (let [my-pool (ot/mk-pool)]
    {:logger (ot/every 600000 scan-and-spit my-pool)
     :send-logs (ot/every 3600000 send-logs my-pool)}))

(defn stop-logging [logger]
  (map #(ot/stop (val %)) logger))

(defn -main [& args]
  (repetitive-logging))