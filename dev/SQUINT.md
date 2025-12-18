# Squint Reference Notes

Squint is a ClojureScript dialect that compiles to JavaScript.

Source: https://github.com/squint-cljs/squint

## Key Difference from ClojureScript

**No `js->clj` or `clj->js` functions** - In Squint, all data structures are represented as raw JavaScript objects under the hood:

- Maps are plain JS objects
- Vectors are JS arrays
- Keywords become strings

This means you can directly use JS interop without conversion:

```clojure
;; This works - no conversion needed
(js/JSON.stringify my-map)
(js/JSON.parse some-json)

;; These DON'T exist in Squint:
;; (clj->js data)  - not needed, already JS
;; (js->clj data)  - not needed, already works
```

## Implications

- Can pass Squint data directly to JS APIs
- JSON parsing returns usable Squint data directly
- No performance overhead from conversions
