package io.github.mohammedshajith.rewardswatcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Cookie jar backed by a Playwright "storage state" JSON file (session.json),
 * the same format the desktop Rewards_Watcher writes. Cookies Microsoft
 * refreshes are written back in that format.
 */
final class Cookies {

    static final class Cookie {
        String name, value, domain, path = "/", sameSite = "Lax";
        double expires = -1; // seconds since epoch, -1 = session cookie
        boolean secure, httpOnly;
    }

    private final List<Cookie> cookies = new ArrayList<>();
    private JSONArray origins = new JSONArray();

    static Cookies fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("cookies");
        Cookies jar = new Cookies();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Cookie c = new Cookie();
            c.name = o.getString("name");
            c.value = o.optString("value", "");
            c.domain = o.getString("domain").toLowerCase(Locale.ROOT);
            c.path = o.optString("path", "/");
            c.expires = o.optDouble("expires", -1);
            c.secure = o.optBoolean("secure", false);
            c.httpOnly = o.optBoolean("httpOnly", false);
            c.sameSite = o.optString("sameSite", "Lax");
            jar.cookies.add(c);
        }
        JSONArray o = root.optJSONArray("origins");
        if (o != null) jar.origins = o;
        return jar;
    }

    String toJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Cookie c : cookies) {
            JSONObject o = new JSONObject();
            o.put("name", c.name);
            o.put("value", c.value);
            o.put("domain", c.domain);
            o.put("path", c.path);
            o.put("expires", c.expires);
            o.put("httpOnly", c.httpOnly);
            o.put("secure", c.secure);
            o.put("sameSite", c.sameSite);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("cookies", arr);
        root.put("origins", origins);
        return root.toString();
    }

    /**
     * Build a jar from a WebView Cookie header ("a=b; c=d") for one host.
     * WebView only exposes name=value, so these are stored host-only for that
     * host; Microsoft's Set-Cookie responses refresh them later.
     */
    void addFromHeader(String host, String header) {
        if (header == null) return;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            Cookie c = new Cookie();
            c.name = part.substring(0, eq).trim();
            c.value = part.substring(eq + 1).trim();
            c.domain = host.toLowerCase(Locale.ROOT);
            c.secure = true;
            Iterator<Cookie> it = cookies.iterator();
            while (it.hasNext()) {
                Cookie o = it.next();
                if (o.name.equals(c.name) && o.domain.equals(c.domain) && o.path.equals(c.path)) it.remove();
            }
            cookies.add(c);
        }
    }

    int size() {
        return cookies.size();
    }

    boolean hasDomain(String suffix) {
        for (Cookie c : cookies) if (c.domain.endsWith(suffix)) return true;
        return false;
    }

    private static boolean domainMatches(String host, String domain) {
        if (domain.startsWith(".")) {
            return host.equals(domain.substring(1)) || host.endsWith(domain);
        }
        return host.equals(domain);
    }

    private static boolean expired(Cookie c, double nowSec) {
        return c.expires > 0 && c.expires < nowSec;
    }

    /** Value for the Cookie request header, or null if none apply. */
    String headerFor(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        String path = url.getPath().isEmpty() ? "/" : url.getPath();
        boolean https = "https".equals(url.getProtocol());
        double now = System.currentTimeMillis() / 1000.0;
        List<Cookie> match = new ArrayList<>();
        for (Cookie c : cookies) {
            if (expired(c, now) || (c.secure && !https)) continue;
            if (!domainMatches(host, c.domain)) continue;
            if (!path.startsWith(c.path)) continue;
            match.add(c);
        }
        if (match.isEmpty()) return null;
        match.sort((a, b) -> b.path.length() - a.path.length());
        StringBuilder sb = new StringBuilder();
        for (Cookie c : match) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name).append('=').append(c.value);
        }
        return sb.toString();
    }

    /** Apply one Set-Cookie response header. */
    void apply(URL url, String header) {
        String[] parts = header.split(";");
        int eq = parts[0].indexOf('=');
        if (eq <= 0) return;
        Cookie c = new Cookie();
        c.name = parts[0].substring(0, eq).trim();
        c.value = parts[0].substring(eq + 1).trim();
        c.domain = url.getHost().toLowerCase(Locale.ROOT); // host-only by default
        Long maxAge = null;
        Double expires = null;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            int e = p.indexOf('=');
            String k = (e < 0 ? p : p.substring(0, e)).trim().toLowerCase(Locale.ROOT);
            String v = e < 0 ? "" : p.substring(e + 1).trim();
            switch (k) {
                case "domain":
                    if (!v.isEmpty()) {
                        v = v.toLowerCase(Locale.ROOT);
                        c.domain = v.startsWith(".") ? v : "." + v;
                    }
                    break;
                case "path":
                    if (v.startsWith("/")) c.path = v;
                    break;
                case "max-age":
                    try { maxAge = Long.parseLong(v); } catch (NumberFormatException ignored) { }
                    break;
                case "expires":
                    Date d = parseDate(v);
                    if (d != null) expires = d.getTime() / 1000.0;
                    break;
                case "secure":
                    c.secure = true;
                    break;
                case "httponly":
                    c.httpOnly = true;
                    break;
                case "samesite":
                    if (!v.isEmpty()) c.sameSite = Character.toUpperCase(v.charAt(0)) + v.substring(1).toLowerCase(Locale.ROOT);
                    break;
                default:
                    break;
            }
        }
        double now = System.currentTimeMillis() / 1000.0;
        boolean delete = false;
        if (maxAge != null) {
            if (maxAge <= 0) delete = true; else c.expires = now + maxAge;
        } else if (expires != null) {
            if (expires < now) delete = true; else c.expires = expires;
        }
        // Replace an existing cookie with the same name/domain/path.
        Iterator<Cookie> it = cookies.iterator();
        while (it.hasNext()) {
            Cookie o = it.next();
            if (o.name.equals(c.name) && o.domain.equals(c.domain) && o.path.equals(c.path)) it.remove();
        }
        if (!delete) cookies.add(c);
    }

    private static Date parseDate(String v) {
        String[] formats = {"EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd-MMM-yyyy HH:mm:ss zzz", "EEE, dd-MMM-yy HH:mm:ss zzz"};
        for (String f : formats) {
            try {
                return new SimpleDateFormat(f, Locale.US).parse(v);
            } catch (ParseException ignored) {
            }
        }
        return null;
    }
}
