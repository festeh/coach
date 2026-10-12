import assert from "node:assert/strict";
import { test } from "node:test";
import {
  OFFER_FRESH_MS,
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
    { role: "human", content: "pay the electricity bill" },
    { role: "tool", content: "{}" },
    { role: "ai", content: "Go for it.", events: [{ type: "ok", minutes: 10, at: AT } as { type: string }] }
  ]);
  assert.deepEqual(messages, [
    { role: "human", content: "pay the electricity bill" },
    { role: "ai", content: "Go for it.", offer: { minutes: 10, at: AT_MS, source: "chat" } }
  ]);
});

test("history ignores events it does not know and malformed OKs", () => {
  const messages = historyMessages([
    { role: "ai", content: "Hm?", events: [{ type: "other" }, { type: "ok", minutes: 0, at: AT } as { type: string }] }
  ]);
  assert.deepEqual(messages, [{ role: "ai", content: "Hm?" }]);
});

test("only the last message's fresh OK is on show", () => {
  const ok: ChatMessage = { role: "ai", content: "Sure.", offer: { minutes: 10, at: AT_MS, source: "chat" } };
  assert.equal(liveOfferIndex([{ role: "human", content: "x" }, ok], AT_MS + 1000), 1);
  assert.equal(liveOfferIndex([{ role: "human", content: "x" }, ok], AT_MS + OFFER_FRESH_MS), -1);
  assert.equal(liveOfferIndex([ok, { role: "human", content: "again" }], AT_MS), -1);
  assert.equal(liveOfferIndex([], AT_MS), -1);
});

test("a coach OK releases with the reason it answered and the reply", () => {
  const messages: ChatMessage[] = [
    { role: "human", content: "idk" },
    { role: "ai", content: "Could you say a bit more?" },
    { role: "human", content: "checking the bus schedule" },
    { role: "ai", content: "Safe travels.", offer: { minutes: 5, at: AT_MS, source: "chat" } }
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
    { role: "human", content: "one video" },
    { role: "error", content: "upstream down" },
    { role: "notice", content: "The coach can't answer right now.", offer: fallbackOffer(AT_MS) }
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
  assert.equal(releaseFor([{ role: "ai", content: "Hi" }], 0), null);
});
