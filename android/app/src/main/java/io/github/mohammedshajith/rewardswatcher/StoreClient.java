package io.github.mohammedshajith.rewardswatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Fetches a store page with plain HTTPS and the session cookies. Android's
 * HttpURLConnection asks for gzip and unpacks it automatically, so a check
 * downloads roughly 30 KB instead of 140 KB. Redirects are followed by hand so
 * cookies set on every hop are kept.
 */
final class StoreClient {

    static String get(String startUrl, Cookies jar, String userAgent) throws IOException {
        URL url = new URL(startUrl);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
                String cookie = jar.headerFor(url);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);

                int code = c.getResponseCode();
                for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                    if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie")) {
                        for (String v : h.getValue()) jar.apply(url, v);
                    }
                }
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without Location");
                    url = new URL(url, loc);
                    continue;
                }
                if (code != 200) throw new IOException(url.getPath() + " returned HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(160_000);
                    byte[] buf = new byte[16_384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }
}
