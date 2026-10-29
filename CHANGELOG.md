# Changelog

## 3.0.0: Android app

- First stable **Android app** release: native, **58 KB**, no libraries.
  Checks the store itself every 10 minutes with exact alarms, has its own
  notifications, and supports in-app Microsoft sign-in.
- Signed with a dedicated release key (future updates install over it).
- The app reschedules itself when opened if its alarm went missing (e.g.
  after a force-stop or a "phone cleaner").
- Size & battery report: ~0.3% battery a day, ~6 MB data a day, longest
  overnight gap 15 min (see `android/README.md`).

## 3.0.0-alpha2: Android in-app sign-in and settings

- **Sign in with Microsoft inside the app**: no computer or `session.json`
  needed. The WebView is only loaded on the sign-in screen.
- **Settings**: check interval, cards to watch or ignore, store region.
- **Quick retry**: after a failed check (Microsoft 503s, brief network
  drops), one retry a minute later, then back to the normal interval.
- Overnight test (M3) passed on a Realme: checks every ~10 min, longest gap
  15 min, ~0.3% battery a day. ~58 KB.

## 3.0.0-alpha1: Android app (in progress)

- **Native Android app** in `android/`: checks the store itself and shows its
  own notifications (no server, ntfy or Python). Plain Java, no libraries,
  **~53 KB**.
- Exact alarms every 10 minutes that may fire in Doze. Nothing runs between
  checks, and it restarts after a reboot or app update.
- Sign-in by importing the desktop `session.json`. Refreshed cookies are
  kept.
- On-phone diagnostics: every check with the gap since the previous one,
  result and data used.

## 2.1.1: Lite by default

- **Fix (macOS):** the long-running watcher no longer keeps a hidden
  Edge/Chrome open. On macOS that hidden browser took over the Dock icon:
  clicking Edge showed the menu bar but no window. Whenever `session.json`
  exists (`login` always writes it), `auth: auto` now uses it, so `run` and
  `check` use lite mode with no browser. The browser profile is only used
  briefly by `login` and `export-session`.
- Signed-out alert on a desktop now suggests `export-session` first.

## 2.1.0: Lite mode

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
