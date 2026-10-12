# Spec: Attention for the Judge

## Problem

The coach judges with one eye open. It knows what I asked for, what it
granted today, and how often I hit the wall [[009-temptations]]. It does not
know what I am doing right now or where my attention went today. "I need
fifteen minutes for research" reads one way after three steady hours on
GitHub and another way after forty minutes of YouTube. The browser already
reports my attention to the server, beacon by beacon; the judge just never
sees it.

## What we build

The server folds today's attention intervals into one compact summary, and
the judge reads it before every reply — in its prompt, next to the focus and
lock state it already gets. No new tool, no extra round trip the model must
choose to make. The judge cannot ignore what is in front of it.

`GET /attention/summary` returns:

| Field | Meaning |
|---|---|
| `now` | what has my attention this moment: state (`site`, `idle`, `away`), the site when on one, and for how many minutes — or null when the browser has gone quiet |
| `site_minutes_today` | minutes spent on sites since local midnight |
| `top_sites_today` | the five sites that took the most of it, with minutes each |

A span counts as "now" when its last beacon is at most 90 seconds old — the
extension heartbeats every 30, so 90 forgives two missed beats and matches
the gap rule that closes intervals. The streak length runs from the span's
true start, even when that start was before midnight: "on youtube.com for 40
minutes" stays true across the date line.

## How the pieces talk

- **Server.** A new endpoint reads today's intervals, clips them to the day,
  sums site time in total and per site, and finds the current span. Pure
  arithmetic over rows that already exist; nothing new is stored.
- **Judge.** The prompt renderer already fetches focus state before every
  model call. It now fetches the summary too and writes two more lines into
  the "Right now" block: what I am on at this moment, and where today went.
  If the fetch fails, the lines are simply absent — attention is context,
  not a precondition for judging.

## What does not change

- **Beacons and storage.** The extension reports as before; intervals are
  folded and stored as before. This phase only reads.
- **The raw feed.** `GET /attention` still returns full intervals for the
  Usage page.
- **The ledger and temptations.** Untouched.

## Known limits

- **Browser only.** The phone reports temptations, not attention. A day
  spent in apps shows up here as silence.
- **Two browsers can overlap.** Each beacons independently; minutes from
  simultaneous spans both count. One person rarely watches two screens, so
  the error stays small — and it errs against me, which is the safe
  direction for a skeptical judge.
- **Quiet is ambiguous.** No fresh beacon may mean I am away, or in a
  browser with the extension off, or on the phone. The summary says only
  "no recent signal" and lets the judge weigh it.

## Success check

I idle on YouTube for half an hour, then ask the coach for a break. Its
reply shows it knew where the half hour went — without my telling it.
