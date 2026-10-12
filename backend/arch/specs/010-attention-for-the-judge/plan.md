# Plan: Attention for the Judge

Phase C of the agent-lock rework. Two repos: `coach` (server), `my-agents`
(judge). See `spec.md` for the why.

## Tech Stack

- Go server: `internal/stats/attention.go` (new, pure fold + tests),
  `internal/api.go` (handler), `internal/server.go` (route)
- Python judge: `my_agents/coach/client.py`, `my_agents/coach/agent.py`
- No new storage; reads the existing `attention` collection

## Structure

```
coach/internal/
├── stats/attention.go       # SummarizeAttention: pure fold over intervals
├── stats/attention_test.go  # clipping, top-site order, fresh vs stale "now"
├── api.go                   # AttentionSummaryHandler
└── server.go                # route /attention/summary

my-agents/my_agents/coach/
├── client.py                # get_attention_summary()
└── agent.py                 # render_prompt: attention lines in "Right now"
```

## Approach

### 1. Fold (coach)

`stats.SummarizeAttention(intervals, dayStart, now)` — a pure function so it
unit-tests without PB:

- Clip each interval to `[dayStart, now]`; skip rows with malformed times.
- Sum `state == "site"` minutes into `site_minutes_today`; sum per site for
  `top_sites_today` (top five, at least one minute, minutes descending, name
  as tie-break). Site spans with an empty site (browser-internal pages) count
  toward the total but not the top list.
- `now`: the interval with the latest `last_seen`; fresh when within 90s of
  now. Minutes run from the span's unclipped start.

### 2. Endpoint (coach)

`GET /attention/summary` in `api.go`: day start is server-local midnight
(matching every other "today"), fetch via the existing
`GetAttentionIntervals`, fold, write JSON. Nil-DB guard returns the empty
summary, like `writeLockState`.

### 3. Judge (my-agents)

- `client.py`: `get_attention_summary()` — GET, raise on non-200.
- `agent.py`: `render_prompt` fetches the summary in its own try/except and
  appends to "Right now":
  - `On youtube.com for the last 40m.` / `Browser idle for the last 8m.` /
    `No recent browser signal.`
  - `Today: 3h 32m on sites — youtube.com 1h 24m, github.com 1h 1m, ...`
  - On any error, the lines are omitted; the focus/lock lines are unaffected.
- Workflow step 2 grows one sentence telling the judge to weigh the
  attention picture.

## Deploy order

Server first; the judge's fetch fails soft until the endpoint exists, so the
order barely matters. Both repos auto-deploy on push.

## Risks

- **Prompt bloat.** Two lines, capped at five sites. Negligible.
- **Per-call latency.** One more HTTP GET per model call, against a server
  on the same box as the judge. Accepted.
- **Open endpoint** until Phase F, like its siblings. Accepted, time-boxed.
