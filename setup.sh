#!/usr/bin/env bash
# One-command install for Rewards_Watcher (macOS / Linux).
#   ./setup.sh          full install: Playwright + a browser (needed for `login`)
#   ./setup.sh --lite   lite install: no Playwright, no browser, standard
#                       library only. For servers and phones that use a
#                       session.json copied from a computer where you ran `login`.
set -euo pipefail
cd "$(dirname "$0")"

LITE=false
[ "${1:-}" = "--lite" ] && LITE=true

if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3.9 or newer is required: https://www.python.org/downloads/"
  exit 1
fi

echo "==> Creating virtual environment (.venv)"
python3 -m venv .venv
.venv/bin/pip install -q --upgrade pip

if [ "$LITE" = true ]; then
  echo "==> Lite install: skipping Playwright and the browser"
  echo "    (you'll need session.json from a computer where you ran 'login')"
else
  echo "==> Installing Playwright"
  .venv/bin/pip install -q -r requirements.txt

  has_browser=false
  for p in "/Applications/Microsoft Edge.app" "/Applications/Google Chrome.app" \
           /usr/bin/microsoft-edge /usr/bin/google-chrome; do
    [ -e "$p" ] && has_browser=true
  done
  if [ "$has_browser" = false ]; then
    if [ "$(uname)" = "Linux" ]; then
      case "$(uname -m)" in
        x86_64|aarch64|arm64) ;;
        *) echo "Playwright needs a 64-bit OS (x86_64 or arm64). On a Raspberry Pi,"
           echo "install Raspberry Pi OS (64-bit), or use: ./setup.sh --lite"; exit 1 ;;
      esac
      echo "==> Downloading Chromium and its system libraries (may ask for sudo)"
      .venv/bin/python -m playwright install --with-deps chromium
    else
      echo "==> No Edge or Chrome found, downloading Playwright's Chromium"
      .venv/bin/python -m playwright install chromium
    fi
  fi

  if [ "$(uname)" = "Darwin" ] && ! command -v terminal-notifier >/dev/null 2>&1; then
    echo "Tip: 'brew install terminal-notifier' gives clickable Mac notifications."
  fi
fi

echo
.venv/bin/python rewards_watcher.py setup
