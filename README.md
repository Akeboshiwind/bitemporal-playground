# Bitemporal Visualizer

An interactive visualizer for bitemporal data, built with Squint (ClojureScript), Bun, Tailwind CSS, and Reagami.

## What is Bitemporal Data?

Bitemporal data tracks two time dimensions:
- **Valid Time** (x-axis): When the data is true in the real world
- **System Time** (y-axis): When the data was recorded in the system

This visualizer renders bitemporal events as colored rectangles that extend from their system time upward (representing that the record is valid from that system time until now/infinity).

## Features

- **Interactive Canvas**: Drag events to reposition them in valid time and system time
- **Resize Events**: Drag the right edge handle to change event duration
- **Multi-Select**: Click and drag on empty space to select multiple events
- **Batch Operations**: Move or resize multiple selected events together (proportional scaling)
- **Grid Overlay**: Toggle grid lines on top of events for alignment
- **Persistence**: Events and settings saved to localStorage
- **Real-time Presence**: Shows number of users currently viewing (via Ably)

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

- **Move event**: Click and drag an event
- **Resize event**: Drag the grey handle on the right edge
- **Select multiple**: Click and drag on empty space to draw selection box
- **Move selected**: Drag any selected event to move all together
- **Resize selected**: Drag any selected event's handle to scale all proportionally
- **Toggle grid**: Check "Grid" in the sidebar to show overlay grid

## Dev Resources

Reference documentation for libraries and patterns used in this project:

- [dev/REAGAMI.md](dev/REAGAMI.md) - Reagami API reference and lifecycle hooks
- [dev/SQUINT.md](dev/SQUINT.md) - Squint differences from ClojureScript

## Deployment

On push to `main`, GitHub Actions builds and pushes static assets to the `dist` branch.

The `dist` branch can be deployed to any static hosting (GitHub Pages, Netlify, Vercel, etc.).
