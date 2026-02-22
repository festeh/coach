# Plan: Enrich AI Hook Context

## Tech Stack

- Language: Go
- Files: `internal/hook_ai.go`, `internal/dimaist/client.go`
- No frontend changes — admin "Show Context" button already previews this

## Structure

```
internal/
├── hook_ai.go           # GatherContext() gets three new sections
└── dimaist/
    └── client.go        # Add WasActiveToday() using /sync endpoint
```

## Approach

### 1. Current local time

Add `time.Now().Local().Format("Monday, 2006-01-02 15:04")` at the top of `GatherContext()` output. Gives the AI a sense of when in the day it is.

### 2. 14-day focus stats

Call `ctx.Server.DBManager.GetFocusHistory(14)` in `GatherContext()` and aggregate:
- Per-day breakdown: date, session count, total duration
- Summary: total sessions, total focus time, days with at least one session

### 3. Dimaist activity check

Add `WasActiveToday()` method to dimaist client. Use the `/sync?sync_token=<start-of-today>` endpoint — it returns tasks/projects modified since that timestamp. If the response has any changed items, the user interacted with dimaist today.

In `GatherContext()`:
- If active today: "User has been using their task manager today"
- If NOT active today: "User has NOT opened their task manager today — encourage them to review and plan their tasks"

This nudges the AI to remind the user to check their tasks.

## Risks

- Sync endpoint returns deleted IDs too, which count as activity: that's fine, any interaction counts.
