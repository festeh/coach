# Plan: Temptations

Phase B of the agent-lock rework. Three repos: `coach` (server),
`coach_browser`, `coach_android`. See `spec.md` for the why.

## Tech Stack

- Go server: `internal/db/temptations.go` (new), `internal/api.go`, `internal/server.go`
- Browser: `src/lib/background/types.ts`, `src/lib/blocking.ts`, `src/entrypoints/background.ts`
- Android: `data/websocket/WebSocketService.kt`, `service/MonitorLogic.kt` (Kotlin)
- Python judge: `my_agents/coach/agent.py` (one docstring line)
- PocketBase collection `temptations`, auto-migrated on server start

## Structure

```
coach/internal/
├── db/temptations.go    # schema, Ensure, InsertTemptation, CountTodayTemptations
├── api.go               # "temptation" WS case; temptation_count_today in /agent-lock/state
└── server.go            # EnsureTemptationsCollection in NewServer
```

## Approach

### 1. Collection (coach)

`temptations`: `source` (text, required), `target` (text). PB's `created` is
the event time. Follow `attention.go`: schema literal,
`EnsureTemptationsCollection()`, wired into `NewServer` with log-and-continue.
Add `InsertTemptation(source, target string) error` and
`CountTodayTemptations() (int, error)` — the latter filters
`created >= '<today> 00:00:00'` and returns `totalItems` (one cheap query, no
row fetch).

### 2. Ingest (coach)

The WS message struct gains `Target string`. New `case "temptation"`:
require non-empty `source` and `target` (the source is an open label set by the
client, not a fixed enum — new browsers must not need a server change), log-and-skip
otherwise. Insert through a `logTemptation` helper shaped like `logLockDecision`
— async goroutine, nil-DB guard, log on failure. A dropped row never blocks the
socket.

### 3. State (coach)

`writeLockState` runs `CountTodayTemptations()` and adds
`temptation_count_today` to the response. A count error logs and falls back to
0 rather than failing the whole state read — the lock summary matters more
than the temptation tally.

### 4. Browser (coach_browser)

- `types.ts`: add `{type:"temptation", source:string, target:string}` to
  `OutgoingMessage`.
- Source label, derived once at startup (new tiny `lib/source.ts`):
  - Firefox build target (`import.meta.env.BROWSER === "firefox"`):
    `"firefox-android"` if `navigator.userAgent` contains `"Android"`, else `"firefox"`.
  - Chromium build target: read `navigator.userAgentData.brands` and pick the
    real brand — `brave` / `chrome` / `edge` / `chromium` — lowercased; fall back
    to `"chrome"` if the API is missing. This is what tells Chromium from Chrome.
  - Two installs of the *same* brand still collide; an optional per-install
    override in the options page can disambiguate later (out of scope for B).
- `blocking.ts`: `blockPage` returns what it did — `{blocked: bool, target: string}`
  — instead of `void`. The hostname comes from the blocked URL.
- `background.ts`: where it calls `blockPage`, when the result is blocked, query
  the active tab (`tabs.query({active:true, lastFocusedWindow:true})`) and send
  `{type:"temptation", source, target}` only if the blocked `tabId` is the active
  one. Keeps background auto-refreshes out of the count.

### 5. Android (coach_android)

- `WebSocketService`: add `sendTemptation(packageName)` that sends
  `{type:"temptation", source:"android", target:packageName}` over the existing
  focus socket (alongside the focus command path).
- `MonitorLogic.onAppChanged`: when `blockMode(pkg)` is not `NONE` (a watched app
  was blocked), call `sendTemptation(pkg)`. This reuses the decision the monitor
  already makes; no new detection.

### 6. Judge (my-agents)

`get_state` already forwards the server dict, so `temptation_count_today`
arrives without code change. Add one line to its docstring naming the field so
the model weighs it. Prompt tuning (how hard to lean on it) is left for later.

## Deploy order

Server first — it accepts and counts; old clients send no temptations, so the
count sits at 0 and nothing breaks. Then browser, then Android, each landing
independently.

## Risks

- **Count inflation** from rapid repeat blocks. Mitigated by active-tab-only
  (browser) and one-event-per-open (Android); no dedup in B (see spec).
- **Manual-focus blocks count too.** Intended — see spec. A future field can
  split them if needed.
- **Unauthenticated ingest** until Phase F: anyone could post fake temptations.
  Accepted, time-boxed by F.
