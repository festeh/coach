// Wire protocol of the agents server's chat WebSocket
// (my-agents serving.py, the same one Android's AgentChatService speaks).

export interface ChatMessage {
  // "notice" is page-local (e.g. an override confirmation) — never sent,
  // never part of the agent thread.
  role: "human" | "ai" | "error" | "notice";
  content: string;
}

// Frame kinds on the chat socket — mirror my-agents' WsIn/WsOut enums.
export const ClientFrameType = {
  Message: "message",
  Clear: "clear"
} as const;

export const ServerFrameType = {
  History: "history",
  Chunk: "chunk",
  Done: "done",
  Error: "error",
  Pong: "pong"
} as const;

// Frames the server sends. History arrives once on connect (and after a
// clear); a turn is zero or more chunks closed by done; error ends a turn.
export type ServerFrame =
  | { type: typeof ServerFrameType.History; messages: { role: string; content: string }[] }
  | { type: typeof ServerFrameType.Chunk; content: string }
  | { type: typeof ServerFrameType.Done; message_id: string }
  | { type: typeof ServerFrameType.Error; message: string }
  | { type: typeof ServerFrameType.Pong };

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
