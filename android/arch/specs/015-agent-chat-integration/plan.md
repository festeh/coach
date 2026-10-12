# Plan: Coach Agent Chat Integration

**Spec**: arch/specs/015-agent-chat-integration/spec.md

This work spans three repos. Most of it lands in `coach_android` (this repo). The token-trim policy lives in `~/projects/my-agents`. The Caddy `/api/*` opening lives in the infra repo.

## Tech Stack

- **Language**: Kotlin (Android), Python (my-agents), Caddyfile (infra)
- **Framework**: Jetpack Compose + OkHttp WebSocket (Android), LangGraph + FastAPI (my-agents)
- **Storage**: PreferencesManager for the persistent `thread_id`. No new Room tables — chat history lives server-side in the existing LangGraph SQLite checkpointer (`./coach.db` under my-agents).
- **Testing**: None on Android (matches project convention). Pytest for the my-agents trim logic.

## Structure

### `coach_android` (this repo) — new and changed files

```
android/app/src/main/
├── AndroidManifest.xml                          # CHANGE — add AGENTS_URL meta-data, register ChatActivity
└── kotlin/com/example/coach_android/
    ├── data/
    │   ├── model/
    │   │   └── FocusData.kt                     # CHANGE — add agentReleaseTimeLeft: Int?
    │   ├── preferences/
    │   │   └── PreferencesManager.kt            # CHANGE — get/set persistent thread_id, agents URL
    │   └── agentchat/
    │       ├── AgentChatService.kt              # NEW — WebSocket client to my-agents server
    │       └── ChatMessage.kt                   # NEW — UI-side message model (role + content + streaming flag)
    ├── service/
    │   └── MonitorLogic.kt                      # CHANGE — three-way decision in shouldShowOverlay; expose
    │                                            #          isAgentLocked; launch ChatActivity for forced flow;
    │                                            #          re-assert hook
    ├── di/
    │   └── AppContainer.kt                      # CHANGE — construct AgentChatService once
    ├── ui/
    │   ├── chat/
    │   │   ├── ChatActivity.kt                  # NEW — full-screen Activity hosting Compose
    │   │   ├── ChatScreen.kt                    # NEW — Compose UI: message list + input + PRETTYPLEASE hint
    │   │   └── ChatViewModel.kt                 # NEW — observes AgentChatService + MonitorLogic, manages thread
    │   ├── components/
    │   │   └── AgentLockCard.kt                 # NEW — sibling to FocusStatusCard, shows lock/release state
    │   └── apps/
    │       └── AppsScreen.kt                    # CHANGE — add AgentLockCard next to FocusStatusCard
    └── env/
        └── EnvConfig.kt (or wherever WEBSOCKET_URL is read today)  # CHANGE — also read AGENTS_URL
```

### `~/projects/my-agents` — changes

```
my_agents/
└── coach/
    └── agent.py                                 # CHANGE — wire a pre-model history trimmer
                                                 #   (token cap 10000, drop ~1000 oldest tokens when over)
tests/
└── coach/
    └── test_history_trim.py                     # NEW — unit tests for the trimmer
```

### infra repo (separate) — Caddy

One change: in the `my-agents` site block, scope `forward_auth` to everything *except* `/api/*`. The `/api/*` matcher gets a plain `reverse_proxy` to the FastAPI backend with no auth gate.

## Approach

### 1. Read agent-lock state from the existing focusing WebSocket (`FocusData.kt`, `WebSocketService.kt`)

`WebSocketService.handleMessage` already passes the full JSON map through `_focusUpdates`. No changes there. Add one nullable field to `FocusData`:

```kotlin
data class FocusData(
    // ...existing...
    val agentReleaseTimeLeft: Int? = null,   // null = locked, positive = seconds until relock
)

val isAgentLocked: Boolean get() = agentReleaseTimeLeft == null
```

In `updateFromWebSocket` and `fromWebSocketResponse`, parse `agent_release_time_left`. The server sends this on every state change (focus AND agent lock), so the existing flow lights up automatically.

`hasSignificantDifference` should also flag a change in `isAgentLocked` so the UI re-renders promptly when the agent grants or expires a release.

### 2. Decision logic (`MonitorLogic.kt`)

Replace the two-state `shouldShowOverlay` with a three-way decision:

```kotlin
private enum class BlockMode { CHAT, STANDARD_OVERLAY, NONE }

private fun blockMode(packageName: String): BlockMode {
    if (!monitoredPackages.contains(packageName)) return BlockMode.NONE
    if (_focusData.value.isAgentLocked) return BlockMode.CHAT          // agent wins
    if (_focusData.value.isFocusing) return BlockMode.STANDARD_OVERLAY
    return BlockMode.NONE
}
```

