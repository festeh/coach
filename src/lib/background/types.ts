import * as z from "zod/mini";

// Attention beacon: what currently has the user's attention.
// "site" — a website (hostname in `site`; omitted for browser-internal pages)
// "idle" — at the machine but no input for a while (and not watching the active tab)
// "away" — browser windows are not focused
export type AttentionState = "site" | "idle" | "away";

export interface AttentionMessage {
  type: "attention";
  state: AttentionState;
  site?: string;
}

// Temptation: a non-whitelisted site the user hit while blocked.
// `source` names this browser; `target` is the blocked hostname.
export interface TemptationMessage {
  type: "temptation";
  source: string;
  target: string;
}

// Override: one button, one message, whatever the phase. The server decides
// what the click means: the day's first grants 15 minutes outright, later
// ones arm a countdown, and a click on a ripe request redeems it. `message`
// is the written reason — required to grant or arm, ignored on a redemption.
export interface OverrideMessage {
  type: "override";
  message: string;
}

// Short unblock: a 30-second peek, no reason asked, never refused.
export interface ShortUnblockMessage {
  type: "short_unblock";
}

// Messages sent to the server over WebSocket
export type OutgoingMessage =
  | { type: "ping" }
  | { type: "get_focusing" }
  | AttentionMessage
  | TemptationMessage
  | OverrideMessage
  | ShortUnblockMessage;

// Where the day's override request stands, as the server tells it.
export const OverridePhase = {
  // No live request: the next click grants (first of the day) or arms.
  Idle: "idle",
  // A request is armed and its countdown is running.
  Cooling: "cooling",
  // The countdown lapsed; a click redeems until the window closes.
  Ready: "ready"
} as const;

export type OverridePhase = (typeof OverridePhase)[keyof typeof OverridePhase];

// Messages received from the server over WebSocket
export const focusingMessageSchema = z.object({
  type: z.string(),
  focusing: z.boolean(),
  since_last_change: z.number(),
  focus_time_left: z.number(),
  agent_release_time_left: z.nullable(z.number()),
  overrides_today: z.number(),
  override_phase: z.enum([OverridePhase.Idle, OverridePhase.Cooling, OverridePhase.Ready]),
  override_cooldown_seconds: z.number(),
  override_cooldown_remaining: z.number(),
  override_redeem_remaining: z.number(),
  override_next_cooldown_seconds: z.number()
});

export type FocusingMessage = z.infer<typeof focusingMessageSchema>;

export function isFocusingMessage(message: object): message is FocusingMessage {
  return focusingMessageSchema.safeParse(message).success;
}

// Messages passed between extension components via browser.runtime
export type ExtensionMessage =
  | { type: "get_focusing" }
  | { type: "reconnect" }
  | { type: "sync_whitelist" }
  | { type: "get_connection" }
  | OverrideMessage
  | ShortUnblockMessage;
