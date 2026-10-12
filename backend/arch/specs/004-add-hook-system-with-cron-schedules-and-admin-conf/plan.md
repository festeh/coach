# Plan: Configurable Hook System

## Tech Stack

- Language: Go (backend), TypeScript (frontend)
- Framework: SolidJS + Vite (existing admin SPA)
- Storage: PocketBase (new `hooks` collection for config)
- Scheduler: Simple `time.AfterFunc` chain (no cron library needed)
- AI Client: `github.com/openai/openai-go` (official SDK, supports custom base URL for CLIProxyAPI)
- Testing: None for first pass

## Concept

Hook implementations live in Go code. Each hook has a unique ID (e.g., `ai_request`). The admin page configures *when* each hook runs and with what parameters. Configs persist in PocketBase.

**Trigger type:**
- `scheduled` — fires on a daily repeating window (see schedule model below)

### Result Delivery

Hook results are **stored in PocketBase** (`hook_results` collection). After storing, the server broadcasts the full result via WebSocket: `{type: "hook_result", hook_id: "...", content: "...", id: "..."}`. Clients that are connected get the payload immediately and can choose to show it or not. Clients that come online later fetch unseen results from the API.

This means:
- Results survive if nobody's connected (persisted in PocketBase).
- No duplication across devices — all clients read from the same store.
- Connected clients get the result instantly without an extra fetch.
- Each client decides whether to display it.

### Run Heuristics

Scheduled hooks check two conditions before running:

1. **At least one WebSocket client is connected** — don't waste AI calls if nobody's there.
2. **Not currently in a focus session** — don't interrupt deep work.

If either condition fails, the hook silently skips that tick and waits for the next scheduled time. Event hooks (`focus_up`/`focus_down`) always run — the user is present by definition.

### Schedule Model

Instead of cron expressions, hooks use a daily window with a repeat interval:

- **First Run** — time of day to start (e.g., `09:00`)
- **Last Run** — time of day to stop (e.g., `21:00`)
- **Frequency** — how often to repeat within the window (e.g., `2h`, `30m`)

The hook fires at: First Run, First Run + freq, First Run + 2*freq, ... up to and including Last Run. Resets daily.

Example: First Run = 09:00, Last Run = 21:00, Frequency = 2h
→ Fires at: 09:00, 11:00, 13:00, 15:00, 17:00, 19:00, 21:00

## Structure

New and modified files:

```
internal/
├── hook.go              # Modified: new HookRunner with registry, triggers, scheduler, heuristics
├── hook_ai.go           # New: AI request hook implementation
├── ai/
│   └── client.go        # New: thin wrapper around openai-go SDK
├── server.go            # Modified: init HookRunner, load configs, start scheduler
├── state.go             # Modified: add HasClients() helper for heuristics
├── api.go               # Modified: add /api/hooks and /api/hook-results endpoints

admin/src/
├── app.tsx              # Modified: add HookManager component
├── api.ts               # Modified: add hook API helpers
└── components/
    └── hook-manager.tsx # New: list hooks, toggle, edit schedule
```

## Approach

### 1. AI client (`internal/ai/client.go`)

A thin wrapper around the official `openai-go` SDK, configured to point at CLIProxyAPI.

- Reads `AI_URL` and `AI_API_KEY` from env (added to `.env`).
- Creates an `openai.Client` with `option.WithBaseURL(aiURL)` and `option.WithAPIKey(apiKey)`.
- Single method: `Complete(ctx, systemPrompt, userMessage string) (string, error)`.
- Calls `client.Chat.Completions.New(...)` and returns the assistant message content.
- The SDK handles request formatting, error parsing, and retries.

### 2. Hook registry and runner (`internal/hook.go`)

Replace the current `Hook` callback type with a structured system:

```go
// ParamDef describes a configurable parameter the admin UI renders
type ParamDef struct {
    Key         string // e.g. "model", "prompt"
    Name        string // display label, e.g. "Model"
    Type        string // "text", "textarea", "select"
    Default     string // default value
    Options     []string // only for type "select"
}

type HookDef struct {
    ID          string
    Name        string
    Description string
    Params      []ParamDef // hook-specific configurable parameters
    Run         func(ctx HookContext) error
}

type HookConfig struct {
    HookID    string            `json:"hook_id"`
    Enabled   bool              `json:"enabled"`
    Trigger   string            `json:"trigger"`   // "scheduled" (more trigger types later)
    FirstRun  string            `json:"first_run"` // "HH:MM" time of day
    LastRun   string            `json:"last_run"`  // "HH:MM" time of day
    Frequency string            `json:"frequency"` // duration string like "2h", "30m"
    Params    map[string]string `json:"params"`    // hook-specific params, e.g. {"model": "...", "prompt": "..."}
}

type HookContext struct {
    Trigger string            // what triggered this run
    State   *State            // current focus state
    Server  *Server           // access to broadcast, db, etc.
    Params  map[string]string // resolved params (config values with defaults filled in)
}
```

The `Params` flow:
- `HookDef.Params` declares what params exist with defaults. Sent to frontend via GET /api/hooks.
- `HookConfig.Params` stores user overrides in PocketBase.
- `HookContext.Params` merges defaults + overrides, passed to `Run` at execution time.

**HookRunner** manages everything:
- Holds a registry of `HookDef` (populated at startup).
- Loads `HookConfig` from PocketBase on startup.
- Exposes `RunHook(hookID string)` called by the trigger endpoint — runs immediately, skips heuristics.
- Exposes `UpdateConfig(config HookConfig)` called by admin API — saves to PocketBase and reschedules.

