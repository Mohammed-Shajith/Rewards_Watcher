package io.github.mohammedshajith.rewardswatcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** One store check: fetch, compare with the last check, notify, record. */
final class Checker {

    static final String TAG = "RewardsWatcher";
    static final String BASE = "https://rewards.bing.com";
    private static final Object LOCK = new Object();

    static String userAgent(Context c) {
        String v = "?";
        try {
            v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        // Identify honestly; no browser impersonation.
        return "RewardsWatcher-Android/" + v + " (+https://github.com/Mohammed-Shajith/Rewards_Watcher)";
    }

    /** Runs a check. scheduled = started by the alarm (schedules the next one first). */
    static void run(Context c, boolean scheduled) {
        if (scheduled) {
            if (!State.watching(c)) return;
            // Schedule the next alarm before doing any work, so the chain survives
            // even if Android kills the process mid-check.
            Scheduler.scheduleNext(c);
        }
        synchronized (LOCK) {
            runLocked(c, scheduled);
        }
    }

    private static void runLocked(Context c, boolean scheduled) {
        SharedPreferences p = State.prefs(c);
        long started = System.currentTimeMillis();
        long t0 = SystemClock.elapsedRealtime();
        long rx0 = TrafficStats.getUidRxBytes(Process.myUid());
        JSONObject log = new JSONObject();
        try {
            log.put("t", started);
            log.put("auto", scheduled);
        } catch (JSONException ignored) {
        }

        String result;
        try {
            String session = State.read(c, State.SESSION);
            if (session == null) throw new IllegalStateException("no session.json imported");
            Cookies jar = Cookies.fromJson(session);
            String html = StoreClient.get(BASE + "/redeem/" + State.country(c) + "/shop", jar, userAgent(c));
            Parser.Result r = Parser.parse(html, BASE);

            if (r.signedIn == null || !r.signedIn) {
                result = "signed out";
                if (!p.getBoolean("warnedSignedOut", false)) {
                    Notifier.show(c, Notifier.INFO, 2, "Rewards Watcher signed out",
                            "Stock can't be read. Open the app and tap Sign in with Microsoft.", null);
                    p.edit().putBoolean("warnedSignedOut", true).apply();
                }
            } else {
                p.edit().putBoolean("warnedSignedOut", false).apply();
                State.write(c, State.SESSION, jar.toJson()); // keep refreshed cookies
                int inStock = 0;
                for (Parser.Item it : r.items.values()) if (it.inStock) inStock++;
                result = r.items.size() + " items, " + inStock + " in stock";
                record(c, r);
            }
            p.edit().putInt("failures", 0).putBoolean("failureAlerted", false).apply();
            log.put("ok", true);
        } catch (Exception e) {
            result = "failed: " + e.getMessage();
            int f = p.getInt("failures", 0) + 1;
            p.edit().putInt("failures", f).apply();
            // One quick retry after a first failure (503s and network blips are
            // usually gone a minute later); then back to the normal interval.
            if (scheduled && f == 1 && State.watching(c)) Scheduler.scheduleIn(c, 60_000);
            if (f >= 5 && !p.getBoolean("failureAlerted", false)) {
                Notifier.show(c, Notifier.INFO, 3, "Rewards Watcher having trouble",
                        "5 checks in a row failed. Last error: " + e.getMessage(), null);
                p.edit().putBoolean("failureAlerted", true).apply();
            }
            try { log.put("ok", false); } catch (JSONException ignored) { }
        }

        long rx = TrafficStats.getUidRxBytes(Process.myUid()) - rx0;
        try {
            log.put("r", result);
            log.put("ms", SystemClock.elapsedRealtime() - t0);
            if (rx0 >= 0 && rx >= 0) log.put("kb", Math.round(rx / 1024.0));
        } catch (JSONException ignored) {
        }
        State.append(c, State.CHECKS, log, State.MAX_CHECKS);
        p.edit().putLong("lastAt", started).putString("lastResult", result).apply();
        Log.i(TAG, (scheduled ? "auto" : "manual") + " check: " + result);
    }

    /** Compare with the saved state, save the new state, notify for watched items. */
    private static void record(Context c, Parser.Result r) throws Exception {
        JSONObject old = State.items(c);
        boolean firstRun = old.length() == 0;
        List<String[]> events = new ArrayList<>(); // {event, id}
        JSONObject now = new JSONObject();

        for (Parser.Item it : r.items.values()) {
            JSONObject prev = old.optJSONObject(it.id);
            boolean wasIn = prev != null && prev.optBoolean("inStock");
            boolean wasListed = prev != null && prev.optBoolean("listed");
            if (prev == null) {
                if (!firstRun) events.add(new String[]{"listed", it.id});
                if (it.inStock) events.add(new String[]{"restocked", it.id});
            } else {
                if (!wasListed) events.add(new String[]{"listed", it.id});
                if (it.inStock && !(wasIn && wasListed)) events.add(new String[]{"restocked", it.id});
                else if (!it.inStock && wasIn && wasListed) events.add(new String[]{"sold_out", it.id});
            }
            JSONObject o = new JSONObject();
            o.put("title", it.title);
            o.put("price", it.price);
            o.put("inStock", it.inStock);
            o.put("listed", true);
            o.put("url", it.url);
            now.put(it.id, o);
        }
        // Items that vanished (often how sold-out cards behave): keep them, unlisted.
        Iterator<String> keys = old.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            if (now.has(id)) continue;
            JSONObject o = old.getJSONObject(id);
            if (o.optBoolean("listed")) events.add(new String[]{"delisted", id});
            o.put("listed", false);
            o.put("inStock", false);
            now.put(id, o);
        }
        State.write(c, State.ITEMS, now.toString());

        List<String> restocked = new ArrayList<>();
        for (String[] e : events) if (e[0].equals("restocked")) restocked.add(e[1]);
        for (String[] e : events) {
            JSONObject o = now.getJSONObject(e[1]);
            String title = o.optString("title");
            JSONObject ev = new JSONObject();
            ev.put("t", System.currentTimeMillis());
            ev.put("event", e[0]);
            ev.put("id", e[1]);
            ev.put("title", title);
            State.append(c, State.EVENTS, ev, State.MAX_EVENTS);

            if (!State.wanted(c, e[1], title)) continue;
            String line = title + " (" + String.format("%,d", o.optLong("price")) + " pts)";
            int nid = 1000 + (e[1].hashCode() & 0xffff);
            if (e[0].equals("restocked")) {
                Notifier.show(c, Notifier.RESTOCK, nid, "Back in stock!", line + ". Tap to redeem.", o.optString("url"));
            } else if (e[0].equals("listed") && !restocked.contains(e[1])) {
                Notifier.show(c, Notifier.INFO, nid, "New on the Rewards store (not redeemable yet)", line, o.optString("url"));
            }
        }
    }
}
