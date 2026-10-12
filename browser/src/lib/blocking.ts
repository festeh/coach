import { getStorage } from "@/lib/storage";

interface BlockOptions {
  tabId: number
  url: string
}

// What blockPage did, so the caller can report a temptation when it blocked.
export interface BlockResult {
  blocked: boolean
  target: string
}

// Agents such as Kimi create the tab before attaching their debugger. Give
// only newly created tabs a bounded grace period; existing tabs do not wait.
const ATTACH_GRACE_MS = 1000;
const ATTACH_POLL_MS = 50;
const newTabs = new Map<number, number>();
const pendingChecks = new Map<number, symbol>();

export function noteTabCreated(tabId: number): void {
  newTabs.set(tabId, Date.now() + ATTACH_GRACE_MS);
  setTimeout(() => newTabs.delete(tabId), ATTACH_GRACE_MS);
}

export function cancelPendingBlock(tabId: number): void {
  pendingChecks.delete(tabId);
}

export function forgetTab(tabId: number): void {
  cancelPendingBlock(tabId);
  newTabs.delete(tabId);
}

async function hasDebugger(tabId: number, isCurrent: () => boolean): Promise<boolean> {
  // Firefox has no debugger API. Coach only observes attachments; it never
  // attaches to a tab or interferes with the agent's debugging connection.
  if (!browser.debugger?.getTargets) return false;

  while (isCurrent()) {
    try {
      const targets = await browser.debugger.getTargets();
      if (targets.some(target => target.tabId === tabId && target.attached)) return true;
    } catch (error) {
      // A missing permission or API failure must not turn off blocking.
      console.warn("[coach] Could not check debugger attachment", error);
      return false;
    }

    const remaining = (newTabs.get(tabId) ?? 0) - Date.now();
    if (remaining <= 0) return false;
    await new Promise(resolve => setTimeout(resolve, Math.min(ATTACH_POLL_MS, remaining)));
  }
  return false;
}

async function shouldBlock(url: string): Promise<boolean> {
  const { focusing, agent_release_time_left, whitelist } = await getStorage('focusing', 'agent_release_time_left', 'whitelist');
  return (focusing || agent_release_time_left === null) && !whitelist.some(site => url.includes(site));
}

export function hostnameOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "";
  }
}

// Where the chat page sends the user back once the lock opens. The redirect
// carries the full URL, so returning lands on the page that was asked for,
// not just its site. Only web pages come back out: anything else is ignored.
export function blockedTargetOf(search: string): { url: string; host: string } | null {
  const url = new URLSearchParams(search).get("blocked") ?? "";
  if (!/^https?:\/\//.test(url)) return null;
  const host = hostnameOf(url);
  return host ? { url, host } : null;
}

export async function blockPage(options: BlockOptions): Promise<BlockResult> {
  const notBlocked: BlockResult = { blocked: false, target: "" };
  const { tabId, url } = options;
  const check = Symbol();
  pendingChecks.set(tabId, check);
  const isCurrent = () => pendingChecks.get(tabId) === check;

  try {
    if (!(await shouldBlock(url)) || !isCurrent()) return notBlocked;
    if (await hasDebugger(tabId, isCurrent)) return notBlocked;

    // The grace period may span another navigation, a tab closing, a release,
    // or a whitelist edit. Never redirect using a superseded decision.
    if (!isCurrent() || !(await shouldBlock(url)) || !isCurrent()) return notBlocked;

    const target = hostnameOf(url);
    const chatUrl = `${browser.runtime.getURL('/chat.html')}?blocked=${encodeURIComponent(url)}`;
    try {
      await browser.tabs.update(tabId, { url: chatUrl });
      return { blocked: true, target };
    } catch {
      // The tab can disappear between the last check and the redirect.
      return notBlocked;
    }
  } finally {
    if (isCurrent()) pendingChecks.delete(tabId);
  }
}
