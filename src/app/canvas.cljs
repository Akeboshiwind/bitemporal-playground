(ns app.canvas)

;; >> Configuration

(def config
  {:padding 50
   :grid-divisions 10
   :grid-color "#e5e7eb"
   :axis-color "#374151"
   :axis-label-color "#6b7280"})

;; >> Drawing Helpers

(defn clear-canvas! [ctx width height]
  (set! (.-fillStyle ctx) "#ffffff")
  (.fillRect ctx 0 0 width height))

(defn draw-grid! [ctx width height padding divisions]
  (let [draw-width (- width (* 2 padding))
        draw-height (- height (* 2 padding))
        step-x (/ draw-width divisions)
        step-y (/ draw-height divisions)]
    (set! (.-strokeStyle ctx) (:grid-color config))
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

;; >> Event Rendering

(defn rgb->css [[r g b]]
  (str "rgb(" r "," g "," b ")"))

(defn sort-events-by-system-time [events]
  (sort-by :_system_from events))

(defn draw-event! [ctx event width height padding]
  (let [{:keys [_valid_from _valid_to _system_from color]} event
        draw-width (- width (* 2 padding))
        draw-height (- height (* 2 padding))
        ;; Map 0..1 valid time to x coordinates
        x1 (+ padding (* _valid_from draw-width))
        x2 (+ padding (* _valid_to draw-width))
        ;; Map 0..1 system time to y coordinate (bottom to top)
        ;; _system_to is infinity, so rect extends from _system_from to top
        y-bottom (- height padding (* _system_from draw-height))
        y-top padding]
    ;; Fill the rectangle
    (set! (.-fillStyle ctx) (rgb->css color))
    (.fillRect ctx x1 y-top (- x2 x1) (- y-bottom y-top))
    ;; Stroke only left, bottom, right sides (no top line - goes to infinity)
    (set! (.-strokeStyle ctx) "#000000")
    (set! (.-lineWidth ctx) 1)
    (.beginPath ctx)
    (.moveTo ctx x1 y-top)
    (.lineTo ctx x1 y-bottom)
    (.lineTo ctx x2 y-bottom)
    (.lineTo ctx x2 y-top)
    (.stroke ctx)))

(defn draw-events! [ctx events width height padding]
  (let [sorted-events (sort-events-by-system-time events)]
    (doseq [event sorted-events]
      (draw-event! ctx event width height padding))))

;; >> Main Render Function

(defn render-canvas! [canvas events]
  (when canvas
    (let [ctx (.getContext canvas "2d")
          width (.-width canvas)
          height (.-height canvas)
          padding (:padding config)
          divisions (:grid-divisions config)]
      ;; Pass 1: Background
      (clear-canvas! ctx width height)
      (draw-grid! ctx width height padding divisions)
      (draw-axes! ctx width height padding)
      (draw-axis-labels! ctx width height padding)
      ;; Pass 2: Events (use 0..1 normalized coordinates)
      (draw-events! ctx events width height padding)
      ;; Pass 3: Foreground (placeholder for future overlays)
      )))
