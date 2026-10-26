# Rewards Watcher for Android (alpha)

A tiny native Android app that checks the Microsoft Rewards store itself
and shows a notification when a gift card is back in stock. No server, no
ntfy, no Python.

- **~53 KB app**, written in plain Java with **no libraries**: only what
  Android already ships (web requests, notifications, alarms, storage).
- **Nothing runs between checks.** An exact alarm wakes the app every
  10 minutes (allowed in Doze), it does one check in ~1 s (about 15–45 KB,
  compressed), schedules the next one and goes back to sleep. If Android
  closes the app, the next alarm starts it again. It restarts its schedule
  after a reboot or update.
- **Read-only**, like the desktop version: it never redeems anything.

> Status: **3.0.0-alpha1**. Sign-in currently works by importing the
> `session.json` from the desktop Rewards_Watcher. In-app sign-in comes in
> a later version.

## Install

1. Build it (below) or download the APK from a release.
2. Install it on your phone (allow installing from your computer/file manager).

## Set up

1. On your computer, run the desktop watcher's `login` once. It writes
   `session.json`. Copy that file to the phone, e.g. into Downloads.
2. Open **Rewards Watcher** → **Import session.json** → pick the file.
   Then delete the file from Downloads: it's as sensitive as a password.
3. In **Setup**, tap each button until all three show ✅:
   - **Notifications**
   - **Exact alarms** (*Alarms & reminders*), needed for 10-minute checks
     in deep sleep
   - **Battery: unrestricted**
4. **Realme / Oppo / OnePlus / Xiaomi:** also allow **Auto launch** and
   background activity for the app in the phone's settings.
5. Tap **Start watching**.

## Diagnostics

The **Diagnostics** section lists every check with the gap since the
previous one, its result and the data used, plus the typical and longest
gap over the last 24 hours. Use it to see whether your phone's battery
manager delays the app. Some phones (e.g. ColorOS) stretch background
alarms by several minutes.

## Build

Needs JDK 17 and the Android SDK (platform 36).

```bash
cd android
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with your local debug key, which is fine for
installing on your own phone.

## How it works (for developers)

| File | Job |
|---|---|
| `Cookies.java` | Reads/writes the `session.json` cookie format, sends matching cookies, applies `Set-Cookie` |
| `StoreClient.java` | HTTPS GET with manual redirects (keeps cookies from every hop), gzip |
| `Parser.java` | Reads the gift card list from the page's `self.__next_f.push(...)` data (same logic as the Python `parse_store`) |
| `Checker.java` | One check: compare with the last state, notify, record |
| `Scheduler.java`, `AlarmReceiver.java`, `BootReceiver.java` | Exact alarms, run a check, restart after reboot/update |
| `State.java` | Small JSON files in private storage (stock, history, check log) |
| `MainActivity.java` | The single screen, built in code |
