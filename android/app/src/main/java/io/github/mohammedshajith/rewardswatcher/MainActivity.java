package io.github.mohammedshajith.rewardswatcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Single screen built in code (no layout files, no support libraries). */
public class MainActivity extends Activity {

    private static final int REQ_IMPORT = 42;
    private static final int REQ_NOTIFY = 43;

    private TextView status, setup, items, diag, history;
    private Button watchBtn, notifyBtn, exactBtn, batteryBtn;
    private EditText intervalIn, watchIn, ignoreIn, countryIn;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dhm = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 20_000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Notifier.ensureChannels(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);

        text(col, "v" + version() + " · checks the Microsoft Rewards store for restocks", 13, false);

        status = text(col, "", 15, false);
        status.setPadding(0, dp(12), 0, dp(8));

        watchBtn = button(col, "", v -> toggleWatching());
        button(col, "Check now", v -> checkNow());
        button(col, "Sign in with Microsoft", v -> startActivity(new Intent(this, SignInActivity.class)));
        button(col, "Import session.json (from the desktop app)", v -> pickSession());

        header(col, "Setup");
        setup = text(col, "", 14, false);
        notifyBtn = button(col, "Allow notifications", v -> askNotifications());
        exactBtn = button(col, "Allow exact alarms (Alarms & reminders)", v -> startActivity(
                new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))));
        batteryBtn = button(col, "Remove battery limits", v -> startActivity(
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));

        header(col, "Settings");
        intervalIn = field(col, "Check every (minutes, 5+; Android allows about 10 in deep sleep)", InputType.TYPE_CLASS_NUMBER);
        watchIn = field(col, "Only alert for (comma separated, empty = every card), e.g. amazon, flipkart", InputType.TYPE_CLASS_TEXT);
        ignoreIn = field(col, "Never alert for (comma separated)", InputType.TYPE_CLASS_TEXT);
        countryIn = field(col, "Store region (as in rewards.bing.com/redeem/XX)", InputType.TYPE_CLASS_TEXT);
        button(col, "Save settings", v -> saveSettings());
        loadSettings();

        header(col, "Gift cards");
        items = text(col, "", 14, false);

        header(col, "Diagnostics (last 24 h)");
        diag = text(col, "", 13, false);
        diag.setTypeface(Typeface.MONOSPACE);

        header(col, "Restock history");
        history = text(col, "", 13, false);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad, pad + insets.getSystemWindowInsetTop(), pad, pad + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Self-heal: if watching but the alarm chain was lost (force-stop, cleaner
        // app, or an overdue alarm), restart it.
        SharedPreferences p = State.prefs(this);
        if (State.watching(this) && (!Scheduler.isScheduled(this)
                || p.getLong("nextAt", 0) < System.currentTimeMillis() - 120_000)) {
            Scheduler.scheduleIn(this, 5_000);
        }
        ui.post(tick); // keeps the status fresh while the screen is open
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    // ------------------------------------------------------------ actions

    private void toggleWatching() {
        SharedPreferences p = State.prefs(this);
        if (State.watching(this)) {
            p.edit().putBoolean("watching", false).apply();
            Scheduler.cancel(this);
        } else {
            if (!State.hasSession(this)) {
                Toast.makeText(this, "Sign in first", Toast.LENGTH_LONG).show();
                return;
            }
            p.edit().putBoolean("watching", true).apply();
            Scheduler.scheduleIn(this, 5_000);
        }
        refresh();
    }

    private void checkNow() {
        status.setText("Checking…");
        new Thread(() -> {
            Checker.run(getApplicationContext(), false);
            runOnUiThread(this::refresh);
        }).start();
    }

    private void pickSession() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String json = out.toString(StandardCharsets.UTF_8.name());
            Cookies jar = Cookies.fromJson(json); // validates the format
            if (!jar.hasDomain("bing.com")) throw new IllegalArgumentException("no Rewards cookies in this file");
            State.write(this, State.SESSION, jar.toJson());
            State.prefs(this).edit().putBoolean("warnedSignedOut", false).apply();
            Toast.makeText(this, "Imported (" + jar.size() + " cookies). You can delete the file now.", Toast.LENGTH_LONG).show();
            checkNow();
        } catch (Exception e) {
            Toast.makeText(this, "Not a valid session.json: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadSettings() {
        intervalIn.setText(String.valueOf(State.intervalMin(this)));
        watchIn.setText(State.watch(this));
        ignoreIn.setText(State.ignore(this));
        countryIn.setText(State.country(this));
    }

    private void saveSettings() {
        int min;
        try { min = Integer.parseInt(intervalIn.getText().toString().trim()); } catch (NumberFormatException e) { min = 10; }
        min = Math.max(5, Math.min(min, 240));
        String country = countryIn.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!country.matches("[a-z]{2}")) country = "in";
        State.prefs(this).edit()
                .putInt("intervalMin", min)
                .putString("watch", watchIn.getText().toString().trim())
                .putString("ignore", ignoreIn.getText().toString().trim())
                .putString("country", country)
                .apply();
        if (State.watching(this)) Scheduler.scheduleNext(this);
        loadSettings();
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        refresh();
    }

    // ------------------------------------------------------------ display

    private boolean notificationsOk() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryOk() {
        return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    private void refresh() {
        SharedPreferences p = State.prefs(this);
        boolean watching = State.watching(this);
        long last = p.getLong("lastAt", 0), next = p.getLong("nextAt", 0);
        StringBuilder s = new StringBuilder();
        s.append("Watching: ").append(watching ? "ON, every " + State.intervalMin(this) + " min" : "OFF").append('\n');
        s.append("Sign-in: ").append(State.hasSession(this) ? "saved" : "not signed in yet").append('\n');
        s.append("Last check: ").append(last == 0 ? "never" : dhm.format(new Date(last)) + " · " + p.getString("lastResult", "")).append('\n');
        s.append("Next check: ").append(watching && next > 0 ? hm.format(new Date(next)) : "-");
        status.setText(s);
        watchBtn.setText(watching ? "Stop watching" : "Start watching");

        boolean n = notificationsOk(), e = Scheduler.canExact(this), bt = batteryOk();
        setup.setText((n ? "✅" : "⚠️") + " Notifications\n"
                + (e ? "✅" : "⚠️") + " Exact alarms (needed for checks every 10 min in deep sleep)\n"
                + (bt ? "✅" : "⚠️") + " Battery: unrestricted\n"
                + "Realme/Oppo: also allow Auto launch and background activity for this app in Settings.");
        notifyBtn.setVisibility(n ? View.GONE : View.VISIBLE);
        exactBtn.setVisibility(e ? View.GONE : View.VISIBLE);
        batteryBtn.setVisibility(bt ? View.GONE : View.VISIBLE);

        items.setText(itemsText());
        diag.setText(diagText());
        history.setText(historyText());
    }

    private String itemsText() {
        JSONObject all = State.items(this);
        if (all.length() == 0) return "No data yet. Sign in, then Check now.";
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject o = all.optJSONObject(id);
            if (o == null) continue;
            String t = o.optString("title");
            String st = !o.optBoolean("listed") ? "not listed" : o.optBoolean("inStock") ? "IN STOCK" : "out of stock";
            sb.append(State.wanted(this, id, t) ? "★ " : "   ").append(t)
                    .append(String.format(Locale.getDefault(), " · %,d pts · ", o.optLong("price"))).append(st).append('\n');
        }
        sb.append("★ = you get alerts for this card");
        return sb.toString();
    }

    private String diagText() {
        JSONArray checks = State.list(this, State.CHECKS);
        long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
        List<Long> gaps = new ArrayList<>();
        int count = 0, failed = 0;
        long kbSum = 0, kbN = 0, prevAuto = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.optJSONObject(i);
            if (c == null) continue;
            long t = c.optLong("t");
            boolean auto = c.optBoolean("auto");
            if (auto && prevAuto > 0 && t >= cutoff) gaps.add((t - prevAuto) / 60_000);
            if (auto) prevAuto = t;
            if (t < cutoff) continue;
            count++;
            if (!c.optBoolean("ok")) failed++;
            if (c.optLong("kb") > 0) { kbSum += c.optLong("kb"); kbN++; }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(count).append(" checks, ").append(failed).append(" failed");
        if (!gaps.isEmpty()) {
            List<Long> sorted = new ArrayList<>(gaps);
            Collections.sort(sorted);
            sb.append("\ngap between auto checks: typical ").append(sorted.get(sorted.size() / 2))
                    .append(" min, longest ").append(sorted.get(sorted.size() - 1)).append(" min");
        }
        if (kbN > 0) sb.append("\ndata: ~").append(kbSum / kbN).append(" KB per check");
        sb.append("\n\nrecent checks (newest first):");
        int shown = 0;
        for (int i = checks.length() - 1; i >= 0 && shown < 25; i--, shown++) {
            JSONObject c = checks.optJSONObject(i);
            if (c == null) continue;
            long t = c.optLong("t");
            String gap = "";
            for (int j = i - 1; j >= 0; j--) { // gap to the previous check
                JSONObject pc = checks.optJSONObject(j);
                if (pc != null) { gap = "+" + (t - pc.optLong("t")) / 60_000 + "m"; break; }
            }
            sb.append('\n').append(hm.format(new Date(t))).append(' ')
                    .append(String.format(Locale.ROOT, "%-5s", gap)).append(c.optBoolean("auto") ? " " : "*")
                    .append(c.optString("r"));
            if (c.optLong("kb") > 0) sb.append(" · ").append(c.optLong("kb")).append("KB");
        }
        sb.append("\n(* = started by you)");
        return sb.toString();
    }

    private String historyText() {
        JSONArray ev = State.list(this, State.EVENTS);
        if (ev.length() == 0) return "Nothing yet.";
        StringBuilder sb = new StringBuilder();
        for (int i = ev.length() - 1, shown = 0; i >= 0 && shown < 15; i--, shown++) {
            JSONObject e = ev.optJSONObject(i);
            if (e == null) continue;
            sb.append(dhm.format(new Date(e.optLong("t")))).append("  ")
                    .append(e.optString("event").replace('_', ' ')).append(" · ").append(e.optString("title")).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ view helpers

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(LinearLayout parent, String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextIsSelectable(!bold);
        parent.addView(t);
        return t;
    }

    private EditText field(LinearLayout parent, String label, int type) {
        TextView l = text(parent, label, 13, false);
        l.setPadding(0, dp(6), 0, 0);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    private void header(LinearLayout parent, String s) {
        TextView t = text(parent, s, 17, true);
        t.setPadding(0, dp(20), 0, dp(6));
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        parent.addView(btn);
        return btn;
    }
}
