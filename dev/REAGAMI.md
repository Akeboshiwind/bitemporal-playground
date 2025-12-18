# Reagami Reference Notes

Reagami is a minimal reactive UI library for Squint/ClojureScript. These notes capture key details for working with this codebase.

Source: https://github.com/borkdude/reagami

## Core API

- `reagami/render` - Renders hiccup into a DOM node
- Manual state management required - use `add-watch` + `render` for reactivity

## Hiccup Syntax

```clojure
;; CSS shorthand
[:div#my-id.class1.class2 "content"]

;; Event handlers
[:button {:on-click #(println "clicked")} "Click me"]
[:input {:on-input #(handle-input %)}]

;; Uncontrolled inputs
[:input {:default-value "initial"}]

;; Boolean attributes
[:button {:disabled (not valid?)} "Submit"]

;; Style maps
[:div {:style {:background-color "green"}} "styled"]
```

## Lifecycle Hook: `:on-render`

**This is how to access DOM nodes** - NOT via `:ref` like React.

```clojure
[:canvas {:on-render (fn [node lifecycle data]
                       (case lifecycle
                         :mount (setup-canvas! node)
                         :update (redraw-canvas! node)
                         :unmount (cleanup! node))
                       ;; Return value becomes `data` in next call
                       )}]
```

**Arguments:**
- `node` - The DOM element
- `lifecycle` - One of `:mount`, `:update`, or `:unmount`
- `data` - Return value from previous call (for persisting state)

## What Reagami Does NOT Have

- No automatic atom watching (must manually call render)
- No React hooks
- No React dependency
- No `:ref` callback support

## Pattern for Canvas/External Libraries

```clojure
(defn my-canvas []
  [:canvas {:on-render (fn [node lifecycle _]
                         (when (= lifecycle :mount)
                           ;; Initialize on mount
                           (setup-my-canvas! node))
                         (when (= lifecycle :update)
                           ;; Redraw on updates
                           (redraw-my-canvas! node)))}])
```
