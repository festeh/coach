// In-browser editing of the whitelist source file. Two transports, tried in
// order: the native host (works in Chrome and Firefox, no prompts — see
// lib/nativeHost.ts) and, without it, the File System Access API (Chrome
// only; the user picks the file once, the handle persists in IndexedDB,
// shared by every extension page). Either way the file stays the source of
// truth: after a write we ask the background to re-sync, the same path an
// editor save takes.

import { hostAvailable, hostReadWhitelist, hostWriteWhitelist } from "./nativeHost";

const DB_NAME = "coach";
const STORE = "handles";
const KEY = "whitelist";

export type EditMode = "host" | "picker" | "none";

export async function editMode(): Promise<EditMode> {
  if (await hostAvailable()) return "host";
  return supportsFileEditing() ? "picker" : "none";
}

export function supportsFileEditing(): boolean {
  return typeof window !== "undefined" && "showOpenFilePicker" in window;
}

// --- line-level edits: comments and ordering always survive ---

function withSiteAdded(text: string, host: string): string | null {
  const lines = text.split("\n");
  if (lines.some((l) => l.trim() === host)) return null;
  while (lines.length > 0 && lines[lines.length - 1].trim() === "") lines.pop();
  lines.push(host, "");
  return lines.join("\n");
}

function withSiteRemoved(text: string, host: string): string {
  return text
    .split("\n")
    .filter((l) => l.trim() !== host)
    .join("\n");
}

// --- File System Access handle plumbing (picker mode) ---

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function idbRequest<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function getConnectedHandle(): Promise<FileSystemFileHandle | null> {
  const db = await openDb();
  const handle = await idbRequest(
    db.transaction(STORE).objectStore(STORE).get(KEY) as IDBRequest<FileSystemFileHandle | undefined>
  );
  db.close();
  return handle ?? null;
}

// Must be called from a user gesture (a click) — the picker requires it.
export async function connectFile(): Promise<FileSystemFileHandle> {
  const [handle] = await window.showOpenFilePicker!({
    types: [{ description: "Coach whitelist", accept: { "text/plain": [".txt"] } }]
  });
  const db = await openDb();
  await idbRequest(db.transaction(STORE, "readwrite").objectStore(STORE).put(handle, KEY));
  db.close();
  return handle;
}

async function withPermission(handle: FileSystemFileHandle): Promise<boolean> {
  if ((await handle.queryPermission!({ mode: "readwrite" })) === "granted") return true;
  return (await handle.requestPermission!({ mode: "readwrite" })) === "granted";
}

// --- read-modify-write over whichever transport answers ---

function notifySynced(): void {
  // The file changed; don't wait for the next alarm tick to show it.
  void browser.runtime.sendMessage({ type: "sync_whitelist" });
}

async function editViaHandle(edit: (text: string) => string | null): Promise<boolean> {
  const handle = await getConnectedHandle();
  if (!handle || !(await withPermission(handle))) return false;
  const text = await (await handle.getFile()).text();
  const next = edit(text);
  if (next !== null) {
    const writable = await handle.createWritable();
    await writable.write(next);
    await writable.close();
  }
  notifySynced();
  return true;
}

async function editFile(edit: (text: string) => string | null): Promise<boolean> {
  const text = await hostReadWhitelist();
  if (text !== null) {
    const next = edit(text);
    if (next !== null && !(await hostWriteWhitelist(next))) return false;
    notifySynced();
    return true;
  }
  return editViaHandle(edit);
}

export async function addSite(host: string): Promise<boolean> {
  return editFile((text) => withSiteAdded(text, host));
}

export async function removeSite(host: string): Promise<boolean> {
  return editFile((text) => withSiteRemoved(text, host));
}
