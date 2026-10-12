/// <reference types="svelte" />

// Local build time, injected by Vite `define` in wxt.config.ts.
declare const __BUILD_DATE__: string;

// Absolute paths to the per-browser whitelist files, injected by Vite
// `define`. Chrome reads its source live through a symlink in dist.
declare const __WHITELIST_CHROME_SOURCE__: string;
declare const __WHITELIST_FIREFOX_SOURCE__: string;

// File System Access API surface TypeScript's dom lib doesn't cover yet.
// Chrome implements these; Firefox has none of it (hence the optionality).
interface FileSystemFileHandle {
  queryPermission?(descriptor: { mode: "read" | "readwrite" }): Promise<PermissionState>;
  requestPermission?(descriptor: { mode: "read" | "readwrite" }): Promise<PermissionState>;
}

interface Window {
  showOpenFilePicker?(options?: {
    types?: { description?: string; accept: Record<string, string[]> }[];
  }): Promise<FileSystemFileHandle[]>;
}
