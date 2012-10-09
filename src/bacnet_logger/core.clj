(ns bacnet-logger.core
  (:gen-class :main true)
  (:require [bacnet-scan-utils.bacnet :as b]
            [bacnet-scan-utils.export :as be]
            [bacnet-logger.systray :as st]
            [gzip64.core :as g]
            [overtone.at-at :as ot]
            [clj-http.client :as client]
            [clojure.java.browse])
  (:use [seesaw.core]
        [seesaw.swingx]
        [seesaw.mig]))

(def posting-address "https://bacnethelp.com/logger/post-to-project")
;;"https://bacnethelp.com:8443/logger/post-to-project")

(defmacro get-logger-version []
  (let [x# (System/getProperty "bacnet-logger.version")]
    `~x#))

(defn quit []
  (System/exit 0))

(defn mapply [f & args] (apply f (apply concat (butlast args) (last args))))

(defn get-configs []
  (let [config-filename (str (be/get-running-directory-path) "/config.cjm")]
    (try (read-string (slurp config-filename))
         (catch Exception e))))


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
  (let [filename-list (map #(.getAbsolutePath %)
                           (seq (.listFiles (clojure.java.io/file
                                             (be/get-running-directory-path)))))]
    (filter #(re-find #"BH.*\.log" %) filename-list)))


(defn send-to-remote-server [data]
  (let [{:keys [password project-id]} (get-configs)]
    (try (client/post posting-address
                      {:form-params {:data data
                                     :logger-version (get-logger-version)
                                     :logger-password password
                                     :project-id project-id}
                       :content-type "application/x-www-form-urlencoded"})
         (catch Exception e))))

(defn send-logs
  "Check in the current folder for any unsent logs" []
  (doseq [file (find-unsent-logs)]
    (when (= 200 (:status (send-to-remote-server (slurp file))))
      ;; if there's an error, keep the files for the next round
           (clojure.java.io/delete-file file))))

(defn repetitive-logging
  "Scan the network every 10 min and send back to server every 60 min" []
  (let [time-interval (* 60000 (or (:time-interval (get-configs)) 10));convert min in ms
        my-pool (ot/mk-pool)]
    {:logger (ot/every time-interval scan-and-spit my-pool)
     :send-logs (ot/every 3600000 send-logs my-pool)}))

(defn stop-logging [logger]
  (map #(ot/stop (val %)) logger))

(defn add-to-system-tray []
  (let [project (:project-id (get-configs))]
    (st/system-tray-menu :icons ["bh-icon-32px.png" "BACnet Help - Logging"]
                         :items
                         [["Browse this project" #(clojure.java.browse/browse-url
                                                   (str "https://bacnethelp.com/project/" project))]
                          ["About" #(javax.swing.JOptionPane/showMessageDialog
                                     nil (str "Logger V" (get-logger-version)
                                              "\nConfigured for project: " project))]
                          [:separator]
                          ["Exit" #(quit)]])))

(defn test-network
  "Send a WhoIs and make sure that at least one device answers. If
  not, suggest to use the scanner to check the network."[]
  (let [result (not (empty? (b/get-remote-devices-list)))]
    (when-not result
      (-> (dialog :type :error
                  :content
                  (mig-panel
                   :constraints ["wrap 1"]
                   :items
                   [["There is no visible BACnet devices on the network."]
                    ["Would you like to use the scanner application to check your network?"]])
                  :option-type :ok-cancel
                  :success-fn (fn [e] (clojure.java.browse/browse-url
                                       "https://bacnethelp.com/how-to/scanner")))
          (pack!) (show!)))
    result))
                   

(defn -main [& args]
  (native!)
  (if (test-network)
    (do (add-to-system-tray)
        (repetitive-logging))
    (quit)))

  