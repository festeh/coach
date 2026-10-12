export interface StorageSchema {
  focusing: boolean;
  whitelist: string[];
  connected: boolean;
  reconnect_at: number;
  agent_release_time_left: number | null;
  chat_thread_id: string;
  last_reload_stamp: string;
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
