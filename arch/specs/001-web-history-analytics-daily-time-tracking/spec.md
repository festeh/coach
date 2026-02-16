# Daily Web Time Analytics

## Decisions

- **UI**: Show in the extension popup (flat domain list, sorted by time)
- **Storage**: Server-side via WebSocket — extension sends time entries, backend stores in PocketBase
- **Scope**: Flat domain list only, no categories, no total summary (v1)
- **Idle detection**: Use `browser.idle` API (replace existing manual polling)

## What Users Can Do

1. **See how much time they spent on each website today**

   - **Scenario: Normal browsing day**
     - **Given:** User has been browsing throughout the day
     - **When:** User opens the extension popup
     - **Then:** They see a list of domains sorted by time spent, like:
       - youtube.com — 2h 32m
       - reddit.com — 1h 02m
       - github.com — 45m

   - **Scenario: No browsing data yet**
     - **Given:** It's a new day or the extension was just installed
     - **When:** User opens the popup
     - **Then:** They see an empty state ("No browsing data for today")

2. **See time analytics for a specific past day**

   - **Scenario: Pick a date**
     - **Given:** User has browsing history stored on the server
     - **When:** User selects a past date in the popup
     - **Then:** They see domain-by-time breakdown for that day

   - **Scenario: No data for selected date**
     - **When:** User picks a date with no data
     - **Then:** They see "No data for this date"

3. **Browsing time is tracked automatically in the background**

   - **Scenario: Active tab tracking**
     - **Given:** User is browsing normally
     - **When:** User switches tabs or navigates to a new site
     - **Then:** Time is recorded for the domain they just left

   - **Scenario: User goes idle**
     - **Given:** User stops interacting with the browser
     - **When:** `browser.idle` fires with state "idle" or "locked" (5-min threshold)
     - **Then:** Time tracking pauses until the user returns to "active"

   - **Scenario: Browser closes**
     - **Given:** User has an active browsing session
     - **When:** Browser is closed or extension is suspended
     - **Then:** Time for the current tab is saved before shutdown

4. **Time entries sync to the backend server**

   - **Scenario: Extension sends time data**
     - **Given:** User switches away from a domain (or goes idle)
     - **When:** A time segment is finalized
     - **Then:** Extension sends `{ type: "history", domain, seconds, date }` via WebSocket

   - **Scenario: Extension requests daily analytics**
     - **Given:** User opens the popup
     - **When:** Popup loads
     - **Then:** Extension requests today's analytics from server via WebSocket or REST

   - **Scenario: Offline / disconnected**
     - **Given:** WebSocket is disconnected
     - **When:** A time segment is finalized
     - **Then:** Entry is dropped (no offline buffering for now)

## Requirements

### Extension (coach_browser)

- [ ] Track time per domain (not full URL) for the active tab only
- [ ] Use `browser.idle` API with 5-minute threshold for idle detection
- [ ] Replace existing manual interaction polling with `browser.idle`
- [ ] Pause tracking when browser window loses focus
- [ ] Add `idle` permission to the extension manifest
- [ ] Send finalized time entries to the server via WebSocket
- [ ] Drop time entries when WebSocket is disconnected (no offline buffering)
- [ ] Periodically flush current tracking state to storage (crash safety)
- [ ] Internal pages (about:, moz-extension:, chrome:) are not tracked
- [ ] Display flat domain list in popup, sorted by time descending
- [ ] Display time in human-readable format ("2h 32m", "45m", "3m")
- [ ] Allow selecting a past date in the popup

### Backend (coach)

- [ ] Handle new `history` WebSocket message type (receive time entries)
- [ ] Store time entries in PocketBase (new collection)
- [ ] Handle `get_history` WebSocket message (query by date)
- [ ] Aggregate time by domain for a given date
- [ ] Clean up data older than 30 days

## Open Questions

None — all resolved.