In `onAppChanged`, dispatch on `blockMode(packageName)`:
- `CHAT` → hide any standard overlay; launch `ChatActivity` with `EXTRA_FORCED=true` and `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`.
- `STANDARD_OVERLAY` → existing `overlayManager.show(packageName)` path.
- `NONE` → existing hide path.

`ensureOverlay` gets a sibling `ensureBlock(packageName)` that handles both surfaces. The existing "bypass via recents" fix stays intact for the standard overlay; a parallel guard re-launches `ChatActivity` if the user navigates back to a monitored app while still agent-locked and the chat Activity isn't currently in front.

### 3. Chat WebSocket client (`AgentChatService.kt`)

OkHttp WebSocket client to `wss://<AGENTS_URL>/api/coach/ws/<thread_id>`. Mirrors the structure of `data/websocket/WebSocketService.kt` (reconnect with exponential backoff, supervised coroutine scope). One instance constructed by `AppContainer` and shared.

Public surface:

```kotlin
class AgentChatService(private val baseUrl: String, private val threadId: String) {
    val events: SharedFlow<ChatEvent>      // History | Chunk | Done | Error | Connecting | Connected
    fun connect()
    fun send(content: String)              // sends {"type":"message","content":...}
    fun disconnect()
}
```

Server frames map cleanly to events:
- `{"type":"history","messages":[...]}` → `ChatEvent.History(messages)`
- `{"type":"chunk","content":"..."}` → `ChatEvent.Chunk(text)`
- `{"type":"done","message_id":"..."}` → `ChatEvent.Done`
- `{"type":"error","message":"..."}` → `ChatEvent.Error(message)`

Connection only opens when `ChatActivity` is in `onStart`; closes in `onStop`. We don't need a persistent connection — the thread state lives server-side, history loads on each (re)connect.

### 4. Persistent thread_id (`PreferencesManager.kt`)

```kotlin
fun getOrCreateAgentChatThreadId(): String {
    val existing = prefs.getString(KEY_AGENT_THREAD_ID, null)
    if (existing != null) return existing
    val fresh = UUID.randomUUID().toString()
    prefs.edit().putString(KEY_AGENT_THREAD_ID, fresh).apply()
    return fresh
}
```

One thread per install. App reinstall = fresh thread. No reset surface in v1.

### 5. ChatActivity + ChatScreen + ChatViewModel

