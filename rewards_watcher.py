#!/usr/bin/env python3
"""Rewards_Watcher: Microsoft Rewards restock watcher.

Watches the Rewards gift card store and alerts you (phone push via ntfy,
email, Mac notification, WhatsApp) when an out-of-stock item comes back. It only
reads the store; it never redeems anything.

Commands:
  setup       first-time setup: region, phone alerts, config.json
  login       open a browser once so you can sign in (session is saved)
  export-session  write session.json to copy to a server (server mode)
  check       check the store once and print every item
  run         keep checking every few minutes and send alerts
  history     show logged restocks and how long they lasted
  test-alert  send a test alert to every enabled channel
"""

import argparse
import getpass
import html
import json
import os
import re
import secrets
import shutil
import smtplib
import sqlite3
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime
from email.message import EmailMessage
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
PROFILE_DIR = HERE / "profile"  # saved browser sign-in, never share this folder
SESSION_PATH = HERE / "session.json"  # portable sign-in for server mode, keep private
DB_PATH = HERE / "data" / "rewards.db"
BASE = "https://rewards.bing.com"
MIN_INTERVAL_MIN = 2  # don't hammer the store

DEFAULT_CONFIG = {
    "country": "in",
    "browser": "auto",
    "auth": "auto",
    "interval_minutes": 4,
    "watch": [],
    "ignore": ["overwatch"],
    "extra_skus": [],
    "mac_notifications": True,
    "ntfy": {"enabled": False, "server": "https://ntfy.sh", "topic": ""},
    "email": {"enabled": False, "smtp_host": "smtp.gmail.com", "smtp_port": 465,
              "security": "ssl", "username": "", "password": "", "from": "",
              "to": []},
    "whatsapp": {"enabled": False, "phone": "+91XXXXXXXXXX", "apikey": ""},
}


# ---------------------------------------------------------------- config

def load_config():
    if not CONFIG_PATH.exists():
        CONFIG_PATH.write_text(json.dumps(DEFAULT_CONFIG, indent=2) + "\n")
        print(f"Created {CONFIG_PATH.name} with default settings.")
    cfg = dict(DEFAULT_CONFIG)
    cfg.update(json.loads(CONFIG_PATH.read_text()))
    return cfg


# ---------------------------------------------------------------- parsing

PUSH_RE = re.compile(r'self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)')


def page_payload(html):
    """The store is a Next.js app: item data sits in inline
    `self.__next_f.push([1, "..."])` scripts. Join and unescape them."""
    return "".join(json.loads('"%s"' % c) for c in PUSH_RE.findall(html))


def parse_store(html):
    """Return (signed_in, items). signed_in is None if the page didn't say
    (e.g. we got bounced to a login page). items maps id -> dict."""
    payload = page_payload(html)
    flags = re.findall(r'"isSignedIn":(true|false)', payload)
    signed_in = (flags[0] == "true") if flags else None

    items = {}
    decoder = json.JSONDecoder()
    for m in re.finditer(r'"items":\[', payload):
        try:
            arr, _ = decoder.raw_decode(payload, m.end() - 1)
        except ValueError:
            continue
        for it in arr:
            if not isinstance(it, dict) or "id" not in it or "isDisabled" not in it:
                continue
            if it.get("sweepstakeDetails") not in (None, "$undefined"):
                continue  # skip sweepstakes entries
            items[it["id"]] = {
                "id": it["id"],
                "title": it.get("fullTitle") or it.get("title") or it["id"],
                "price": it.get("price"),
                # isDisabled means "out of stock" only when signed in
                "in_stock": not it["isDisabled"],
                "url": BASE + it.get("href", "/redeem/sku/" + it["id"]),
            }
    return signed_in, items


def _matches(item, terms):
    title = item["title"].lower()
    return any(t == item["id"] or t.lower() in title for t in terms)


def wanted(item, cfg):
    """Alert for an item unless it's on the ignore list. An empty watch list
    means every item (so new items added later are covered automatically);
    otherwise only items matching a watch term (id or part of the title)."""
    if _matches(item, cfg.get("ignore", [])):
        return False
    watch = cfg.get("watch", [])
    return not watch or _matches(item, watch)


# ---------------------------------------------------------------- fetching

