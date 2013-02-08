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

(import 'java.util.Calendar)

(declare posting-address)
(declare config-address)

(defn safe-read
  "Evaluate the string in a safe way. If error while reading, return
  nil" [s]
  (try (binding [*read-eval* false]
         (read-string s))
       (catch Exception e)))

(defmacro get-logger-version []
  (let [x# (System/getProperty "bacnet-logger.version")]
    `~x#))

(defn quit []
  (System/exit 0))

(def path (str (System/getProperty "user.home")"/BH-Logger")) 

(defn mdir-spit
  "Try to make the directories leading to the file if they don't
  already exists." [f content]
  (clojure.java.io/make-parents f)
  (spit f content))

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
   (let [dialog-result (-> (apply dialog dialog-args) pack! show!)]
     (dispose! f)
     dialog-result)))
;; ================================

(defn mapply [f & args] (apply f (apply concat (butlast args) (last args))))

(defn get-configs
  "Get the local  configs"[]
  (let [config-filename (str path "/config.cjm")]
    (safe-read (slurp config-filename))))

(defn save-configs
  "Save data to config file." [data]
  (mdir-spit (str path "/config.cjm") data))
  

(defn get-configs-from-server
  "Get the configs file from server and update the local one. If bad
  password or connection problem, do nothing. Return true on success."
  [&[{:keys [project-id logger-password]}]]
  (let [local-config (get-configs)
        id (or project-id (:project-id local-config))
        psw (or logger-password (:logger-password local-config))
        new-config (safe-read (:body (client/get config-address
                                                 {:query-params {:project-id id
                                                                 :logger-password psw
                                                                 :logger-version (get-logger-version)}})))]
    (when new-config
      (save-configs (merge new-config {:project-id id :logger-password psw}))
      true)))

(defn scan-and-spit []
  (try (let [configs (get-configs)
             data (b/with-local-device (mapply b/new-local-device configs)
                    (let [rds (mapply b/get-remote-devices-and-info configs)]
                      (b/remote-devices-object-and-properties rds
                                                              :get-trend-log false
                                                              :get-backup false)))]
         (when (map? data)
           (mdir-spit (str path "/" "BH" (.getTimeInMillis (Calendar/getInstance)) ".log")
                      (g/gz64 (str data)))))
       (catch Exception e)))


(defn find-unsent-logs []
  (let [filename-list (map #(.getAbsolutePath %)
                           (seq (.listFiles (clojure.java.io/file
                                             path))))]
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
     :send-logs (ot/at (+ 3600100 (ot/now)) ;delay 1 hour (3600000ms)
                       #(do (send-logs) ;send back to server
                            (st/update-tray! (not (get-configs-from-server))) ;in case the configs have changed
                            (restart-logging))
                       pool)}))
 

(defn test-network
  "Send a WhoIs and make sure that at least one device answers. If
  not, suggest to use the scanner to check the network."[]
  (let [configs (get-configs)
        result (try (b/get-remote-devices-list
                     :local-device (mapply b/new-local-device configs)
                     :dest-port (:port configs))
                    (catch java.net.BindException e
                      (do (fdialog :type :error
                                   :title "Bind error"
                                   :content
                                   (mig-panel
                                    :constraints ["wrap 1"]
                                    :items
                                    [[(str "The logger can't bind with the provided BACnet port ("
                                           (or (:port configs) 47808)").")]
                                     ["There is probably another application already running with it."]
                                     ["You will have to close the other application or use another machine."]]))
                          :bind-error)))]
    (when (not (= :bind-error result))
      (if (empty? result)
        (do (future (fdialog :type :error
                             :title "Nothing on the network"
                             :content
                             (mig-panel
                              :constraints ["wrap 1"]
                              :items
                              [["There is no visible BACnet devices on the network."]
                               ["Would you like to use the scanner application to check your network?"]])
                             :option-type :ok-cancel
                             :success-fn (fn [e] (clojure.java.browse/browse-url
                                                  "https://bacnethelp.com/how-to/scanner"))))
            nil)
            result))))

(defn restart-with-test
  "Test the network and start the logging if success."[]
  (let [results (test-network)]
    (if results
      (do (restart-logging)
          (st/tray-logging!))
      (do (stop-logging)
          (st/tray-neutral!)))))
    

(def legend
  (let [in-legend
        (mig-panel :constraints ["wrap 2"]
                   :items [[(seesaw.core/make-widget (clojure.java.io/resource "bh-icon-32px-logging.png"))]
                           ["Logging: everything is fine."]
                           [(seesaw.core/make-widget (clojure.java.io/resource "bh-icon-32px-error.png"))]
                           ["ERROR: Can't reach server, will try again next hour."]
                           [(seesaw.core/make-widget (clojure.java.io/resource "bh-icon-32px-neutral.png"))]
                           ["Stopped: you have to re-start manually using the system tray."]])]
    (mig-panel :constraints ["wrap 1"]
               :items [[:separator         "grow, span,"]
                       ["Legend:"]
                       [in-legend]])))


(defn init-config-dialog
  "Ask the user for the initial project-id and password. Try to import
  the remaining configs from the server. True if success, nil otherwise."[]
  (let [{:keys [project-id logger-password]} (get-configs)]
    (fdialog :type :question
                :title "Retrieve configurations from server"
                :content
                (mig-panel
                 :constraints ["wrap 2" "[shrink 0]20px[300, grow, fill]"]
                 :items [["Project ID"] [(text :id :project :text project-id)] ["Password"] [(text :id :psw :text logger-password)]
                         ["Get your project-id: "][(hyperlink :uri "https://bacnethelp.com/my-projects"
                                                                                      :text "My projects")]])
                :option-type :ok-cancel
                :options [(action :name "Retrieve" :handler (fn [e] (let [id-psw {:project-id (clojure.string/trim (text (select (to-root e) [:#project])))
                                                                                  :logger-password (clojure.string/trim (text (select (to-root e) [:#psw])))}]
                                                                      (if (get-configs-from-server id-psw) (do (fdialog :title "Success!" :content
                                                                                                                        (mig-panel
                                                                                                                         :constraints ["wrap 1"]
                                                                                                                         :items [["Logging now in progress."]
                                                                                                                                 ["You can access the logger via the system tray"]
                                                                                                                                 [(seesaw.core/make-widget (clojure.java.io/resource "systray.png"))]
                                                                                                                                 [legend]])
                                                                                                                        :type :info)
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
                                                                                                             [(str "Temporary files are in: " path)]
                                                                                                             [legend]]))]
                               [:separator]
                               ["Exit" #(quit)]]))



(defn init-config
  "Check if there's an existing config file. If not, ask the user for
  his project-id and password."[]
  (if-not (get-configs)
    (init-config-dialog)
    true))

(defn -main
  "Optional argument is the port to use when sending to the server."
  [& port]
  (let [dest-port (when (first port)
                    (str ":" (first port)))]
    (def posting-address (str "https://bacnethelp.com"dest-port"/logger/post-to-project"))
    (def config-address (str "https://bacnethelp.com"dest-port"/logger/get-config"))
    (print posting-address)
    (native!)
    (add-to-system-tray)
    (when (and (init-config)(test-network))
      (get-configs-from-server) ;update the configs        
      (start-logging)
      (st/tray-logging!))))
  ;;(quit)))

  