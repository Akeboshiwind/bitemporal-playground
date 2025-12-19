(ns app.canvas)

;; >> Configuration

(def config
  {:padding 60
   :grid-divisions 10
   :grid-color "#e5e7eb"
   :foreground-grid-color "rgba(0, 0, 0, 0.3)"
   :axis-color "#374151"
   :axis-label-color "#6b7280"
   :handle-width 8
   :handle-color "rgba(100, 100, 100, 0.4)"
   :selection-fill "rgba(59, 130, 246, 0.2)"
   :selection-stroke "rgba(59, 130, 246, 0.8)"
   :selected-stroke "rgba(59, 130, 246, 1)"
   :selected-stroke-width 2
   :point-radius 8
   :point-stroke-width 2})

;; >> Drawing Helpers

(defn clear-canvas! [ctx width height]
  (set! (.-fillStyle ctx) "#ffffff")
  (.fillRect ctx 0 0 width height))

(defn draw-grid! [ctx width height padding divisions color]
  (let [draw-width (- width (* 2 padding))
        draw-height (- height (* 2 padding))
        step-x (/ draw-width divisions)
        step-y (/ draw-height divisions)]
    (set! (.-strokeStyle ctx) color)
    (set! (.-lineWidth ctx) 1)
    ;; Vertical lines
    (doseq [i (range (inc divisions))]
      (let [x (+ padding (* i step-x))]
        (.beginPath ctx)
        (.moveTo ctx x padding)
        (.lineTo ctx x (- height padding))
        (.stroke ctx)))
    ;; Horizontal lines
    (doseq [i (range (inc divisions))]
      (let [y (- height padding (* i step-y))]
        (.beginPath ctx)
        (.moveTo ctx padding y)
        (.lineTo ctx (- width padding) y)
        (.stroke ctx)))))

(defn draw-axes! [ctx width height padding]
  (set! (.-strokeStyle ctx) (:axis-color config))
  (set! (.-lineWidth ctx) 2)
  ;; X axis (bottom)
  (.beginPath ctx)
  (.moveTo ctx padding (- height padding))
  (.lineTo ctx (- width padding) (- height padding))
  (.stroke ctx)
  ;; Y axis (left)
  (.beginPath ctx)
  (.moveTo ctx padding padding)
  (.lineTo ctx padding (- height padding))
  (.stroke ctx)
  ;; Arrow heads
  (set! (.-fillStyle ctx) (:axis-color config))
  ;; X axis arrow
  (.beginPath ctx)
  (.moveTo ctx (- width padding) (- height padding))
  (.lineTo ctx (- width padding 10) (- height padding 5))
  (.lineTo ctx (- width padding 10) (+ (- height padding) 5))
  (.closePath ctx)
  (.fill ctx)
  ;; Y axis arrow
  (.beginPath ctx)
  (.moveTo ctx padding padding)
  (.lineTo ctx (- padding 5) (+ padding 10))
  (.lineTo ctx (+ padding 5) (+ padding 10))
  (.closePath ctx)
  (.fill ctx))

(defn draw-axis-labels! [ctx width height padding]
  (set! (.-fillStyle ctx) (:axis-label-color config))
  (set! (.-font ctx) "14px system-ui, sans-serif")
  ;; X axis label
  (set! (.-textAlign ctx) "center")
  (set! (.-textBaseline ctx) "top")
  (.fillText ctx "Valid Time" (/ width 2) (- height padding -20))
  ;; Y axis label
  (.save ctx)
  (.translate ctx (- padding 30) (/ height 2))
  (.rotate ctx (- (/ js/Math.PI 2)))
  (set! (.-textAlign ctx) "center")
  (set! (.-textBaseline ctx) "bottom")
  (.fillText ctx "System Time" 0 0)
  (.restore ctx))

(defn draw-ticks! [ctx width height padding divisions]
  (let [draw-width (- width (* 2 padding))
        draw-height (- height (* 2 padding))
        step-x (/ draw-width divisions)
        step-y (/ draw-height divisions)
        tick-length 6]
    (set! (.-fillStyle ctx) (:axis-label-color config))
    (set! (.-strokeStyle ctx) (:axis-color config))
    (set! (.-lineWidth ctx) 1)
    (set! (.-font ctx) "10px system-ui, sans-serif")
    ;; X axis ticks (bottom)
    (set! (.-textAlign ctx) "center")
    (set! (.-textBaseline ctx) "top")
    (doseq [i (range (inc divisions))]
      (let [x (+ padding (* i step-x))
            y (- height padding)]
        ;; Tick mark
        (.beginPath ctx)
        (.moveTo ctx x y)
        (.lineTo ctx x (+ y tick-length))
        (.stroke ctx)
        ;; Label
        (.fillText ctx (str "t" i) x (+ y tick-length 2))))
    ;; Y axis ticks (left)
    (set! (.-textAlign ctx) "right")
    (set! (.-textBaseline ctx) "middle")
    (doseq [i (range (inc divisions))]
      (let [x padding
            y (- height padding (* i step-y))]
        ;; Tick mark
        (.beginPath ctx)
        (.moveTo ctx x y)
        (.lineTo ctx (- x tick-length) y)
        (.stroke ctx)
        ;; Label
        (.fillText ctx (str "t" i) (- x tick-length 2) y)))))

;; >> Event Rendering

(defn rgb->css [[r g b]]
  (str "rgb(" r "," g "," b ")"))

(defn sort-events-by-system-time [events]
  (sort-by :_system_from events))

