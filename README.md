# Squint Webapp Starter

A minimal starter for building static frontend webapps with Squint (ClojureScript), Bun, Tailwind CSS, Reagami, and Ably real-time.

## Stack

- **Squint** - ClojureScript dialect that compiles to JavaScript
- **Bun** - JavaScript runtime and bundler
- **Tailwind CSS** - Utility-first CSS framework
- **Reagami** - Lightweight React-like UI library
- **Ably** - Real-time messaging and presence
- **Babashka** - Task runner

## Features

- Real-time presence counter (shows number of users online)
- Connection status indicator (shows when connecting/disconnected)

## Project Structure

```
├── src/
│   ├── app/              # ClojureScript source files
│   │   ├── app.cljs      # App entry point with presence UI
│   │   ├── ably.cljs     # Ably wrapper (presence, pub/sub)
│   │   └── entry.css     # Tailwind CSS entry point
│   └── index.html        # HTML entry point
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

## Ably Integration

The `ably.cljs` module provides:

- **Connection management** - `init!`, `connected?`, `connection-status`
- **Presence** - `enter-presence!`, `update-presence!`, `on-presence-change!`, `get-presence-members`
- **Pub/Sub** - `publish!`, `subscribe!`
- **Stable client ID** - Persisted in localStorage for consistent identity

## Deployment

On push to `main`, GitHub Actions builds and pushes static assets to the `dist` branch.

The `dist` branch can be deployed to any static hosting (GitHub Pages, Netlify, Vercel, etc.).

## Dev Resources

Reference documentation for libraries and patterns used in this project:

- [dev/REAGAMI.md](dev/REAGAMI.md) - Reagami API reference and lifecycle hooks
- [dev/SQUINT.md](dev/SQUINT.md) - Squint differences from ClojureScript
