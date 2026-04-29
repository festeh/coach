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
