# Plan: Dimaist Integration for AI Hook Context

## Tech Stack

- Language: Go
- External API: Dimaist REST API (no auth, JSON over HTTP)
- Env: `DIMAIST_URL` (e.g. `https://dimaist.dimalip.in`)

## Structure

New and changed files:

```
internal/
├── dimaist/
│   └── client.go        # HTTP client: fetch tasks, filter today's
├── hook_ai.go           # Refactor: context-gathering step + AI call
└── server.go            # Init dimaist client, pass to AI hook
```

## Approach

### 1. Dimaist HTTP client (`internal/dimaist/client.go`)

Create a thin client that calls `GET /tasks` on the dimaist API and filters for today's tasks client-side.

- `NewClient(baseURL string) *Client` — stores base URL, creates `http.Client` with 10s timeout.
- `GetTodayTasks(ctx context.Context) ([]Task, error)` — fetches all tasks, filters where `due_date` or `due_datetime` is today or earlier (not completed, not deleted). This matches the CLI's `--due today` logic.
- `Task` struct — minimal: `ID`, `Title`, `Description`, `DueDate`, `DueDatetime`, `CompletedAt`, `Labels`, `Project` (just `Name`). Only what we need for context.

Init is non-fatal: if `DIMAIST_URL` is unset, client is nil and the hook skips task context.

### 2. Refactor AI hook into context-gathering + AI call (`hook_ai.go`)

Split the current `Run` function into two phases:

**Phase 1 — Gather context:**
- Get coach focus info (existing: `ctx.State.GetCurrentFocusInfo()`)
- Get today's tasks from dimaist (new: `dimaistClient.GetTodayTasks()`)
- Build a structured user message combining both

**Phase 2 — AI call:**
- Same as today: send system prompt + user message to AI, store result, broadcast.

The user message becomes richer:

```
## Focus Stats
- Currently focusing: false
- Sessions today: 3
- Time since last change: 120s

## Today's Tasks
1. Write API docs (project: Work)
2. Review PR #42 (project: Work)
3. Buy groceries
```

`NewAIHookDef` gains an optional `*dimaist.Client` parameter. If nil, the tasks section is omitted.

### 3. Wire it up in `server.go`

- Read `DIMAIST_URL` from env.
- If set, create `dimaist.NewClient(url)`.
- Pass client to `NewAIHookDef(aiClient, dimaistClient)`.

## Risks

- **Dimaist API down**: The hook should not fail entirely. Catch the error, log it, and proceed with coach-only context.
- **Large task list**: Dimaist returns all tasks. For now this is fine (personal use), but we filter client-side to keep the AI message focused on today.

### 4. Better default system prompt

Replace the generic "You are a focus coach" prompt with one that:
- Encourages the user to do more focus sessions
- References their actual tasks to help prioritize
- Keeps it brief and actionable (not a wall of text)
- Tone: direct, motivating, not cheesy

## Open Questions

None — requirements are clear.
