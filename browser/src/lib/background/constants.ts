// Reconnect backoff (ms)
export const RECONNECT_BASE_DELAY_MS = 2000;
export const RECONNECT_MAX_DELAY_MS = 30000;
export const RECONNECT_GROW_FACTOR = 2;

// WebSocket ping/pong
// Ping must fire before the MV3 SW idle timeout (~30s) so the SW stays alive
// while a connection is open.
export const PING_INTERVAL_MS = 20000;
export const PONG_TIMEOUT_MS = 5000;

// Logging helpers
export function logError(context: string, error: unknown): void {
  console.error(`[Background] ${context}:`, error);
}
