#!/usr/bin/env bash
# Register the coach native-messaging host with Chrome, Chromium and Firefox.
# Idempotent; rerun after moving the repo.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST="$REPO/scripts/coach-native-host.py"
NAME="in.dimalip.coach"
# Chrome derives an unpacked extension's id from its directory; this one is
# stable as long as the extension loads from $REPO/dist/chrome-mv3.
CHROME_ID="bkgfmlfpicibookmhogkodnolhcfgfhf"

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
