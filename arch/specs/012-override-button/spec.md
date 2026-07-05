# Spec: Override Button

## Problem

The coach has the last word, and mostly that is the point. But sometimes
it is wrong — a real errand it cannot verify, an emergency it cannot
weigh — and arguing with a stubborn judge defeats the purpose of having
one. I need an escape hatch that is honest instead of sneaky: visible,
bounded, and on the record.

## What we build

A button on the chat page: **Override — 15 minutes**. Pressing it
releases the agent lock for a fixed quarter hour, no negotiation. The
catch is the price of admission: the button stays dead until I have
written why. The written reason and the act itself land in the ledger as
`kind = override` — the row Phase A reserved for exactly this — and the
judge reads today's override count the next time I come asking.

Fifteen minutes is fixed. An override that can be stretched is not an
escape hatch, it is a second lock-picker. If fifteen is not enough, the
coach is right there in the same window.

## How the pieces talk

- **Chat page.** The override button sits in the composer, beside send.
  It enables only when the input holds text — the reason. Pressing it
  sends the reason to the background, drops a quiet "override taken"
  note into the conversation view (a local note, not a chat message — the
  coach was not consulted), and clears the input.
- **Background.** Forwards `{type: "override", message}` over the
  WebSocket it already holds to the coach server — the temptation path,
  reused. No new permissions, no direct HTTP from the page.
- **Server.** A new `override` case in the WebSocket handler: reject an
  empty message, otherwise release the lock for 900 seconds and journal
  `kind = override` with the reason as `user_message`. The release
  broadcast the server already does flips every client's state — the
  chat header shows "Released" without asking.

## What does not change

- **The judge.** It is not consulted and not notified mid-conversation;
  it sees the override in the ledger (`override_count_today`, the
  `recent` rows) like any other decision.
- **The HTTP release path.** `/agent-lock/release` keeps its
  `is_override` wire field; the agent and any future caller still use
  it. The WS case is the browser's road to the same journal.
- **Android.** Gets the same button eventually; the WS case is
  client-agnostic, so that is purely phone-side work.

## Known limits

- **The reason is not validated.** "asdf" unlocks fifteen minutes. The
  friction is having to type and the row with my name on it, not a
  quality bar — the ledger shames better than a regex.
- **Open socket until Phase F.** Anyone who can reach the WebSocket can
  override. Same standing risk as every ingest path, closed by F.

## Success check

The coach says no. I type "pharmacy closes in twenty minutes" and press
Override. The lock releases for fifteen minutes, the header flips, and
`/agent-lock/state` shows an override row with those words — plus an
override count the coach will quote back at me tomorrow.
