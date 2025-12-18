(ns app.canvas)

;; >> Configuration

(def config
  {:padding 50
   :grid-color "#e5e7eb"
   :axis-color "#374151"
   :axis-label-color "#6b7280"
   :grid-divisions 10})

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
      (let [y (+ padding (* i step-y))]
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

(defn draw-event! [ctx event width height padding max-valid max-system]
  (let [{:keys [_valid_from _valid_to _system_from color]} event
        draw-width (- width (* 2 padding))
        draw-height (- height (* 2 padding))
        ;; Map valid time to x coordinates (left to right)
        x1 (+ padding (* (/ _valid_from max-valid) draw-width))
        x2 (+ padding (* (/ _valid_to max-valid) draw-width))
        ;; Map system time to y coordinates (bottom to top, inverted because canvas y goes down)
        y (- height padding (* (/ _system_from max-system) draw-height))
        rect-height 20
        rect-y (- y (/ rect-height 2))]
    (set! (.-fillStyle ctx) (rgb->css color))
    (set! (.-strokeStyle ctx) "#000000")
    (set! (.-lineWidth ctx) 1)
    (.fillRect ctx x1 rect-y (- x2 x1) rect-height)
    (.strokeRect ctx x1 rect-y (- x2 x1) rect-height)))

(defn draw-events! [ctx events width height padding max-valid max-system]
  (let [sorted-events (sort-events-by-system-time events)]
    (doseq [event sorted-events]
      (draw-event! ctx event width height padding max-valid max-system))))

;; >> Main Render Function

(defn render-canvas! [canvas events {:keys [max-valid max-system]}]
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
      ;; Pass 2: Events
      (draw-events! ctx events width height padding max-valid max-system)
      ;; Pass 3: Foreground (placeholder for future overlays)
      )))
