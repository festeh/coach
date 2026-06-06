import { createMemo, createResource, createSignal, For, Show } from "solid-js";
import { fetchAttention, type AttentionInterval } from "../api";

// Matches the server-side stitch rule: a new interval starting within this gap
// of the previous one means attention was continuous through the transition.
const GAP_MS = 90_000;
const DAY_MS = 86_400_000;

const SITE_COLORS = ["#58a6ff", "#3fb950", "#d29922", "#f778ba", "#a371f7", "#ff7b72"];
const OTHER_SITE_COLOR = "#6e7681";
const IDLE_COLOR = "#30363d";
const AWAY_COLOR = "#21262d";
const INTERNAL_LABEL = "(internal pages)";
const TOP_SITES_SHOWN = 15;

interface Span {
  state: "site" | "idle" | "away";
  site: string;
  start: number; // ms epoch, clipped to the selected day
  end: number;
}

function startOfDay(d: Date): Date {
  const r = new Date(d);
  r.setHours(0, 0, 0, 0);
  return r;
}

function addDays(d: Date, n: number): Date {
  const r = new Date(d);
  r.setDate(r.getDate() + n);
  return r;
}

// Convert raw intervals into day-clipped spans. last_seen is only a lower bound
// on a span's end: when the next interval starts within the heartbeat gap, the
// attention actually lasted until that transition, so extend up to it.
function toSpans(intervals: AttentionInterval[], dayStart: number, dayEnd: number): Span[] {
  const spans: Span[] = [];
  for (let i = 0; i < intervals.length; i++) {
    const cur = intervals[i];
    const start = Date.parse(cur.started_at);
    let end = Date.parse(cur.last_seen);
    const next = intervals[i + 1];
    if (next) {
      const nextStart = Date.parse(next.started_at);
      if (nextStart - end <= GAP_MS) end = nextStart;
    }
    const s = Math.max(start, dayStart);
    const e = Math.min(end, dayEnd);
    if (e > s) spans.push({ state: cur.state, site: cur.site, start: s, end: e });
  }
  return spans;
}

