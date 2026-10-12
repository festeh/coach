import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const host = fileURLToPath(new URL("../scripts/coach-native-host.py", import.meta.url));
const bootstrap = `
import importlib.util, os, sys
spec = importlib.util.spec_from_file_location("coach_native_host", sys.argv[1])
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)
assert host.FILES["chrome"] == os.path.expanduser("~/.local/share/coach/whitelist-chrome.txt")
assert host.FILES["firefox"] == os.path.expanduser("~/.local/share/coach/whitelist-firefox.txt")
host.FILES = {
    "chrome": os.path.join(sys.argv[2], "data", "whitelist-chrome.txt"),
    "firefox": os.path.join(sys.argv[2], "data", "whitelist-firefox.txt"),
    "build": os.path.join(sys.argv[2], "build.json"),
}
host.main()
`;

function exchange(directory: string, messages: object[]): unknown[] {
  const input = Buffer.concat(messages.map(message => {
    const payload = Buffer.from(JSON.stringify(message));
    const header = Buffer.alloc(4);
    header.writeUInt32LE(payload.length);
    return Buffer.concat([header, payload]);
  }));
  const result = spawnSync("python3", ["-B", "-c", bootstrap, host, directory], { input });
  assert.equal(result.status, 0, result.stderr?.toString());
  const replies: unknown[] = [];
  for (let offset = 0; offset < result.stdout.length;) {
    const size = result.stdout.readUInt32LE(offset);
    offset += 4;
    replies.push(JSON.parse(result.stdout.subarray(offset, offset + size).toString()));
    offset += size;
  }
  return replies;
}

test("native host initializes and edits separate personal files without overwriting existing data", () => {
  const directory = mkdtempSync(join(tmpdir(), "coach-native-host-"));
  try {
    assert.deepEqual(exchange(directory, [
      { cmd: "read", target: "chrome" },
      { cmd: "write", target: "chrome", content: "allowed.example\n" },
      { cmd: "read", target: "firefox" },
    ]), [{ ok: true, content: "" }, { ok: true }, { ok: true, content: "" }]);
    assert.deepEqual(exchange(directory, [
      { cmd: "read", target: "chrome" },
      { cmd: "write", target: "build", content: "overwrite" },
      { cmd: "read", target: "arbitrary-path" },
    ]), [
      { ok: true, content: "allowed.example\n" },
      { ok: false, error: "read-only target" },
      { ok: false, error: "unknown target" },
    ]);
    assert.equal(readFileSync(join(directory, "data", "whitelist-chrome.txt"), "utf8"), "allowed.example\n");
    assert.equal(readFileSync(join(directory, "data", "whitelist-firefox.txt"), "utf8"), "");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
