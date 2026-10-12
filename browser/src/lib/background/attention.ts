import type { AttentionMessage } from "./types";

// Matches the browser's default idle detection interval, so idle.onStateChanged
// events and queryState answers agree without calling setDetectionInterval
// (which Firefox doesn't support).
const IDLE_THRESHOLD_S = 60;

// Builds the current attention beacon by querying live browser state. No state
// is kept here: the MV3 service worker dies at will, so the source of truth is
// always the browser itself — this makes transitions and heartbeats one code path.
export async function queryAttention(): Promise<AttentionMessage> {
  // Firefox for Android has neither the idle nor the windows API. On mobile
  // the visible tab simply is the attention — no idle/away distinction.
  if (!browser.idle || !browser.windows) {
    const [activeTab] = await browser.tabs.query({ active: true });
    return { type: "attention", state: "site", ...siteOf(activeTab?.url) };
  }

  const [idleState, lastFocused, [activeTab]] = await Promise.all([
    browser.idle.queryState(IDLE_THRESHOLD_S),
    browser.windows.getLastFocused(),
    browser.tabs.query({ active: true, lastFocusedWindow: true })
  ]);

  if (idleState !== "active") {
    // No input for a while. Exception: watching/listening to the focused tab
    // (e.g. a video) needs no input but is very much attention on the site.
    const watching = lastFocused.focused && activeTab?.audible;
    if (!watching) {
      return { type: "attention", state: "idle" };
    }
  }

  if (!lastFocused.focused) {
    return { type: "attention", state: "away" };
  }

  return { type: "attention", state: "site", ...siteOf(activeTab?.url) };
}

// Hostname for http(s) pages; omitted for browser-internal pages (chrome://, newtab).
function siteOf(url: string | undefined): { site?: string } {
  if (!url) return {};
  try {
    const parsed = new URL(url);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") {
      return { site: parsed.hostname };
    }
  } catch {
    // not a parseable URL — treat as internal
  }
  return {};
}
