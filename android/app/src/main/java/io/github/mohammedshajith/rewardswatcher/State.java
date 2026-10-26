package io.github.mohammedshajith.rewardswatcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * All saved state, kept small: a few settings in SharedPreferences plus tiny
 * JSON files in the app's private storage (no database library).
 */
final class State {

    static final int MAX_EVENTS = 300;
    static final int MAX_CHECKS = 500; // ~3.5 days at 10 minutes

    static final String SESSION = "session.json";
    static final String ITEMS = "items.json";
    static final String EVENTS = "events.json";
    static final String CHECKS = "checks.json";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("rw", Context.MODE_PRIVATE);
    }

    static boolean watching(Context c) { return prefs(c).getBoolean("watching", false); }
    static int intervalMin(Context c) { return Math.max(5, prefs(c).getInt("intervalMin", 10)); }
    static String country(Context c) { return prefs(c).getString("country", "in"); }
    static String ignore(Context c) { return prefs(c).getString("ignore", "overwatch"); }
    static String watch(Context c) { return prefs(c).getString("watch", ""); }

    static boolean wanted(Context c, String id, String title) {
        String t = title.toLowerCase();
        for (String term : ignore(c).split(",")) {
            term = term.trim().toLowerCase();
            if (!term.isEmpty() && (t.contains(term) || term.equals(id))) return false;
        }
        String w = watch(c).trim();
        if (w.isEmpty()) return true;
        for (String term : w.split(",")) {
            term = term.trim().toLowerCase();
            if (!term.isEmpty() && (t.contains(term) || term.equals(id))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ files

    static String read(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static void write(Context c, String name, String data) throws IOException {
        File tmp = new File(c.getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), name))) throw new IOException("rename failed");
    }

    static boolean hasSession(Context c) {
        return new File(c.getFilesDir(), SESSION).exists();
    }

    static JSONObject items(Context c) {
        try {
            String s = read(c, ITEMS);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static JSONArray list(Context c, String name) {
        try {
            String s = read(c, name);
            return s == null ? new JSONArray() : new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** Append to a capped JSON list file (oldest entries dropped). */
    static synchronized void append(Context c, String name, JSONObject entry, int max) {
        JSONArray old = list(c, name);
        JSONArray out = new JSONArray();
        int start = Math.max(0, old.length() + 1 - max);
        for (int i = start; i < old.length(); i++) out.put(old.opt(i));
        out.put(entry);
        try {
            write(c, name, out.toString());
        } catch (IOException ignored) {
        }
    }
}
