# Plan: Replace Hand-Rolled WebSocket Reconnect with `partysocket`

## Tech Stack

- Language: TypeScript
- Runtime: MV3 service worker (Chrome + Firefox via WXT)
- Library: `partysocket` — `WebSocket`-API-compatible client with auto-reconnect. Explicitly lists ServiceWorkers as a supported runtime; "dependency-free, does not depend on Window, DOM or any EventEmitter library" per the docs.
- Testing: manual load-unload cycles + observe Chrome DevTools service worker

## What Changes

`src/lib/background/websocket.ts` currently hand-rolls reconnect timers, backoff, and a `reconnectScheduled` race guard. Recent commits (`a0dbeda`, `a6e76f4`, `f46e56a`) have all been bug fixes in this area, so the surface keeps regrowing complexity. Move the timer/backoff/listener-cleanup work onto `partysocket` and keep the wrapper for the parts that are actually app-specific:

- App-level ping/pong (browser `WebSocket` doesn't expose ping frames, and we need the 20s ping to keep the MV3 SW alive).
- `onReconnectScheduled(reconnectAt)` callback that drives the popup countdown.
- The `ensureConnected()` safety net called from the 30s `browser.alarms` heartbeat.
- The same callback shape (`onConnected`, `onDisconnected`, `onFocusMessage`, `onHookResult`) so `src/entrypoints/background.ts` doesn't change.

## Structure

Files to **edit**:
```
package.json                     # add partysocket
src/lib/background/websocket.ts  # rewrite around partysocket, keep public surface
src/lib/background/constants.ts  # unchanged values, but reused by both wrapper and lib config
```

Files **unchanged**:
```
src/entrypoints/background.ts    # callback shape preserved
src/lib/background/types.ts
src/lib/background/index.ts
```

## Approach

### 1. Add the dependency

`bun add partysocket` (project uses bun based on the lockfile / WXT defaults — confirm in step 0). Verify the import resolves under WXT's Vite bundler for both Chrome and Firefox builds.

### 2. Construct the socket

Replace the raw `WebSocket` instance with `partysocket`'s `WebSocket` class, configured with the existing constants:

```typescript
import PartySocket from "partysocket";

this.socket = new PartySocket({
  url: `${this.serverUrl}/connect`,
  minReconnectionDelay: RECONNECT_BASE_DELAY_MS,    // 2000
  maxReconnectionDelay: RECONNECT_MAX_DELAY_MS,     // 30000
  reconnectionDelayGrowFactor: 2,
  maxRetries: Infinity,                              // matches a6e76f4
  startClosed: true,                                 // we call connect() ourselves from initState
});
```

`partysocket` owns the timer and listener lifecycle — no more `reconnectScheduled` flag, no manual `setTimeout`.

### 3. Keep the ping/pong loop

`startPingLoop`, `sendPing`, `handlePong`, `stopPingLoop` stay. They still serve two purposes the lib doesn't:
- **MV3 keepalive**: pinging every 20s prevents the SW from going idle while a connection is open.
- **Dead-connection detection**: a TCP-half-open socket won't fire `close` on its own; pong timeout forces it.

On pong timeout, call `socket.reconnect()` (partysocket's API) instead of our own `reconnect()`. That closes the underlying socket, which fires `close`, which the lib then handles with its backoff.

### 4. Replace `connect()` / `reconnect()` / `ensureConnected()`

- `connect()` → `socket.reconnect()` if `socket.readyState === CLOSED`, else no-op. (partysocket starts closed because of `startClosed: true`.)
- `reconnect(force)` → `socket.reconnect()`. The `force` flag previously reset the attempt counter to start backoff from 0 — partysocket resets its internal counter on every successful open, so the user-triggered "reconnect now" button just calls `reconnect()` and the lib does the right thing.
- `ensureConnected()` → if `socket.readyState === CLOSED`, call `socket.reconnect()`. partysocket already guards against double-connects internally, so the `reconnectScheduled` race is gone.

### 5. Surface "next reconnect at" to the UI

The popup shows a countdown using `reconnect_at` storage, fed from `onReconnectScheduled(timestamp)`. partysocket doesn't expose its scheduled time. Two options:

**A.** Compute the same backoff curve in our wrapper using `reconnectAttempts` we track ourselves (increment on `close`, reset on `open`). Same formula, just kept locally.

**B.** Drop the live countdown and just show "reconnecting…" with no ETA.

Go with **A** — the formula is one line and the UX value is real. We'd track `reconnectAttempts` only for display purposes; the lib still owns the actual timer.

### 6. Listener wiring

Use `socket.addEventListener("open" | "close" | "message", ...)`. Cleanup on shutdown isn't a concern (the SW is the lifetime), but `removeEventListener` with stored handler refs is cleaner than property assignment and avoids accidental override.

### 7. Verify in both browsers

Build for chrome and firefox, load the unpacked extension, kill the coach server, watch the DevTools SW console for backoff progression (2s → 4s → 8s → 16s → 30s cap), then bring the server back and confirm immediate reconnect + `connected: true` in storage. Force the SW to suspend (chrome://serviceworker-internals or `await new Promise(r => setTimeout(r, 35000))` with no activity) and confirm the alarm-driven `ensureConnected()` brings it back.

## Risks

- **partysocket in a service worker context**: docs explicitly list ServiceWorkers as a supported runtime and the package is dependency-free of Window/DOM. Still worth a sanity check on the bundled output for `window.` or `document.` references after `wxt build`, since WXT's bundling could in theory pull in something unexpected. Mitigation: if it breaks, fall back to `reconnecting-websocket` (older but pure-WebSocket-API).
- **Bundle size in the extension**: partysocket is ~3KB minified. Negligible, but worth confirming with `wxt build` size report.
- **Backoff curve drift**: our display-only attempt counter could disagree with partysocket's internal counter if it ever resets at a different moment than `open`. Keep the rule simple — increment on `close`, reset on `open` — same as the lib.
- **`force` reconnect semantics change**: today, force resets the attempt counter so backoff restarts at 2s; with partysocket, calling `reconnect()` while connected closes-and-reopens, and a successful open resets the counter naturally. End behavior matches; intermediate state during a forced reconnect is slightly different but not user-visible.

## Open Questions

None — `isOpen()` is unused (only defined in `websocket.ts:41`, no callers), so it gets deleted with the rewrite.
