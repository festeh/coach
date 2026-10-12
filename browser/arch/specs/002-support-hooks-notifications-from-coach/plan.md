# Plan: Support Hook Notifications from Coach

## Tech Stack

- Language: TypeScript
- Framework: WXT (browser extension), Svelte (UI)
- Testing: manual (no test framework in project)

## What Changes

The coach backend now sends `hook_result` messages over WebSocket. The extension should show these as browser notifications — but only when the user is active in the browser. All existing timer-based motivational notification logic gets removed.

## Structure

Files to **delete**:
```
src/lib/notifications.ts                    # random phrases
src/components/LastNotificationStatus.svelte # "last notification" UI
```

Files to **edit**:
```
src/lib/background/types.ts      # add HookResultMessage type
src/lib/background/websocket.ts  # handle hook_result messages
src/lib/background/timers.ts     # remove notification logic
src/lib/background/constants.ts  # remove notification constants
src/lib/storage.ts               # remove last_notification_sent
src/entrypoints/background.ts    # remove showNotification, add hook handler
src/entrypoints/popup/App.svelte # remove notification UI and button
```

## Approach

### 1. Add `hook_result` message type

In `types.ts`, add a schema and type guard for the incoming hook result message:
```typescript
{ type: "hook_result", hook_id: string, id: string, content: string }
```

### 2. Handle `hook_result` in WebSocket manager

Add `onHookResult` callback to `WebSocketManagerCallbacks`. In `onmessage`, detect `hook_result` messages and call the callback.

### 3. Show notification only when user is active

In `background.ts`, implement the `onHookResult` callback. Use `browser.idle.queryState(60)` to check if the user is active. If active, show a browser notification with the hook result content. If not active, skip it (the coach server already checks for connected clients).

Why `browser.idle.queryState`: it's the standard WebExtension API for checking user activity. Passing 60 means "consider idle after 60 seconds of no mouse/keyboard input."

### 4. Remove old notification system

**Delete** `src/lib/notifications.ts` (random phrases — no longer needed).

**In `timers.ts`**: Remove `shouldSendNotification()`, remove the notification check inside `startTimeUpdateTimer()`, remove `showNotification` from `TimerManagerDeps`.

**In `constants.ts`**: Remove `TWO_HOURS_SECONDS`, `FIVE_MINUTES_SECONDS`, `TWO_HOURS_MS`.

**In `storage.ts`**: Remove `last_notification_sent` from `StorageSchema` and `DEFAULTS`.

**In `background.ts`**: Remove `showNotification()` function, `getRandomPhrase` import, `show_notification` message case, and notification click listener. Remove `showNotification` from `TimerManager` constructor.

### 5. Clean up popup UI

**Delete** `src/components/LastNotificationStatus.svelte`.

**In `App.svelte`**: Remove the "Show Notification" button, `handleNotification()`, `lastNotificationSent` state, and the `LastNotificationStatus` component import and usage. Remove `last_notification_sent` from storage reads.

### 6. Clean up ExtensionMessage type

Remove `{ type: "show_notification" }` from the `ExtensionMessage` union in `types.ts`.

## Risks

- **User doesn't see notification if idle**: Acceptable — the coach server already skips hooks when no clients are connected. If the user is truly away, showing a notification they'll never see is pointless anyway.
- **`browser.idle` permission**: May need to add `"idle"` to the extension permissions in `wxt.config.ts`. Low risk — it's a standard permission.

## Open Questions

None — the approach is straightforward.
