# Bitemporal Visualizer

An interactive visualizer for bitemporal data, built with Squint (ClojureScript), Bun, Tailwind CSS, and Reagami.

**Try it live:** https://blog.bythe.rocks/tools/bitemporal/

## What is Bitemporal Data?

Bitemporal data tracks two time dimensions:
- **Valid Time** (x-axis): When the data is true in the real world
- **System Time** (y-axis): When the data was recorded in the system

This visualizer renders bitemporal events as colored rectangles that extend from their system time upward (representing that the record is valid from that system time until now/infinity).

## Features

### Drawing & Editing
- **Rectangle Tool**: Draw events by clicking and dragging
- **Point Tool**: Place individual points on the canvas
- **Select Tool**: Click to select, drag on empty space for box selection
- **Move Events**: Drag events to reposition them in valid/system time
- **Resize Events**: Drag the right edge handle to change event duration
- **Open Events**: Mark events as "open" (no end time) via context menu
- **Color Picker**: Change colors via right-click context menu with presets or custom colors
- **Delete**: Remove selected events/points via context menu or keyboard

### Multi-Selection
- **Box Selection**: Click and drag on empty space to select multiple items
- **Batch Move**: Drag any selected item to move all together
- **Batch Resize**: Drag any selected item's handle to scale all proportionally

### Canvas Options
- **Grid Overlay**: Toggle grid lines for visual alignment
- **Snap to Grid**: Snap events/points to grid intersections when moving
- **Axis Ticks**: Show tick marks on the axes

### Collaboration
- **Real-time Sync**: Changes sync instantly to all users in the same room
- **Room System**: Share a 6-character room code to collaborate
- **Presence Indicators**: See how many users are in your room and online globally
- **Synced Settings**: Grid snap and axis ticks sync across the room

### Persistence
- **Auto-save**: Events, points, and settings saved to localStorage
- **Saved States**: Save canvas snapshots with thumbnails for quick recall
- **Load States**: Click any saved state to restore it

## Stack

- **Squint** - ClojureScript dialect that compiles to JavaScript
- **Bun** - JavaScript runtime and bundler
- **Tailwind CSS** - Utility-first CSS framework
- **Reagami** - Lightweight React-like UI library
- **Ably** - Real-time messaging and presence
- **Babashka** - Task runner

## Project Structure

```
├── src/
│   ├── app/
│   │   ├── app.cljs      # Main app: state, event handlers, UI components
│   │   ├── canvas.cljs   # Canvas rendering: grid, axes, events, selection
│   │   ├── ably.cljs     # Ably wrapper (presence, pub/sub)
│   │   └── entry.css     # Tailwind CSS entry point
│   └── index.html        # HTML entry point
├── dev/                  # Developer reference notes
│   ├── REAGAMI.md        # Reagami API reference
│   └── SQUINT.md         # Squint differences from ClojureScript
├── llm-tasks/            # Task documents for LLM continuity
├── build/                # Squint compilation output (gitignored)
├── target/public/        # Final bundled output (gitignored)
├── bb.edn                # Babashka task definitions
├── squint.edn            # Squint compiler configuration
├── build.js              # Bun build script
└── package.json          # Node dependencies
```

## Requirements

- [Bun](https://bun.sh) - JavaScript runtime and bundler
- [Babashka](https://babashka.org) - Task runner for ClojureScript tooling
- [Ably API Key](https://ably.com) - For real-time features

## Setup

1. Install dependencies:

```bash
bun install
```

2. Add your Ably API key in `src/app/app.cljs`:

```clojure
(def ABLY_API_KEY "your-api-key-here")
```

## Development

Start the development server with hot-reload:

```bash
bb dev
```

This runs in parallel:
- Squint compilation (watches `src/app/`)
- Bun bundling (watches `build/`)
- Local server at https://localhost:3000

## Build

Build for production:

```bash
bb build
```

Output goes to `target/public/` containing:
- `index.html` - Entry point
- `app.js` - Bundled JavaScript
- `app.css` - Compiled Tailwind CSS

Clean build artifacts:

```bash
bb clean
```

## Usage

### Tools (toolbar in top-left)
- **Select** (arrow icon): Click to select items, drag on empty space for box selection
- **Rectangle** (square icon): Click and drag to draw a new event
- **Point** (circle icon): Click to place a point

### Interactions
- **Move**: Click and drag any event or point
- **Resize**: Drag the grey handle on the right edge of an event
- **Multi-select**: Use the select tool and drag on empty space
- **Batch operations**: With multiple items selected, drag any one to move/resize all
- **Context menu**: Right-click on events/points to change color, toggle "open", or delete
- **Delete**: Select items and press Delete/Backspace, or use context menu

### Sidebar Options
- **Grid**: Toggle grid overlay on the canvas
- **Auto-select after draw**: Switch back to select tool after drawing
- **Snap to grid**: Align items to grid when moving (synced to room)
- **Axis ticks**: Show tick marks on axes (synced to room)

### Collaboration
- **Room code**: Displayed in sidebar - share to collaborate in real-time
- **Copy**: Copy room code to clipboard
- **Join**: Enter another room code to join that room

## Dev Resources

Reference documentation for libraries and patterns used in this project:

- [dev/REAGAMI.md](dev/REAGAMI.md) - Reagami API reference and lifecycle hooks
- [dev/SQUINT.md](dev/SQUINT.md) - Squint differences from ClojureScript

## Deployment

On push to `main`, GitHub Actions:
1. Compiles Squint to JavaScript
2. Bundles with Bun (including Tailwind CSS)
3. Pushes static assets to the `dist` branch

The production build is hosted at https://blog.bythe.rocks/tools/bitemporal/ via a git submodule in the blog repository.
