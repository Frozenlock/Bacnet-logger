(ns bacnet-logger.systray)

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


(defn make-tray-icon [sys-tray path description]
  (let [tray-icon (java.awt.TrayIcon.
                     (create-image path "Tray Icon")
                     description)]
    (.setImageAutoSize tray-icon true)
    (.add sys-tray tray-icon)
    tray-icon))


(defn system-tray-menu
  "Take multiple vectors as menu-items.
 [[\"Exit\" (fn [e] (exit))]
  [:separator]
  [\"Open\" (fn [e] (open))]]"
  [&{:keys [items icons]}]
  (when (java.awt.SystemTray/isSupported)
    (let [sys-tray (java.awt.SystemTray/getSystemTray)
          popup-menu (java.awt.PopupMenu.)
          tray-icon (make-tray-icon sys-tray (first icons) (second icons))]
      (add-to-popup-menu popup-menu items)
      (.setPopupMenu tray-icon popup-menu))))
