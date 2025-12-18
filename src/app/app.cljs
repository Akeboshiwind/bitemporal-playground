(ns app.app
  (:require ["reagami" :as reagami]
            ["./entry.css"]
            ["./ably.js" :as ably]
            ["./canvas.js" :as canvas]))

;; >> Configuration

(def ABLY_API_KEY "Vr1zhQ.IsKO2g:nY_BkeBtmxQxs0W_MYZNkx-34cZBzzNLOrrAARrygfQ")
(def CHANNEL_NAME "presence-demo")
(def STORAGE_KEY "bitemporal-visualizer")

;; >> Sample Bitemporal Events

(def default-events
  [{:_valid_from 0.1 :_valid_to 0.4 :_system_from 0.1 :color [59 130 246]}   ; blue
   {:_valid_from 0.2 :_valid_to 0.6 :_system_from 0.2 :color [34 197 94]}    ; green
   {:_valid_from 0.3 :_valid_to 0.7 :_system_from 0.3 :color [249 115 22]}   ; orange
   {:_valid_from 0.1 :_valid_to 0.5 :_system_from 0.4 :color [168 85 247]}   ; purple
   {:_valid_from 0.5 :_valid_to 0.9 :_system_from 0.5 :color [236 72 153]}]) ; pink

(def default-settings
  {:show-grid false})

;; >> LocalStorage

(defn save-to-storage! [data]
  (js/localStorage.setItem STORAGE_KEY (js/JSON.stringify data)))

(defn load-from-storage []
  (when-let [stored (js/localStorage.getItem STORAGE_KEY)]
    (js/JSON.parse stored)))

(defn get-persisted-state []
  (let [stored (load-from-storage)]
    {:events (or (:events stored) default-events)
     :show-grid (get stored :show-grid (:show-grid default-settings))}))

;; >> State

(def initial-persisted (get-persisted-state))

(def state (atom {:presence-count 0
                  :events (:events initial-persisted)
                  :canvas-ref nil
                  :show-grid (:show-grid initial-persisted)
                  :current-tool :select      ; :select or :rectangle
                  :auto-select true}))       ; switch to select after drawing

;; Save to localStorage when events or settings change
(add-watch state ::persist
           (fn [_ _ old-state new-state]
             (when (or (not= (:events old-state) (:events new-state))
                       (not= (:show-grid old-state) (:show-grid new-state)))
               (save-to-storage! {:events (:events new-state)
                                  :show-grid (:show-grid new-state)}))))