BROWSER_PATHS = {
    "msedge": ["/Applications/Microsoft Edge.app", "/usr/bin/microsoft-edge",
               "/opt/microsoft/msedge/msedge"],
    "chrome": ["/Applications/Google Chrome.app", "/usr/bin/google-chrome",
               "/opt/google/chrome/chrome"],
}


def pick_browser(cfg):
    """'auto' uses Edge if installed, then Chrome, then Playwright's own
    Chromium (needs: python -m playwright install chromium)."""
    choice = cfg.get("browser", "auto")
    if choice != "auto":
        return choice
    for name, paths in BROWSER_PATHS.items():
        if any(Path(p).exists() for p in paths):
            return name
    return "chromium"


def _channel_kwargs(cfg):
    browser = pick_browser(cfg)
    return {} if browser == "chromium" else {"channel": browser}


def launch(pw, cfg, headless):
    return pw.chromium.launch_persistent_context(
        str(PROFILE_DIR), headless=headless, **_channel_kwargs(cfg))


def save_session(ctx):
    """Write the sign-in (cookies) to session.json, owner-only. This file
    is as sensitive as a password: anyone with it is signed in as you."""
    tmp = SESSION_PATH.with_suffix(".tmp")
    ctx.storage_state(path=str(tmp))
    os.chmod(tmp, 0o600)
    tmp.replace(SESSION_PATH)


def auth_mode(cfg):
    """profile = browser profile on this machine (desktop).
    session = session.json copied from another machine (server mode)."""
    mode = cfg.get("auth", "auto")
    if mode == "auto":
        mode = "profile" if PROFILE_DIR.exists() else "session"
    return mode