**Scheduler logic** (for trigger = "scheduled"):
- On startup (and after config changes), calculate the next fire time:
  - Parse FirstRun and LastRun as today's times.
  - Starting from FirstRun, step by Frequency until we find a time > now.
  - If all times for today have passed, schedule FirstRun for tomorrow.
- Use `time.AfterFunc(duration)` to fire at the next time.
- When the timer fires, check heuristics before running:
  - `State.HasClients()` — at least one WebSocket client connected?
  - `!State.IsFocusing()` — not in a focus session?
  - If either fails, skip this tick silently.
- After each fire (or skip), calculate and schedule the next one.
- At midnight (or when LastRun passes), roll over to FirstRun next day.

No cron library needed — just `time.AfterFunc` chaining, same pattern the project already uses for focus expiry timers.

**Keep DatabaseHook as-is.** It's a hardcoded hook that always runs on focus_up. The new system is for user-configurable hooks.

### 3. AI request hook (`internal/hook_ai.go`)

The first configurable hook. Registered as:
- ID: `ai_request`
- Name: "AI Coaching Prompt"
- Description: "Sends focus context to AI and broadcasts the response"

**Configurable params:**
- `model` (text) — which model to use (e.g., "claude-sonnet-4-20250514"). Default: "claude-sonnet-4-20250514".
- `prompt` (textarea) — the system prompt. Default: "You are a focus coach. Provide a brief motivational message based on the user's focus session data."

When triggered, it:
1. Reads `model` and `prompt` from `ctx.Params`.
2. Gathers context: current focus state, today's session count.
3. Sends a chat completion request using the configured model and prompt.
4. Stores the result in PocketBase (`hook_results` collection).
5. Broadcasts `{type: "hook_result_new"}` via WebSocket to nudge connected clients.

Default trigger: `focus_down` (send a coaching nudge when focus ends).

### 4. Hook management API (`internal/api.go`)

Hook config endpoints:

- **GET /api/hooks** — List all registered hooks with their current config and param definitions.
- **PUT /api/hooks/{id}** — Update a hook's config. Saves to PocketBase and reschedules.
- **POST /api/hooks/{id}/trigger** — Run a hook once immediately. Skips all heuristics (client check, focus check). Always runs.

Hook results endpoints:

- **GET /api/hook-results** — List recent results (default last 20). Returns `[{id, hook_id, content, created}]`.
- **POST /api/hook-results/{id}/read** — Mark a result as read (so clients can track what's been seen).

### 6. PocketBase collection

New `hooks` collection with fields:
- `hook_id` (text, unique) — matches the Go hook ID
- `enabled` (bool)
- `trigger` (text) — "scheduled" (more trigger types later)
- `first_run` (text) — "HH:MM", only for scheduled
- `last_run` (text) — "HH:MM", only for scheduled
- `frequency` (text) — duration string like "2h", "30m", only for scheduled
- `params` (json) — hook-specific parameters, e.g. `{"model": "claude-sonnet-4-20250514", "prompt": "You are..."}`

New `hook_results` collection with fields:
- `hook_id` (text) — which hook produced this
- `content` (text) — the result content (e.g., AI response text)
- `read` (bool) — whether the user has seen it. Default: false.
- `created` (autodate) — PocketBase auto-populates this.

Create both collections via `cmd/coach_db/main.go` (extend the existing DB setup tool).

### 7. Admin frontend — hook manager (`admin/src/components/hook-manager.tsx`)

A new card in the admin dashboard:

- Lists each registered hook (fetched from GET /api/hooks).
- For each hook shows: name, description, enabled toggle, trigger dropdown.
- When trigger is "scheduled", shows three inputs:
  - **First Run** — time picker (`<input type="time">`)
  - **Last Run** — time picker (`<input type="time">`)
  - **Frequency** — dropdown with presets: "15 min", "30 min", "1 hour", "2 hours", "4 hours"
- **Hook-specific params** rendered dynamically from `ParamDef`:
  - `type: "text"` → `<input type="text">`
  - `type: "textarea"` → `<textarea>`
  - `type: "select"` → `<select>` with options from `ParamDef.Options`
  - Pre-filled with current value or default
- Save button calls PUT /api/hooks/{id}.
- "Trigger" button calls POST /api/hooks/{id}/trigger — runs immediately, skips heuristics.

### 8. Environment and secrets

Add two new env vars to `.env`:

```
AI_URL=https://ai.dimalip.in
AI_API_KEY=<from CLIPROXYAPI_API_KEYS>
```

Update the CI/CD workflow to include these as GitHub secrets and write them to the deployed `.env`.

## Implementation Order

1. `internal/ai/client.go` — AI client (standalone, testable)
2. `internal/hook.go` — HookRunner, registry, config loading, scheduler, heuristics
3. `internal/hook_ai.go` — AI hook implementation
4. `internal/state.go` — Add `HasClients()` helper
5. `internal/server.go` — Initialize HookRunner, register hooks, start scheduler
6. `internal/api.go` — Hook config + results + trigger endpoints
7. `cmd/coach_db/main.go` — Add hooks + hook_results collections
8. `admin/src/api.ts` — Hook API helpers
9. `admin/src/components/hook-manager.tsx` — Hook management UI
10. `admin/src/app.tsx` — Add HookManager to dashboard
11. `.env` + CI/CD — Add new secrets

## Risks

- **CLIProxyAPI format assumptions**: We assume OpenAI-compatible `/v1/chat/completions`. If the API differs, the client needs adjusting. Mitigation: test with `/api/hooks/{id}/test` endpoint.
- **PocketBase auth token expiry during hook execution**: The DB manager already handles token refresh on 401/403. The AI client is separate and uses a static API key, so no issue there.

## Open Questions

None — all resolved.
