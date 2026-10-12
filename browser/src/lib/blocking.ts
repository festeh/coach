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

export function hostnameOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "";
  }
}

export async function blockPage(options: BlockOptions): Promise<BlockResult> {
  const notBlocked: BlockResult = { blocked: false, target: "" };

  const { focusing, agent_release_time_left, whitelist } = await getStorage('focusing', 'agent_release_time_left', 'whitelist');

  if (!focusing && agent_release_time_left !== null) return notBlocked;

  const isWhitelisted = whitelist.some(site => options.url.includes(site));
  if (isWhitelisted) return notBlocked;

  // Blocked → the coach chat, carrying which wall was hit. The plea (or the
  // override) starts there.
  const target = hostnameOf(options.url);
  const chatUrl = `${browser.runtime.getURL('/chat.html')}?blocked=${encodeURIComponent(target)}`;
  browser.tabs.update(options.tabId, { url: chatUrl });

  return { blocked: true, target };
}
