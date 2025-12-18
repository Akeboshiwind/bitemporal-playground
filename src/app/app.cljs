(ns app.app
  (:require ["reagami" :as reagami]
            ["./entry.css"]
            ["./ably.js" :as ably]))

;; >> Configuration

(def ABLY_API_KEY "Vr1zhQ.IsKO2g:nY_BkeBtmxQxs0W_MYZNkx-34cZBzzNLOrrAARrygfQ")
(def CHANNEL_NAME "presence-demo")

;; >> State

(def state (atom {:presence-count 0}))

;; >> Components

(defn connection-pill []
  (let [status (ably/connection-status)]
    (when (not= status "connected")
      [:div {:class "fixed bottom-4 left-1/2 -translate-x-1/2 px-4 py-2 bg-yellow-500 text-white text-sm font-medium rounded-full shadow-lg animate-pulse"}
       (case status
         "initialized" "Connecting..."
         "connecting" "Connecting..."
         "disconnected" "Disconnected"
         "suspended" "Connection suspended"
         "failed" "Connection failed"
         "closing" "Closing..."
         "closed" "Closed"
         (str "Status: " status))])))

(defn app []
  (let [{:keys [presence-count]} @state]
    [:div {:class "min-h-screen bg-gray-100 flex items-center justify-center"}
     [:div {:class "bg-white p-8 rounded-lg shadow-md text-center"}
      [:h1 {:class "text-2xl font-bold mb-4"} "Hello from Squint!"]
      [:p {:class "text-gray-600 mb-4"}
       [:span {:class "inline-flex items-center gap-2"}
        [:span {:class "w-2 h-2 bg-green-500 rounded-full"}]
        (str presence-count " " (if (= presence-count 1) "person" "people") " online")]]]
     [connection-pill]]))

;; >> Render

(defn render []
  (reagami/render (js/document.getElementById "app") [app]))

(add-watch state ::render (fn [_ _ _ _] (render)))
(add-watch ably/state ::render-ably (fn [_ _ _ _] (render)))

;; >> Presence

(defn update-presence-count! []
  (ably/get-presence-members CHANNEL_NAME
                             (fn [members]
                               (swap! state assoc :presence-count (count members)))))

;; >> Init

(defn init! []
  (render)
  (ably/init! ABLY_API_KEY)
  (ably/enter-presence! CHANNEL_NAME)
  (ably/on-presence-change! CHANNEL_NAME update-presence-count!))

(init!)