(defn draw-event! [ctx event width height padding selected?]
  (let [{:keys [_valid_from _valid_to _system_from color]} event
        open? (nil? _valid_to)
        draw-width (- width (* 2 padding))
        draw-height (- height (* 2 padding))
        handle-width (:handle-width config)
        ;; Map 0..1 valid time to x coordinates
        x1 (+ padding (* _valid_from draw-width))
        x2 (+ padding (* (if open? 1 _valid_to) draw-width))
        ;; Map 0..1 system time to y coordinate (bottom to top)
        ;; _system_to is infinity, so rect extends from _system_from to top
        y-bottom (- height padding (* _system_from draw-height))
        y-top padding]
    ;; Fill the rectangle
    (set! (.-fillStyle ctx) (rgb->css color))
    (.fillRect ctx x1 y-top (- x2 x1) (- y-bottom y-top))
    ;; Stroke sides (no top line - goes to infinity, no right line if open)
    (if selected?
      (do
        (set! (.-strokeStyle ctx) (:selected-stroke config))
        (set! (.-lineWidth ctx) (:selected-stroke-width config)))
      (do
        (set! (.-strokeStyle ctx) "#000000")
        (set! (.-lineWidth ctx) 1)))
    (.beginPath ctx)
    (.moveTo ctx x1 y-top)
    (.lineTo ctx x1 y-bottom)
    (.lineTo ctx x2 y-bottom)
    (when-not open?
      (.lineTo ctx x2 y-top))
    (.stroke ctx)
    ;; Draw resize handle on right edge (only for closed events)
    (when-not open?
      (set! (.-fillStyle ctx) (:handle-color config))
      (.fillRect ctx (- x2 handle-width) y-top handle-width (- y-bottom y-top)))))

(defn draw-events! [ctx events width height padding selected]
  (let [indexed-events (map-indexed vector events)
        sorted-events (sort-by #(:_system_from (second %)) indexed-events)]
    (doseq [[idx event] sorted-events]
      (draw-event! ctx event width height padding (contains? selected idx)))))

;; >> Point Rendering

(defn draw-point! [ctx point width height padding selected?]
  (let [{:keys [x y color]} point
        draw-width (- width (* 2 padding))
        draw-height (- height (* 2 padding))
        radius (:point-radius config)
        ;; Map 0..1 coordinates to pixel coordinates
        px (+ padding (* x draw-width))
        py (- height padding (* y draw-height))]
    ;; Fill the circle
    (set! (.-fillStyle ctx) (rgb->css color))
    (.beginPath ctx)
    (.arc ctx px py radius 0 (* 2 js/Math.PI))
    (.fill ctx)
    ;; Stroke the circle
    (if selected?
      (do
        (set! (.-strokeStyle ctx) (:selected-stroke config))
        (set! (.-lineWidth ctx) (:selected-stroke-width config)))
      (do
        (set! (.-strokeStyle ctx) "#000000")
        (set! (.-lineWidth ctx) 1)))
    (.stroke ctx)))

(defn draw-points! [ctx points width height padding selected-points]
  (doseq [[idx point] (map-indexed vector points)]
    (draw-point! ctx point width height padding (contains? selected-points idx))))

(defn draw-selection-box! [ctx width height padding selection-box]
  (when selection-box
    (let [{:keys [start end]} selection-box
          draw-width (- width (* 2 padding))
          draw-height (- height (* 2 padding))
          ;; Convert normalized coords to pixel coords
          x1 (+ padding (* (:x start) draw-width))
          y1 (- height padding (* (:y start) draw-height))
          x2 (+ padding (* (:x end) draw-width))
          y2 (- height padding (* (:y end) draw-height))
          ;; Get min/max for proper rectangle
          rx (min x1 x2)
          ry (min y1 y2)
          rw (js/Math.abs (- x2 x1))
          rh (js/Math.abs (- y2 y1))]
      ;; Fill
      (set! (.-fillStyle ctx) (:selection-fill config))
      (.fillRect ctx rx ry rw rh)
      ;; Stroke
      (set! (.-strokeStyle ctx) (:selection-stroke config))
      (set! (.-lineWidth ctx) 1)
      (.strokeRect ctx rx ry rw rh))))

;; >> Main Render Function

(defn render-canvas! [canvas events opts]
  (when canvas
    (let [ctx (.getContext canvas "2d")
          width (.-width canvas)
          height (.-height canvas)
          padding (:padding config)
          divisions (:grid-divisions config)
          show-grid (get opts :show-grid false)
          show-ticks (get opts :show-ticks false)
          selection-box (get opts :selection-box)
          selected (or (get opts :selected) #{})
          points (or (get opts :points) [])
          selected-points (or (get opts :selected-points) #{})]
      ;; Pass 1: Background
      (clear-canvas! ctx width height)
      (draw-grid! ctx width height padding divisions (:grid-color config))
      (draw-axes! ctx width height padding)
      (draw-axis-labels! ctx width height padding)
      (when show-ticks
        (draw-ticks! ctx width height padding divisions))
      ;; Pass 2: Events (use 0..1 normalized coordinates)
      (draw-events! ctx events width height padding selected)
      ;; Pass 3: Points (always on top of events)
      (draw-points! ctx points width height padding selected-points)
      ;; Pass 4: Foreground grid
      (when show-grid
        (draw-grid! ctx width height padding divisions (:foreground-grid-color config)))
      ;; Pass 5: Selection box (drawn on top)
      (draw-selection-box! ctx width height padding selection-box))))
