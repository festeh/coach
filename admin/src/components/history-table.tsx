import { createResource, For } from "solid-js";
import { fetchHistory } from "../api";

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  if (m === 0) return `${s}s`;
  return s > 0 ? `${m}m ${s}s` : `${m}m`;
}

function formatTime(ts: string): string {
  const d = new Date(ts);
  return d.toLocaleString();
}

export default function HistoryTable() {
  const [history] = createResource(() => fetchHistory(7));

  return (
    <section class="card">
      <h2>Recent Sessions</h2>
      {history.loading && <p class="muted">Loading...</p>}
      {history.error && <p class="error">Failed to load history</p>}
      {history() && (
        history()!.length === 0 ? (
          <p class="muted">No sessions in the last 7 days</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Duration</th>
              </tr>
            </thead>
            <tbody>
              <For each={history()}>
                {(record) => (
                  <tr>
                    <td>{formatTime(record.timestamp)}</td>
                    <td>{formatDuration(record.duration)}</td>
                  </tr>
                )}
              </For>
            </tbody>
          </table>
        )
      )}
    </section>
  );
}
