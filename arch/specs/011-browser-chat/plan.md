# Plan: Browser Chat

Phase D of the agent-lock rework. One repo: `coach_browser`. See
`spec.md` for the why.

## Tech Stack

- WXT unlisted page entrypoint (`/chat.html`) with Svelte 5 runes
- Plain `WebSocket` against the agents server (page-lifetime connection)
- Existing popup design tokens; existing typed storage helpers
- `VITE_AGENTS` env var alongside `VITE_SERVER`

## Structure

```
coach_browser/src/
├── entrypoints/chat/
│   ├── index.html        # /chat.html
│   ├── main.ts
│   ├── App.svelte        # header (lock state) + messages + composer
│   └── app.css           # popup tokens, page-width layout
├── lib/chat/
│   ├── protocol.ts       # frame types, ws URL builder (https→wss)
│   └── client.svelte.ts  # ChatClient: runes state, connect/send
└── entrypoints/popup/App.svelte  # + "Talk to coach" button
```

## Approach

### 1. Protocol client (`lib/chat/`)

`protocol.ts` types the frames (`history`, `chunk`, `done`, `error`) and
builds the URL from `VITE_AGENTS` + thread id, converting http(s) to
ws(s) the way Android's `AgentChatService` does.

`client.svelte.ts` holds the page's state in runes: `messages` (from
history, roles `human`/`ai` only), `streaming` (the reply being
assembled from chunks), `status` (`connecting`/`open`/`closed`/`error`).
`send()` appends the human message locally and writes the frame;
`done` folds the streamed text into `messages`. A dropped socket sets
`closed` and the UI offers reconnect — no background keepalive.

### 2. Thread id

`chat_thread_id` in extension storage: `crypto.randomUUID()` on first
open, stable afterwards. Mirrors Android's
`getOrCreateAgentChatThreadId()`.

### 3. Page (`entrypoints/chat/`)

Unlisted WXT page. Layout: status header reusing `CoachState`
(connected / focus / lock-with-countdown), scrolling message list
(auto-stick to bottom), composer with Enter-to-send. Streaming reply
renders live under an "assistant is typing" affordance. Errors render
inline as a muted row.

### 4. Popup button

One button in the popup header row: opens
`browser.runtime.getURL("/chat.html")` in a new tab via
`browser.tabs.create`, then closes the popup.

## Verification

- `npm run check`; build both targets.
- Live test against agents.dimalip.in from the built page: history
  replays, a message streams back, `done` closes the turn (same protocol
  the streaming check already proved from the terminal).
- Manual: grant path once (short release), ledger row visible in
  `/agent-lock/state`.

## Risks

- **Popup→tab friction.** Two clicks to start talking. Acceptable; the
  blocked-page shortcut is the follow-up that removes even those.
- **Long threads.** History grows per install; the agents server already
  trims model context (`history_trim`), and replay cost is one frame.
- **Firefox for Android popup.** `tabs.create` works there; the page is
  responsive by construction (plain flex column).
