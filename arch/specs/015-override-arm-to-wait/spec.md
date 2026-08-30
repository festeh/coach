# Spec: Override Arm-to-Wait

## Problem

Spec 014 priced the habit with an escalating cooldown, but it ran the
clock in the background: it started when the release lapsed and ticked
whether or not anyone wanted anything. Come back an hour later and the
wait had quietly served itself — the next override was one tap again,
and the price was never felt. A cooldown nobody sits through stops
nothing.

## What we build

**The wait starts at the ask.** The first override of each local day is
still free and immediate. Every one after it takes two taps: the first —
with the written reason — arms a countdown of `redeemed-today × 30s`;
when it lapses, a second tap redeems the fifteen minutes. Nothing ever
counts down while nobody is waiting for it.

**A short claim window.** A ripened request stays claimable for two
minutes, then evaporates. Long enough to notice the wait has ended, too
short to arm a request over breakfast and cash it in when the craving
hits. Evaporating costs nothing: walking away is the win, so only
redeemed overrides escalate the price, and an abandoned request re-arms
at the same cost.

**The peek is its own door.** `short_unblock` — 30 seconds, no reason —
is no longer tied to a cooldown window and no longer capped. It is
available whenever the lock is on, unlimited, because having to come
back and tap again every half-minute is the deterrent, and the journal
keeps every use visible to the judge.

**Released means released.** Any granted release — a redeemed override,
a peek, an agent grant — ends a running focus session, and the journal
row for the session is truncated to the time it actually lived (a
stacked extension that never started is deleted). The header saying
"Released" while a second lock keeps every page shut was a lie; now it
is the truth, and a peek during focus knowingly costs the session.

## State machine

```
idle ──tap+reason──► granted (first of day)          15 min, redeemed+1
idle ──tap+reason──► cooling                          countdown = redeemed × 30s
cooling ──tap──► cooling                              no-op, no penalty
cooling ──countdown lapses──► ready                   2-minute window
ready ──tap──► granted                                15 min, redeemed+1
ready ──window closes──► idle                         evaporates, same price
any ──local midnight──► idle                          counters zeroed, arm dropped
```

The agent's HTTP road (`POST /agent-lock/release` with `is_override`)
still bypasses the wait — the agent's authority over the lock is not the
user's budget — but it bills the day's tally and retires any pending
request.

## Wire

The focus frame carries the whole story, flattened as before:
`overrides_today`, `override_phase` (`idle` | `cooling` | `ready`),
`override_cooldown_seconds`, `override_cooldown_remaining`,
`override_redeem_remaining`, `override_next_cooldown_seconds`.
`unblock_refused` is gone: nothing is refused any more — a tap either
changes the state or is a no-op the client can already see. Clients tick
the remaining seconds locally and ask for a fresh frame (`get_focusing`)
when a countdown crosses zero, so the redeem window's length stays the
server's to own.

## Journal

`override_armed` is a new decision kind: click one, with its reason, at
duration 0 — so abandoned cravings still tell their story. Redemptions
and the free first stay `override` at 900s, and the arming reason rides
the redemption row. Restart restores the tally from today's `override`
rows and a live countdown from the newest unanswered `override_armed`
row: a restart hands back neither a free override nor a skipped wait.
