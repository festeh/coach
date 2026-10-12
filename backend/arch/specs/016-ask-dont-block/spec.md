# Spec: Ask, Don't Block

## Problem

The coach was a judge. Opening something blocked meant arguing a case
with a skeptical agent that could refuse, and the ways around it, the
peek and the override with its escalating wait, existed only to make
getting past a hard block cost something. The friction landed on honest
reasons as hard as on cravings, and the judging set the tone of every
exchange.

## What we build

**The coach asks; it doesn't decide.** Opening something blocked still
lands the user in the chat, which now knows what they were opening. They
say why. Any understandable reason, however small, gets a warm, short
reply with an **OK · N min** button under it, and OK opens the lock. The
coach may say what it notices ("third time today"), but it never refuses.

**Only nonsense is asked again.** "idk", "because", "nothing", ".",
keyboard mash: the coach gently asks for the reason in a few words and
offers no OK. Retrying costs nothing.

**The coach picks the time.** 10 minutes by default, fitted to the
reason, 30 at most. When the time runs out the lock comes back, and the
next opening asks again. No "still on it?" check in between.

**A dead chat never strands anyone.** If the chat service is unreachable
or a turn fails, the client offers OK itself for the reason just typed:
10 minutes, journalled as a fallback.

**Peek, override and the override wait are gone,** with the ledger,
the denial journal and the agent's release and denial tools.

## How the pieces talk

- Chat socket (my-agents): a `message` frame may carry
  `context: {blocked}`, the site or app the user was opening. The coach's
  `offer_ok(minutes)` tool attaches `{type: "ok", minutes, at}`, sent
  after the reply's text and before `done`, and replayed in history under
  the reply's `events`.
- Clients show OK only on the latest reply, only while its `at` is under
  five minutes old, and only until clicked, so an old OK in the history
  never opens the lock without a fresh reason.
- Coach socket: OK sends `{type: "release", duration, message,
  agent_message, source}`, `source` being `chat` or `fallback`. The
  backend checks only the shape (1 to 1800 seconds, a reason, a known
  source), releases, journals a `grant` row with that source, and
  broadcasts.
- The focus frame loses the six `override_*` fields.
  `POST /agent-lock/release` and `POST /lock-decisions` go: nothing calls
  them now.

## What does not change

One shared release for the browser and the phone. The allow list. Focus
sessions, including that a release ends a running one. Android's rule
challenges. Older `override`, `short_unblock` and `denial` rows stay in
the journal and still count toward the days they happened on.

## Rollout

Clients first: the old browser build requires the `override_*` fields
and silently drops frames without them. Then the backend and my-agents
together: an OK needs the new coach to offer it and the new backend to
take it.
