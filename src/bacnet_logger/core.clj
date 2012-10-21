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

(def posting-address "https://bacnethelp.com:8443/logger/post-to-project")
;;"https://bacnethelp.com:8443/logger/post-to-project")
(def config-address "https://bacnethelp.com:8443/logger/get-config")

(defmacro get-logger-version []
  (let [x# (System/getProperty "bacnet-logger.version")]
    `~x#))

(defn quit []
  (System/exit 0))


;; ================================
(defn fdialog
  "The jdialog doesn't have a task bar presence. If user bring another
  window in front, there's no easy way to get the dialog
  back. This solves the problem by wrapping the dialog in a frame.

 Takes the same argument as the normal 'dialog' function, but the
 'pack!' and 'show!' are already called."[& dialog-args]
 (let [f (frame  :icon st/icon-running :title "BACnet Help Logger")]
   (.setUndecorated f true)
   (show! f)
   (-> (apply dialog dialog-args) pack! show!)
       (dispose! f)))
;; ================================

(defn mapply [f & args] (apply f (apply concat (butlast args) (last args))))

(defn get-configs
  "Get the local  configs"[]
  (let [config-filename (str (be/get-running-directory-path) "/config.cjm")]
    (try (read-string (slurp config-filename))
         (catch Exception e))))

(defn save-configs
  "Save dat to config file." [data]
  (spit (str (be/get-running-directory-path) "/config.cjm") data))
  

(defn get-configs-from-server
  "Get the configs file from server and update the local one. If bad
  password or connection problem, do nothing. Return true on success."
  [&[{:keys [project-id logger-password]}]]
  (let [local-config (get-configs)
        id (or project-id (:project-id local-config))
        psw (or logger-password (:logger-password local-config))
        new-config (try (read-string (:body (client/get config-address
                                                        {:query-params {:project-id id
                                                                        :logger-password psw
                                                                    :logger-version (get-logger-version)}})))
                        (catch Exception e))]
    (when new-config
      (save-configs (merge new-config {:project-id id :logger-password psw}))
      true)))

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
  (let [{:keys [logger-password project-id]} (get-configs)]
    (try (client/post posting-address
                      {:form-params {:data data
                                     :logger-version (get-logger-version)
                                     :logger-password logger-password
                                     :project-id project-id}
                       :content-type "application/x-www-form-urlencoded"})
         (catch Exception e))))

(defn send-logs
  "Check in the current folder for any unsent logs. If the server
  can't be reached, keep every log." []
  (doseq [file (find-unsent-logs)]
    (when (= 200 (:status (send-to-remote-server (slurp file))))
      ;; if there's an error, keep the files for the next round
           (clojure.java.io/delete-file file))))

(def pool (ot/mk-pool))

(declare start-logging)

(defn stop-logging []
  (ot/stop-and-reset-pool! pool))

(defn restart-logging []
  (stop-logging)
  (start-logging))

(defn start-logging []
  (let [time-interval (* 60000 (or (:time-interval (get-configs)) 10))];convert min in ms
    {:logger (ot/every time-interval scan-and-spit pool :initial-delay time-interval) ;scan the BACnet network
     :send-logs (ot/at (+ 3600000 (ot/now)) ;delay 1 hour (3600000ms)
                       #(do (send-logs) ;send back to server
                            (st/update-tray! (not (get-configs-from-server))) ;in case the configs have changed
                            (restart-logging))
                       pool)}))
 

(defn test-network
  "Send a WhoIs and make sure that at least one device answers. If
  not, suggest to use the scanner to check the network."[]
  (let [result (not (empty? (b/get-remote-devices-list)))]
    (when-not result
      (future (fdialog :type :error
                      :content
                      (mig-panel
                       :constraints ["wrap 1"]
                       :items
                       [["There is no visible BACnet devices on the network."]
                        ["Would you like to use the scanner application to check your network?"]])
                      :option-type :ok-cancel
                      :success-fn (fn [e] (clojure.java.browse/browse-url
                                           "https://bacnethelp.com/how-to/scanner")))))
    result))

(defn restart-with-test
  "Test the network and start the logging if success."[]
  (let [results (test-network)]
    (if results
      (do (restart-logging)
          (st/tray-logging!))
      (do (stop-logging)
          (st/tray-neutral!)))))
    
    
(defn init-config-dialog
  "Ask the user for the initial project-id and password. Try to import
  the remaining configs from the server. True if success, nil otherwise."[]
  (let [{:keys [project-id logger-password]} (get-configs)]
    (fdialog :type :question
                :title "Retrieve configurations from server"
                :content
                (mig-panel
                 :constraints ["wrap 2" "[shrink 0]20px[300, grow, fill]"]
                 :items [["Project-id"] [(text :id :project :text project-id)] ["Password"] [(text :id :psw :text logger-password)]
                         ["Get your project-id: "][(hyperlink :uri "https://bacnethelp.com/my-projects"
                                                                                      :text "My projects")]])
                :option-type :ok-cancel
                :options [(action :name "Retrieve" :handler (fn [e] (let [id-psw {:project-id (text (select (to-root e) [:#project]))
                                                                                  :logger-password (text (select (to-root e) [:#psw]))}]
                                                                      (if (get-configs-from-server id-psw) (do (fdialog :content "Success!" :type :info)
                                                                                                               (return-from-dialog e true))
                                                                          (fdialog :title "Oups!" :type :error :content
                                                                                                    (mig-panel
                                                                                                     :constraints ["wrap 1"]
                                                                                                     :items [["We could not retrieve the data."]
                                                                                                             ["Please make sure the project-id and password are correct."]
                                                                                                             ["In addition, make sure you have an internet access."]]))))))
                          (action :name "Cancel" :handler (fn [e] (return-from-dialog e nil)))])))


(defn add-to-system-tray []
  (st/create-system-tray-menu :icons ["bh-icon-32px.png" "BACnet Help - Logging"]
                              :items
                              [["Update configurations" #(do (init-config-dialog) (restart-with-test))]
                               ["Re-start logging" #(restart-with-test)]
                               ["Send logs" #(send-logs)]
                               ["Browse this project" #(clojure.java.browse/browse-url
                                                        (str "https://bacnethelp.com/project/" (:project-id (get-configs))))]
                               [:separator]
                               ["Help" #(clojure.java.browse/browse-url "https://bacnethelp.com/how-to/logger")]
                               ["About" #(fdialog :title "About" :type :info :content
                                                                                                    (mig-panel
                                                                                                     :constraints ["wrap 1"]
                                                                                                     :items [[(str "Logger V" (get-logger-version))]
                                                                                                             [(str "Configured for project: " (:project-id (get-configs)))]
                                                                                                             [(str "Temporary files are in: " (be/get-running-directory-path))]]))]
                               [:separator]
                               ["Exit" #(quit)]]))



(defn init-config
  "Check if there's an existing config file. If not, ask the user for
  his project-id and password."[]
  (if-not (get-configs)
    (init-config-dialog)
    true))

(defn -main [& args]
  (native!)
  (add-to-system-tray)
  (when (and (init-config)(test-network))
    (get-configs-from-server) ;update the configs        
    (start-logging)
    (st/tray-logging!)))
    ;(quit)))

  