;; Drag state (separate atom to avoid re-renders during drag)
(def drag-state (atom {:dragging nil       ; index of event being dragged
                       :mode nil           ; :move, :move-multi, :resize, or :select
                       :offset-x 0         ; offset from click to event left edge
                       :offset-y 0         ; offset from click to event bottom
                       :multi-offsets nil  ; map of idx -> {offset-x, offset-y} for multi-drag
                       :select-start nil   ; {x, y} start of selection box
                       :select-end nil     ; {x, y} end of selection box
                       :selected #{}}))    ; set of selected event indices

;; Context menu state
(def context-menu (atom {:open false
                         :x 0              ; screen x position
                         :y 0              ; screen y position
                         :target-indices #{}})) ; event indices to modify

(defn close-context-menu! []
  (reset! context-menu {:open false :x 0 :y 0 :target-indices #{}}))

;; >> Canvas Management

(def PADDING 50)        ;; Must match canvas.cljs config
(def HANDLE_WIDTH 8)    ;; Must match canvas.cljs config
(def MIN_DURATION 0.05) ;; Minimum event duration in normalized units

(defn render-canvas! [canvas-el]
  (let [container (.-parentElement canvas-el)
        width (.-clientWidth container)
        height (.-clientHeight container)
        {:keys [mode select-start select-end selected]} @drag-state]
    (when (and (pos? width) (pos? height))
      (set! (.-width canvas-el) width)
      (set! (.-height canvas-el) height)
      (canvas/render-canvas! canvas-el
                             (:events @state)
                             {:show-grid (:show-grid @state)
                              :selection-box (when (or (= mode :select) (= mode :draw))
                                               {:start select-start :end select-end})
                              :selected selected}))))

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

(defn hit-test [canvas-el events norm-x norm-y]
  "Find which event (if any) contains the point. Returns {:index idx :on-handle bool} or nil.
   Tests in reverse order (top events first) since later events are drawn on top."
  (let [width (.-width canvas-el)
        draw-width (- width (* 2 PADDING))
        handle-norm-width (/ HANDLE_WIDTH draw-width)
        sorted-indices (->> events
                            (map-indexed vector)
                            (sort-by #(:_system_from (second %)))
                            (map first)
                            reverse)]
    (first (keep (fn [idx]
                   (let [event (nth events idx)
                         {:keys [_valid_from _valid_to _system_from]} event
                         open? (nil? _valid_to)
                         effective-valid-to (if open? 1 _valid_to)]
                     (when (and (>= norm-x _valid_from)
                                (<= norm-x effective-valid-to)
                                (>= norm-y _system_from))
                       {:index idx
                        ;; Open events have no handle
                        :on-handle (and (not open?)
                                        (>= norm-x (- effective-valid-to handle-norm-width)))})))
                 sorted-indices))))

(defn on-mouse-down [canvas-el e]
  ;; Close context menu on any click
  (when (:open @context-menu)
    (close-context-menu!))
  (let [{:keys [x y]} (canvas->normalized canvas-el (.-clientX e) (.-clientY e))
        events (:events @state)
        currently-selected (:selected @drag-state)
        current-tool (:current-tool @state)]
    ;; Rectangle tool always draws, regardless of what's underneath
    (if (= current-tool :rectangle)
      (reset! drag-state {:dragging nil
                          :mode :draw
                          :offset-x 0
                          :offset-y 0
                          :select-start {:x x :y y}
                          :select-end {:x x :y y}
                          :selected #{}})
      ;; Select tool - check for hits
      (let [hit (hit-test canvas-el events x y)]
        (if hit
          (let [{:keys [index on-handle]} hit
                event (nth events index)
                {:keys [_valid_from _valid_to _system_from]} event]
            (if on-handle
              ;; Check if resizing a selected event - if so, resize all selected
              (if (and (contains? currently-selected index) (> (count currently-selected) 1))
                ;; Multi-resize: store original positions for all selected events
                (let [selected-events (map #(nth events %) currently-selected)
                      min-valid-from (apply min (map :_valid_from selected-events))
                      max-valid-to (apply max (map :_valid_to selected-events))
                      original-span (- max-valid-to min-valid-from)
                      original-positions (into {}
                                           (map (fn [idx]
                                                  (let [evt (nth events idx)]
                                                    [idx {:_valid_from (:_valid_from evt)
                                                          :_valid_to (:_valid_to evt)}]))
                                                currently-selected))]
                  (swap! drag-state assoc
                         :dragging index
                         :mode :resize-multi
                         :offset-x (- _valid_to x)
                         :anchor-point min-valid-from
                         :original-span original-span
                         :original-positions original-positions))
                ;; Single resize
                (swap! drag-state assoc
                       :dragging index
                       :mode :resize
                       :offset-x (- _valid_to x)
                       :offset-y 0))
              ;; Check if clicking on a selected event - if so, drag all selected
              (if (and (contains? currently-selected index) (> (count currently-selected) 1))
                ;; Multi-drag: store offsets for all selected events
                (let [offsets (into {}
                                (map (fn [idx]
                                       (let [evt (nth events idx)]
                                         [idx {:offset-x (- x (:_valid_from evt))
                                               :offset-y (- (:_system_from evt) y)}]))
                                     currently-selected))]
                  (swap! drag-state assoc
                         :dragging index
                         :mode :move-multi
                         :multi-offsets offsets))
                ;; Single drag
                (swap! drag-state assoc
                       :dragging index
                       :mode :move
                       :offset-x (- x _valid_from)
                       :offset-y (- _system_from y)
                       :selected #{}))))
          ;; Clicked on empty space - start selection
          (reset! drag-state {:dragging nil
                              :mode :select
                              :offset-x 0
                              :offset-y 0
                              :select-start {:x x :y y}
                              :select-end {:x x :y y}
                              :selected #{}}))))))

(defn events-in-selection [events select-start select-end]
  "Find indices of events that intersect with the selection box"
  (let [min-x (min (:x select-start) (:x select-end))
        max-x (max (:x select-start) (:x select-end))
        min-y (min (:y select-start) (:y select-end))
        max-y (max (:y select-start) (:y select-end))]
    (set (keep-indexed
          (fn [idx event]
            (let [{:keys [_valid_from _valid_to _system_from]} event
                  effective-valid-to (if (nil? _valid_to) 1 _valid_to)]
              ;; Event intersects if its x range overlaps selection x range
              ;; and its _system_from is within selection y range
              ;; (since events extend to infinity upward, we check if _system_from <= max-y)
              (when (and (< _valid_from max-x)
                         (> effective-valid-to min-x)
                         (<= _system_from max-y)
                         (>= 1 min-y)) ; event goes to top, so always >= min-y if _system_from <= max-y
                idx)))
          events))))

(defn on-mouse-move [canvas-el e]
  (let [{:keys [x y]} (canvas->normalized canvas-el (.-clientX e) (.-clientY e))
        {:keys [dragging mode offset-x offset-y select-start multi-offsets selected]} @drag-state]
    ;; Update cursor based on mode and what we're hovering over
    (let [current-tool (:current-tool @state)]
      (cond
        ;; Rectangle tool always shows crosshair
        (= current-tool :rectangle)
        (set! (.-cursor (.-style canvas-el)) "crosshair")

        (= mode :select)
        (set! (.-cursor (.-style canvas-el)) "crosshair")

        dragging
        (set! (.-cursor (.-style canvas-el))
              (if (or (= mode :resize) (= mode :resize-multi)) "ew-resize" "move"))

        :else
        (let [hit (hit-test canvas-el (:events @state) x y)]
          (set! (.-cursor (.-style canvas-el))
                (cond
                  (and hit (:on-handle hit)) "ew-resize"
                  hit "move"
                  :else "crosshair")))))
    ;; Handle different modes
    (cond
      ;; Draw mode (rectangle tool)
      (= mode :draw)
      (do
        (swap! drag-state assoc :select-end {:x x :y y})
        (render-canvas! canvas-el))

      ;; Selection mode
      (= mode :select)
      (let [events (:events @state)
            selected (events-in-selection events select-start {:x x :y y})]
        (swap! drag-state assoc
               :select-end {:x x :y y}
               :selected selected)
        (render-canvas! canvas-el))

      ;; Single resize mode
      (and dragging (= mode :resize))
      (let [events (:events @state)
            event (nth events dragging)
            new-valid-to (+ x offset-x)
            min-valid-to (+ (:_valid_from event) MIN_DURATION)
            new-valid-to (max min-valid-to (min 1 new-valid-to))
            updated-event (assoc event :_valid_to new-valid-to)
            updated-events (assoc (vec events) dragging updated-event)]
        (swap! state assoc :events updated-events)
        (render-canvas! canvas-el))

      ;; Multi-resize mode - scale all selected events proportionally
      (and dragging (= mode :resize-multi))
      (let [{:keys [anchor-point original-span original-positions]} @drag-state
            events (vec (:events @state))
            ;; Calculate new max valid-to based on drag
            new-max-valid-to (+ x offset-x)
            new-max-valid-to (max (+ anchor-point MIN_DURATION) (min 1 new-max-valid-to))
            ;; Calculate scale factor
            new-span (- new-max-valid-to anchor-point)
            scale (/ new-span original-span)
            ;; Apply scale to all selected events
            updated-events (reduce
                            (fn [evts idx]
                              (let [event (nth evts idx)
                                    orig-pos (get original-positions idx)
                                    orig-from (:_valid_from orig-pos)
                                    orig-to (:_valid_to orig-pos)
                                    ;; Scale positions relative to anchor point
                                    new-from (+ anchor-point (* (- orig-from anchor-point) scale))
                                    new-to (+ anchor-point (* (- orig-to anchor-point) scale))
                                    ;; Ensure minimum duration
                                    new-to (max (+ new-from MIN_DURATION) new-to)
                                    updated-event (assoc event
                                                         :_valid_from new-from
                                                         :_valid_to (min 1 new-to))]
                                (assoc evts idx updated-event)))
                            events
                            (keys original-positions))]
        (swap! state assoc :events updated-events)
        (render-canvas! canvas-el))

      ;; Multi-move mode - move all selected events together
      (and dragging (= mode :move-multi))
      (let [events (vec (:events @state))
            updated-events (reduce
                            (fn [evts idx]
                              (let [event (nth evts idx)
                                    {:keys [offset-x offset-y]} (get multi-offsets idx)
                                    open? (nil? (:_valid_to event))
                                    event-width (if open? 0 (- (:_valid_to event) (:_valid_from event)))
                                    new-valid-from (- x offset-x)
                                    new-system-from (+ y offset-y)
                                    new-valid-from (max 0 (min (- 1 event-width) new-valid-from))
                                    new-system-from (max 0 (min 1 new-system-from))
                                    updated-event (if open?
                                                    (assoc event
                                                           :_valid_from new-valid-from
                                                           :_system_from new-system-from)
                                                    (assoc event
                                                           :_valid_from new-valid-from
                                                           :_valid_to (+ new-valid-from event-width)
                                                           :_system_from new-system-from))]
                                (assoc evts idx updated-event)))
                            events
                            (keys multi-offsets))]
        (swap! state assoc :events updated-events)
        (render-canvas! canvas-el))

      ;; Single move mode
      (and dragging (= mode :move))
      (let [events (:events @state)
            event (nth events dragging)
            open? (nil? (:_valid_to event))
            event-width (if open? 0 (- (:_valid_to event) (:_valid_from event)))
            new-valid-from (- x offset-x)
            new-system-from (+ y offset-y)
            new-valid-from (max 0 (min (- 1 event-width) new-valid-from))
            new-system-from (max 0 (min 1 new-system-from))
            updated-event (if open?
                            (assoc event
                                   :_valid_from new-valid-from
                                   :_system_from new-system-from)
                            (assoc event
                                   :_valid_from new-valid-from
                                   :_valid_to (+ new-valid-from event-width)
                                   :_system_from new-system-from))
            updated-events (assoc (vec events) dragging updated-event)]
        (swap! state assoc :events updated-events)
        (render-canvas! canvas-el)))))

(def default-new-event-color [100 116 139]) ; slate-500

(defn create-event-from-drag! [select-start select-end]
  (let [min-x (min (:x select-start) (:x select-end))
        max-x (max (:x select-start) (:x select-end))
        min-y (min (:y select-start) (:y select-end))
        ;; Ensure minimum size
        width (- max-x min-x)
        valid-from (max 0 min-x)
        valid-to (min 1 (+ valid-from (max MIN_DURATION width)))
        system-from (max 0 (min 1 min-y))
        new-event {:_valid_from valid-from
                   :_valid_to valid-to
                   :_system_from system-from
                   :color default-new-event-color}]
    (swap! state update :events conj new-event)))

(defn on-mouse-up [canvas-el _e]
  (let [{:keys [mode select-start select-end selected]} @drag-state]
    ;; If in draw mode with valid drag, create new event
    (when (and (= mode :draw) select-start select-end)
      (let [width (js/Math.abs (- (:x select-end) (:x select-start)))
            height (js/Math.abs (- (:y select-end) (:y select-start)))]
        ;; Only create if dragged a meaningful distance
        (when (or (> width 0.02) (> height 0.02))
          (create-event-from-drag! select-start select-end)
          ;; Switch back to select tool if auto-select is enabled
          (when (:auto-select @state)
            (swap! state assoc :current-tool :select)))))
    (reset! drag-state {:dragging nil
                        :mode nil
                        :offset-x 0
                        :offset-y 0
                        :select-start nil
                        :select-end nil
                        :selected selected})
    (render-canvas! canvas-el)))

(defn on-context-menu [canvas-el e]
  (.preventDefault e)
  (let [{:keys [x y]} (canvas->normalized canvas-el (.-clientX e) (.-clientY e))
        events (:events @state)
        hit (hit-test canvas-el events x y)
        currently-selected (:selected @drag-state)]
    (when hit
      (let [clicked-idx (:index hit)
            ;; If clicked event is in selection, target all selected; otherwise just the clicked one
            target-indices (if (contains? currently-selected clicked-idx)
                             currently-selected
                             #{clicked-idx})]
        (reset! context-menu {:open true
                              :x (.-clientX e)
                              :y (.-clientY e)
                              :target-indices target-indices})))))

(defn canvas-lifecycle [node lifecycle _data]
  (case lifecycle
    "mount" (let [resize-handler #(render-canvas! node)
                  mousedown-handler #(on-mouse-down node %)
                  mousemove-handler #(on-mouse-move node %)
                  mouseup-handler #(on-mouse-up node %)
                  contextmenu-handler #(on-context-menu node %)]
              (.addEventListener js/window "resize" resize-handler)
              (.addEventListener node "mousedown" mousedown-handler)
              (.addEventListener js/window "mousemove" mousemove-handler)
              (.addEventListener js/window "mouseup" mouseup-handler)
              (.addEventListener node "contextmenu" contextmenu-handler)
              (render-canvas! node)
              {:resize resize-handler
               :mousedown mousedown-handler
               :mousemove mousemove-handler
               :mouseup mouseup-handler
               :contextmenu contextmenu-handler})
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

;; >> Context Menu

(def preset-colors
  [[59 130 246]    ; blue
   [34 197 94]     ; green
   [249 115 22]    ; orange
   [168 85 247]    ; purple
   [236 72 153]])  ; pink

(defn rgb->css [[r g b]]
  (str "rgb(" r "," g "," b ")"))

(defn rgb->hex [[r g b]]
  (str "#"
       (.padStart (.toString r 16) 2 "0")
       (.padStart (.toString g 16) 2 "0")
       (.padStart (.toString b 16) 2 "0")))

(defn hex->rgb [hex]
  (let [hex (if (= (first hex) "#") (subs hex 1) hex)
        r (js/parseInt (subs hex 0 2) 16)
        g (js/parseInt (subs hex 2 4) 16)
        b (js/parseInt (subs hex 4 6) 16)]
    [r g b]))

(defn set-event-colors! [indices color]
  (let [events (vec (:events @state))
        updated-events (reduce
                        (fn [evts idx]
                          (update evts idx assoc :color color))
                        events
                        indices)]
    (swap! state assoc :events updated-events))
  (close-context-menu!))

(defn toggle-events-open! [indices make-open?]
  (let [events (vec (:events @state))
        updated-events (reduce
                        (fn [evts idx]
                          (let [event (nth evts idx)]
                            (if make-open?
                              (assoc evts idx (assoc event :_valid_to nil))
                              ;; When closing, set _valid_to to 1.0 (right edge)
                              ;; or maintain a minimum width from _valid_from
                              (let [new-valid-to (min 1 (+ (:_valid_from event) 0.2))]
                                (assoc evts idx (assoc event :_valid_to new-valid-to))))))
                        events
                        indices)]
    (swap! state assoc :events updated-events)))

(defn color-menu []
  (let [{:keys [open x y target-indices]} @context-menu
        events (:events @state)
        ;; Check if all targeted events are open
        all-open? (and (seq target-indices)
                       (every? #(nil? (:_valid_to (nth events %))) target-indices))]
    (when open
      [:div {:class "fixed bg-white rounded-lg shadow-xl border border-gray-200 p-2 z-50"
             :style {:left (str x "px")
                     :top (str y "px")}}
       ;; Color swatches row
       [:div {:class "flex gap-1 items-center"}
        (for [color preset-colors]
          [:button {:key (rgb->css color)
                    :class "w-6 h-6 rounded border border-gray-300 hover:scale-110 transition-transform cursor-pointer"
                    :style {:background-color (rgb->css color)}
                    :on-click #(set-event-colors! target-indices color)}])
        [:input {:type "color"
                 :class "w-6 h-6 rounded cursor-pointer border-0 p-0"
                 :on-change #(set-event-colors! target-indices (hex->rgb (.-value (.-target %))))}]]
       ;; Separator
       [:div {:class "h-px bg-gray-200 my-2"}]
       ;; Open checkbox
       [:label {:class "flex items-center gap-2 text-sm text-gray-700 cursor-pointer select-none px-1"}
        [:input {:type "checkbox"
                 :checked all-open?
                 :on-change #(toggle-events-open! target-indices (not all-open?))
                 :class "w-4 h-4 cursor-pointer"}]
        "Open"]])))

(defn set-tool! [tool]
  (swap! state assoc :current-tool tool))

(defn toolbar []
  (let [current-tool (:current-tool @state)]
    [:div {:class "absolute top-2 left-2 bg-white rounded-lg shadow-lg border border-gray-200 p-1 flex flex-col gap-1 z-10"}
     ;; Select tool
     [:button {:class (str "w-8 h-8 rounded flex items-center justify-center transition-colors "
                           (if (= current-tool :select)
                             "bg-blue-500 text-white"
                             "hover:bg-gray-100 text-gray-600"))
               :title "Select"
               :on-click #(set-tool! :select)}
      [:svg {:width "16" :height "16" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
       [:path {:d "M3 3l7.07 16.97 2.51-7.39 7.39-2.51L3 3z"}]
       [:path {:d "M13 13l6 6"}]]]
     ;; Rectangle tool
     [:button {:class (str "w-8 h-8 rounded flex items-center justify-center transition-colors "
                           (if (= current-tool :rectangle)
                             "bg-blue-500 text-white"
                             "hover:bg-gray-100 text-gray-600"))
               :title "Rectangle"
               :on-click #(set-tool! :rectangle)}
      [:svg {:width "16" :height "16" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
       [:rect {:x "3" :y "3" :width "18" :height "18" :rx "2"}]]]]))

(defn toggle-auto-select! []
  (swap! state update :auto-select not))

(defn side-panel []
  [:div {:class "w-64 bg-gray-800 text-white p-4 flex flex-col"}
   [:h1 {:class "text-xl font-bold mb-6"} "Bitemporal Visualizer"]
   [:div {:class "flex flex-col gap-2"}
    [:label {:class "flex items-center gap-2 cursor-pointer"}
     [:input {:type "checkbox"
              :checked (:show-grid @state)
              :on-change toggle-grid!
              :class "w-4 h-4"}]
     [:span "Grid"]]
    [:label {:class "flex items-center gap-2 cursor-pointer"}
     [:input {:type "checkbox"
              :checked (:auto-select @state)
              :on-change toggle-auto-select!
              :class "w-4 h-4"}]
     [:span "Auto-select after draw"]]]])

(defn main-canvas []
  [:div {:class "flex-1 bg-gray-100 p-4"}
   [:div {:class "relative w-full h-full bg-white rounded-lg shadow-md overflow-hidden"}
    [:canvas {:on-render canvas-lifecycle
              :class "w-full h-full"}]
    [toolbar]]])

(defn app []
  [:div {:class "h-screen flex"}
   [side-panel]
   [main-canvas]
   [presence-indicator]
   [connection-pill]
   [color-menu]])

;; >> Render

(defn render []
  (reagami/render (js/document.getElementById "app") [app]))

(add-watch state ::render (fn [_ _ _ _] (render)))
(add-watch ably/state ::render-ably (fn [_ _ _ _] (render)))
(add-watch context-menu ::render-context-menu (fn [_ _ _ _] (render)))

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