**ChatActivity** (`ChatActivity.kt`):
- `setContent { CoachAndroidTheme { ChatScreen(...) } }`.
- Reads `EXTRA_FORCED: Boolean` from intent, hands it to the ViewModel.
- `singleTop` launchMode in manifest so re-launches don't stack.
- Window flags for full-screen feel: `FLAG_KEEP_SCREEN_ON`, `FLAG_SHOW_WHEN_LOCKED` only if needed (probably not — agent lock isn't security-critical).
- Back press: forced mode → `dismissAndNavigate()` (same helper as overlays use today: launch the configured target app or home, then `finish()`). Voluntary mode → plain `finish()`.

**ChatViewModel** (`ChatViewModel.kt`):
- `AndroidViewModel`. Reaches `MonitorLogic` via `FocusMonitorService.getInstance()?.getMonitorLogic()` (existing pattern from `AppsViewModel`).
- Reaches `AgentChatService` via `AppContainer` accessor (will need to expose getter through the service singleton, mirroring `getMonitorLogic()`).
- Holds `StateFlow<ChatUiState>`: `messages: List<ChatMessage>`, `connecting: Boolean`, `streaming: Boolean`, `error: String?`.
- On init: connects, observes `events` flow, reduces into `messages` (appends user message on send, assembles assistant chunks into the last assistant message, marks done).
- Observes `monitorLogic.focusData`. When `agentReleaseTimeLeft` transitions from `null` → non-null, emits `dismissEvent` → Activity finishes. (This satisfies "auto-close within a few seconds.")
- Send: optimistically append user message, then `agentChatService.send(content)`.

**ChatScreen** (`ChatScreen.kt`):
- `LazyColumn` of message bubbles (user right, assistant left). Auto-scroll to bottom on new content.
- Streaming chunks append to the last assistant bubble.
- Bottom: text field + send button. Field is enabled even while streaming (server queues).
- Below the input, a one-liner hint: *"Type **PRETTYPLEASE** for a 15-minute override."* Subtle, low contrast, always visible.
- Error banner at the top when present, with a Retry button that calls `viewModel.reconnect()`.
- When `connecting && messages.isEmpty()`, show a small spinner where the list would be.

### 6. AgentLockCard + AppsScreen wiring

**AgentLockCard** (`ui/components/AgentLockCard.kt`): Compose card mirroring `FocusStatusCard`'s look.
- States:
  - **Locked** — title "Agent lock engaged", body "Tap to chat with coach".
  - **Released** — title "Released for N min", body "Tap to chat".
- `Card(onClick = openChat)` — `openChat` calls `context.startActivity(Intent(context, ChatActivity::class.java))` *without* `EXTRA_FORCED`.

**AppsScreen.kt** — render `AgentLockCard` directly under `FocusStatusCard`. Both observe the same `focusData` from `AppsViewModel`'s state.

### 7. Manifest + env config

`AndroidManifest.xml`:
- Add `<meta-data android:name="AGENTS_URL" android:value="${AGENTS_URL}" />` next to the existing `WEBSOCKET_URL`.
- Register `ChatActivity` with `android:launchMode="singleTop"`, `android:exported="false"`, `android:configChanges="orientation|screenSize|keyboardHidden"` (avoid teardown on rotation).

`build.gradle` placeholders: read `AGENTS_URL` from `.env` the same way `WEBSOCKET_URL` is read today.

Wherever the WebSocket URL is parsed from manifest meta-data, also parse `AGENTS_URL` and pass it into `AppContainer`.

### 8. my-agents history trimmer (separate repo)

In `my_agents/coach/agent.py`, attach a `pre_model_hook` (or message reducer) to the LangGraph react agent that:
1. Counts tokens across `state.messages` using the model's tokenizer (`tiktoken.encoding_for_model` if OpenAI-compatible — falls back to a rough char/4 estimate if the model is unknown, since CLIPROXYAPI proxies many providers).
2. If total > 10_000, drops oldest non-system messages until total ≤ 9_000 (~1k removed in one pass; avoids retrimming on every turn).
3. Always preserves the system prompt and any tool-call/tool-result pairs (don't strip a tool call without its result — that'll confuse the model).

Add `tests/coach/test_history_trim.py` covering: under cap is no-op, over cap drops oldest user/assistant pairs, system prompt is never dropped, tool-call/tool-result pairs stay together.

### 9. Caddy (infra repo)

In the `my-agents` site block, replace the blanket `forward_auth` with a matcher-scoped one:

```caddyfile
@api path /api/*
handle @api {
    reverse_proxy localhost:8001
}
handle {
    forward_auth localhost:4180 { ... existing ... }
    reverse_proxy localhost:8001
}
```

Order matters in Caddy `handle` blocks; the matcher block runs first.

## Risks

- **Activity launch latency vs. user seeing the monitored app**: the user opens, say, Instagram, and there's a ~100–300ms gap before `ChatActivity` covers it. The current overlay is a `WindowManager.addView`, which is faster than `startActivity`. *Mitigation*: keep an empty pre-warmed Activity instance via `singleTop` + `FLAG_ACTIVITY_REORDER_TO_FRONT` so subsequent launches reuse it. If still too slow, fall back to a thin transparent overlay that immediately covers the screen and then launches the Activity behind it.
- **Re-assert loop**: `AppMonitorHandler` polls foreground app every ~1s. When `ChatActivity` is foreground, `packageName == "com.example.coach_android"`, which is not in `monitoredPackages`, so `onAppChanged` is a no-op. Good. The risk is the opposite: user backs out → monitored app foregrounds → `onAppChanged` fires → re-launches `ChatActivity`. This is exactly what we want, but in the *voluntary* flow we must **not** set `EXTRA_FORCED=true` even if `MonitorLogic` re-launches. Resolution: `MonitorLogic` always launches with `EXTRA_FORCED=true`, since it only launches in response to a monitored-app open. Voluntary launches come from the AgentLockCard with no extra. The two paths never collide because they have different triggers.
- **Auto-close while user is typing**: agent grants release → Activity finishes → user loses their half-typed reply. *Mitigation*: when `dismissEvent` fires, show a 2-second toast "Released — chat closing" before `finish()`. Keep it simple, don't try to preserve the draft.
- **Public `/api/*`**: anyone with the URL can chat with the agent and burn tokens, or call `grant_release` indirectly via the agent. Already named in the spec; living with it.
- **Token trimmer dropping a tool result**: would corrupt the conversation state. The test suite must cover the tool-call/tool-result invariant explicitly.
- **WebSocket reconnect cycles burning battery if my-agents is down**: the ChatService should only attempt reconnect while `ChatActivity` is in `onStart`. When the Activity stops (user left), reconnects stop too.
- **Manifest placeholder mistake**: forgetting to add `AGENTS_URL` to the gradle env-loader will result in a literal `${AGENTS_URL}` string at runtime. Easy to catch in the first manual build.

## Open Questions

_(none — all spec decisions are locked. Implementation can begin.)_
