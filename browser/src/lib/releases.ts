// The release journal, as the coach backend pages it: newest first, with a
// cursor to the next older page. Each row is one time the lock came off (or,
// in older rows, a peek, an override or a judge's denial).

export interface Release {
  id: number;
  kind: string; // "grant"; older rows: "override", "short_unblock", "denial"
  source: string; // "chat" | "fallback"; older rows: "agent"
  user_message: string;
  agent_message: string;
  duration_seconds: number;
  created: string; // RFC 3339, UTC
}

export interface ReleasePage {
  decisions: Release[];
  next_before: number | null;
}

export const RELEASE_PAGE_SIZE = 50;

// One page, older than `before` when given. The token rides the query string
// like the sockets' does: a plain GET then needs no CORS preflight, which the
// edge would turn away for lacking the token.
export async function fetchReleases(
  serverUrl: string,
  token: string,
  before: number | null
): Promise<ReleasePage> {
  const url = new URL("/lock-decisions", serverUrl);
  url.searchParams.set("limit", String(RELEASE_PAGE_SIZE));
  if (before !== null) url.searchParams.set("before", String(before));
  if (token) url.searchParams.set("token", token);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Coach answered ${res.status}`);
  return (await res.json()) as ReleasePage;
}

// What kind of release a row was, in a word for its badge.
export function releaseLabel(release: Release): string {
  switch (release.kind) {
    case "grant":
      // Before the coach asked, the judge granted ("agent").
      if (release.source === "agent") return "granted";
      return release.source === "fallback" ? "fallback" : "OK";
    case "override":
      return "override";
    case "short_unblock":
      return "peek";
    case "denial":
      return "denied";
    default:
      return release.kind;
  }
}

export interface ReleaseDay {
  key: string; // local YYYY-MM-DD
  label: string;
  releases: Release[];
}

function dayKey(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

// Newest-first rows folded into local days, in the order they came. A day cut
// by a page boundary continues when the next page is appended.
export function groupByDay(releases: Release[], now: Date): ReleaseDay[] {
  const today = dayKey(now);
  const yesterday = dayKey(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1));
  const days: ReleaseDay[] = [];
  for (const release of releases) {
    const at = new Date(release.created);
    const key = dayKey(at);
    let day = days[days.length - 1];
    if (day?.key !== key) {
      const label =
        key === today
          ? "Today"
          : key === yesterday
            ? "Yesterday"
            : at.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" });
      day = { key, label, releases: [] };
      days.push(day);
    }
    day.releases.push(release);
  }
  return days;
}
