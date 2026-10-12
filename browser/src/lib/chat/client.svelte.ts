import {
  ClientFrameType,
  ServerFrameType,
  fallbackOffer,
  historyMessages,
  offerOf,
  type ChatMessage,
  type MessageContext,
  type ServerFrame
} from "./protocol";

const FALLBACK_NOTICE = "The coach can't answer right now. Go ahead when you're ready.";

// Page-lifetime chat connection: the tab holds it open, closing the tab
// drops it. No keepalive — a chat page has a human in it, and a dead
// socket surfaces as a Reconnect button instead of silent retries.
export class ChatClient {
  messages = $state<ChatMessage[]>([]);
  streamText = $state("");
  awaitingReply = $state(false);
  status = $state<"connecting" | "open" | "closed">("connecting");

  private ws: WebSocket | null = null;

  constructor(private url: string, private context?: MessageContext) {}

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
      // A turn the socket dropped will never be answered.
      if (this.awaitingReply) this.offerFallback();
      this.awaitingReply = false;
      this.status = "closed";
    };
  }

  disconnect(): void {
    const ws = this.ws;
    this.ws = null;
    ws?.close(1000, "Page closed");
  }

  // With the coach out of reach the reason still counts: the page offers
  // the OK itself instead of leaving the user stuck behind the lock.
  send(content: string): void {
    const trimmed = content.trim();
    if (!trimmed || this.status === "connecting" || this.awaitingReply) return;
    this.messages = [...this.messages, { role: "human", content: trimmed }];
    if (this.status !== "open") {
      this.offerFallback();
      return;
    }
    this.awaitingReply = true;
    this.ws!.send(JSON.stringify({ type: ClientFrameType.Message, content: trimmed, context: this.context }));
  }

  // Wipe the thread server-side; the empty history frame that comes back
  // resets this state the same way a fresh connect would.
  clear(): void {
    if (this.status !== "open") return;
    this.streamText = "";
    this.awaitingReply = false;
    this.ws!.send(JSON.stringify({ type: ClientFrameType.Clear }));
  }

  // A clicked OK is spent: the page stops showing it.
  takeOffer(index: number): void {
    this.messages = this.messages.map((m, i) => (i === index ? { role: m.role, content: m.content } : m));
  }

  private handleFrame(frame: ServerFrame): void {
    switch (frame.type) {
      case ServerFrameType.History:
        this.messages = historyMessages(frame.messages);
        break;
      case ServerFrameType.Chunk:
        this.streamText += frame.content;
        break;
      case ServerFrameType.Ok: {
        // The OK closes the turn's reply; a turn with no text gets an empty one.
        const offer = offerOf(frame);
        if (!offer) break;
        this.foldStream();
        const last = this.messages[this.messages.length - 1];
        this.messages =
          last?.role === "ai"
            ? [...this.messages.slice(0, -1), { ...last, offer }]
            : [...this.messages, { role: "ai", content: "", offer }];
        break;
      }
      case ServerFrameType.Done:
        this.foldStream();
        this.awaitingReply = false;
        break;
      case ServerFrameType.Error:
        this.foldStream();
        this.messages = [...this.messages, { role: "error", content: frame.message }];
        if (this.awaitingReply) this.offerFallback();
        this.awaitingReply = false;
        break;
    }
  }

  private offerFallback(): void {
    this.messages = [...this.messages, { role: "notice", content: FALLBACK_NOTICE, offer: fallbackOffer(Date.now()) }];
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
