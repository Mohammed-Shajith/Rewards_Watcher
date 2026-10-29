# Rewards Watcher

**Get a phone notification the moment a Microsoft Rewards gift card is back in stock.**

**v3.0.0** · [Download the Android app](https://github.com/Mohammed-Shajith/Rewards_Watcher/releases/latest) · [Changelog](CHANGELOG.md)

In India (and some other regions), Microsoft Rewards gift cards like Amazon
and Flipkart are almost always "out of stock", and when they restock they
sell out within minutes. Rewards Watcher is a tiny Android app that checks
the Rewards store for you every 10 minutes, day and night, and notifies you
so you can redeem before it's gone.

- **Tiny:** a 58 KB app, plain Java, no libraries. About 0.3% battery and ~6 MB of data a day.
- **No computer or server:** the phone does the checking and sends its own notifications.
- **Tap to redeem:** the 🚨 *Back in stock!* notification opens the card's page.
- **Catches new cards too:** reads the whole gift card list on every check, so
  cards Microsoft adds later (or Amazon cards reappearing) are picked up automatically.
- **Read-only:** it never redeems anything and never touches your points.

---

## Install (Android 10+)

1. Download **`RewardsWatcher-3.0.0.apk`** from the
   [latest release](https://github.com/Mohammed-Shajith/Rewards_Watcher/releases/latest)
   and open it on your phone. Allow installing from your browser or file manager when asked.
2. Open **Rewards Watcher** → **Sign in with Microsoft**. Sign in, and tap **Done** once you can see your points.
3. Under **Setup**, tap each button until all three show ✅:
   **Notifications**, **Exact alarms** (*Alarms & reminders*) and **Battery: unrestricted**.
   On Realme / Oppo / OnePlus / Xiaomi phones, also allow **Auto launch** for the app in Settings.
4. Tap **Start watching**. Done.

Optional, under **Settings**: check interval, cards to watch or ignore (Overwatch is
ignored by default; clear the box to watch it), and store region.

## What you'll get

| Notification | When |
|---|---|
| 🚨 **Back in stock!** | A card you couldn't redeem becomes redeemable. Tap to open it |
| 🔔 **New on the Rewards store** | A card appears that wasn't listed before |
| 🔔 **Signed out** | Your Microsoft sign-in expired. Open the app and tap *Sign in with Microsoft* |
| 🔔 **Having trouble** | 5 checks in a row failed (e.g. no internet) |

The app's **Diagnostics** section shows every check, the gap between checks and
the data used, so you can see that your phone lets it run.

---

## How it works

The Rewards store only says whether a card is in stock **when you're signed in**.
Signed out, every card shows the same greyed-out button:

| Signed out: can't tell | Signed in: real stock status |
|---|---|
| ![Signed out: greyed Redeem now button](docs/images/signed-out.png) | ![Signed in: So popular we're restocking! Back soon!](docs/images/signed-in-out-of-stock.png) |

Every 10 minutes an exact alarm wakes the app. It loads the gift card list
with your sign-in cookies (one ~40 KB compressed request), compares it with the
last check, notifies you about changes, and goes back to sleep. Nothing runs in
between, and the schedule survives reboots, updates and force-stops.

![Rewards gift card store](docs/images/gift-card-store.png)

### Measured on a real phone

Realme 11 Pro+, Android 15 / ColorOS, 3+ days:

| | |
|---|---|
| App size | 58 KB |
| Battery | ≈0.3% a day |
| Data | ~6 MB a day |
| Checks | every ~10 min, **longest overnight gap 15 min** |

Details and the developer guide: [android/README.md](android/README.md).

---

## Other ways to run it

The original **Python watcher** runs on a Mac, Linux PC or an always-on server
(Raspberry Pi, Oracle free tier), and alerts you by **ntfy push, email, Mac
notifications or WhatsApp**. Useful if you don't use Android, or want alerts on
several devices.

→ **[Desktop & server guide](docs/desktop.md)**

## Contributing

Issues and pull requests are welcome. Fork the repo, work on a branch, and open
a pull request against `main`. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Disclaimer

This is an independent personal project. It is **not affiliated with, endorsed
by, or sponsored by Microsoft**. Microsoft, Bing, Microsoft Rewards and the gift
card brands shown are trademarks of their respective owners. Screenshots are
included only to explain how the tool works.

Microsoft's Rewards terms restrict automated activity. Rewards Watcher only
reads the store page at a low rate with your own signed-in session, and never
earns or redeems points, but **use it at your own risk**.

## License

[MIT](LICENSE)
