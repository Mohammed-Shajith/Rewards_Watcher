package io.github.mohammedshajith.rewardswatcher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the gift card list from the Rewards store page. rewards.bing.com is a
 * Next.js app: item data sits in inline self.__next_f.push([1,"..."]) scripts.
 * Same logic as parse_store() in the Python version.
 */
final class Parser {

    static final class Item {
        String id, title, url;
        long price;
        boolean inStock;
    }

    static final class Result {
        Boolean signedIn; // null = page didn't say
        final Map<String, Item> items = new LinkedHashMap<>();
    }

    private static final Pattern PUSH = Pattern.compile("self\\.__next_f\\.push\\(\\[1,\"((?:[^\"\\\\]|\\\\.)*)\"\\]\\)");
    private static final Pattern SIGNED_IN = Pattern.compile("\"isSignedIn\":(true|false)");
    private static final String ITEMS = "\"items\":[";

    static Result parse(String html, String base) {
        StringBuilder payload = new StringBuilder();
        Matcher m = PUSH.matcher(html);
        while (m.find()) {
            try {
                payload.append(new JSONArray("[\"" + m.group(1) + "\"]").getString(0));
            } catch (Exception ignored) {
                // skip a chunk we can't decode
            }
        }
        String p = payload.toString();
        Result r = new Result();
        Matcher s = SIGNED_IN.matcher(p);
        if (s.find()) r.signedIn = "true".equals(s.group(1));

        int from = 0;
        while (true) {
            int idx = p.indexOf(ITEMS, from);
            if (idx < 0) break;
            from = idx + ITEMS.length();
            Object v;
            try {
                v = new JSONTokener(p.substring(idx + ITEMS.length() - 1)).nextValue();
            } catch (Exception e) {
                continue;
            }
            if (!(v instanceof JSONArray)) continue;
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.has("id") || !o.has("isDisabled")) continue;
                Object sweep = o.opt("sweepstakeDetails");
                if (sweep != null && sweep != JSONObject.NULL && !"$undefined".equals(sweep)) continue;
                Item it = new Item();
                it.id = o.optString("id");
                String full = o.optString("fullTitle", "");
                it.title = !full.isEmpty() && !full.equals("$undefined") ? full : o.optString("title", it.id);
                it.price = o.optLong("price", -1);
                // isDisabled means "out of stock" only when signed in
                it.inStock = !o.optBoolean("isDisabled", true);
                it.url = base + o.optString("href", "/redeem/sku/" + it.id);
                r.items.put(it.id, it);
            }
        }
        return r;
    }
}
