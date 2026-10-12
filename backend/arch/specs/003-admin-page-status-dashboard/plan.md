# Plan: Admin Status Dashboard

## Tech Stack

- Language: TypeScript (frontend), Go (backend, existing)
- Framework: SolidJS + Vite
- Storage: None new (reads from existing API)
- Testing: None for first pass

### Why SolidJS + Vite

- SolidJS has fine-grained reactivity with no virtual DOM. Tiny runtime (~7KB).
- JSX syntax, so familiar to write. Signals are a natural fit for real-time WebSocket data.
- Vite gives us TypeScript, hot reload, and fast builds with zero config.
- The built output is plain static files. We embed them in the Go binary.

## Structure

New files:

```
admin/                     # Frontend source
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
└── src/
    ├── main.tsx           # Entry point, mount app
    ├── app.tsx            # Main dashboard layout
    ├── components/
    │   ├── focus-status.tsx    # Current focus state (live)
    │   └── history-table.tsx   # Recent focus sessions
    └── api.ts             # Fetch helpers + WebSocket connection

internal/
├── api.go                 # Modified: serve embedded SPA instead of inline HTML
└── admin_assets.go        # New: go:embed directive for admin dist files
```

## Approach

### 1. Set up SolidJS + Vite project in `admin/`

Create a minimal Vite project with the `vite-plugin-solid` preset. Configure the build to output to `admin/dist/`.

### 2. Embed built assets in Go binary

Use `go:embed admin/dist/*` to bundle the frontend into the binary. Create a file server handler that serves the embedded files at `/admin/`.

This means:
- Single binary deployment (no separate file copy step).
- The CI pipeline builds the frontend first, then builds the Go binary.
- No changes to Caddy config.

### 3. Build the dashboard UI

The admin page shows two sections:

**Focus Status (live via WebSocket)**
- Whether we're currently focusing or not.
- Time remaining (counts down live).
- Time since last state change.
- Number of focus sessions today.

Connect to `ws://<host>/connect` and send `{"type": "get_focusing"}` to get state. Listen for broadcast updates to stay in sync.

**Recent History (fetched once on load)**
- Table of recent focus sessions from `/history?days=7`.
- Each row shows: date/time and duration.

### 4. Update the Go server to serve the SPA

Replace the inline HTML in `AdminHandler` with a file server that reads from the embedded filesystem. Serve `index.html` for `/admin` and static assets for `/admin/assets/*`.

### 5. Update CI/CD pipeline

Add a step before the Go build:
1. Install Node.js.
2. Run `npm install && npm run build` in `admin/`.
3. Then build Go as usual (embedded files are now present).

## Risks

- **Build dependency**: CI now needs Node.js in addition to Go. Mitigation: add a setup-node step before the Go build.
- **Embedding stale assets**: If you forget to build the frontend before `go build`, the embed will use old files (or fail if `admin/dist/` doesn't exist). Mitigation: CI always builds frontend first. Add `admin/dist/` to `.gitignore` and document the workflow.

## Open Questions

- Should we add a `/admin/api/status` endpoint that bundles all status data in one call? Or keep using the existing endpoints (`/focusing`, `/history`, `/health`) separately? Starting with existing endpoints keeps things simple.
