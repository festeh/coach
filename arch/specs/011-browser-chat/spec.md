# Spec: Browser Chat

## Problem

The coach lives where I am not. To negotiate a release I need the phone —
but the temptation happens in the browser, on the machine where I work.
The judge is ready, the protocol is proven, the phone already chats.
The browser, my main surface, has no way to talk.

## What we build

A chat page inside the extension. One click from the popup opens it in a
tab; I plead my case; the coach answers, streaming; a grant or denial
lands in the ledger like any other. The same negotiation the phone has —
where the temptation actually is.

### A page, not a popup conversation

The popup dies the moment focus leaves it — one alt-tab mid-plea and the
text is gone. Negotiation needs a surface that stays put. So the chat is
a full extension page opened in a tab; the popup keeps its job (status at
a glance) and gains one button: **Talk to coach**.

### One thread per browser, like the phone

Each browser install generates a UUID thread id once and keeps it. The
server holds the history; opening the chat replays it. This copies
Android's convention exactly — same shape of thread, same replay
behavior — so the server treats every device alike. Two browsers are two
threads; the coach may hear my morning YouTube plea twice. Accepted: the
judge reads shared state (the ledger, temptations, attention) from the
server, so the facts stay consistent even when the conversations don't.

## How the pieces talk

The page speaks the agents server's existing WebSocket protocol at
`wss://agents.dimalip.in/api/coach/ws/<threadId>` — the one the phone
already uses and the streaming check verified:

- on connect the server sends `history` (roles `human`/`ai`; the page
  hides `tool` and `system` rows),
- the page sends `{type: "message", content}`,
- the server streams `chunk` frames and closes the turn with `done`,
- `error` frames render as an error row instead of a reply.

The page connects while open and disconnects when closed. No keepalive
gymnastics: a chat tab has a human in it, and a dropped socket shows a
reconnect button.

The agents base URL enters the build as `VITE_AGENTS`, next to the
existing `VITE_SERVER`.

## What does not change

- **The judge.** Same agent, same tools, same journaling. The browser is
  a new mouth, not a new brain.
- **The server.** No new endpoints; `/api/*` on agents.dimalip.in is
  already reachable from clients.
- **Blocking.** The blocked page keeps its current behavior. Wiring a
  "talk to the coach" button into the blocked page itself is a natural
  follow-up, not part of D.

## Known limits

- **Open endpoint.** Anyone who finds the URL can chat with my coach
  until Phase F adds tokens. Same standing risk as every other endpoint,
  accepted and time-boxed.
- **No override button yet.** That is Phase E, and it will live on this
  page.
- **The popup stays dumb.** If a one-line quick plea from the popup
  proves worth it, it reuses this page's client later.

## Success check

I hit a blocked site, open the extension, click "Talk to coach", and ask
for fifteen minutes. The reply streams in; the lock releases or I get one
pointed sentence back; the row is in the ledger. Closing and reopening
the page shows the same conversation.
