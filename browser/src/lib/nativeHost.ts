// Bridge to the native host (scripts/coach-native-host.py) — the one
// capability the sandbox lacks: reading and writing the whitelist source
// files on disk. Registered per browser by `npm run install:host`; every
// call degrades gracefully when the host isn't installed.

const HOST = "in.dimalip.coach";
const TARGET = import.meta.env.BROWSER === "firefox" ? "firefox" : "chrome";

interface HostResponse {
  ok: boolean;
  content?: string;
  error?: string;
}

async function call(message: object): Promise<HostResponse | null> {
  try {
    return (await browser.runtime.sendNativeMessage(HOST, message)) as HostResponse;
  } catch {
    return null; // host not installed, or nativeMessaging unavailable (Android)
  }
}

export async function hostReadWhitelist(): Promise<string | null> {
  const res = await call({ cmd: "read", target: TARGET });
  return res?.ok && typeof res.content === "string" ? res.content : null;
}

export async function hostWriteWhitelist(content: string): Promise<boolean> {
  return (await call({ cmd: "write", target: TARGET, content }))?.ok === true;
}

export async function hostAvailable(): Promise<boolean> {
  return (await hostReadWhitelist()) !== null;
}

// The build stamp of the newest build on disk (public/build.json) — the one
// signal a frozen Firefox xpi cannot see by itself.
export async function hostReadBuildStamp(): Promise<string | null> {
  const res = await call({ cmd: "read", target: "build" });
  if (!res?.ok || typeof res.content !== "string") return null;
  try {
    const parsed = JSON.parse(res.content) as { build?: string };
    return typeof parsed.build === "string" ? parsed.build : null;
  } catch {
    return null;
  }
}
