export interface FocusInfo {
  type: string;
  focusing: boolean;
  since_last_change: number;
  focus_time_left: number;
  num_focuses: number;
}

export interface FocusRecord {
  timestamp: string;
  duration: number;
}

export function connectWebSocket(onMessage: (data: FocusInfo) => void): WebSocket {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(`${proto}//${location.host}/connect`);

  ws.addEventListener("open", () => {
    ws.send(JSON.stringify({ type: "get_focusing" }));
  });

  ws.addEventListener("message", (event) => {
    const data = JSON.parse(event.data);
    if (data.type === "focusing") {
      onMessage(data as FocusInfo);
    }
  });

  return ws;
}

export async function fetchHistory(days = 7): Promise<FocusRecord[]> {
  const res = await fetch(`/history?days=${days}`);
  return res.json();
}

export async function fetchHealth(): Promise<boolean> {
  try {
    const res = await fetch("/health");
    return res.ok;
  } catch {
    return false;
  }
}

// Hook types

export interface ParamDef {
  key: string;
  name: string;
  type: "text" | "textarea" | "select";
  default: string;
  options?: string[];
}

export interface HookConfig {
  hook_id: string;
  enabled: boolean;
  trigger: string;
  first_run: string;
  last_run: string;
  frequency: string;
  params: Record<string, string>;
}

export interface HookInfo {
  id: string;
  name: string;
  description: string;
  params: ParamDef[];
  config: HookConfig | null;
}

export interface HookResult {
  id: string;
  hook_id: string;
  content: string;
  read: boolean;
  created: string;
}

export async function fetchHooks(): Promise<HookInfo[]> {
  const res = await fetch("/api/hooks");
  return res.json();
}

export async function updateHookConfig(
  hookId: string,
  config: Omit<HookConfig, "hook_id">
): Promise<void> {
  await fetch(`/api/hooks/${hookId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(config),
  });
}

export async function triggerHook(hookId: string): Promise<void> {
  const res = await fetch(`/api/hooks/${hookId}/trigger`, { method: "POST" });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text);
  }
}

export async function fetchHookResults(): Promise<HookResult[]> {
  const res = await fetch("/api/hook-results");
  return res.json();
}

export async function markHookResultRead(resultId: string): Promise<void> {
  await fetch(`/api/hook-results/${resultId}/read`, { method: "POST" });
}

export async function fetchHookContext(hookId: string): Promise<string> {
  const res = await fetch(`/api/hooks/${hookId}/context`);
  if (!res.ok) {
    throw new Error(await res.text());
  }
  const data = await res.json();
  return data.context;
}
