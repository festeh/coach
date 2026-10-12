# Spec: Agent Lock Ledger

## Problem

The coach agent decides when to unlock my browsing and apps. I tell it why I
need a break. It grants or denies. Today those decisions land in
`coach.state.json` — a file on the agents host, capped at 50 entries, trimmed
after 30 days. I cannot browse it. The database knows nothing about it.

This breaks two promises:

1. **I want to inspect my reasons.** Every plea, grant, denial, and override
   should sit in one place I can read.
2. **The judge should remember what the database remembers.** Today the
   agent's memory and my records are different systems. They will drift.

## What we build

One PocketBase collection, `lock_decisions`, becomes the record of every lock
decision. The coach server owns it. The agent writes to it through the server
and reads its own history back from it. The JSON state file retires.

Each decision is one row:

| Field | Meaning |
|---|---|
| `kind` | `grant`, `override`, or `denial` |
| `source` | who reported it (always `agent` for now) |
| `user_message` | what I said, in full — copied from the wire by code, never retyped by the model |
| `agent_message` | what the coach tells me, in its own words |
| `duration_seconds` | how long a grant or override runs (empty for a denial) |

Three kinds, one shape: every row is a plea and the coach's answer. Grants
and overrides add how long; a denial leaves that empty.

Temptations — the sites and apps I hit while locked — are a different kind of
record. They come from the browser and the phone, not the agent, and carry a
target instead of a dialogue. They get their own collection when Phase B adds
them, not a sparse corner of this one.

## How the pieces talk

- **Grant.** The agent calls `POST /agent-lock/release` as before, now with
  `agent_message`, `user_message`, and an override flag attached. The server
  releases the lock and writes a `grant` row — or an `override` row when the
  flag is set.
- **Denial.** The agent calls the new `POST /lock-decisions` with my message
  and its answer. The server writes a `denial` row.
- **Judge memory.** The agent's `get_state()` tool calls the new
  `GET /agent-lock/state`. The server answers from the collection: seconds
  released today, override count, recent requests with outcomes.

## What does not change

- Lock semantics: locked by default, releases extend and never shorten,
  snap-back timer, restart recovery.
- The chat protocol between Android and the agents service.
- The PRETTYPLEASE override: still a 15-minute grant. It just gets recorded
  as an `override` row where I can see it.

## Out of scope (later phases)

- Temptation reporting from browser and Android (B).
- Attention summary in the judge's context (C).
- Browser chat surface (D), override buttons (E), auth tokens (F).
  Until F lands, the new endpoints stay as open as the old ones.
- Re-locking early. The `/agent-lock/engage` endpoint already exists but no
  client or agent tool calls it. We leave it asleep and journal nothing for
  it. It wakes up with a "lock me now" button in a later UI phase.

## Success check

I ask the coach for a break from my phone. I open PocketBase and see the
request, the verdict, and the reason as rows. The agent, asked again an hour
later, cites that earlier decision — read from the same rows.
