// Ring buffer of the latest site visits, so occasionally-blocked sites are
// easy to find and whitelist. Lives in storage.session: RAM-backed (never
// written to disk), survives MV3 service-worker restarts, cleared when the
// browser closes — "in-memory" that actually works in MV3.

export interface Visit {
  host: string;
  at: number;
  blocked: boolean;
}

const KEY = "recent_visits";
const MAX = 100;

export async function recordVisit(host: string, blocked: boolean): Promise<void> {
  if (!host) return;
  try {
    const raw = await browser.storage.session.get(KEY);
    const list = (raw[KEY] ?? []) as Visit[];
    // The same navigation reaches the background from two listeners
    // (onBeforeNavigate and tabs.onUpdated); collapse the echo.
    const head = list[0];
    if (head && head.host === host && head.blocked === blocked && Date.now() - head.at < 3000) {
      return;
    }
    const next = [{ host, at: Date.now(), blocked }, ...list].slice(0, MAX);
    await browser.storage.session.set({ [KEY]: next });
  } catch {
    // No storage.session (old Firefox): the visits page just stays empty.
  }
}

export async function getVisits(): Promise<Visit[]> {
  try {
    const raw = await browser.storage.session.get(KEY);
    return (raw[KEY] ?? []) as Visit[];
  } catch {
    return [];
  }
}
