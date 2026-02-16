# Plan: Daily Web Time Analytics

**Spec**: arch/specs/001-web-history-analytics-daily-time-tracking/spec.md

## Tech Stack

- **Extension**: TypeScript, Svelte 5, WXT, Tailwind CSS
- **Backend**: Go, gorilla/websocket, PocketBase
- **Storage**: PocketBase `browsing_history` collection
- **Testing**: Manual (no test infrastructure exists in either project)

## Structure

### Extension (coach_browser)

```
src/lib/background/
├── tracker.ts          # NEW — BrowsingTracker class (core tracking logic)
├── websocket.ts        # EDIT — add onHistoryMessage callback, handle history responses
├── types.ts            # EDIT — add HistoryEntry, HistoryMessage, GetHistoryMessage types
├── timers.ts           # EDIT — remove startLastInteractionUpdateTimer, use idle API
├── constants.ts        # EDIT — add IDLE_THRESHOLD_SECONDS, FLUSH_INTERVAL_MS

src/entrypoints/
├── background.ts       # EDIT — wire up BrowsingTracker, idle API, remove old interaction polling

src/components/
├── BrowsingHistory.svelte  # NEW — domain list with date picker

src/entrypoints/popup/
├── App.svelte          # EDIT — add BrowsingHistory component
```

### Backend (coach)

```
internal/
├── api.go              # EDIT — add "history" and "get_history" WebSocket cases
├── db/
│   └── manager.go      # EDIT — add browsing history CRUD methods

cmd/coach_db/
├── main.go             # EDIT — add browsing_history collection creation
```

## Approach

### Phase 1: Backend — store and query browsing history

1. **New PocketBase collection: `browsing_history`**

   Fields:
   - `domain` (text, required) — e.g., "youtube.com"
   - `seconds` (number, required) — time spent in this segment
   - `date` (text, required) — calendar date "2026-02-10"
   - `timestamp` (date, required) — when the entry was recorded

   Index: `CREATE INDEX idx_bh_date ON browsing_history (date)`.

   Update `cmd/coach_db/main.go` to create this collection.

2. **New DB methods on `Manager`**

   - `AddBrowsingEntry(domain string, seconds int, date string)` — insert one row.
   - `GetBrowsingHistory(date string) ([]BrowsingEntry, error)` — get all entries for a date.
     Returns raw entries. Aggregation happens here: query all rows for the date, group by domain, sum seconds. Return `[]DomainTime{Domain string, Seconds int}` sorted descending by seconds.
   - `DeleteOldBrowsingHistory(days int)` — delete entries older than N days.

3. **New WebSocket message types in `api.go`**

   Receive:
   ```json
   { "type": "history", "domain": "youtube.com", "seconds": 120, "date": "2026-02-10" }
   ```
   → Call `AddBrowsingEntry`. No response needed.

   Receive:
   ```json
   { "type": "get_history", "date": "2026-02-10" }
   ```
   → Call `GetBrowsingHistory`, send response:
   ```json
   { "type": "history_response", "date": "2026-02-10", "domains": [{"domain": "youtube.com", "seconds": 9120}, ...] }
   ```

   Extend the message struct in `WebsocketHandler` to include `Domain`, `Seconds`, `Date` fields. Add `"history"` and `"get_history"` cases to the switch.

4. **Cleanup old data**

   Run `DeleteOldBrowsingHistory(30)` once on server startup. No scheduler needed — the server restarts often enough via deploys.

### Phase 2: Extension — tracking engine

5. **New `BrowsingTracker` class** (`src/lib/background/tracker.ts`)

   State:
   ```typescript
   currentDomain: string | null
   currentStart: number | null  // Date.now() timestamp
   isIdle: boolean
   isWindowFocused: boolean
   ```

   Methods:
   - `handleTabChange(domain: string | null)` — finalize old segment, start new one
   - `handleIdle(isIdle: boolean)` — pause/resume tracking
   - `handleWindowFocus(focused: boolean)` — pause/resume tracking
   - `flush()` — finalize current segment, restart timer (for crash safety)
   - `finalize()` — calculate elapsed, send via WebSocket if connected, reset state

   Constructor takes a `send` callback `(entry: HistoryEntry) => void` so it's decoupled from WebSocket.

   Helper: `extractDomain(url: string): string | null` — `new URL(url).hostname`, filter out internal schemes (about:, moz-extension:, chrome:).

