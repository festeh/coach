import { OverridePhase } from "./background/types";

// The override state as one stored value: written whole by the background on
// every focus frame, read whole by pages. Field names mirror the wire frame.
export interface OverrideSnapshot {
  overrides_today: number;
  override_phase: OverridePhase;
  override_cooldown_seconds: number;
  override_cooldown_remaining: number;
  override_redeem_remaining: number;
  override_next_cooldown_seconds: number;
  // When the frame landed (ms epoch). The remaining values decay from this
  // moment; pages tick them down locally between frames.
  synced_at: number;
}

export const DEFAULT_OVERRIDE_STATE: OverrideSnapshot = {
  overrides_today: 0,
  override_phase: OverridePhase.Idle,
  override_cooldown_seconds: 0,
  override_cooldown_remaining: 0,
  override_redeem_remaining: 0,
  override_next_cooldown_seconds: 0,
  synced_at: 0,
};

export interface StorageSchema {
  focusing: boolean;
  whitelist: string[];
  connected: boolean;
  reconnect_at: number;
  agent_release_time_left: number | null;
  chat_thread_id: string;
  last_reload_stamp: string;
  override_state: OverrideSnapshot;
}

type StorageKey = keyof StorageSchema;

type StorageResult<K extends StorageKey> = { [P in K]: StorageSchema[P] };

const DEFAULTS: StorageSchema = {
  focusing: false,
  whitelist: [],
  connected: false,
  reconnect_at: 0,
  agent_release_time_left: null,
  chat_thread_id: '',
  last_reload_stamp: '',
  override_state: DEFAULT_OVERRIDE_STATE,
};

export async function getStorage<K extends StorageKey>(...keys: K[]): Promise<StorageResult<K>> {
  const raw = await browser.storage.local.get(keys);
  const result = {} as StorageResult<K>;
  for (const key of keys) {
    result[key] = (key in raw ? raw[key] : DEFAULTS[key]) as StorageSchema[K];
  }
  return result;
}

export async function setStorage(values: Partial<StorageSchema>): Promise<void> {
  await browser.storage.local.set(values);
}

export type StorageChanges = {
  [K in StorageKey]?: { newValue: StorageSchema[K]; oldValue?: StorageSchema[K] };
};

export function onStorageChanged(callback: (changes: StorageChanges) => void): () => void {
  browser.storage.local.onChanged.addListener(callback);
  return () => browser.storage.local.onChanged.removeListener(callback);
}
