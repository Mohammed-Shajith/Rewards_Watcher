#!/usr/bin/env bash
# Run Rewards_Watcher in the background on macOS: starts at login, restarts
# if it crashes, and keeps going when you close the terminal.
#   ./deploy/macos-agent.sh install | uninstall | start | stop | restart | status | logs
set -euo pipefail
cd "$(dirname "$0")/.."
DIR="$(pwd)"
LABEL="com.rewardswatcher.agent"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"

case "${1:-}" in
  install)
    [ -x .venv/bin/python ] || { echo "Run ./setup.sh first."; exit 1; }
    [ -d profile ] || [ -f session.json ] || { echo "Run '.venv/bin/python rewards_watcher.py login' first."; exit 1; }
    if pgrep -f "rewards_watcher.py run" >/dev/null; then
      echo "Note: a watcher is already running (e.g. in a terminal). Stop it with"
      echo "Ctrl+C first, or you'll get every alert twice."
    fi
    mkdir -p data "$HOME/Library/LaunchAgents"
    sed "s|__DIR__|$DIR|g" deploy/$LABEL.plist > "$PLIST"
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    launchctl bootstrap "$DOMAIN" "$PLIST"
    echo "Installed. Rewards_Watcher now runs in the background and starts at login."
    echo "Log: $DIR/data/watcher.log   (./deploy/macos-agent.sh logs)"
    ;;
  uninstall)
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    echo "Removed. The watcher no longer runs in the background."
    ;;
  restart)
    launchctl kickstart -k "$DOMAIN/$LABEL"
    echo "Restarted (picks up config.json changes)."
    ;;
  stop)
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    echo "Stopped until next login (or './deploy/macos-agent.sh start')."
    ;;
  start)
    [ -f "$PLIST" ] || { echo "Not installed. Run: ./deploy/macos-agent.sh install"; exit 1; }
    launchctl bootstrap "$DOMAIN" "$PLIST" 2>/dev/null || launchctl kickstart "$DOMAIN/$LABEL"
    echo "Started."
    ;;
  status)
    if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
      launchctl print "$DOMAIN/$LABEL" | grep -E "^\s+(state|pid|last exit code) =" || true
      echo "--- last log lines ---"
      tail -n 5 data/watcher.log 2>/dev/null || true
    else
      echo "Not installed."
    fi
    ;;
  logs)
    tail -n 30 -f data/watcher.log
    ;;
  *)
    echo "Usage: ./deploy/macos-agent.sh install | uninstall | start | stop | restart | status | logs"
    exit 1
    ;;
esac
