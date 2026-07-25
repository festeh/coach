# Spec: Override Cooldown

## Problem

Spec 012 gave me an escape hatch and priced it at a written reason. The
price turned out to be too low. Typing eight words costs nothing when I
have already decided, and the ledger only shames me tomorrow — by which
time the fifteen minutes are spent. An escape hatch I can pull twenty
times a day is not a hatch, it is a door.

What is missing is a cost that grows. The first override of a day is
genuinely an accident and should stay free. The fifth is a habit wearing
an emergency's clothes, and it should have to wait.

## What we build

Two things.

**A confirmation.** Writing the reason no longer takes the override; it
earns the right to be asked once more, on a screen that names the price:
which override of the day this is, what it buys, and what the next one
will cost. Two deliberate taps instead of one, with the number in
between.

**An escalating cooldown.** The first override each local day is free.
Every one after it makes the next wait thirty seconds longer — 30s, 60s,
90s — and the count resets at local midnight, so a bad Tuesday does not
punish Wednesday.

The clock runs from the moment the lock re-engages, not from the tap.
Measured from the tap, a 30-second cooldown would expire 870 seconds
before the release it followed and would never refuse anything.

**The way out.** While a cooldown runs the override is off the table, so
there is one door left: **Unblock 30s**. No reason asked — thirty seconds
is short enough that the friction would cost more than it buys — and
capped at two per cooldown window. Free, because the point is to soften
a wait I have already earned, and capped, because a free unlock I can tap
forever is the cooldown's way out rather than mine.

## How the pieces talk

- **Server owns the count.** The `lock_decisions` journal already
  records every override with a timestamp, so the day's tally is derived,
  not invented. State keeps it in memory and reloads it from the journal
  on startup: a restart is not a way to buy back a free override or a
  fresh pair of hatches.
- **The rule is one function.** `computeCooldown` takes the day's ledger
  and a clock and returns the price. Everything else — the WebSocket
  handler, the focus frame, `/agent-lock/state` — reads that answer.
- **Cooldown rides the focus frame.** The four numbers are flattened
  into the frame the phone already collects, so there is no second
  endpoint and no extra round trip. `override_next_cooldown_seconds` is
  sent rather than derived, so no client needs its own copy of the step.
- **Refusals are explicit.** The server answers a declined override or
  hatch with `unblock_refused`, carrying the live cooldown. The phone can
  usually predict a refusal from the state it holds, but its copy is up
  to a poll interval stale, so the refusal settles close calls.
- **The phone runs the clock.** The server sends the remaining seconds
  once per frame; the phone counts down locally between frames and
  resyncs on every one, so the banner ticks without a chatty socket.

## What does not change

- **Fifteen minutes.** Still fixed, still not stretchable. The cooldown
  prices *how often*, never *how long*.
- **The judge.** Not consulted, not notified. It reads the ledger.
- **The HTTP release path.** `/agent-lock/release` stays the agent's
  road and is not refused by the cooldown: the agent's authority over the
  lock is not my budget. It does bill the same ledger when
  `is_override=true`, so both roads to a `kind = override` row tell the
  same story about the day.
- **Grants.** A coach-granted release is not an override and costs
  nothing.

## Known limits

- **Thirty seconds is small next to fifteen minutes.** Early cooldowns
  barely register; the escalation only bites after several overrides in a
  day. That is deliberate — the first mistake stays cheap — but it means
  the feature does nothing visible on a quiet day.
- **Hatch uses land in the unlock ledger.** They take the lock off, so
  they count in `GetUnlockStats` alongside grants and overrides. The
  phone's sparkline will show slightly more, shorter unlocks than before.
- **A grant during a cooldown lets it tick.** If the coach extends the
  release past an override's window, the cooldown runs while the lock is
  still off. Harmless: nobody needs an override during a release.
- **The reason is still not validated.** "asdf" still works, twice as
  deliberately.

## Success check

The coach says no. I override for the pharmacy — free, first of the day,
two taps with the price shown. Twenty minutes later I ask again and the
menu item is dead: *Override · 30s to go*, with a banner saying so and a
30-second door beside it. I take one of the two, read the thing I needed
to read, and the lock closes. At midnight the tally resets and tomorrow's
first mistake is free again.
