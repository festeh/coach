import { createSignal, onCleanup, onMount } from "solid-js";
import { connectWebSocket, type FocusInfo } from "../api";

function formatDuration(totalSeconds: number): string {
  if (totalSeconds <= 0) return "0s";
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const parts: string[] = [];
  if (h > 0) parts.push(`${h}h`);
  if (m > 0) parts.push(`${m}m`);
  if (s > 0 || parts.length === 0) parts.push(`${s}s`);
  return parts.join(" ");
}

export default function FocusStatus() {
  const [focus, setFocus] = createSignal<FocusInfo | null>(null);
  const [timeLeft, setTimeLeft] = createSignal(0);

  let ws: WebSocket | undefined;
  let timer: ReturnType<typeof setInterval> | undefined;

  onMount(() => {
    ws = connectWebSocket((data) => {
      setFocus(data);
      setTimeLeft(data.focus_time_left);
    });

    timer = setInterval(() => {
      setTimeLeft((t) => Math.max(0, t - 1));
    }, 1000);
  });

  onCleanup(() => {
    ws?.close();
    if (timer) clearInterval(timer);
  });

  return (
    <section class="card">
      <h2>Focus Status</h2>
      {focus() === null ? (
        <p class="muted">Connecting...</p>
      ) : (
        <div class="status-grid">
          <div class="status-item">
            <span class="label">State</span>
            <span class={`value ${timeLeft() > 0 ? "focusing" : "idle"}`}>
              {timeLeft() > 0 ? "Focusing" : "Idle"}
            </span>
          </div>
          <div class="status-item">
            <span class="label">Time remaining</span>
            <span class="value">{formatDuration(timeLeft())}</span>
          </div>
          <div class="status-item">
            <span class="label">Since last change</span>
            <span class="value">{formatDuration(focus()!.since_last_change)}</span>
          </div>
          <div class="status-item">
            <span class="label">Sessions today</span>
            <span class="value">{focus()!.num_focuses}</span>
          </div>
        </div>
      )}
    </section>
  );
}
