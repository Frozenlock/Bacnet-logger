(ns bacnet-logger.systray
    (:use [seesaw.core]
        [seesaw.swingx]
        [seesaw.mig]))


(import '(java.awt.SystemTray)
        '(java.awt.event ActionListener))

(defn create-image
  [path description]
  (-> (javax.swing.ImageIcon. (clojure.java.io/resource path) description)
      (.getImage)))

(defn add-to-popup-menu [popup-menu items]
  (doseq [item items]
    (let [string-or-key (first item)
          func (last item)]
    (if (= string-or-key :separator)
      (.addSeparator popup-menu)
      (let [menu-item (java.awt.MenuItem. string-or-key)]
        (.addActionListener menu-item
                            (proxy [ActionListener] []
                              (actionPerformed [evt] (func))))
        (.add popup-menu menu-item))))))

(def icon-running (create-image "bh-icon-32px-logging.png" "Tray Icon"))
(def icon-error (create-image "bh-icon-32px-error.png" "Tray Icon"))
(def icon-neutral (create-image "bh-icon-32px-neutral.png" "Tray Icon"))

(defn make-tray-icon [sys-tray description]
  (let [tray-icon (java.awt.TrayIcon. icon-neutral description)]
    (.setImageAutoSize tray-icon true)
    (.add sys-tray tray-icon)
    tray-icon))

(declare tray-icon)
(defn tray-logging! [] (doto tray-icon (.setImage icon-running) (.setToolTip "BACnet Help - Logging")))
(defn tray-error! [] (doto tray-icon (.setImage icon-error) (.setToolTip "Can't reach server; will try again next hour")))
(defn tray-neutral! [] (doto tray-icon (.setImage icon-neutral) (.setToolTip "Stopped")))

(defn update-tray!
  "When error? is true, change for the red icon. Otherwise, change for
  the green."[error?]
  (if error? (tray-error!)
      (tray-logging!)))


(def sys-tray (java.awt.SystemTray/getSystemTray))
(def popup-menu (java.awt.PopupMenu.))

(defn create-system-tray-menu
  "Take multiple vectors as menu-items.
 [[\"Exit\" (fn [e] (exit))]
  [:separator]
  [\"Open\" (fn [e] (open))]]"
  [&{:keys [items icons]}]
  (def tray-icon (make-tray-icon  sys-tray "BACnet Help - Stopped"))
  (add-to-popup-menu popup-menu items)
  (.setPopupMenu tray-icon popup-menu))
