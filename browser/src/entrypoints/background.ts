import { blockPage, cancelPendingBlock, forgetTab, hostnameOf, noteTabCreated, type BlockResult } from "@/lib/blocking";
import { recordVisit } from "@/lib/visits";
import { hostReadWhitelist, hostReadBuildStamp } from "@/lib/nativeHost";
import {
  logError,
  WebSocketManager,
  queryAttention
} from "@/lib/background";
import type { ExtensionMessage } from "@/lib/background";
import { getStorage, setStorage } from "@/lib/storage";
import { updateIcon } from "@/lib/icons";
import { browserSource } from "@/lib/source";

const serverUrl = import.meta.env.VITE_SERVER as string;
const coachToken = (import.meta.env.VITE_COACH_TOKEN as string | undefined) ?? "";
const RECONNECT_CHECK_ALARM = "reconnect-check";
const SOURCE = browserSource();

let wsManager: WebSocketManager;

function setupBrowserListeners(): void {
  browser.tabs.onCreated.addListener(tab => {
    if (tab.id !== undefined) noteTabCreated(tab.id);
  });
  browser.tabs.onRemoved.addListener(forgetTab);

  browser.runtime.onMessage.addListener((message: ExtensionMessage) => {
    switch (message.type) {
      case "get_connection": {
        // Live socket truth for page mounts: storage can hold a stale value
        // when a page missed a change event, so pages re-anchor on this. Also
        // heal storage while we're at it.
        const connected = wsManager.isOpen();
        if (connected) cancelPendingDisconnect();
        void setStorage({ connected });
        return Promise.resolve({ connected });
      }
      case "get_focusing":
        wsManager.send(message);
        break;
      case "reconnect":
        wsManager.reconnect();
        break;
      case "override":
      case "short_unblock":
        // From the chat page: rides the coach socket like temptations do,
        // so the page needs no host permissions of its own.
        wsManager.send(message);
        break;
      case "sync_whitelist":
        // A page just wrote the whitelist file; pick it up now instead of
        // waiting for the next alarm tick.
        void syncWhitelistFromFile();
        break;
    }
  });

  browser.webNavigation.onBeforeNavigate.addListener(async (details) => {
    const { tabId, url, frameId } = details;
    if (frameId !== 0) return;
    if (!url || !url.startsWith("http")) {
      cancelPendingBlock(tabId);
      return;
    }

    const result = await blockPage({ url, tabId });
    void recordVisit(hostnameOf(url), result.blocked);
    void reportIfBlocked(tabId, result);
  });

  browser.tabs.onUpdated.addListener(async (tabId, changeInfo) => {
    if (!tabId) return;

    const { url } = changeInfo;
    if (!url) return;
    if (!url.startsWith("http")) {
      cancelPendingBlock(tabId);
      return;
    }

    const result = await blockPage({ url, tabId });
    void recordVisit(hostnameOf(url), result.blocked);
    void reportIfBlocked(tabId, result);
  });

  browser.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === RECONNECT_CHECK_ALARM) {
      wsManager.ensureConnected();
      // Heartbeat half of the attention beacon: confirms "still on X" even
      // if the service worker slept through every transition since last tick.
      void sendAttention();
      void reloadIfNewBuild();
      void syncWhitelistFromFile();
    }
  });
}

// Transition half of the attention beacon: any change to what could have the
// user's attention re-queries live state and reports it immediately.
function setupAttentionListeners(): void {
  const report = () => void sendAttention();

  browser.tabs.onActivated.addListener(report);
  // Desktop-only signals — Firefox for Android has neither API.
  browser.windows?.onFocusChanged.addListener(report);
  browser.idle?.onStateChanged.addListener(report);
  browser.tabs.onUpdated.addListener((_tabId, changeInfo, tab) => {
    // URL changes move attention; audible changes toggle the watching-video
    // exception to idle. Either way only the active tab matters.
    if (tab.active && (changeInfo.url || changeInfo.audible !== undefined)) {
      report();
    }
  });
}

async function sendAttention(): Promise<void> {
  try {
    wsManager.send(await queryAttention());
  } catch (error) {
    logError("Failed to send attention beacon", error);
  }
}

// The MV3 worker dies and resurrects constantly, and each cycle bounces the
// socket for well under a second. Writing connected:false immediately makes
// every open page flash "Reconnecting" through each blip — so give a
// reconnect a grace window and only publish disconnects that last.
const DISCONNECT_GRACE_MS = 3000;
let disconnectTimer: ReturnType<typeof setTimeout> | null = null;

function markDisconnectedSoon(): void {
  if (disconnectTimer) return;
  disconnectTimer = setTimeout(async () => {
    disconnectTimer = null;
    await setStorage({ connected: false });
    await updateIcon(false, false);
  }, DISCONNECT_GRACE_MS);
}

function cancelPendingDisconnect(): void {
  if (disconnectTimer) {
    clearTimeout(disconnectTimer);
    disconnectTimer = null;
  }
}

