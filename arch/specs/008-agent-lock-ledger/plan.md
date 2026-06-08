# Plan: Agent Lock Ledger

Phase A of the agent-lock rework. Two repos change: `coach` (Go server) and
`my-agents` (the judge). See `spec.md` for the why.

## Tech Stack

- Go server: `internal/api.go`, `internal/state.go`, `internal/db/lock_decisions.go` (new)
- Python agents: `my_agents/coach/agent.py`, `my_agents/coach/client.py`; `state.py` retires
- PocketBase collection `lock_decisions`, auto-migrated on server start (agent_lock pattern)

## Structure

```
coach/
├── internal/
│   ├── db/lock_decisions.go # collection schema, Ensure, insert, today-queries
│   ├── api.go               # release gains fields; new /lock-decisions, /agent-lock/state
│   └── server.go            # EnsureLockDecisionsCollection in NewServer
my-agents/
└── my_agents/coach/
    ├── client.py            # pass-through fields; get_lock_state(); post_denial()
    ├── agent.py             # tools call the server instead of state.py
    └── state.py             # deleted
```

## Approach

### 1. Collection (coach)

`lock_decisions`: `kind`, `source`, `user_message`, `agent_message`,
`duration_seconds`. All text/number, nothing required except `kind`. PB's
`created` autodate is the decision time. Follow `agent_lock.go`: schema
literal, `EnsureLockDecisionsCollection()`, called from `NewServer` with a
log-and-continue on failure. Temptations are a separate collection, built in
Phase B.

### 2. Write paths (coach)

- `AgentLockHandler` `/release`: read optional `agent_message`,
  `user_message`, `is_override` form fields. After a successful release,
  insert a row — kind `override` when the flag is set, `grant` otherwise
  (async goroutine, log on failure — same stance as
  `persistAgentReleaseUntilLocked`). The flag exists only on the wire; the
  stored kind carries it.
- `/engage`: untouched. No caller today, no journal row — see spec's
  out-of-scope note.
- New `POST /lock-decisions`: JSON body `{user_message, agent_message}`.
  Writes a `denial` row. This endpoint exists only for denials; grants,
  overrides, and engages go through the lock endpoints that actually change
  state. Reject any body carrying a `kind` — kind is implied.

### 3. Read path (coach)

New `GET /agent-lock/state` returns:

```json
{
  "released_seconds_today": 1800,
  "override_count_today": 1,
  "recent": [ {"at", "kind", "user_message", "agent_message", "duration_seconds"} ]
}
```

One query against `lock_decisions`: today's rows sorted by `created`,
aggregated in Go. `released_seconds_today` sums durations over kinds `grant`
and `override`; `override_count_today` counts kind `override`. "Today" uses
the server's local time, matching `GetTodayFocusCount`. `recent` is the last
5 rows — same shape `get_state()` serves today. (Phase B adds
`temptation_count_today` from the temptations collection — a second query.)

### 4. Judge rewiring (my-agents)

- Fields split by source of truth. Code supplies the facts: the serving
  layer stashes each incoming raw message in a contextvar before invoking
  the graph, and the tools read `user_message` from it — the model never
  retypes my words. The model supplies only its own output: `agent_message`
  (docstring: "the message you are telling the user, not a summary") and
  the duration.
- `client.py`: `release_agent_lock(duration, agent_message, user_message, is_override)`
  sends the new form fields. Add `get_lock_state()` → `GET /agent-lock/state`
  and `post_denial(user_message, agent_message)` → `POST /lock-decisions`.
- `agent.py`: `grant_release(duration_seconds, agent_message, is_override)` and
  `log_denial(agent_message)` drop their `state.py` writes and their
  user-text parameters; both rely on the server and the contextvar.
  `get_state` returns `get_lock_state()` verbatim.
- Delete `state.py` and its tests; the prompt text does not change.

### 5. Deploy order

Server first, agents second. The old agents binary calls `/release` without
the new fields — the server records a grant with empty reason, nothing
breaks. `coach.state.json` stays on disk as a historical artifact; nothing
reads it.

## Risks

- A PB outage makes ledger writes fail silently (async, logged). The lock
  itself still works; we lose journal rows. Same trade the agent-lock
  persistence already makes.
- `get_state` becomes a network hop per judged message instead of a local
  file read. Single user, one query — acceptable.
- Until Phase F, `/lock-decisions` is unauthenticated. Anyone could write
  fake denial rows. Accepted, time-boxed by F.
