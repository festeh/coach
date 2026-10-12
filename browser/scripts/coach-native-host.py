#!/usr/bin/env python3
"""Native messaging host for the coach extension: the file bridge.

Speaks the browser's native-messaging protocol on stdio (4-byte LE length
prefix + JSON) and does exactly one job — read and write the per-browser
whitelist source files. Paths are fixed relative to this repo; the extension
cannot ask for anything else.

Registered by scripts/install-native-host.sh (`npm run install:host`).
"""

import json
import os
import struct
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = {
    "chrome": os.path.join(REPO, "public", "whitelist-chrome.txt"),
    "firefox": os.path.join(REPO, "public", "whitelist-firefox.txt"),
    # Read-only: lets a running extension see the latest build stamp on disk
    # and reload itself onto it (Firefox can't read past its frozen xpi).
    "build": os.path.join(REPO, "public", "build.json"),
}
WRITABLE = {"chrome", "firefox"}


def read_message():
    raw = sys.stdin.buffer.read(4)
    if len(raw) < 4:
        return None
    (length,) = struct.unpack("<I", raw)
    return json.loads(sys.stdin.buffer.read(length))


def send(obj):
    data = json.dumps(obj).encode()
    sys.stdout.buffer.write(struct.pack("<I", len(data)))
    sys.stdout.buffer.write(data)
    sys.stdout.buffer.flush()


def main():
    while True:
        msg = read_message()
        if msg is None:
            return
        cmd = msg.get("cmd")
        path = FILES.get(msg.get("target"))
        if path is None:
            send({"ok": False, "error": "unknown target"})
            continue
        try:
            if cmd == "read":
                with open(path, encoding="utf-8") as f:
                    send({"ok": True, "content": f.read()})
            elif cmd == "write":
                if msg.get("target") not in WRITABLE:
                    send({"ok": False, "error": "read-only target"})
                    continue
                with open(path, "w", encoding="utf-8") as f:
                    f.write(msg.get("content", ""))
                send({"ok": True})
            else:
                send({"ok": False, "error": "unknown cmd"})
        except OSError as e:
            send({"ok": False, "error": str(e)})


if __name__ == "__main__":
    main()
