export type IconState = "active" | "inactive" | "disconnected";

const SIZES = [16, 32, 48, 128] as const;

function getIconPaths(state: IconState): Record<number, string> {
  return Object.fromEntries(
    SIZES.map((size) => [size, `${state}-${size}.png`])
  );
}

export async function updateIcon(connected: boolean, blocking: boolean): Promise<void> {
  const state: IconState = !connected ? "disconnected" : blocking ? "active" : "inactive";
  await browser.action.setIcon({ path: getIconPaths(state) });
}
