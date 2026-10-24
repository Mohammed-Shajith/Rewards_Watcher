# Changelog

## 2.1.0: Lite mode (unreleased)

- **Browserless "lite" fetcher**: checks the store with plain HTTP requests
  carrying the cookies from `session.json`. Uses only the Python standard
  library: no Playwright, no Chromium. About 0.7 s and 140 KB per check.
- Lite mode saves cookies Microsoft refreshes back to `session.json` (same
  format, so browser mode can still read it) and reloads the file when a
  fresh copy arrives.
- New `fetcher` setting: `auto` (browser with a local profile, lite with
  `session.json`), `browser`, or `lite`.
- `./setup.sh --lite`: install without Playwright or a browser, for servers
  and phones.
- `--version` flag. `check` and `run` print the version, sign-in mode and
  fetcher.
- Lite requests identify themselves as `Rewards_Watcher/<version>`.

## 2.0.0: Keep it running

- **Server mode**: portable `session.json` sign-in, `export-session`,
  `deploy/push-session.sh`, systemd service via `deploy/install-service.sh`,
  Linux installer.
- **macOS background agent**: `deploy/macos-agent.sh` (starts at login,
  restarts on crash, survives closing the terminal).

## 1.0.0: Initial release

- Restock watcher for the Microsoft Rewards gift card store.
- Alerts: ntfy (phone), email (SMTP), Mac notifications, WhatsApp (CallMeBot).
- Restock history, setup wizard, one-command installer.
