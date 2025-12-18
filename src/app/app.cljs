(ns app.app
  (:require ["reagami" :as reagami]
            ["./entry.css"]
            ["./ably.js" :as ably]
            ["./canvas.js" :as canvas]))

;; >> Configuration

(def ABLY_API_KEY "Vr1zhQ.IsKO2g:nY_BkeBtmxQxs0W_MYZNkx-34cZBzzNLOrrAARrygfQ")
(def CHANNEL_NAME "presence-demo")

;; >> Sample Bitemporal Events

(def sample-events
  [{:_valid_from 1 :_valid_to 4 :_system_from 1 :color [59 130 246]}   ; blue
   {:_valid_from 2 :_valid_to 6 :_system_from 2 :color [34 197 94]}    ; green
   {:_valid_from 3 :_valid_to 7 :_system_from 3 :color [249 115 22]}   ; orange
   {:_valid_from 1 :_valid_to 5 :_system_from 4 :color [168 85 247]}   ; purple
   {:_valid_from 5 :_valid_to 9 :_system_from 5 :color [236 72 153]}]) ; pink

;; >> State

(def state (atom {:presence-count 0
                  :events sample-events
                  :canvas-ref nil}))

;; >> Canvas Management

(defn render-canvas! [canvas-el]
  (let [container (.-parentElement canvas-el)
        width (.-clientWidth container)
        height (.-clientHeight container)]
    (when (and (pos? width) (pos? height))
      (set! (.-width canvas-el) width)
      (set! (.-height canvas-el) height)
      (canvas/render-canvas! canvas-el
                             (:events @state)
                             {:max-valid 10 :max-system 6}))))

(defn canvas-lifecycle [node lifecycle _data]
  (case lifecycle
    "mount" (let [resize-handler #(render-canvas! node)]
              (.addEventListener js/window "resize" resize-handler)
              (render-canvas! node)
              ;; Return resize handler so we can remove it on unmount
              resize-handler)
    "update" (render-canvas! node)
    "unmount" nil
    nil))

;; >> Components

(defn connection-pill []
  (let [status (ably/connection-status)]
    (when (not= status "connected")
      [:div {:class "fixed bottom-4 left-1/2 -translate-x-1/2 px-4 py-2 bg-yellow-500 text-white text-sm font-medium rounded-full shadow-lg animate-pulse z-10"}
       (case status
         "initialized" "Connecting..."
         "connecting" "Connecting..."
         "disconnected" "Disconnected"
         "suspended" "Connection suspended"
         "failed" "Connection failed"
         "closing" "Closing..."
         "closed" "Closed"
         (str "Status: " status))])))

(defn presence-indicator []
  (let [{:keys [presence-count]} @state]
    [:div {:class "fixed bottom-4 right-4 px-3 py-2 bg-white rounded-lg shadow-md text-sm text-gray-600 z-10"}
     [:span {:class "inline-flex items-center gap-2"}
      [:span {:class "w-2 h-2 bg-green-500 rounded-full"}]
      (str presence-count " " (if (= presence-count 1) "person" "people") " online")]]))

(defn side-panel []
  [:div {:class "w-64 bg-gray-800 text-white p-4 flex flex-col"}
   [:h1 {:class "text-xl font-bold"} "Bitemporal Visualizer"]])

(defn main-canvas []
  [:div {:class "flex-1 bg-gray-100 p-4"}
   [:div {:class "w-full h-full bg-white rounded-lg shadow-md overflow-hidden"}
    [:canvas {:on-render canvas-lifecycle
              :class "w-full h-full"}]]])

(defn app []
  [:div {:class "h-screen flex"}
   [side-panel]
   [main-canvas]
   [presence-indicator]
   [connection-pill]])

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