// The whitelist lives in ~/.local/share/coach/whitelist-<browser>.txt,
// one hostname per line, # for comments. The file is the source
// of truth: whenever its parsed content differs from storage, storage is
// replaced. Preferred read is the native host, which sees the live file on
// disk — on Firefox that's fresher than the copy frozen into the xpi. Without
// the host, fall back to the bundled copy (on Chrome still live, via the
// symlink in dist).
async function syncWhitelistFromFile(): Promise<void> {
  const file =
    import.meta.env.BROWSER === "firefox" ? "/whitelist-firefox.txt" : "/whitelist-chrome.txt";
  let text = await hostReadWhitelist();
  if (text === null) {
    try {
      const res = await fetch(browser.runtime.getURL(file));
      if (!res.ok) return;
      text = await res.text();
    } catch {
      // Old bundle without the file, or xpi mid-replacement: keep what we have.
      return;
    }
  }
  const sites = text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line !== "" && !line.startsWith("#"));
  const { whitelist } = await getStorage("whitelist");
  const changed = JSON.stringify([...sites].sort()) !== JSON.stringify([...whitelist].sort());
  if (changed) {
    await setStorage({ whitelist: sites });
    console.info(`[coach] whitelist synced from file (${sites.length} sites)`);
  }
}

// A newer build on disk means reload to become it. The stamp comes from the
// native host when available (public/build.json — the only way Firefox can
// see past its frozen xpi; runtime.reload() then re-reads the replaced file),
// else from fetching our own bundled copy (Chrome serves it live from disk).
// last_reload_stamp guards the Firefox case where a reload might not pick up
// the new xpi: one attempt per stamp, never a loop.
async function reloadIfNewBuild(): Promise<void> {
  let build = await hostReadBuildStamp();
  if (build === null) {
    try {
      const res = await fetch(browser.runtime.getURL("/build.json"));
      build = ((await res.json()) as { build?: string }).build ?? null;
    } catch {
      return; // old bundle without build.json, or unreadable mid-install
    }
  }
  if (!build || build === __BUILD_DATE__) return;
  const { last_reload_stamp } = await getStorage("last_reload_stamp");
  if (last_reload_stamp === build) return;
  await setStorage({ last_reload_stamp: build });
  console.info(`[coach] build ${build} found on disk (running ${__BUILD_DATE__}), reloading`);
  browser.runtime.reload();
}

// Report a temptation only when the blocked tab is the active one — a
// background tab refreshing a blocked site is noise, not a deliberate reach.
async function reportIfBlocked(tabId: number, result: BlockResult): Promise<void> {
  if (!result.blocked) return;
  try {
    const [active] = await browser.tabs.query({ active: true, lastFocusedWindow: true });
    if (active?.id !== tabId) return;
    wsManager.send({ type: "temptation", source: SOURCE, target: result.target });
  } catch (error) {
    logError("Failed to send temptation", error);
  }
}

export default defineBackground({
  main() {
    // Print the build stamp on every boot so the service-worker console says
    // which bundle is actually running.
    console.info(`[coach] background started — build ${__BUILD_DATE__}`);
    // MV3 service workers replay events that fire while the SW is asleep, but
    // only to listeners registered synchronously during script evaluation.
    // Anything past an `await` may miss wakeup events, so wire listeners and
    // construct managers up front, then do async init in the background.
    wsManager = new WebSocketManager(serverUrl, {
      onConnected: async () => {
        cancelPendingDisconnect();
        await setStorage({ connected: true, reconnect_at: 0 });
        const { focusing, agent_release_time_left } = await getStorage("focusing", "agent_release_time_left");
        await updateIcon(true, focusing || agent_release_time_left === null);
        // Give the server the current attention state right away rather than
        // waiting for the next transition or heartbeat.
        void sendAttention();
      },
      onDisconnected: () => {
        markDisconnectedSoon();
      },
      onReconnectScheduled: (reconnectAt) => {
        setStorage({ reconnect_at: reconnectAt });
      },
      onFocusMessage: async (message) => {
        try {
          // The one wire→storage mapping; everyone else passes the snapshot whole.
          await setStorage({
            focusing: message.focusing,
            agent_release_time_left: message.agent_release_time_left,
            override_state: {
              overrides_today: message.overrides_today,
              override_phase: message.override_phase,
              override_cooldown_seconds: message.override_cooldown_seconds,
              override_cooldown_remaining: message.override_cooldown_remaining,
              override_redeem_remaining: message.override_redeem_remaining,
              override_next_cooldown_seconds: message.override_next_cooldown_seconds,
              synced_at: Date.now()
            }
          });
          await updateIcon(true, message.focusing || message.agent_release_time_left === null);
        } catch (error) {
          logError("Error saving focus to storage", error);
        }
      }
    }, coachToken);

    setupBrowserListeners();
    try {
      setupAttentionListeners();
    } catch (error) {
      // The attention sensor is auxiliary — a failure here (e.g. a stale
      // permission grant on an unpacked install) must not take down
      // blocking and the WebSocket with it.
      logError("Failed to set up attention listeners", error);
    }
    browser.alarms.create(RECONNECT_CHECK_ALARM, { periodInMinutes: 0.5 });

    void initState();
  }
});

async function initState(): Promise<void> {
  // Don't stamp connected:false just because the worker rebooted — the socket
  // usually reopens in well under a second, and pages would flash
  // "Reconnecting" for nothing. If the connect fails, the close event flips
  // the flag through the grace window; until then the last known state stands.
  await syncWhitelistFromFile();
  wsManager.connect();
}
