import { closeSync, mkdirSync, openSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { defineConfig } from 'wxt';

// Stamp the bundle with its build time so a loaded extension can show how
// fresh it is. Computed once here (config eval = build start) and in the
// builder's local time, so every entrypoint shares the same value.
const buildDate = (() => {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
})();

// Also drop the stamp into public/ so it ships as a loose file next to the
// bundles. The running extension polls it and reloads itself when the file
// on disk is newer than the constant compiled into its code.
writeFileSync(new URL('./public/build.json', import.meta.url), JSON.stringify({ build: buildDate }));

// Absolute whitelist paths, shown on the options page so the file is one
// copy-paste away from an editor. Each browser owns an independent file.
// Personal data lives outside the checkout. Chrome's generated bundle links
// to its live file; Firefox includes a snapshot as a native-host fallback.
const whitelistDir = join(homedir(), '.local', 'share', 'coach');
const whitelistChromeSource = join(whitelistDir, 'whitelist-chrome.txt');
const whitelistFirefoxSource = join(whitelistDir, 'whitelist-firefox.txt');

// See https://wxt.dev/api/config.html
export default defineConfig({
  outDir: 'dist',
  hooks: {
    'prepare:publicPaths'(_wxt, paths) {
      paths.push('whitelist-chrome.txt', 'whitelist-firefox.txt');
    },
    'build:publicAssets'(wxt, files) {
      const isFirefox = wxt.config.browser === 'firefox';
      const source = isFirefox ? whitelistFirefoxSource : whitelistChromeSource;
      mkdirSync(whitelistDir, { recursive: true, mode: 0o700 });
      closeSync(openSync(source, 'a', 0o600));
      files.push({
        absoluteSrc: source,
        relativeDest: isFirefox ? 'whitelist-firefox.txt' : 'whitelist-chrome.txt',
      });
    },
    'build:done'(wxt) {
      if (wxt.config.browser === 'firefox') return;
      const bundledFile = join(wxt.config.outDir, 'whitelist-chrome.txt');
      rmSync(bundledFile, { force: true });
      symlinkSync(whitelistChromeSource, bundledFile);
    },
  },
  manifest: ({ browser }) => ({
    icons: {
      16: "active-16.png",
      32: "active-32.png",
      48: "active-48.png",
      128: "active-128.png",
    },
    permissions: [
      "tabs", "storage", "webNavigation", "alarms", "idle", "nativeMessaging",
      ...(browser === "firefox" ? [] : ["debugger" as const]),
    ],
    browser_specific_settings: {
      gecko: {
        "id": "coach@dimalip.in"
      }
    }
  }),
  srcDir: 'src',
  modules: ['@wxt-dev/module-svelte'],
  manifestVersion: 3,
  webExt: {
    disabled: true
  },
  // Private extension: readable bundles beat small ones (debugging in DevTools).
  vite: () => ({
    build: {
      minify: false
    },
    define: {
      __BUILD_DATE__: JSON.stringify(buildDate),
      __WHITELIST_CHROME_SOURCE__: JSON.stringify(whitelistChromeSource),
      __WHITELIST_FIREFOX_SOURCE__: JSON.stringify(whitelistFirefoxSource)
    }
  })
});
