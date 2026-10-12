// Wire protocol of the agents server's chat WebSocket
// (my-agents serving.py, the same one Android's AgentChatService speaks).

import type { ReleaseMessage, ReleaseSource } from "../background/types";

// An OK the user can click: a release of `minutes`, offered at `at` (ms
// epoch) — by the coach's reply, or by the page when the coach can't answer.
export interface Offer {
  minutes: number;
  at: number;
  source: ReleaseSource;
}

export interface ChatMessage {
  // "notice" is page-local (e.g. the fallback OK when the coach can't
  // answer) — never sent, never part of the agent thread.
  role: "human" | "ai" | "error" | "notice";
  content: string;
  offer?: Offer;
}

// An OK reaches the user only while it is fresh: an old one in the history
// must not open the lock without a new reason.
export const OFFER_FRESH_MS = 5 * 60 * 1000;
// What the page offers on its own when the coach can't answer.
export const FALLBACK_MINUTES = 10;

// Frame kinds on the chat socket — mirror my-agents' WsIn/WsOut enums.
export const ClientFrameType = {
  Message: "message",
  Clear: "clear"
} as const;

export const ServerFrameType = {
  History: "history",
  Chunk: "chunk",
  Ok: "ok",
  Done: "done",
  Error: "error",
  Pong: "pong"
} as const;

// What the client tells the coach beyond the text: `blocked` names the site
// whose blocking brought the user here.
export interface MessageContext {
  blocked?: string;
}

// The coach's OK: the reply it closes lets the user in for `minutes`.
export interface OkEvent {
  type: typeof ServerFrameType.Ok;
  minutes: number;
  at: string;
}

export interface HistoryEntry {
  role: string;
  content: string;
  events?: { type: string }[];
}

// Frames the server sends. History arrives once on connect (and after a
// clear); a turn is zero or more chunks, an optional ok, then done; error
// ends a turn.
export type ServerFrame =
  | { type: typeof ServerFrameType.History; messages: HistoryEntry[] }
  | { type: typeof ServerFrameType.Chunk; content: string }
  | OkEvent
  | { type: typeof ServerFrameType.Done; message_id: string }
  | { type: typeof ServerFrameType.Error; message: string }
  | { type: typeof ServerFrameType.Pong };

function isOkEvent(event: { type: string }): event is OkEvent {
  const ok = event as Partial<OkEvent>;
  return ok.type === ServerFrameType.Ok && typeof ok.minutes === "number" && ok.minutes > 0;
}

export function offerOf(event: { type: string }): Offer | undefined {
  if (!isOkEvent(event)) return undefined;
  const at = Date.parse(event.at);
  return Number.isNaN(at) ? undefined : { minutes: event.minutes, at, source: "chat" };
}

export function fallbackOffer(now: number): Offer {
  return { minutes: FALLBACK_MINUTES, at: now, source: "fallback" };
}

// Tool and system rows are the coach's internals, not dialogue; an AI row
// keeps the OK its turn offered.
export function historyMessages(entries: HistoryEntry[]): ChatMessage[] {
  return entries
    .filter((m) => m.role === "human" || m.role === "ai")
    .map((m) => {
      const message: ChatMessage = { role: m.role as "human" | "ai", content: m.content };
      const offer = m.events?.map(offerOf).find((o) => o !== undefined);
      if (offer) message.offer = offer;
      return message;
    });
}

// The OK on show, if any: only the conversation's last message carries one,
// and only while it is fresh.
export function liveOfferIndex(messages: ChatMessage[], now: number): number {
  const last = messages.length - 1;
  const offer = messages[last]?.offer;
  if (!offer || now - offer.at >= OFFER_FRESH_MS) return -1;
  return last;
}

// The release an OK asks for. The reason is the user's message the offer
// answers; the coach's reply rides along when the coach made the offer.
export function releaseFor(messages: ChatMessage[], index: number): ReleaseMessage | null {
  const message = messages[index];
  if (!message?.offer) return null;
  let reason = "";
  for (let i = index - 1; i >= 0; i--) {
    if (messages[i].role === "human") {
      reason = messages[i].content;
      break;
    }
  }
  return {
    type: "release",
    duration: message.offer.minutes * 60,
    message: reason,
    agent_message: message.role === "ai" ? message.content : "",
    source: message.offer.source
  };
}

// Build the chat socket URL the way Android does: https→wss, http→ws,
// pass ws(s) through untouched. The token rides the query string — the
// browser WebSocket API can't set headers.
export function chatWsUrl(baseUrl: string, threadId: string, token = ""): string {
  const trimmed = baseUrl.trim().replace(/\/+$/, "");
  const withoutScheme = trimmed.replace(/^(https?|wss?):\/\//, "");
  const scheme = trimmed.startsWith("http://") || trimmed.startsWith("ws://") ? "ws://" : "wss://";
  const url = `${scheme}${withoutScheme}/api/coach/ws/${threadId}`;
  return token ? `${url}?token=${token}` : url;
}
