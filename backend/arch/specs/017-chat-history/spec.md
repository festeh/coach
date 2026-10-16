# Spec: Chat History

## Problem

The coach only knew what was said on the device in front of it, and never
when. Each browser and the phone had a thread of their own, trimmed to about
10k tokens, and no message had a time. "Third time today" was a guess. A plea
on the phone was news to the browser, and last week read the same as five
minutes ago. The chat bubbles didn't show times either.

## What we build

**One thread for every device.** The browser and the phone both talk in the
thread `main`. The coach hears all of it, and either client replays all of it.

**The coach sees the last 30 days, with times.** Every message is stamped
when it is said. Before each model call, turns older than 30 days leave the
thread. Each user message the model reads starts with its local time, like
`[Thu 2026-10-15 14:05]`, and the system prompt gives the current time. The
token trim is gone.

**Times in the chat.** Each message shows its time, and a label (Today,
Yesterday, Mon, Oct 12) goes above the first message of each day.

**Clearing only tidies one screen.** Clear hides what has been said so far on
that device and keeps a timestamp of when. The thread and the coach's memory
of it stay. Clearing is unavailable while a reply is streaming, because the
reply's OK needs the reason it answers.

## How the pieces talk

- Chat socket (my-agents): the server stamps each incoming user message as
  `additional_kwargs.at`, ISO-8601 UTC to the millisecond. The coach stamps
  each reply the same way. History entries carry `at`. A reply without a
  stamp of its own, such as an OK with no text, takes the last stamp before
  it.
- The `clear` frame is gone, and the server ignores it from older clients.
- The browser keeps `chat_cleared_at` in extension storage and Android keeps
  `agentChatClearedAt` in its preferences, both in ms since the epoch. A
  history row stamped at or before that time stays hidden. A row without a
  readable stamp stays visible.
- Turns are cut whole, at user messages, so a tool call never loses its
  result. A turn whose user message has no stamp is older than stamping, so
  it counts as expired.

## What does not change

The ledger, OK and its five-minute freshness, the `blocked` context on a
message, and the fallback OK when the coach can't answer.

## Known limits

- The old per-device threads stay in `coach.db` untouched. The shared thread
  starts empty.
- A reply sent from one device reaches a chat open on the other only when
  that chat reconnects.
- 30 days of a busy month is more context per call than the old 10k cap.
- Clearing compares server stamps with the device clock. A badly skewed clock
  can hide or show a message from around the moment of the clear.

## Rollout

Any order works. Old clients against the new server keep their own threads
and get `at` they ignore. New clients against the old server get an empty
`main` thread with no times until my-agents ships.
