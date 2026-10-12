# Coach Agent Chat Integration

The coach server now has a second lock — the **agent lock** — held by a chat agent. The user negotiates release windows with the agent; the agent decides. This spec is about how the Android app behaves when the agent lock is engaged.

The agent lock is independent from manual focus. The phone is "blocked" when either is on.

## What Users Can Do

1. **See that the agent has the lock**

   - **Scenario: Open a monitored app while the agent lock is engaged**
     - **Given:** Agent lock is engaged on the server
     - **And:** Manual focus is off
     - **When:** User opens a monitored app
     - **Then:** A chat interface appears instead of the standard focus overlay
     - **And:** The chat is already connected to the coach agent and shows past messages, if any

   - **Scenario: Open a monitored app while both locks are on**
     - **Given:** Agent lock is engaged and manual focus is also active
     - **When:** User opens a monitored app
     - **Then:** The chat interface appears (it takes priority over the standard focus overlay)

   - **Scenario: Open a monitored app while only manual focus is on**
     - **Given:** Manual focus is on, agent lock is released
     - **When:** User opens a monitored app
     - **Then:** The standard focus overlay appears (unchanged from today)

2. **Talk to the coach agent**

   - **Scenario: Ask for a release**
     - **Given:** Chat interface is open
     - **When:** User types a message and sends it
     - **Then:** The agent's reply appears in the chat
     - **And:** The chat does not change the lock by itself — only the agent can release it

   - **Scenario: Agent grants a release**
     - **Given:** User is mid-conversation
     - **When:** The agent decides to grant a release window
     - **Then:** Within a few seconds, the phone learns the lock is released
     - **And:** The chat interface closes on its own
     - **And:** The user can use the app for the granted window

   - **Scenario: Agent denies the request**
     - **When:** The agent declines
     - **Then:** The agent's denial message appears in the chat
     - **And:** The lock stays engaged
     - **And:** The chat stays open so the user can keep negotiating or back out

3. **Back out of the chat**

   - **Scenario: User closes the chat without a release**
     - **When:** User dismisses the chat
     - **Then:** The blocked monitored app is no longer in front (user lands on home or a designated landing app)
     - **And:** Re-opening the monitored app brings the chat back

4. **See the agent lock inside the coach app, and open the chat by tapping it**

   - **Scenario: Agent lock is engaged, user opens the coach app**
     - **Given:** Agent lock is engaged
     - **When:** User opens the coach app
     - **Then:** A status indicator shows that the agent lock is on (sibling to the focus status card today)
     - **And:** Tapping the indicator opens the chat as a normal screen
     - **And:** User can leave the chat freely with the back button — the lock is irrelevant here, the user came on purpose

   - **Scenario: Agent has granted a release window, user opens the coach app**
     - **When:** User opens the coach app
     - **Then:** The indicator shows the release is active and how long is left
     - **And:** Tapping it still opens the chat

5. **Auto-relock when the granted window ends**

   - **Scenario: Release window expires**
     - **Given:** The agent granted a release that has now elapsed
     - **When:** User opens a monitored app
     - **Then:** The chat interface appears again

## Requirements

- [ ] The phone reflects agent-lock state from the coach server (not from the agent server). The lock is "engaged" when the server reports no remaining release time.
- [ ] Agent-lock state arrives via the existing focusing WebSocket payload, in the `agent_release_time_left` field (seconds left when released, `null` when locked). No new endpoint, no extra poll. The coach server already broadcasts on every release / re-engage / expiry.
- [ ] When the agent lock is engaged and the user opens a monitored app, the chat interface is shown — even if manual focus is also active.
- [ ] The chat interface is a full-screen surface with a real keyboard (not a system overlay). It must accept text input as smoothly as any normal chat app.
- [ ] If the user tries to bypass the chat (back button, recent apps, switching apps) *while it was forced open by hitting a monitored app*, the chat re-asserts itself the same way the standard focus overlay does today.
- [ ] When the user opens the chat voluntarily from inside the coach app, the back button just closes it — no re-assert.
- [ ] The coach app shows an agent-lock status indicator (engaged / released-for-N-minutes) alongside the existing focus status card. Tapping it opens the chat.
- [ ] The chat surface visibly tells the user that typing the literal token `PRETTYPLEASE` is a 15-minute override (the agent enforces the duration server-side; the UI just makes it discoverable).
- [ ] The chat interface shows prior messages in the same conversation thread when re-opened.
- [ ] The chat interface streams the agent's reply as it is generated (no long blank wait while a full reply is composed).
- [ ] The chat interface does not expose any control that ends the lock from the device side. Releases happen only via the agent.
- [ ] When the lock transitions to released (for any reason — agent grant, manual override, etc.), any open chat interface closes within a few seconds.
- [ ] If the agent server is unreachable, the chat interface tells the user clearly and lets them retry. The lock stays engaged.
- [ ] Closing the chat returns the user to the home screen (or the configured landing app), the same way the standard overlay does today.
- [ ] Re-opening the monitored app while still locked re-shows the chat with conversation history intact.
- [ ] Conversation history persists across app restarts and across separate "block episodes." There is one continuous thread (no daily reset).
- [ ] Conversation history is capped at roughly 10,000 tokens. When the cap is exceeded, the oldest messages are dropped in a chunk of about 1,000 tokens (so trimming doesn't run on every new message). Cross-day pattern memory does not depend on chat history — the agent already tracks daily totals and recent requests in server-side stats.
- [ ] The agent server URL is configurable the same way the coach server URL is today (project `.env`, surfaced through manifest meta-data).
- [ ] The phone reaches the agent server's `/api/*` routes directly, with no auth header. Caddy's oauth2-proxy gate stays on the frontend only — `/api/*` is opened up. (The agent server itself has no inbound auth in code.)

## Out of Scope

- **Offline / no-network behavior.** If the chat can't reach the agent server, the failure mode is left to the generic error-and-retry path covered above. No special offline handling.
- **Notifications.** Agent replies do not raise system notifications. The user reads them when the chat is in front.

## Open Questions

_(none)_
