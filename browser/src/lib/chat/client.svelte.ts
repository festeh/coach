import { ClientFrameType, ServerFrameType, type ChatMessage, type ServerFrame } from "./protocol";

// Page-lifetime chat connection: the tab holds it open, closing the tab
// drops it. No keepalive — a chat page has a human in it, and a dead
// socket surfaces as a Reconnect button instead of silent retries.
export class ChatClient {
  messages = $state<ChatMessage[]>([]);
  streamText = $state("");
  awaitingReply = $state(false);
  status = $state<"connecting" | "open" | "closed">("connecting");

  private ws: WebSocket | null = null;

  constructor(private url: string) {}

  connect(): void {
    this.disconnect();
    this.status = "connecting";
    const ws = new WebSocket(this.url);
    this.ws = ws;

    ws.onopen = () => {
      this.status = "open";
    };
    ws.onmessage = (event) => {
      try {
        this.handleFrame(JSON.parse(event.data as string) as ServerFrame);
      } catch {
        // Not a protocol frame; nothing to render.
      }
    };
    ws.onclose = () => {
      if (this.ws !== ws) return; // superseded by a reconnect
      this.foldStream();
      this.awaitingReply = false;
      this.status = "closed";
    };
  }

  disconnect(): void {
    const ws = this.ws;
    this.ws = null;
    ws?.close(1000, "Page closed");
  }

  send(content: string): void {
    const trimmed = content.trim();
    if (!trimmed || this.status !== "open" || this.awaitingReply) return;
    this.messages = [...this.messages, { role: "human", content: trimmed }];
    this.awaitingReply = true;
    this.ws!.send(JSON.stringify({ type: ClientFrameType.Message, content: trimmed }));
  }

  // Wipe the thread server-side; the empty history frame that comes back
  // resets this state the same way a fresh connect would.
  clear(): void {
    if (this.status !== "open") return;
    this.streamText = "";
    this.awaitingReply = false;
    this.ws!.send(JSON.stringify({ type: ClientFrameType.Clear }));
  }

  private handleFrame(frame: ServerFrame): void {
    switch (frame.type) {
      case ServerFrameType.History:
        // Tool and system rows are the judge's internals, not dialogue.
        this.messages = frame.messages
          .filter((m) => m.role === "human" || m.role === "ai")
          .map((m) => ({ role: m.role as "human" | "ai", content: m.content }));
        break;
      case ServerFrameType.Chunk:
        this.streamText += frame.content;
        break;
      case ServerFrameType.Done:
        this.foldStream();
        this.awaitingReply = false;
        break;
      case ServerFrameType.Error:
        this.foldStream();
        this.messages = [...this.messages, { role: "error", content: frame.message }];
        this.awaitingReply = false;
        break;
    }
  }

  // Move whatever has streamed so far into the message list, so a close or
  // error mid-turn never loses text.
  private foldStream(): void {
    if (this.streamText) {
      this.messages = [...this.messages, { role: "ai", content: this.streamText }];
      this.streamText = "";
    }
  }
}
