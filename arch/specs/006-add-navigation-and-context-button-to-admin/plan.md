# Plan: Navigation + Context Preview Button

## Tech Stack

- Language: Go (backend), TypeScript/SolidJS (frontend)
- Framework: net/http, SolidJS, `@solidjs/router`
- Storage: PocketBase (existing)
- Testing: manual

## Structure

Changes to existing files:

```
internal/
├── server.go          # store dimaistClient, register new route
├── api_hooks.go       # add GET /api/hooks/{id}/context handler
├── hook_ai.go         # export GatherContext (rename from gatherContext)
admin/
├── package.json       # add @solidjs/router dependency
└── src/
    ├── main.tsx       # wrap app in <HashRouter>
    ├── app.tsx        # nav bar + <Route> definitions
    ├── api.ts         # add fetchHookContext() function
    ├── index.css      # add nav styles
    └── components/
        └── hook-manager.tsx  # add "Show Context" button + inline display
```

## Approach

### 1. Client-side routing with `@solidjs/router`

Install `@solidjs/router`. Use `<HashRouter>` — hash-based routing keeps all routing in the frontend with no backend changes. URLs: `/admin/#/`, `/admin/#/hooks`, `/admin/#/history`.

In `main.tsx`, replace the direct `<App />` render with `<HashRouter root={App}>` and route definitions. In `app.tsx`, add a nav bar with `<A>` links above `props.children`. Active link gets a bottom border highlight. Simple horizontal row, same dark theme.

Routes:
- `#/` → `<FocusStatus />`
- `#/hooks` → `<HookManager />`
- `#/history` → `<HistoryTable />`

### 2. New backend endpoint: `GET /api/hooks/{id}/context`

Returns the same context string that gets sent to the AI, so the user can preview it.

- Store `dimaistClient` on `Server` struct (it's already created in `NewServer`, just not stored)
- Export `GatherContext` (rename from `gatherContext`) so it can be called from the handler
- Add a `context` action case in `HookByIDHandler` that builds a `HookContext` and calls `GatherContext`
- Response: `{ "context": "## Focus Stats\n- Currently focusing: false\n..." }`

### 3. "Show Context" button in hook-manager.tsx

Add a button next to "Trigger Now" in the hook actions bar. When clicked:
- Calls `GET /api/hooks/ai_request/context`
- Shows the context text inline below the actions bar in a styled `<pre>` block
- Toggle: clicking again hides it

### 4. Frontend API function in api.ts

```typescript
export async function fetchHookContext(hookId: string): Promise<string>
```

Calls the new endpoint and returns the context string.

## Risks

- **dimaist client may not be available**: `gatherContext` already handles this gracefully (returns just focus stats). No risk.

## Open Questions

None — scope is clear and self-contained.
