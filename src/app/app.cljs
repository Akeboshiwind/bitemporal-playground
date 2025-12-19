(ns app.app
  (:require ["reagami" :as reagami]
            ["./entry.css"]
            ["./ably.js" :as ably]
            ["./canvas.js" :as canvas]))

;; >> Configuration

(def ABLY_API_KEY "Vr1zhQ.IsKO2g:nY_BkeBtmxQxs0W_MYZNkx-34cZBzzNLOrrAARrygfQ")
(def GLOBAL_CHANNEL "global")
(def STORAGE_KEY "bitemporal-visualizer")
(def SAVED_STATES_KEY "bitemporal-saved-states")

;; >> Room Code Generation

(defn generate-room-code []
  (let [chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" ; no I, O, 0, 1 to avoid confusion
        len 6]
    (apply str (repeatedly len #(nth chars (rand-int (count chars)))))))

(defn room-channel-name [code]
  (str "room:" code))

;; >> Sample Bitemporal Events

(def default-events
  [{:_valid_from 0.1 :_valid_to 0.4 :_system_from 0.1 :color [59 130 246]}   ; blue
   {:_valid_from 0.2 :_valid_to 0.6 :_system_from 0.2 :color [34 197 94]}    ; green
   {:_valid_from 0.3 :_valid_to 0.7 :_system_from 0.3 :color [249 115 22]}   ; orange
   {:_valid_from 0.1 :_valid_to 0.5 :_system_from 0.4 :color [168 85 247]}   ; purple
   {:_valid_from 0.5 :_valid_to 0.9 :_system_from 0.5 :color [236 72 153]}]) ; pink

(def default-settings
  {:show-grid false
   :snap-to-grid false
   :show-ticks false})

;; >> LocalStorage

(defn save-to-storage! [data]
  (js/localStorage.setItem STORAGE_KEY (js/JSON.stringify data)))

(defn load-from-storage []
  (when-let [stored (js/localStorage.getItem STORAGE_KEY)]
    (js/JSON.parse stored)))

(defn get-persisted-state []
  (let [stored (load-from-storage)]
    {:events (or (:events stored) default-events)
     :points (or (:points stored) [])
     :show-grid (get stored :show-grid (:show-grid default-settings))
     :snap-to-grid (get stored :snap-to-grid (:snap-to-grid default-settings))
     :show-ticks (get stored :show-ticks (:show-ticks default-settings))}))

;; >> Saved States Storage

(defn save-saved-states! [saved-states]
  (js/localStorage.setItem SAVED_STATES_KEY (js/JSON.stringify saved-states)))

(defn load-saved-states []
  (when-let [stored (js/localStorage.getItem SAVED_STATES_KEY)]
    (js/JSON.parse stored)))

;; >> State

(def initial-persisted (get-persisted-state))
(def initial-saved-states (or (load-saved-states) []))

(def state (atom {:events (:events initial-persisted)
                  :points (:points initial-persisted)
                  :show-grid (:show-grid initial-persisted)
                  :snap-to-grid (:snap-to-grid initial-persisted)
                  :show-ticks (:show-ticks initial-persisted)
                  :current-tool :select      ; :select, :rectangle, or :point
                  :auto-select true          ; switch to select after drawing
                  :room-code (generate-room-code)
                  :room-count 0              ; users in current room
                  :global-count 0            ; total users online
                  :syncing false             ; true when receiving remote state
                  :saved-states initial-saved-states ; list of saved canvas states
                  :dml-start-date "2024-01-01"
                  :dml-end-date "2025-01-01"}))

;; Canvas ref stored separately to avoid re-render loops
(def canvas-ref (atom nil))

;; Save to localStorage when events, points, or settings change
(add-watch state ::persist
           (fn [_ _ old-state new-state]
             (when (or (not= (:events old-state) (:events new-state))
                       (not= (:points old-state) (:points new-state))
                       (not= (:show-grid old-state) (:show-grid new-state))
                       (not= (:snap-to-grid old-state) (:snap-to-grid new-state))
                       (not= (:show-ticks old-state) (:show-ticks new-state)))
               (save-to-storage! {:events (:events new-state)
                                  :points (:points new-state)
                                  :show-grid (:show-grid new-state)
                                  :snap-to-grid (:snap-to-grid new-state)
                                  :show-ticks (:show-ticks new-state)}))))

;; Save saved-states to localStorage when they change
(add-watch state ::persist-saved-states
           (fn [_ _ old-state new-state]
             (when (not= (:saved-states old-state) (:saved-states new-state))
               (save-saved-states! (:saved-states new-state)))))

;; Drag state (separate atom to avoid re-renders during drag)
(def drag-state (atom {:dragging nil         ; index of event being dragged
                       :dragging-point nil   ; index of point being dragged
                       :mode nil             ; :move, :move-multi, :resize, :select, :move-point, :move-points
                       :offset-x 0           ; offset from click to event left edge
                       :offset-y 0           ; offset from click to event bottom
                       :multi-offsets nil    ; map of idx -> {offset-x, offset-y} for multi-drag
                       :point-offsets nil    ; map of idx -> {offset-x, offset-y} for point drag
                       :select-start nil     ; {x, y} start of selection box
                       :select-end nil       ; {x, y} end of selection box
                       :selected #{}         ; set of selected event indices
                       :selected-points #{}})) ; set of selected point indices

;; Context menu state
(def context-menu (atom {:open false
                         :x 0                    ; screen x position
                         :y 0                    ; screen y position
                         :target-indices #{}     ; event indices to modify
                         :target-point-indices #{}})) ; point indices to modify

(defn close-context-menu! []
  (reset! context-menu {:open false :x 0 :y 0 :target-indices #{} :target-point-indices #{}}))

;; >> Canvas Management

(def PADDING 60)        ;; Must match canvas.cljs config
(def HANDLE_WIDTH 8)    ;; Must match canvas.cljs config
(def POINT_RADIUS 8)    ;; Must match canvas.cljs config
(def MIN_DURATION 0.05) ;; Minimum event duration in normalized units
(def GRID_DIVISIONS 10) ;; Must match canvas.cljs config

(defn snap-to-grid-value [v]
  "Round a normalized 0..1 value to the nearest grid line (0.1 increments)"
  (let [step (/ 1.0 GRID_DIVISIONS)]
    (* step (js/Math.round (/ v step)))))

(defn render-canvas! [canvas-el]
  (let [container (.-parentElement canvas-el)
        width (.-clientWidth container)
        height (.-clientHeight container)
        {:keys [mode select-start select-end selected selected-points]} @drag-state]
    (when (and (pos? width) (pos? height))
      (set! (.-width canvas-el) width)
      (set! (.-height canvas-el) height)
      (canvas/render-canvas! canvas-el
                             (:events @state)
                             {:show-grid (:show-grid @state)
                              :show-ticks (:show-ticks @state)
                              :selection-box (when (or (= mode :select) (= mode :draw))
                                               {:start select-start :end select-end})
                              :selected selected
                              :points (:points @state)
                              :selected-points selected-points}))))

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

(defn point-hit-test [canvas-el points norm-x norm-y]
  "Find which point (if any) is at the click location. Returns {:index idx} or nil.
   Tests in reverse order (later points are drawn on top)."
  (let [width (.-width canvas-el)
        height (.-height canvas-el)
        draw-width (- width (* 2 PADDING))
        draw-height (- height (* 2 PADDING))
        ;; Convert radius to normalized coordinates (average of x and y scale)
        norm-radius-x (/ POINT_RADIUS draw-width)
        norm-radius-y (/ POINT_RADIUS draw-height)]
    (first (keep (fn [idx]
                   (let [point (nth points idx)
                         {:keys [x y]} point
                         dx (- norm-x x)
                         dy (- norm-y y)
                         ;; Use elliptical distance to handle non-square aspect ratio
                         dist-sq (+ (/ (* dx dx) (* norm-radius-x norm-radius-x))
                                    (/ (* dy dy) (* norm-radius-y norm-radius-y)))]
                     (when (<= dist-sq 1)
                       {:index idx})))
                 (reverse (range (count points)))))))

(defn on-mouse-down [canvas-el e]
  ;; Close context menu on any click
  (when (:open @context-menu)
    (close-context-menu!))
  ;; Only handle left-clicks (button 0) for drag operations
  (when (zero? (.-button e))
    (let [{:keys [x y]} (canvas->normalized canvas-el (.-clientX e) (.-clientY e))
          events (:events @state)
          points (:points @state)
          currently-selected (:selected @drag-state)
          currently-selected-points (:selected-points @drag-state)
          current-tool (:current-tool @state)]
      (cond
        ;; Rectangle tool always draws, regardless of what's underneath
        (= current-tool :rectangle)
        (reset! drag-state {:dragging nil
                            :dragging-point nil
                            :mode :draw
                            :offset-x 0
                            :offset-y 0
                            :select-start {:x x :y y}
                            :select-end {:x x :y y}
                            :selected #{}
                            :selected-points #{}})

        ;; Point tool - place a point
        (= current-tool :point)
        (do
          (swap! state update :points conj {:x x :y y :color default-new-event-color})
          ;; Switch back to select tool if auto-select is enabled
          (when (:auto-select @state)
            (swap! state assoc :current-tool :select))
          (render-canvas! canvas-el))

        ;; Select tool - check for hits (points first since they're on top)
        :else
        (let [point-hit (point-hit-test canvas-el points x y)
              event-hit (hit-test canvas-el events x y)]
          (cond
            ;; Hit a point
            point-hit
            (let [{:keys [index]} point-hit
                  point (nth points index)]
              ;; Check if clicking on a selected point - if so, drag all selected points
              (if (and (contains? currently-selected-points index) (> (count currently-selected-points) 1))
                ;; Multi-drag points: store offsets for all selected points
                (let [offsets (into {}
                                (map (fn [idx]
                                       (let [p (nth points idx)]
                                         [idx {:offset-x (- x (:x p))
                                               :offset-y (- y (:y p))}]))
                                     currently-selected-points))]
                  (swap! drag-state assoc
                         :dragging-point index
                         :mode :move-points
                         :point-offsets offsets))
                ;; Single point drag - also select the clicked point
                (swap! drag-state assoc
                       :dragging-point index
                       :mode :move-point
                       :offset-x (- x (:x point))
                       :offset-y (- y (:y point))
                       :selected #{}
                       :selected-points #{index})))

            ;; Hit an event
            event-hit
            (let [{:keys [index on-handle]} event-hit
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
                  ;; Single drag - also select the clicked event
                  (swap! drag-state assoc
                         :dragging index
                         :mode :move
                         :offset-x (- x _valid_from)
                         :offset-y (- _system_from y)
                         :selected #{index}
                         :selected-points #{}))))

            ;; Clicked on empty space - start selection
            :else
            (reset! drag-state {:dragging nil
                                :dragging-point nil
                                :mode :select
                                :offset-x 0
                                :offset-y 0
                                :select-start {:x x :y y}
                                :select-end {:x x :y y}
                                :selected #{}
                                :selected-points #{}})))))))

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

(defn points-in-selection [points select-start select-end]
  "Find indices of points that are inside the selection box"
  (let [min-x (min (:x select-start) (:x select-end))
        max-x (max (:x select-start) (:x select-end))
        min-y (min (:y select-start) (:y select-end))
        max-y (max (:y select-start) (:y select-end))]
    (set (keep-indexed
          (fn [idx point]
            (let [{:keys [x y]} point]
              (when (and (>= x min-x) (<= x max-x)
                         (>= y min-y) (<= y max-y))
                idx)))
          points))))

(defn on-mouse-move [canvas-el e]
  (let [{:keys [x y]} (canvas->normalized canvas-el (.-clientX e) (.-clientY e))
        {:keys [dragging dragging-point mode offset-x offset-y select-start multi-offsets point-offsets selected]} @drag-state]
    ;; Update cursor based on mode and what we're hovering over
    (let [current-tool (:current-tool @state)]
      (cond
        ;; Rectangle or point tool always shows crosshair
        (or (= current-tool :rectangle) (= current-tool :point))
        (set! (.-cursor (.-style canvas-el)) "crosshair")

        (= mode :select)
        (set! (.-cursor (.-style canvas-el)) "crosshair")

        (or dragging dragging-point)
        (set! (.-cursor (.-style canvas-el))
              (if (or (= mode :resize) (= mode :resize-multi)) "ew-resize" "move"))

        :else
        (let [point-hit (point-hit-test canvas-el (:points @state) x y)
              event-hit (hit-test canvas-el (:events @state) x y)]
          (set! (.-cursor (.-style canvas-el))
                (cond
                  point-hit "move"
                  (and event-hit (:on-handle event-hit)) "ew-resize"
                  event-hit "move"
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
            points (:points @state)
            selected-events (events-in-selection events select-start {:x x :y y})
            selected-pts (points-in-selection points select-start {:x x :y y})]
        (swap! drag-state assoc
               :select-end {:x x :y y}
               :selected selected-events
               :selected-points selected-pts)
        (render-canvas! canvas-el))

      ;; Single point move mode
      (and dragging-point (= mode :move-point))
      (let [points (vec (:points @state))
            point (nth points dragging-point)
            snap? (:snap-to-grid @state)
            new-x (- x offset-x)
            new-y (- y offset-y)
            new-x (max 0 (min 1 new-x))
            new-y (max 0 (min 1 new-y))
            new-x (if snap? (snap-to-grid-value new-x) new-x)
            new-y (if snap? (snap-to-grid-value new-y) new-y)
            updated-point (assoc point :x new-x :y new-y)
            updated-points (assoc points dragging-point updated-point)]
        (swap! state assoc :points updated-points)
        (render-canvas! canvas-el))

      ;; Multi-point move mode
      (and dragging-point (= mode :move-points))
      (let [points (vec (:points @state))
            snap? (:snap-to-grid @state)
            updated-points (reduce
                            (fn [pts idx]
                              (let [point (nth pts idx)
                                    {:keys [offset-x offset-y]} (get point-offsets idx)
                                    new-x (- x offset-x)
                                    new-y (- y offset-y)
                                    new-x (max 0 (min 1 new-x))
                                    new-y (max 0 (min 1 new-y))
                                    new-x (if snap? (snap-to-grid-value new-x) new-x)
                                    new-y (if snap? (snap-to-grid-value new-y) new-y)
                                    updated-point (assoc point :x new-x :y new-y)]
                                (assoc pts idx updated-point)))
                            points
                            (keys point-offsets))]
        (swap! state assoc :points updated-points)
        (render-canvas! canvas-el))

      ;; Single resize mode
      (and dragging (= mode :resize))
      (let [events (:events @state)
            event (nth events dragging)
            snap? (:snap-to-grid @state)
            new-valid-to (+ x offset-x)
            new-valid-to (if snap? (snap-to-grid-value new-valid-to) new-valid-to)
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
            snap? (:snap-to-grid @state)
            ;; Calculate new max valid-to based on drag
            new-max-valid-to (+ x offset-x)
            new-max-valid-to (if snap? (snap-to-grid-value new-max-valid-to) new-max-valid-to)
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
            snap? (:snap-to-grid @state)
            updated-events (reduce
                            (fn [evts idx]
                              (let [event (nth evts idx)
                                    {:keys [offset-x offset-y]} (get multi-offsets idx)
                                    open? (nil? (:_valid_to event))
                                    event-width (if open? 0 (- (:_valid_to event) (:_valid_from event)))
                                    new-valid-from (- x offset-x)
                                    new-system-from (+ y offset-y)
                                    new-valid-from (if snap? (snap-to-grid-value new-valid-from) new-valid-from)
                                    new-system-from (if snap? (snap-to-grid-value new-system-from) new-system-from)
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
            snap? (:snap-to-grid @state)
            open? (nil? (:_valid_to event))
            event-width (if open? 0 (- (:_valid_to event) (:_valid_from event)))
            new-valid-from (- x offset-x)
            new-system-from (+ y offset-y)
            new-valid-from (if snap? (snap-to-grid-value new-valid-from) new-valid-from)
            new-system-from (if snap? (snap-to-grid-value new-system-from) new-system-from)
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
  (let [{:keys [mode select-start select-end selected selected-points]} @drag-state]
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
                        :dragging-point nil
                        :mode nil
                        :offset-x 0
                        :offset-y 0
                        :select-start nil
                        :select-end nil
                        :selected selected
                        :selected-points selected-points})
    (render-canvas! canvas-el)))

(defn on-context-menu [canvas-el e]
  (.preventDefault e)
  (let [{:keys [x y]} (canvas->normalized canvas-el (.-clientX e) (.-clientY e))
        events (:events @state)
        points (:points @state)
        point-hit (point-hit-test canvas-el points x y)
        event-hit (hit-test canvas-el events x y)
        currently-selected (:selected @drag-state)
        currently-selected-points (:selected-points @drag-state)]
    (cond
      ;; Right-clicked on a point
      point-hit
      (let [clicked-idx (:index point-hit)
            ;; If clicked point is in selection, target all selected (events + points)
            ;; otherwise just the clicked point
            in-selection? (contains? currently-selected-points clicked-idx)]
        (reset! context-menu {:open true
                              :x (.-clientX e)
                              :y (.-clientY e)
                              :target-indices (if in-selection? currently-selected #{})
                              :target-point-indices (if in-selection?
                                                      currently-selected-points
                                                      #{clicked-idx})}))

      ;; Right-clicked on an event
      event-hit
      (let [clicked-idx (:index event-hit)
            ;; If clicked event is in selection, target all selected (events + points)
            ;; otherwise just the clicked event
            in-selection? (contains? currently-selected clicked-idx)]
        (reset! context-menu {:open true
                              :x (.-clientX e)
                              :y (.-clientY e)
                              :target-indices (if in-selection?
                                                currently-selected
                                                #{clicked-idx})
                              :target-point-indices (if in-selection?
                                                      currently-selected-points
                                                      #{})})))))

(defn on-keydown [e]
  (let [key (.-key e)
        selected (:selected @drag-state)
        selected-points (:selected-points @drag-state)]
    (when (and (or (= key "Delete") (= key "Backspace"))
               (or (seq selected) (seq selected-points)))
      (.preventDefault e)
      (when (seq selected)
        (delete-events! selected))
      (when (seq selected-points)
        (delete-points! selected-points)))))

(defn canvas-lifecycle [node lifecycle _data]
  (case lifecycle
    "mount" (let [resize-handler #(render-canvas! node)
                  mousedown-handler #(on-mouse-down node %)
                  mousemove-handler #(on-mouse-move node %)
                  mouseup-handler #(on-mouse-up node %)
                  contextmenu-handler #(on-context-menu node %)
                  keydown-handler on-keydown]
              (.addEventListener js/window "resize" resize-handler)
              (.addEventListener node "mousedown" mousedown-handler)
              (.addEventListener js/window "mousemove" mousemove-handler)
              (.addEventListener js/window "mouseup" mouseup-handler)
              (.addEventListener node "contextmenu" contextmenu-handler)
              (.addEventListener js/window "keydown" keydown-handler)
              ;; Store canvas reference for thumbnail capture (separate atom to avoid re-render loop)
              (reset! canvas-ref node)
              (render-canvas! node)
              {:resize resize-handler
               :mousedown mousedown-handler
               :mousemove mousemove-handler
               :mouseup mouseup-handler
               :contextmenu contextmenu-handler
               :keydown keydown-handler})
    "update" (render-canvas! node)
    "unmount" (reset! canvas-ref nil)
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
  (let [{:keys [room-count global-count]} @state]
    [:div {:class "fixed bottom-4 right-4 px-3 py-2 bg-white rounded-lg shadow-md text-sm text-gray-600 z-10"}
     [:span {:class "inline-flex items-center gap-2"}
      [:span {:class "w-2 h-2 bg-green-500 rounded-full"}]
      (str room-count "/" global-count " online")]]))

(defn toggle-grid! []
  (swap! state update :show-grid not))

(defn toggle-snap-to-grid! []
  (swap! state update :snap-to-grid not))

(defn toggle-show-ticks! []
  (swap! state update :show-ticks not))

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

(defn set-point-colors! [indices color]
  (let [points (vec (:points @state))
        updated-points (reduce
                        (fn [pts idx]
                          (update pts idx assoc :color color))
                        points
                        indices)]
    (swap! state assoc :points updated-points))
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

(defn delete-events! [indices]
  (when (seq indices)
    (let [events (:events @state)
          indices-set (set indices)
          updated-events (vec (keep-indexed
                                (fn [idx event]
                                  (when-not (contains? indices-set idx)
                                    event))
                                events))]
      ;; Clear selection first since indices become invalid
      (swap! drag-state assoc :selected #{})
      ;; Then update events (triggers re-render with cleared selection)
      (swap! state assoc :events updated-events))))

(defn delete-points! [indices]
  (when (seq indices)
    (let [points (:points @state)
          indices-set (set indices)
          updated-points (vec (keep-indexed
                                (fn [idx point]
                                  (when-not (contains? indices-set idx)
                                    point))
                                points))]
      ;; Clear selection first since indices become invalid
      (swap! drag-state assoc :selected-points #{})
      ;; Then update points (triggers re-render with cleared selection)
      (swap! state assoc :points updated-points))))

(defn set-all-colors! [event-indices point-indices color]
  "Set color for both events and points, then close context menu"
  (when (seq event-indices)
    (let [events (vec (:events @state))
          updated-events (reduce
                          (fn [evts idx]
                            (update evts idx assoc :color color))
                          events
                          event-indices)]
      (swap! state assoc :events updated-events)))
  (when (seq point-indices)
    (let [points (vec (:points @state))
          updated-points (reduce
                          (fn [pts idx]
                            (update pts idx assoc :color color))
                          points
                          point-indices)]
      (swap! state assoc :points updated-points)))
  (close-context-menu!))

(defn delete-all-selected! [event-indices point-indices]
  "Delete both events and points, then close context menu"
  (when (seq event-indices)
    (delete-events! event-indices))
  (when (seq point-indices)
    (delete-points! point-indices))
  (close-context-menu!))

(defn color-menu []
  (let [{:keys [open x y target-indices target-point-indices]} @context-menu
        events (:events @state)
        has-events? (seq target-indices)
        has-points? (seq target-point-indices)
        ;; Check if all targeted events are open (only show Open option when events are selected)
        all-open? (and has-events?
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
                    :on-click #(set-all-colors! target-indices target-point-indices color)}])
        [:input {:type "color"
                 :class "w-6 h-6 rounded cursor-pointer border-0 p-0"
                 :on-change #(set-all-colors! target-indices target-point-indices (hex->rgb (.-value (.-target %))))}]]
       ;; Open checkbox (only for events)
       (when has-events?
         [:div
          ;; Separator
          [:div {:class "h-px bg-gray-200 my-2"}]
          [:label {:class "flex items-center gap-2 text-sm text-gray-700 cursor-pointer select-none px-1"}
           [:input {:type "checkbox"
                    :checked all-open?
                    :on-change #(toggle-events-open! target-indices (not all-open?))
                    :class "w-4 h-4 cursor-pointer"}]
           "Open"]])
       ;; Separator
       [:div {:class "h-px bg-gray-200 my-2"}]
       ;; Delete button
       [:button {:class "w-full text-left text-sm text-red-600 hover:bg-red-50 px-1 py-1 rounded cursor-pointer"
                 :on-click #(delete-all-selected! target-indices target-point-indices)}
        "Delete"]])))

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
       [:rect {:x "3" :y "3" :width "18" :height "18" :rx "2"}]]]
     ;; Point tool
     [:button {:class (str "w-8 h-8 rounded flex items-center justify-center transition-colors "
                           (if (= current-tool :point)
                             "bg-blue-500 text-white"
                             "hover:bg-gray-100 text-gray-600"))
               :title "Point"
               :on-click #(set-tool! :point)}
      [:svg {:width "16" :height "16" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
       [:circle {:cx "12" :cy "12" :r "4"}]]]]))

(defn toggle-auto-select! []
  (swap! state update :auto-select not))

(defn prompt-join-room! []
  (when-let [code (js/prompt "Enter room code to join:")]
    (let [code (-> code .trim .toUpperCase (subs 0 6))]
      (when (and (seq code) (>= (count code) 4))
        (join-room! code)))))

(defn copy-room-code! []
  (let [code (:room-code @state)]
    (-> (js/navigator.clipboard.writeText code)
        (.then #(js/console.log "Copied room code:" code))
        (.catch #(js/console.error "Failed to copy:" %)))))

;; >> DML Export

(defn parse-date [date-str]
  "Parse a date string (YYYY-MM-DD) to a js/Date at midnight UTC"
  (let [[year month day] (map js/parseInt (.split date-str "-"))]
    (js/Date. (js/Date.UTC year (dec month) day))))

(defn format-date-for-dml [date]
  "Format a js/Date as YYYY-MM-DD for XTDB DML"
  (let [year (.getUTCFullYear date)
        month (inc (.getUTCMonth date))
        day (.getUTCDate date)
        pad #(.padStart (str %) 2 "0")]
    (str year "-" (pad month) "-" (pad day))))

(defn normalized-to-date [normalized start-date end-date]
  "Convert a normalized 0-1 value to a date within the range"
  (let [start-ms (.getTime start-date)
        end-ms (.getTime end-date)
        range-ms (- end-ms start-ms)
        target-ms (+ start-ms (* range-ms normalized))]
    (js/Date. target-ms)))

(defn event-to-dml [event start-date end-date]
  "Convert a single event to a DML INSERT statement with system time transaction"
  (let [{:keys [_valid_from _valid_to _system_from color]} event
        valid-from-date (format-date-for-dml (normalized-to-date _valid_from start-date end-date))
        system-from-date (format-date-for-dml (normalized-to-date _system_from start-date end-date))
        open? (nil? _valid_to)
        color-str (str "[" (first color) ", " (second color) ", " (nth color 2) "]")]
    (str "BEGIN READ WRITE WITH (SYSTEM_TIME = DATE '" system-from-date "');\n"
         "INSERT INTO docs RECORDS {_id: 1, _valid_from: DATE '" valid-from-date "', "
         (when-not open?
           (let [valid-to-date (format-date-for-dml (normalized-to-date _valid_to start-date end-date))]
             (str "_valid_to: DATE '" valid-to-date "', ")))
         "color: " color-str "};\n"
         "COMMIT;")))

(defn generate-dml []
  "Generate DML for all events on the canvas"
  (let [events (:events @state)
        start-date (parse-date (:dml-start-date @state))
        end-date (parse-date (:dml-end-date @state))
        dml-statements (map #(event-to-dml % start-date end-date) events)]
    (.join (to-array dml-statements) "\n\n")))

(defn copy-dml! []
  "Copy generated DML to clipboard"
  (let [dml (generate-dml)]
    (-> (js/navigator.clipboard.writeText dml)
        (.then #(js/console.log "Copied DML to clipboard"))
        (.catch #(js/console.error "Failed to copy DML:" %)))))

;; >> Saved States

(defn generate-state-id []
  (str (js/Date.now) "-" (rand-int 10000)))

(defn capture-thumbnail []
  "Capture canvas as a small thumbnail data URL"
  (when-let [canvas @canvas-ref]
    ;; Create a smaller canvas for thumbnail
    (let [thumb-width 120
          thumb-height 80
          thumb-canvas (js/document.createElement "canvas")
          thumb-ctx (.getContext thumb-canvas "2d")]
      (set! (.-width thumb-canvas) thumb-width)
      (set! (.-height thumb-canvas) thumb-height)
      ;; Draw scaled version of main canvas
      (.drawImage thumb-ctx canvas 0 0 thumb-width thumb-height)
      (.toDataURL thumb-canvas "image/png" 0.8))))

(defn save-current-state! []
  "Save current canvas state with thumbnail"
  (when-let [thumbnail (capture-thumbnail)]
    (let [new-state {:id (generate-state-id)
                     :events (:events @state)
                     :points (:points @state)
                     :thumbnail thumbnail
                     :timestamp (js/Date.now)}]
      (swap! state update :saved-states conj new-state))))

(defn load-saved-state! [saved-state]
  "Load a saved state onto the canvas"
  (swap! state assoc
         :events (:events saved-state)
         :points (:points saved-state))
  ;; Clear selection
  (swap! drag-state assoc :selected #{} :selected-points #{}))

(defn delete-saved-state! [state-id]
  "Delete a saved state by its ID"
  (swap! state update :saved-states
         (fn [states]
           (vec (remove #(= (:id %) state-id) states)))))

(defn saved-state-card [saved-state]
  (let [{:keys [id thumbnail]} saved-state]
    [:div {:class "relative group cursor-pointer"
           :on-click #(load-saved-state! saved-state)}
     ;; Thumbnail
     [:img {:src thumbnail
            :class "w-full rounded border border-gray-600 hover:border-blue-400 transition-colors"}]
     ;; Delete button (top-right corner)
     [:button {:class "absolute -top-2 -right-2 w-5 h-5 bg-red-500 hover:bg-red-600 rounded-full flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity"
               :on-click (fn [e]
                           (.stopPropagation e)
                           (delete-saved-state! id))}
      [:svg {:width "10" :height "10" :viewBox "0 0 24 24" :fill "none" :stroke "white" :stroke-width "3"}
       [:path {:d "M18 6L6 18M6 6l12 12"}]]]]))

(defn side-panel []
  (let [saved-states (:saved-states @state)]
    [:div {:class "w-64 bg-gray-800 text-white p-4 flex flex-col h-full"}
     [:h1 {:class "text-xl font-bold mb-4"} "Bitemporal Visualizer"]
     ;; Room section
     [:div {:class "mb-6 p-3 bg-gray-700 rounded-lg"}
      [:div {:class "text-xs text-gray-400 mb-1"} "Room"]
      [:div {:class "flex items-center gap-2"}
       [:span {:class "font-mono text-lg tracking-wider"} (:room-code @state)]
       [:button {:class "px-2 py-1 text-xs bg-gray-600 hover:bg-gray-500 rounded cursor-pointer"
                 :title "Copy room code"
                 :on-click copy-room-code!}
        "Copy"]
       [:button {:class "px-2 py-1 text-xs bg-blue-500 hover:bg-blue-600 rounded cursor-pointer"
                 :on-click prompt-join-room!}
        "Join"]]]
     ;; Options
     [:div {:class "flex flex-col gap-2 mb-4"}
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
       [:span "Auto-select after draw"]]]

     ;; Divider
     [:div {:class "h-px bg-gray-600 my-4"}]

     ;; Synced Settings section
     [:div {:class "mb-4"}
      [:div {:class "text-xs text-gray-400 mb-2"} "Room Settings"]
      [:div {:class "flex flex-col gap-2"}
       [:label {:class "flex items-center gap-2 cursor-pointer"}
        [:input {:type "checkbox"
                 :checked (:snap-to-grid @state)
                 :on-change toggle-snap-to-grid!
                 :class "w-4 h-4"}]
        [:span "Snap to grid"]
        ;; Sync icon
        [:svg {:class "w-3.5 h-3.5 text-gray-400"
               :viewBox "0 0 24 24"
               :fill "none"
               :stroke "currentColor"
               :stroke-width "2"
               :title "Synced to room"}
         [:path {:d "M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"}]]]
       [:label {:class "flex items-center gap-2 cursor-pointer"}
        [:input {:type "checkbox"
                 :checked (:show-ticks @state)
                 :on-change toggle-show-ticks!
                 :class "w-4 h-4"}]
        [:span "Axis ticks"]
        ;; Sync icon
        [:svg {:class "w-3.5 h-3.5 text-gray-400"
               :viewBox "0 0 24 24"
               :fill "none"
               :stroke "currentColor"
               :stroke-width "2"
               :title "Synced to room"}
         [:path {:d "M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"}]]]]]

     ;; Divider
     [:div {:class "h-px bg-gray-600 my-4"}]

     ;; DML Export section
     [:div {:class "mb-4"}
      [:div {:class "text-xs text-gray-400 mb-2"} "DML Export"]
      [:div {:class "flex flex-col gap-2"}
       [:div {:class "flex items-center gap-2"}
        [:label {:class "text-sm text-gray-300 w-12"} "Start"]
        [:input {:type "date"
                 :value (:dml-start-date @state)
                 :on-change #(swap! state assoc :dml-start-date (.-value (.-target %)))
                 :class "flex-1 px-2 py-1 text-sm bg-gray-700 border border-gray-600 rounded text-white"}]]
       [:div {:class "flex items-center gap-2"}
        [:label {:class "text-sm text-gray-300 w-12"} "End"]
        [:input {:type "date"
                 :value (:dml-end-date @state)
                 :on-change #(swap! state assoc :dml-end-date (.-value (.-target %)))
                 :class "flex-1 px-2 py-1 text-sm bg-gray-700 border border-gray-600 rounded text-white"}]]
       [:button {:class "w-full px-3 py-1.5 text-sm bg-blue-500 hover:bg-blue-600 rounded cursor-pointer mt-1"
                 :on-click copy-dml!}
        "Copy DML"]]]

     ;; Divider
     [:div {:class "h-px bg-gray-600 my-4"}]

     ;; Saved States section
     [:div {:class "flex-1 flex flex-col min-h-0"}
      [:div {:class "flex items-center justify-between mb-3"}
       [:span {:class "text-sm font-medium text-gray-300"} "Saved States"]
       [:button {:class "px-3 py-1 text-xs bg-blue-500 hover:bg-blue-600 rounded cursor-pointer"
                 :on-click save-current-state!}
        "Save"]]
      ;; Scrollable list of saved states
      [:div {:class "flex-1 overflow-y-auto"}
       (if (seq saved-states)
         [:div {:class "grid grid-cols-2 gap-2"}
          (for [s saved-states]
            [:div {:key (:id s)}
             [saved-state-card s]])]
         [:div {:class "text-xs text-gray-500 text-center py-4"}
          "No saved states yet"])]]]))

(defn main-canvas []
  [:div {:class "flex-1 bg-gray-100 p-4"}
   [:div {:class "relative w-full h-full bg-white rounded-lg shadow-md overflow-hidden"}
    [:div {:class "absolute inset-0 left-5"}
     [:canvas {:on-render canvas-lifecycle
               :class "w-full h-full"}]]
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

;; >> Presence & Room Management

(defn update-global-presence-count! []
  (ably/get-presence-members GLOBAL_CHANNEL
                             (fn [members]
                               (swap! state assoc :global-count (count members)))))

(defn update-room-presence-count! []
  (let [room-code (:room-code @state)]
    (ably/get-presence-members (room-channel-name room-code)
                               (fn [members]
                                 (swap! state assoc :room-count (count members))))))

(defn broadcast-state! []
  "Broadcast current events, points, and synced settings to room"
  (when-not (:syncing @state)
    (let [room-code (:room-code @state)
          events (:events @state)
          points (:points @state)
          snap-to-grid (:snap-to-grid @state)
          show-ticks (:show-ticks @state)]
      (ably/publish! (room-channel-name room-code)
                     "state-sync"
                     #js {:events events
                          :points points
                          :snapToGrid snap-to-grid
                          :showTicks show-ticks
                          :from ably/client-id}))))

(defn request-state! []
  "Request current state from room members"
  (let [room-code (:room-code @state)]
    (ably/publish! (room-channel-name room-code)
                   "state-request"
                   #js {:from ably/client-id})))

(defn handle-room-message [msg]
  (let [event (.-name msg)
        data (.-data msg)
        from (.-from data)]
    (when (not= from ably/client-id) ; ignore own messages
      (case event
        "state-request"
        (broadcast-state!)

        "state-sync"
        (let [events (.-events data)
              points (.-points data)
              snap-to-grid (.-snapToGrid data)
              show-ticks (.-showTicks data)]
          (swap! state assoc :syncing true)
          (swap! state assoc :events (vec events))
          (when points
            (swap! state assoc :points (vec points)))
          (when (some? snap-to-grid)
            (swap! state assoc :snap-to-grid snap-to-grid))
          (when (some? show-ticks)
            (swap! state assoc :show-ticks show-ticks))
          (swap! state assoc :syncing false))

        nil))))

(defn join-room! [code]
  "Join a room by code"
  (let [old-code (:room-code @state)
        old-channel (room-channel-name old-code)
        new-channel (room-channel-name code)]
    ;; Leave old room
    (when old-code
      (-> (.-presence (ably/channel old-channel))
          (.leave)))
    ;; Update room code
    (swap! state assoc :room-code code :room-count 0)
    ;; Join new room
    (ably/enter-presence! new-channel)
    (ably/on-presence-change! new-channel update-room-presence-count!)
    ;; Subscribe to room messages
    (ably/subscribe! new-channel handle-room-message)
    ;; Request current state from room
    (js/setTimeout request-state! 500))) ; small delay to let presence sync

;; >> State Sync

;; Broadcast state changes to room (but not when syncing from remote)
(add-watch state ::sync-to-room
           (fn [_ _ old-state new-state]
             (when (and (not (:syncing new-state))
                        (or (not= (:events old-state) (:events new-state))
                            (not= (:points old-state) (:points new-state))
                            (not= (:snap-to-grid old-state) (:snap-to-grid new-state))
                            (not= (:show-ticks old-state) (:show-ticks new-state))))
               (broadcast-state!))))

;; >> Init

(defn init! []
  (render)
  (ably/init! ABLY_API_KEY)
  ;; Global presence
  (ably/enter-presence! GLOBAL_CHANNEL)
  (ably/on-presence-change! GLOBAL_CHANNEL update-global-presence-count!)
  ;; Join initial room
  (join-room! (:room-code @state)))

(init!)
