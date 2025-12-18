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
  [{:_valid_from 0.1 :_valid_to 0.4 :_system_from 0.1 :color [59 130 246]}   ; blue
   {:_valid_from 0.2 :_valid_to 0.6 :_system_from 0.2 :color [34 197 94]}    ; green
   {:_valid_from 0.3 :_valid_to 0.7 :_system_from 0.3 :color [249 115 22]}   ; orange
   {:_valid_from 0.1 :_valid_to 0.5 :_system_from 0.4 :color [168 85 247]}   ; purple
   {:_valid_from 0.5 :_valid_to 0.9 :_system_from 0.5 :color [236 72 153]}]) ; pink

;; >> State

(def state (atom {:presence-count 0
                  :events sample-events
                  :canvas-ref nil
                  :show-grid false}))

;; Drag state (separate atom to avoid re-renders during drag)
(def drag-state (atom {:dragging nil      ; index of event being dragged
                       :offset-x 0        ; offset from click to event left edge
                       :offset-y 0}))     ; offset from click to event bottom

;; >> Canvas Management

(def PADDING 50) ;; Must match canvas.cljs config

(defn render-canvas! [canvas-el]
  (let [container (.-parentElement canvas-el)
        width (.-clientWidth container)
        height (.-clientHeight container)]
    (when (and (pos? width) (pos? height))
      (set! (.-width canvas-el) width)
      (set! (.-height canvas-el) height)
      (canvas/render-canvas! canvas-el
                             (:events @state)
                             {:show-grid (:show-grid @state)}))))

;; >> Drag and Drop

(defn canvas->normalized [canvas-el client-x client-y]
  "Convert canvas pixel coordinates to 0..1 normalized coordinates"
  (let [rect (.getBoundingClientRect canvas-el)
        canvas-x (- client-x (.-left rect))
        canvas-y (- client-y (.-top rect))
        width (.-width canvas-el)
        height (.-height canvas-el)
        draw-width (- width (* 2 PADDING))
        draw-height (- height (* 2 PADDING))
        norm-x (/ (- canvas-x PADDING) draw-width)
        norm-y (- 1 (/ (- canvas-y PADDING) draw-height))]
    {:x norm-x :y norm-y}))

(defn hit-test [events norm-x norm-y]
  "Find which event (if any) contains the point. Returns index or nil.
   Tests in reverse order (top events first) since later events are drawn on top."
  (let [sorted-indices (->> events
                            (map-indexed vector)
                            (sort-by #(:_system_from (second %)))
                            (map first)
                            reverse)]
    (first (filter (fn [idx]
                     (let [event (nth events idx)
                           {:keys [_valid_from _valid_to _system_from]} event]
                       (and (>= norm-x _valid_from)
                            (<= norm-x _valid_to)
                            (>= norm-y _system_from))))
                   sorted-indices))))

(defn on-mouse-down [canvas-el e]
  (let [{:keys [x y]} (canvas->normalized canvas-el (.-clientX e) (.-clientY e))
        events (:events @state)
        hit-idx (hit-test events x y)]
    (when hit-idx
      (let [event (nth events hit-idx)
            {:keys [_valid_from _system_from]} event]
        (reset! drag-state {:dragging hit-idx
                            :offset-x (- x _valid_from)
                            :offset-y (- _system_from y)})))))

(defn on-mouse-move [canvas-el e]
  (when-let [idx (:dragging @drag-state)]
    (let [{:keys [x y]} (canvas->normalized canvas-el (.-clientX e) (.-clientY e))
          {:keys [offset-x offset-y]} @drag-state
          events (:events @state)
          event (nth events idx)
          event-width (- (:_valid_to event) (:_valid_from event))
          ;; Calculate new position
          new-valid-from (- x offset-x)
          new-system-from (+ y offset-y)
          ;; Clamp to valid range
          new-valid-from (max 0 (min (- 1 event-width) new-valid-from))
          new-system-from (max 0 (min 1 new-system-from))
          new-valid-to (+ new-valid-from event-width)
          ;; Update event
          updated-event (assoc event
                               :_valid_from new-valid-from
                               :_valid_to new-valid-to
                               :_system_from new-system-from)
          updated-events (assoc (vec events) idx updated-event)]
      (swap! state assoc :events updated-events)
      (render-canvas! canvas-el))))

(defn on-mouse-up [_canvas-el _e]
  (reset! drag-state {:dragging nil :offset-x 0 :offset-y 0}))

(defn canvas-lifecycle [node lifecycle _data]
  (case lifecycle
    "mount" (let [resize-handler #(render-canvas! node)
                  mousedown-handler #(on-mouse-down node %)
                  mousemove-handler #(on-mouse-move node %)
                  mouseup-handler #(on-mouse-up node %)]
              (.addEventListener js/window "resize" resize-handler)
              (.addEventListener node "mousedown" mousedown-handler)
              (.addEventListener js/window "mousemove" mousemove-handler)
              (.addEventListener js/window "mouseup" mouseup-handler)
              (render-canvas! node)
              {:resize resize-handler
               :mousedown mousedown-handler
               :mousemove mousemove-handler
               :mouseup mouseup-handler})
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

(defn toggle-grid! []
  (swap! state update :show-grid not))

(defn side-panel []
  [:div {:class "w-64 bg-gray-800 text-white p-4 flex flex-col"}
   [:h1 {:class "text-xl font-bold mb-6"} "Bitemporal Visualizer"]
   [:label {:class "flex items-center gap-2 cursor-pointer"}
    [:input {:type "checkbox"
             :checked (:show-grid @state)
             :on-change toggle-grid!
             :class "w-4 h-4"}]
    [:span "Grid"]]])

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
