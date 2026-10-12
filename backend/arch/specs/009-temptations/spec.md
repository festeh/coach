# Spec: Temptations

## Problem

The coach judges my release requests half-blind. It knows what I asked for
and what it granted. It does not know how often I hit the wall today —
bouncing off a blocked site, reaching for a blocked app. That is the clearest
signal of how hard the day is, and the judge cannot see it.

## What we build

Every time a client blocks me — a non-whitelisted site in the browser, a
watched app on the phone — it tells the server. The server records a
temptation. The judge reads today's count when it weighs my next request.

A temptation is one row in a new `temptations` collection:

| Field | Meaning |
|---|---|
| `source` | which client it came from — specific, e.g. `chromium`, `firefox`, `firefox-android`, `android` |
| `target` | the site or app I reached for |
| `created` | when, from PocketBase |

I run several browsers, so `source` names the exact one, not a coarse
"browser". Each client reports the most specific label it can know for sure:
the extension reads its build target and, on Chromium, the real brand; the
phone reports `android`. The judge only counts temptations, so this detail
costs nothing there — it is for me, when I want to see which browser I am
weakest on.

Temptations get their own collection, not the decision ledger. They are a
different kind of record: discrete events from my devices, not a dialogue with
the coach. This is the separate collection [[008-agent-lock-ledger]] promised.

## How the pieces talk

- **Browser.** When the extension blocks the active tab, it sends
  `{type:"temptation", source:<browser>, target:<hostname>}` over the
  WebSocket it already holds open. Active tab only — a background tab
  refreshing a blocked site is noise, not a choice.
- **Android.** When the monitor blocks a watched app, it sends
  `{type:"temptation", source:"android", target:<package>}` over its focus
  WebSocket.
- **Server.** A new `temptation` case in the WebSocket handler writes the row.
  `GET /agent-lock/state` gains `temptation_count_today`, counted from today's
  rows.
- **Judge.** `get_state()` already returns whatever the server sends, so the
  count arrives for free; its description grows one line so the model knows to
  weigh it.

## What does not change

- **Blocking itself.** A temptation report is a side effect of a block that
  already happens. What gets blocked, redirected, or alerted stays the same.
- **The lock.** Temptations are data, not enforcement. Hitting the wall does
  not engage or extend anything — I am already locked.
- **The decision ledger.** `lock_decisions` is untouched.

## A temptation is any block, not only an agent-lock block

The browser and phone block during manual focus too, which I own. I still
report those. A temptation is a temptation whichever lock raised the wall, and
the judge wants the whole picture of my day. If that proves too broad, a later
field can split focus blocks from lock blocks.

## Out of scope

- Acting on temptations — auto-engaging the lock on repeated drift. Later, if
  at all.
- Deduplication. One block, one row. A stuck page could inflate the count, but
  the browser redirects or alerts after a single navigation, so a repeat needs
  a fresh attempt from me. We revisit only if the count proves noisy.
- Auth on the ingest path — anyone could post a fake temptation until Phase F
  closes the door.

## Success check

I hit three blocked sites this morning and open a blocked app on my phone. I
ask the coach for a break. It knows I hit the wall four times today and can
say so. The four rows sit in the `temptations` collection for me to read.
