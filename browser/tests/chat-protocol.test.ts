import assert from "node:assert/strict";
import { test } from "node:test";
import {
  OFFER_FRESH_MS,
  chatWsUrl,
  fallbackOffer,
  historyMessages,
  liveOfferIndex,
  releaseFor,
  type ChatMessage
} from "../src/lib/chat/protocol";

const AT = "2026-10-12T10:00:00Z";
const AT_MS = Date.parse(AT);

test("history keeps the OK on the reply that offered it and drops tool rows", () => {
  const messages = historyMessages([
    { role: "human", content: "pay the electricity bill", at: AT },
    { role: "tool", content: "{}", at: AT },
    { role: "ai", content: "Go for it.", at: AT, events: [{ type: "ok", minutes: 10, at: AT } as { type: string }] }
  ]);
  assert.deepEqual(messages, [
    { role: "human", content: "pay the electricity bill", at: AT_MS },
    { role: "ai", content: "Go for it.", at: AT_MS, offer: { minutes: 10, at: AT_MS, source: "chat" } }
  ]);
});

test("history ignores events it does not know and malformed OKs", () => {
  const messages = historyMessages([
    { role: "ai", content: "Hm?", at: AT, events: [{ type: "other" }, { type: "ok", minutes: 0, at: AT } as { type: string }] }
  ]);
  assert.deepEqual(messages, [{ role: "ai", content: "Hm?", at: AT_MS }]);
});

test("history hides what was said by the time this device cleared the chat", () => {
  const later = "2026-10-12T10:00:00.500Z";
  const messages = historyMessages(
    [
      { role: "human", content: "before", at: AT },
      { role: "human", content: "after", at: later }
    ],
    AT_MS
  );
  assert.deepEqual(
    messages.map((m) => m.content),
    ["after"]
  );
});

test("history keeps a row without a readable stamp in view", () => {
  const messages = historyMessages([{ role: "human", content: "old", at: "" }], AT_MS);
  assert.equal(messages.length, 1);
  assert.ok(Number.isNaN(messages[0].at));
});

test("every device talks in the one shared thread", () => {
  assert.equal(chatWsUrl("https://agents.example/", "t0k"), "wss://agents.example/api/coach/ws/main?token=t0k");
  assert.equal(chatWsUrl("http://localhost:8000"), "ws://localhost:8000/api/coach/ws/main");
});

test("only the last message's fresh OK is on show", () => {
  const ok: ChatMessage = { role: "ai", content: "Sure.", at: AT_MS, offer: { minutes: 10, at: AT_MS, source: "chat" } };
  const ask: ChatMessage = { role: "human", content: "x", at: AT_MS };
  assert.equal(liveOfferIndex([ask, ok], AT_MS + 1000), 1);
  assert.equal(liveOfferIndex([ask, ok], AT_MS + OFFER_FRESH_MS), -1);
  assert.equal(liveOfferIndex([ok, ask], AT_MS), -1);
  assert.equal(liveOfferIndex([], AT_MS), -1);
});

test("a coach OK releases with the reason it answered and the reply", () => {
  const messages: ChatMessage[] = [
    { role: "human", content: "idk", at: AT_MS },
    { role: "ai", content: "Could you say a bit more?", at: AT_MS },
    { role: "human", content: "checking the bus schedule", at: AT_MS },
    { role: "ai", content: "Safe travels.", at: AT_MS, offer: { minutes: 5, at: AT_MS, source: "chat" } }
  ];
  assert.deepEqual(releaseFor(messages, 3), {
    type: "release",
    duration: 300,
    message: "checking the bus schedule",
    agent_message: "Safe travels.",
    source: "chat"
  });
});

test("a fallback OK releases ten minutes without a coach reply", () => {
  const messages: ChatMessage[] = [
    { role: "human", content: "one video", at: AT_MS },
    { role: "error", content: "upstream down", at: AT_MS },
    { role: "notice", content: "The coach can't answer right now.", at: AT_MS, offer: fallbackOffer(AT_MS) }
  ];
  assert.deepEqual(releaseFor(messages, 2), {
    type: "release",
    duration: 600,
    message: "one video",
    agent_message: "",
    source: "fallback"
  });
});

test("a message without an OK releases nothing", () => {
  assert.equal(releaseFor([{ role: "ai", content: "Hi", at: AT_MS }], 0), null);
});