function formatDuration(ms: number): string {
  const totalSeconds = Math.round(ms / 1000);
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${totalSeconds}s`;
}

function formatClock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

export default function Usage() {
  const [day, setDay] = createSignal(startOfDay(new Date()));

  const dayWindow = createMemo(() => {
    const from = day();
    return { from, to: addDays(from, 1) };
  });

  const [intervals] = createResource(dayWindow, (w) => fetchAttention(w.from, w.to));

  const spans = createMemo(() => {
    const data = intervals();
    if (!data) return [];
    const w = dayWindow();
    return toSpans(data, w.from.getTime(), w.to.getTime());
  });

  const stateTotals = createMemo(() => {
    const totals = { site: 0, idle: 0, away: 0 };
    for (const s of spans()) totals[s.state] += s.end - s.start;
    return totals;
  });

  const siteTotals = createMemo(() => {
    const totals = new Map<string, number>();
    for (const s of spans()) {
      if (s.state !== "site") continue;
      const key = s.site || INTERNAL_LABEL;
      totals.set(key, (totals.get(key) ?? 0) + (s.end - s.start));
    }
    return [...totals.entries()].sort((a, b) => b[1] - a[1]);
  });

  const siteColor = createMemo(() => {
    const colors = new Map<string, string>();
    siteTotals().forEach(([site], i) => {
      colors.set(site, SITE_COLORS[i] ?? OTHER_SITE_COLOR);
    });
    return colors;
  });

  const colorOf = (s: Span): string => {
    if (s.state === "idle") return IDLE_COLOR;
    if (s.state === "away") return AWAY_COLOR;
    return siteColor().get(s.site || INTERNAL_LABEL) ?? OTHER_SITE_COLOR;
  };

  const labelOf = (s: Span): string => {
    const what = s.state === "site" ? s.site || INTERNAL_LABEL : s.state;
    return `${what} · ${formatClock(s.start)}–${formatClock(s.end)} · ${formatDuration(s.end - s.start)}`;
  };

  const isToday = () => day().getTime() === startOfDay(new Date()).getTime();
  const dayPct = (ms: number) => ((ms - day().getTime()) / DAY_MS) * 100;
  const maxSiteTotal = () => siteTotals()[0]?.[1] ?? 0;

  return (
    <>
      <section class="card">
        <div class="usage-header">
          <h2>Usage</h2>
          <div class="daynav">
            <button onClick={() => setDay((d) => addDays(d, -1))}>‹</button>
            <span class="daynav-date">
              {day().toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" })}
            </span>
            <button disabled={isToday()} onClick={() => setDay((d) => addDays(d, 1))}>›</button>
            <Show when={!isToday()}>
              <button onClick={() => setDay(startOfDay(new Date()))}>Today</button>
            </Show>
          </div>
        </div>

        {intervals.loading && <p class="muted">Loading...</p>}
        {intervals.error && <p class="error">Failed to load attention data</p>}

        <Show when={intervals() && !intervals.loading}>
          <Show when={spans().length > 0} fallback={<p class="muted">No attention data for this day</p>}>
            <div class="status-grid usage-totals">
              <div class="status-item">
                <span class="label">On sites</span>
                <span class="value focusing">{formatDuration(stateTotals().site)}</span>
              </div>
              <div class="status-item">
                <span class="label">Idle</span>
                <span class="value idle">{formatDuration(stateTotals().idle)}</span>
              </div>
              <div class="status-item">
                <span class="label">Away from browser</span>
                <span class="value idle">{formatDuration(stateTotals().away)}</span>
              </div>
            </div>

            <div class="timeline" role="img" aria-label="Attention timeline for the day">
              <For each={spans()}>
                {(s) => (
                  <div
                    class="timeline-span"
                    style={{
                      left: `${dayPct(s.start)}%`,
                      width: `${Math.max(0.1, dayPct(s.end) - dayPct(s.start))}%`,
                      background: colorOf(s),
                    }}
                    title={labelOf(s)}
                  />
                )}
              </For>
            </div>
            <div class="timeline-hours">
              <span>00</span><span>06</span><span>12</span><span>18</span><span>24</span>
            </div>

            <div class="legend">
              <For each={siteTotals().slice(0, SITE_COLORS.length)}>
                {([site]) => (
                  <span class="legend-item">
                    <span class="legend-dot" style={{ background: siteColor().get(site) }} />
                    {site}
                  </span>
                )}
              </For>
              <span class="legend-item">
                <span class="legend-dot" style={{ background: IDLE_COLOR }} />
                idle
              </span>
              <span class="legend-item">
                <span class="legend-dot" style={{ background: AWAY_COLOR }} />
                away
              </span>
            </div>
          </Show>
        </Show>
      </section>

      <Show when={siteTotals().length > 0}>
        <section class="card">
          <h2>Top Sites</h2>
          <div class="site-bars">
            <For each={siteTotals().slice(0, TOP_SITES_SHOWN)}>
              {([site, total]) => (
                <div class="site-bar-row">
                  <span class="site-bar-name" title={site}>{site}</span>
                  <div class="site-bar-track">
                    <div
                      class="site-bar-fill"
                      style={{
                        width: `${(total / maxSiteTotal()) * 100}%`,
                        background: siteColor().get(site) ?? OTHER_SITE_COLOR,
                      }}
                    />
                  </div>
                  <span class="site-bar-time">{formatDuration(total)}</span>
                </div>
              )}
            </For>
          </div>
          <Show when={siteTotals().length > TOP_SITES_SHOWN}>
            <p class="muted site-bars-more">+ {siteTotals().length - TOP_SITES_SHOWN} more sites</p>
          </Show>
        </section>
      </Show>
    </>
  );
}