class Store:
    """Headless browser carrying your sign-in cookies. Kept open between
    checks. Uses the local browser profile, or session.json on a server."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.mode = auth_mode(cfg)
        self._pw = None
        self._browser = None
        self.ctx = None
        self._session_mtime = None

    def __enter__(self):
        from playwright.sync_api import sync_playwright

        if self.mode == "profile" and not PROFILE_DIR.exists():
            sys.exit("No saved sign-in yet. Run:  .venv/bin/python rewards_watcher.py login")
        if self.mode == "session" and not SESSION_PATH.exists():
            sys.exit("No sign-in found. On a desktop run `login`. On a server, copy "
                     "session.json here from a computer where you ran `login` "
                     "(see README: Server mode).")
        self._pw = sync_playwright().start()
        if self.mode == "profile":
            self.ctx = launch(self._pw, self.cfg, headless=True)
        else:
            self._browser = self._pw.chromium.launch(
                headless=True, **_channel_kwargs(self.cfg))
            self._open_session()
        return self

    def _open_session(self):
        if self.ctx:
            self.ctx.close()
        self.ctx = self._browser.new_context(storage_state=str(SESSION_PATH))
        self._session_mtime = SESSION_PATH.stat().st_mtime

    def __exit__(self, *exc):
        try:
            if self.ctx:
                self.ctx.close()
            if self._browser:
                self._browser.close()
        finally:
            self._pw.stop()

    def fetch(self):
        if self.mode == "session" and SESSION_PATH.stat().st_mtime != self._session_mtime:
            print("  session.json changed on disk, reloading it")
            self._open_session()
        urls = [f"{BASE}/redeem/{self.cfg['country']}/shop"]
        urls += [f"{BASE}/redeem/sku/{s}" for s in self.cfg.get("extra_skus", [])]
        signed_in, items = None, {}
        for url in urls:
            resp = self.ctx.request.get(url, timeout=30_000)
            if not resp.ok:
                raise RuntimeError(f"{url} returned HTTP {resp.status}")
            s, found = parse_store(resp.text())
            signed_in = s if signed_in is None else (signed_in and s is not False)
            items.update(found)
        if self.mode == "session" and signed_in:
            # Keep cookies Microsoft refreshed, so the session lasts longer
            save_session(self.ctx)
            self._session_mtime = SESSION_PATH.stat().st_mtime
        return signed_in, items


# ---------------------------------------------------------------- storage

def db():
    DB_PATH.parent.mkdir(exist_ok=True)
    con = sqlite3.connect(DB_PATH)
    con.executescript(
        """
        CREATE TABLE IF NOT EXISTS items (
            id TEXT PRIMARY KEY, title TEXT, price INTEGER,
            in_stock INTEGER, listed INTEGER,
            first_seen TEXT, last_seen TEXT, last_change TEXT);
        CREATE TABLE IF NOT EXISTS events (
            n INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT,
            item_id TEXT, title TEXT, price INTEGER, event TEXT);
        """
    )
    return con


def now():
    return datetime.now().isoformat(timespec="seconds")


def record(con, items):
    """Compare with the last check, save the new state and return events
    as (event, item). Events: restocked, sold_out, listed, delisted."""
    ts = now()
    old = {r[0]: r for r in con.execute("SELECT id, in_stock, listed FROM items")}
    first_run = not old
    events = []

    for it in items.values():
        prev = old.get(it["id"])
        if prev is None:
            ev = None if first_run else "listed"
            con.execute(
                "INSERT INTO items VALUES (?,?,?,?,1,?,?,?)",
                (it["id"], it["title"], it["price"], int(it["in_stock"]), ts, ts, ts),
            )
            if ev:
                events.append((ev, it))
            if it["in_stock"]:
                events.append(("restocked", it))
            continue
        _, was_in, was_listed = prev
        changed = False
        if not was_listed:
            events.append(("listed", it))
            changed = True
        if it["in_stock"] and not (was_in and was_listed):
            events.append(("restocked", it))
            changed = True
        elif not it["in_stock"] and was_in and was_listed:
            events.append(("sold_out", it))
            changed = True
        con.execute(
            "UPDATE items SET title=?, price=?, in_stock=?, listed=1, last_seen=?"
            + (", last_change=?" if changed else "")
            + " WHERE id=?",
            (it["title"], it["price"], int(it["in_stock"]), ts)
            + ((ts,) if changed else ())
            + (it["id"],),
        )

    # Items that vanished from the store (often how sold-out cards behave)
    for item_id, (_, was_in, was_listed) in old.items():
        if item_id not in items and was_listed:
            title, price = con.execute(
                "SELECT title, price FROM items WHERE id=?", (item_id,)
            ).fetchone()
            con.execute(
                "UPDATE items SET listed=0, in_stock=0, last_change=? WHERE id=?",
                (ts, item_id),
            )
            events.append(("delisted", {"id": item_id, "title": title, "price": price}))

    for ev, it in events:
        con.execute(
            "INSERT INTO events (ts, item_id, title, price, event) VALUES (?,?,?,?,?)",
            (ts, it["id"], it["title"], it.get("price"), ev),
        )
    con.commit()
    return events


# ---------------------------------------------------------------- alerts

def mac_notify(title, text, url=None):
    """Prefer terminal-notifier (brew install terminal-notifier): it shows a
    normal banner and clicking it opens the item page. Falls back to
    AppleScript, whose banners macOS sometimes hides almost instantly."""
    if sys.platform != "darwin":
        return
    tn = shutil.which("terminal-notifier")
    if tn:
        cmd = [tn, "-title", title, "-message", text, "-sound", "Glass",
               "-group", "rewards-watcher"]
        if url:
            cmd += ["-open", url]
        r = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if r.returncode == 0:
            return
        print("  terminal-notifier failed, using AppleScript:",
              (r.stderr or r.stdout).strip()[:150])
    script = 'display notification {} with title {} sound name "Glass"'.format(
        json.dumps(text), json.dumps(title)
    )
    subprocess.run(["osascript", "-e", script], check=False)


def whatsapp(cfg, text):
    wa = cfg.get("whatsapp", {})
    if not wa.get("enabled"):
        return
    q = urllib.parse.urlencode(
        {"phone": wa["phone"], "text": text, "apikey": wa["apikey"]}
    )
    url = "https://api.callmebot.com/whatsapp.php?" + q
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            body = r.read().decode("utf-8", "replace")
        if "queued" not in body.lower() and "sent" not in body.lower():
            print("  WhatsApp: unexpected reply from CallMeBot:", body[:200])
    except Exception as e:  # never let an alert failure stop the watcher
        print("  WhatsApp alert failed:", e)


def ntfy(cfg, title, text, url=None, urgent=False):
    """Phone push via ntfy (free app for Android/iOS). Sent as JSON so
    characters like ₹ and — survive. Anyone who knows the topic name can
    read it, so keep the topic long and random."""
    n = cfg.get("ntfy", {})
    if not n.get("enabled") or not n.get("topic"):
        return
    body = {
        "topic": n["topic"],
        "title": title,
        "message": text,
        # 5 = max (restocks), 4 = high. Priority 3 and below don't show a
        # pop-up banner on Android, only an entry in the notification shade.
        "priority": 5 if urgent else 4,
        "tags": ["rotating_light"] if urgent else ["bell"],
    }
    if url:
        body["click"] = url
        body["actions"] = [{"action": "view", "label": "Open", "url": url}]
    req = urllib.request.Request(
        n.get("server", "https://ntfy.sh"),
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            r.read()
    except Exception as e:  # never let an alert failure stop the watcher
        print("  ntfy alert failed:", e)


def email(cfg, title, text, url=None, urgent=False):
    """Email via any SMTP server. For Gmail: turn on 2-Step Verification,
    create an App Password, and use it as the password (not your normal
    Gmail password). The password can also come from RW_EMAIL_PASSWORD."""
    e = cfg.get("email", {})
    to = e.get("to") or []
    if isinstance(to, str):
        to = [a.strip() for a in to.split(",") if a.strip()]
    if not e.get("enabled") or not to:
        return
    sender = e.get("from") or e.get("username")
    msg = EmailMessage()
    msg["Subject"] = f"{'🚨 ' if urgent else ''}{title} {text.splitlines()[0]}"
    msg["From"] = f"Rewards_Watcher <{sender}>"
    msg["To"] = ", ".join(to)
    if urgent:
        msg["X-Priority"] = "1"
        msg["Importance"] = "high"
    msg.set_content(f"{title}\n\n{text}\n" + (f"\nRedeem: {url}\n" if url else ""))
    if url:
        msg.add_alternative(
            f"<h2>{html.escape(title)}</h2><p>{html.escape(text)}</p>"
            f'<p><a href="{html.escape(url)}" style="background:#0067b8;color:#fff;'
            f'padding:10px 18px;border-radius:6px;text-decoration:none">'
            f"Open on Microsoft Rewards</a></p>",
            subtype="html",
        )
    host = e.get("smtp_host", "smtp.gmail.com")
    port = int(e.get("smtp_port", 465))
    security = e.get("security", "ssl")
    password = os.environ.get("RW_EMAIL_PASSWORD") or e.get("password", "")
    try:
        if security == "ssl":
            server = smtplib.SMTP_SSL(host, port, timeout=30)
        else:
            server = smtplib.SMTP(host, port, timeout=30)
            if security == "starttls":
                server.starttls()
        with server:
            if e.get("username") and password:
                server.login(e["username"], password)
            server.send_message(msg)
    except Exception as ex:  # never let an alert failure stop the watcher
        print("  email alert failed:", ex)


def alert(cfg, title, text, url=None, urgent=False):
    print(f"  ALERT: {title} | {text}" + (f" | {url}" if url else ""))
    if cfg.get("mac_notifications", True):
        mac_notify(title, text, url)
    ntfy(cfg, title, text, url, urgent)
    email(cfg, title, text, url, urgent)
    whatsapp(cfg, f"{title}\n{text}" + (f"\n{url}" if url else ""))


def pts(p):
    return f"{p:,} pts" if isinstance(p, int) else "? pts"


# ---------------------------------------------------------------- commands

def cmd_login(cfg):
    from playwright.sync_api import sync_playwright

    print(f"Opening {pick_browser(cfg)}. Sign in to Microsoft Rewards there.")
    with sync_playwright() as pw:
        ctx = launch(pw, cfg, headless=False)
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        page.goto(f"{BASE}/redeem/{cfg['country']}")
        # Waiting for the window to close is unreliable on macOS (Edge keeps
        # running with no windows), so ask for Enter instead.
        input("When you can see your points balance, come back here and press Enter... ")
        try:
            save_session(ctx)
        except Exception as e:
            print("  (couldn't write session.json:", e, ")")
        try:
            ctx.close()
        except Exception:
            pass
    print(f"Saved. ({SESSION_PATH.name} written too, for server mode.)")
    print("Checking that the sign-in worked...")
    cmd_check(cfg)


def cmd_export_session(cfg):
    """Write session.json from the saved browser profile, to copy to a server."""
    from playwright.sync_api import sync_playwright

    if not PROFILE_DIR.exists():
        sys.exit("No browser profile here. Run `login` first.")
    with sync_playwright() as pw:
        try:
            ctx = launch(pw, cfg, headless=True)
        except Exception as e:
            sys.exit("Couldn't open the browser profile. If the watcher is running on "
                     f"this computer, stop it first (Ctrl+C) and try again.\n({e})")
        ctx.request.get(f"{BASE}/redeem/{cfg['country']}/shop", timeout=30_000)
        save_session(ctx)
        ctx.close()
    print(f"Wrote {SESSION_PATH} (owner-only). Copy it to your server, e.g.:")
    print("  ./deploy/push-session.sh pi@raspberrypi.local")
    print("Treat this file like a password: it signs whoever has it in as you.")


def print_items(signed_in, items, cfg):
    print(f"Signed in: {'yes' if signed_in else 'NO'}   items: {len(items)}")
    for it in sorted(items.values(), key=lambda i: (i["price"] or 0)):
        if not signed_in:
            status = "unknown (sign in)"
        else:
            status = "IN STOCK" if it["in_stock"] else "out of stock"
        mark = "*" if wanted(it, cfg) else " "
        print(f" {mark} {it['id']}  {pts(it['price']):>11}  {status:<17} {it['title']}")
    print(" (* = you'll get alerts for this item)")


def cmd_check(cfg):
    with Store(cfg) as store:
        signed_in, items = store.fetch()
    print_items(signed_in, items, cfg)
    if not signed_in:
        print("\nNot signed in, so stock status can't be read. Run the login command.")


def cmd_run(cfg):
    interval = max(float(cfg["interval_minutes"]), MIN_INTERVAL_MIN) * 60
    con = db()
    warned_signed_out = False
    failures = 0
    print(f"Watching every {interval / 60:g} min. Ctrl+C to stop.")
    with Store(cfg) as store:
        while True:
            stamp = datetime.now().strftime("%H:%M:%S")
            try:
                signed_in, items = store.fetch()
                failures = 0
            except Exception as e:
                failures += 1
                print(f"[{stamp}] check failed ({failures}): {e}")
                if failures == 5:
                    alert(cfg, "Rewards_Watcher having trouble",
                          f"5 checks in a row failed. Last error: {e}")
                time.sleep(interval)
                continue

            if not signed_in:
                print(f"[{stamp}] signed out, stock unknown")
                if not warned_signed_out:
                    if store.mode == "session":
                        fix = ("On your computer run `login` (or `export-session`), "
                               "then copy session.json to the server: "
                               "./deploy/push-session.sh user@server")
                    else:
                        fix = "Run: .venv/bin/python rewards_watcher.py login"
                    alert(cfg, "Rewards_Watcher signed out", fix)
                    warned_signed_out = True
                time.sleep(interval)
                continue
            warned_signed_out = False

            events = record(con, items)
            in_stock = sum(i["in_stock"] for i in items.values())
            print(f"[{stamp}] {len(items)} items, {in_stock} in stock")
            restocked = {it["id"] for ev, it in events if ev == "restocked"}
            for ev, it in events:
                if not wanted(it, cfg):
                    continue
                line = f"{it['title']} ({pts(it['price'])})"
                url = it.get("url")
                if ev == "restocked":
                    alert(cfg, "Back in stock!", line, url, urgent=True)
                elif ev == "listed" and it["id"] not in restocked:
                    # A card that disappeared (or a brand new one) showed up
                    alert(cfg, "New on the Rewards store (not redeemable yet)", line, url)
                elif ev in ("sold_out", "delisted"):
                    print(f"  {ev.replace('_', ' ')}: {it['title']}")
            time.sleep(interval)


def cmd_history(cfg):
    con = db()
    rows = con.execute(
        "SELECT ts, item_id, title, event FROM events ORDER BY n"
    ).fetchall()
    if not rows:
        print("No history yet. Leave `run` going for a while.")
        return
    opened = {}
    print(f"{'when':<20} {'event':<10} {'stayed':>8}  item")
    for ts, item_id, title, ev in rows:
        stayed = ""
        if ev == "restocked":
            opened[item_id] = ts
        elif ev in ("sold_out", "delisted") and item_id in opened:
            secs = (datetime.fromisoformat(ts)
                    - datetime.fromisoformat(opened.pop(item_id))).total_seconds()
            stayed = f"~{secs / 60:.0f} min"
        print(f"{ts.replace('T', ' '):<20} {ev:<10} {stayed:>8}  {title}")
    counts = con.execute(
        "SELECT title, COUNT(*) FROM events WHERE event='restocked' "
        "GROUP BY item_id ORDER BY 2 DESC"
    ).fetchall()
    if counts:
        print("\nRestocks per item:")
        for title, n in counts:
            print(f"  {n:>3}  {title}")


def cmd_test_alert(cfg):
    on = [name for name, flag in [
        ("Mac", cfg.get("mac_notifications", True)),
        ("ntfy", cfg.get("ntfy", {}).get("enabled")),
        ("email", cfg.get("email", {}).get("enabled")),
        ("WhatsApp", cfg.get("whatsapp", {}).get("enabled")),
    ] if flag]
    print("Sending test to:", ", ".join(on) or "nothing (all alerts off)")
    alert(cfg, "Rewards_Watcher test", "Tap to open the Rewards store.",
          f"{BASE}/redeem/{cfg['country']}")


def ask(question, default=""):
    hint = f" [{default}]" if default else ""
    answer = input(f"{question}{hint}: ").strip()
    return answer or default


def cmd_setup(cfg):
    print("Rewards_Watcher setup. Press Enter to keep the value in [brackets].\n")

    cfg["country"] = ask("Rewards store region (as in rewards.bing.com/redeem/XX)",
                         cfg.get("country", "in")).lower()
    print(f"Browser: {pick_browser(cfg)} (change 'browser' in config.json if needed)")

    ntfy_cfg = dict(cfg.get("ntfy") or DEFAULT_CONFIG["ntfy"])
    if ask("Send alerts to your phone with the free ntfy app? (y/n)",
           "y" if ntfy_cfg.get("enabled") or not ntfy_cfg.get("topic") else "n"
           ).lower().startswith("y"):
        if not ntfy_cfg.get("topic"):
            ntfy_cfg["topic"] = "rewards-" + secrets.token_hex(12)
        ntfy_cfg["enabled"] = True
        topic = ntfy_cfg["topic"]
        print(f"""
  Your private topic:  {topic}
  1. Install the ntfy app (Android: Google Play / F-Droid, iPhone: App Store).
  2. In the app tap +, paste the topic above, keep server ntfy.sh, Subscribe.
     (Android shortcut: open  ntfy://ntfy.sh/{topic}  on the phone.)
  Anyone who knows the topic can read your alerts, so don't post it publicly.
""")
    else:
        ntfy_cfg["enabled"] = False
    cfg["ntfy"] = ntfy_cfg

    em = dict(DEFAULT_CONFIG["email"])
    em.update(cfg.get("email") or {})
    if ask("Send alerts by email too? (y/n)", "y" if em.get("enabled") else "n"
           ).lower().startswith("y"):
        print("  Gmail works out of the box. You need an App Password (not your\n"
              "  normal password): Google Account > Security > 2-Step Verification\n"
              "  > App passwords. Other providers: edit smtp_* in config.json.")
        em["username"] = ask("  Gmail address to send from", em.get("username", ""))
        default_to = ", ".join(em.get("to") or []) or em["username"]
        em["to"] = [a.strip() for a in ask("  Send alerts to (comma separated)",
                                           default_to).split(",") if a.strip()]
        pw = getpass.getpass("  App Password (input hidden, Enter to keep current): ")
        if pw:
            em["password"] = pw.replace(" ", "")
        em["enabled"] = True
    else:
        em["enabled"] = False
    cfg["email"] = em

    if sys.platform == "darwin":
        cfg["mac_notifications"] = True
        if not shutil.which("terminal-notifier"):
            print("Tip: `brew install terminal-notifier` gives better Mac banners "
                  "that open the item page when clicked.")
    else:
        cfg["mac_notifications"] = False

    CONFIG_PATH.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n")
    os.chmod(CONFIG_PATH, 0o600)  # may hold an email password: owner-only
    print(f"\nSaved {CONFIG_PATH.name}.")
    print("Next:  .venv/bin/python rewards_watcher.py login   (sign in once)")
    print("Then:  .venv/bin/python rewards_watcher.py test-alert, check, run")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command",
                    choices=["setup", "login", "export-session", "check", "run",
                             "history", "test-alert"])
    args = ap.parse_args()
    cfg = load_config()
    {
        "setup": cmd_setup,
        "login": cmd_login,
        "export-session": cmd_export_session,
        "check": cmd_check,
        "run": cmd_run,
        "history": cmd_history,
        "test-alert": cmd_test_alert,
    }[args.command](cfg)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped.")
