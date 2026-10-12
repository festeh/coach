import ReconnectingWebSocket from "partysocket/ws";
import {
  PING_INTERVAL_MS,
  PONG_TIMEOUT_MS,
  RECONNECT_BASE_DELAY_MS,
  RECONNECT_MAX_DELAY_MS,
  RECONNECT_GROW_FACTOR,
  logError
} from "./constants";
import { OutgoingMessage, FocusingMessage, isFocusingMessage } from "./types";

export interface WebSocketManagerCallbacks {
  onConnected: () => void;
  onDisconnected: () => void;
  onReconnectScheduled: (reconnectAt: number) => void;
  onFocusMessage: (message: FocusingMessage) => void;
}

export class WebSocketManager {
  private socket: ReconnectingWebSocket;
  private pendingPongResolve: ((value: boolean) => void) | null = null;
  private pingGen = 0;

  constructor(serverUrl: string, private callbacks: WebSocketManagerCallbacks, token = "") {
    // Token rides the query string — the browser WebSocket API can't set headers.
    const url = token ? `${serverUrl}/connect?token=${token}` : `${serverUrl}/connect`;
    this.socket = new ReconnectingWebSocket(url, [], {
      minReconnectionDelay: RECONNECT_BASE_DELAY_MS,
      maxReconnectionDelay: RECONNECT_MAX_DELAY_MS,
      reconnectionDelayGrowFactor: RECONNECT_GROW_FACTOR,
      maxRetries: Infinity,
      startClosed: true
    });

    this.socket.addEventListener("open", () => {
      this.callbacks.onConnected();
      this.startPingLoop();
    });

    this.socket.addEventListener("close", () => {
      this.stopPingLoop();
      this.callbacks.onDisconnected();
      const attempts = this.socket.retryCount;
      const delay = Math.min(
        RECONNECT_BASE_DELAY_MS * Math.pow(RECONNECT_GROW_FACTOR, Math.max(0, attempts - 1)),
        RECONNECT_MAX_DELAY_MS
      );
      console.log(`[Background] Reconnecting in ${delay}ms (attempt ${attempts})`);
      this.callbacks.onReconnectScheduled(Date.now() + delay);
    });

    this.socket.addEventListener("message", (event) => {
      try {
        const message = JSON.parse(event.data);
        if (message.type === "pong") {
          this.handlePong();
          return;
        }
        if (isFocusingMessage(message)) {
          this.callbacks.onFocusMessage(message);
        }
      } catch (error) {
        logError("Failed to parse WebSocket message", error);
      }
    });
  }

  connect(): void {
    if (this.socket.readyState !== ReconnectingWebSocket.CLOSED) {
      return;
    }
    this.socket.reconnect();
  }

  isOpen(): boolean {
    return this.socket.readyState === ReconnectingWebSocket.OPEN;
  }

  send(message: OutgoingMessage): void {
    if (this.socket.readyState === ReconnectingWebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    }
  }

  reconnect(): void {
    this.socket.reconnect();
  }

  // Safety net for the MV3 service worker: if the SW was killed during a
  // backoff window, the lib's setTimeout is gone with it. The reconnect alarm
  // calls this on a 30s heartbeat. socket.reconnect() restarts a fresh attempt;
  // partysocket's internal _connectLock keeps it from racing itself.
  ensureConnected(): void {
    if (this.socket.readyState === ReconnectingWebSocket.OPEN ||
        this.socket.readyState === ReconnectingWebSocket.CONNECTING) {
      return;
    }
    this.socket.reconnect();
  }

  private startPingLoop(): void {
    const gen = ++this.pingGen;
    void this.runPingLoop(gen);
  }

  private async runPingLoop(gen: number): Promise<void> {
    while (this.pingGen === gen && this.socket.readyState === ReconnectingWebSocket.OPEN) {
      await this.sleep(PING_INTERVAL_MS);
      if (this.pingGen !== gen) return;

      const alive = await this.sendPing();
      if (this.pingGen !== gen) return;

      if (!alive) {
        this.callbacks.onDisconnected();
        this.socket.reconnect();
        return;
      }
    }
  }

  private stopPingLoop(): void {
    this.pingGen++;
    if (this.pendingPongResolve) {
      this.pendingPongResolve(false);
      this.pendingPongResolve = null;
    }
  }

  private sendPing(): Promise<boolean> {
    return new Promise((resolve) => {
      if (this.socket.readyState !== ReconnectingWebSocket.OPEN) {
        resolve(false);
        return;
      }

      let settled = false;
      const settle = (alive: boolean): void => {
        if (settled) return;
        settled = true;
        if (this.pendingPongResolve === settle) {
          this.pendingPongResolve = null;
        }
        resolve(alive);
      };

      this.pendingPongResolve = settle;
      this.socket.send(JSON.stringify({ type: "ping" }));
      setTimeout(() => settle(false), PONG_TIMEOUT_MS);
    });
  }

  private handlePong(): void {
    if (this.pendingPongResolve) {
      this.pendingPongResolve(true);
    }
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}
