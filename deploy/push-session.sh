#!/usr/bin/env bash
# Run ON YOUR COMPUTER (where you ran `login`). Copies your sign-in to the
# server over SSH. The running watcher picks it up automatically.
#   ./deploy/push-session.sh user@server [remote-folder]
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -lt 1 ]; then
  echo "Usage: ./deploy/push-session.sh user@server [remote-folder]"
  echo "Example: ./deploy/push-session.sh pi@raspberrypi.local"
  exit 1
fi
HOST="$1"
REMOTE_DIR="${2:-Rewards_Watcher}"

echo "==> Refreshing session.json from your browser profile"
.venv/bin/python rewards_watcher.py export-session >/dev/null

echo "==> Copying to $HOST:$REMOTE_DIR/session.json"
scp -q session.json "$HOST:$REMOTE_DIR/session.json"
ssh "$HOST" "chmod 600 '$REMOTE_DIR/session.json'"
echo "Done. The watcher on the server reloads it on its next check."
