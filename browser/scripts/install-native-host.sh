#!/usr/bin/env bash
# Register the coach native-messaging host with Chrome, Chromium and Firefox.
# Idempotent; rerun after moving the repo.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST="$REPO/scripts/coach-native-host.py"
NAME="in.dimalip.coach"
# Chrome derives an unpacked extension's id from its absolute directory.
# Calculate it again when this project moves. An explicitly configured ID
# also supports installations whose manifest supplies a public key.
CHROME_ID="${COACH_CHROME_EXTENSION_ID:-$(python3 - "$REPO/dist/chrome-mv3" <<'PY'
import hashlib
import os
import sys

digest = hashlib.sha256(os.path.realpath(sys.argv[1]).encode()).hexdigest()[:32]
print(''.join(chr(ord('a') + int(nibble, 16)) for nibble in digest))
PY
)}"

chmod +x "$HOST"

for dir in "$HOME/.config/google-chrome/NativeMessagingHosts" "$HOME/.config/chromium/NativeMessagingHosts"; do
  mkdir -p "$dir"
  cat > "$dir/$NAME.json" <<EOF
{
  "name": "$NAME",
  "description": "Coach extension file bridge (whitelist read/write)",
  "path": "$HOST",
  "type": "stdio",
  "allowed_origins": ["chrome-extension://$CHROME_ID/"]
}
EOF
  echo "wrote $dir/$NAME.json"
done

mkdir -p "$HOME/.mozilla/native-messaging-hosts"
cat > "$HOME/.mozilla/native-messaging-hosts/$NAME.json" <<EOF
{
  "name": "$NAME",
  "description": "Coach extension file bridge (whitelist read/write)",
  "path": "$HOST",
  "type": "stdio",
  "allowed_extensions": ["coach@dimalip.in"]
}
EOF
echo "wrote $HOME/.mozilla/native-messaging-hosts/$NAME.json"
