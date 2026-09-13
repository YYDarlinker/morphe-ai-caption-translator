package app.yydarlinker.deepseekcaptions;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small anonymous YouTube cookie refresher used only for source-caption fetches. This mirrors the
 * cookie set Morphe already uses for Timed Text, but keeps it in memory so our translation path
 * does not depend on the age of a setting that may have been captured much earlier.
 */
final class FreshYouTubeCookies {
    private static final List<String> ACCEPTED = Arrays.asList(
            "YSC",
            "VISITOR_INFO1_LIVE",
            "VISITOR_PRIVACY_METADATA",
            "__Secure-ROLLOUT_TOKEN"
    );
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final long MAX_AGE_MS = 8L * 60L * 1000L;

    private static volatile String cached = "";
    private static volatile long fetchedAt = 0L;

    private FreshYouTubeCookies() {}

    static String get(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        String current = cached;
        if (!forceRefresh && !current.isEmpty() && now - fetchedAt < MAX_AGE_MS) return current;

        synchronized (FreshYouTubeCookies.class) {
            now = System.currentTimeMillis();
            current = cached;
            if (!forceRefresh && !current.isEmpty() && now - fetchedAt < MAX_AGE_MS) return current;
            String refreshed = fetch();
            if (!refreshed.isEmpty()) {
                cached = refreshed;
                fetchedAt = now;
                return refreshed;
            }
            return current;
        }
    }

    static String userAgent() {
        return USER_AGENT;
    }

    private static String fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://www.youtube.com/sw.js").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Referer", "https://www.youtube.com/");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return "";

            StringBuilder output = new StringBuilder();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                String name = header.getKey();
                if (name == null || !"set-cookie".equals(name.toLowerCase(Locale.ROOT))) continue;
                List<String> values = header.getValue();
                if (values == null) continue;
                for (String value : values) {
                    if (value == null) continue;
                    String entry = value.split(";", 2)[0].trim();
                    int equals = entry.indexOf('=');
                    if (equals <= 0) continue;
                    String key = entry.substring(0, equals).trim();
                    if (!ACCEPTED.contains(key)) continue;
                    if (output.length() > 0) output.append("; ");
                    output.append(entry);
                }
            }
            return output.toString();
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
