#!/usr/bin/env bash
# Run ON THE SERVER, from the Rewards_Watcher folder, after ./setup.sh and
# after copying session.json here. Installs a systemd service so the watcher
# starts on boot and restarts if it crashes.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR="$(pwd)"
USER_NAME="$(id -un)"

if [ ! -f session.json ]; then
  echo "session.json not found in $DIR."
  echo "On your computer run:  ./deploy/push-session.sh $USER_NAME@<this-server>"
  exit 1
fi

echo "==> Checking the sign-in works here"
.venv/bin/python rewards_watcher.py check

sed -e "s|__USER__|$USER_NAME|g" -e "s|__DIR__|$DIR|g" \
  deploy/rewards-watcher.service | sudo tee /etc/systemd/system/rewards-watcher.service >/dev/null
sudo systemctl daemon-reload
sudo systemctl enable --now rewards-watcher
echo
echo "Installed and started. Useful commands:"
echo "  sudo systemctl status rewards-watcher     # is it running?"
echo "  journalctl -u rewards-watcher -f          # live log"
echo "  sudo systemctl restart rewards-watcher    # after changing config.json"