6. **Wire up browser events** in `background.ts`

   - `browser.tabs.onActivated` → get tab URL → `tracker.handleTabChange(domain)`
   - `browser.webNavigation.onCommitted` (frameId === 0) → `tracker.handleTabChange(domain)`
   - `browser.windows.onFocusChanged` → `tracker.handleWindowFocus(id !== WINDOW_ID_NONE)`
   - `browser.idle.onStateChanged` → `tracker.handleIdle(state !== "active")`
   - `browser.idle.setDetectInterval(300)` — 5 minutes
   - Periodic flush: `setInterval(() => tracker.flush(), 30000)` — every 30s

7. **Replace manual interaction polling with idle API**

   - Remove `startLastInteractionUpdateTimer()` from `TimerManager`
   - Remove `last_interaction`, `last_interaction_timestamp` from storage
   - The idle state is now purely event-driven via `browser.idle`
   - The notification logic in `shouldSendNotification` needs adjusting: instead of checking `last_interaction < 5 min`, check if idle state is "active" (store a boolean in `browser.storage.local`)

8. **Add `idle` permission** to `wxt.config.ts`

### Phase 3: Extension — popup UI

9. **`BrowsingHistory.svelte` component**

   - On mount: send `{ type: "get_history", date: today }` via `browser.runtime.sendMessage`
   - Background handles the message, forwards to WebSocket, and stores the response in `browser.storage.local` under key `browsing_history`
   - Component reads from storage and listens for changes
   - Date picker: simple prev/next day buttons with date display
   - Domain list: `domain — Xh Ym` rows, sorted by time
   - Empty state: "No browsing data for this date"
   - Format helper: `formatDuration(seconds)` → "2h 32m", "45m", "3m"

10. **Add to popup `App.svelte`**

    Import and place `<BrowsingHistory />` below existing components.

### Phase 4: WebSocket plumbing

11. **Extend `WebSocketManager`**

    - Add `onHistoryResponse` callback to `WebSocketManagerCallbacks`
    - In `onmessage` handler, check for `type === "history_response"` and call callback
    - The callback saves to `browser.storage.local` so the popup picks it up

12. **Handle `get_history` message from popup**

    In `background.ts`, add a `browser.runtime.onMessage` listener for `type: "get_history"`:
    - Forward to WebSocket: `wsManager.send({ type: "get_history", date })`
    - Response arrives async via `onHistoryResponse` callback → saved to storage → popup reads it

## Message Flow

```
[Popup]                    [Background]                  [Server]
  |                            |                            |
  |-- get_history(date) ------>|                            |
  |                            |-- WS: get_history -------->|
  |                            |                            |-- query PocketBase
  |                            |<-- WS: history_response --|
  |                            |-- storage.set(history) --->|
  |<-- storage.onChanged ------|                            |
  |                            |                            |
  |                       [tab switch]                      |
  |                            |-- WS: history(entry) ----->|
  |                            |                            |-- insert PocketBase
```

## Risks

- **Rapid tab switching floods server**: Each tab switch sends a WS message. Mitigate by ignoring segments shorter than 2 seconds.
- **Midnight rollover**: If `currentStart` is before midnight and `finalize()` runs after midnight, the date changes. Mitigate by using the date of `currentStart`, not `now`, for the `date` field.
- **Popup shows stale data**: After a tab switch, the popup's history is outdated. Acceptable for v1 — user can switch dates back and forth to refresh. Could add periodic refresh later.
- **PocketBase collection not created**: Server will fail on first history write. Mitigate by running `coach_db` during deploy.

## Open Questions

None.
