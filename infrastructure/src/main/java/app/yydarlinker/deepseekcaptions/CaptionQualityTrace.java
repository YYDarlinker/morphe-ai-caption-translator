package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Evidence of the quality of the translation: the source and the answer of a request, kept on the
 * device only while the display text debug is on. It never stores an HTTP body, key, cookie or
 * endpoint.
 */
final class CaptionQualityTrace {
    private static final String PREFS = "caption_quality_evidence";
    private static final int MAX_RECORDS = 6, MAX_CHARS = 36_000;
    private static final long KEEP_MS = 24 * 60 * 60 * 1000L;

    private CaptionQualityTrace() {}

    /**
     * Keeps one record: in full in the archive, and shortened in the few newest records the report
     * shows. [metadata] and the key of the credential are redacted first.
     */
    static synchronized void record(
            Context context,
            String key,
            long request,
            JSONObject source,
            String response,
            String metadata) {
        long epoch = CaptionDiagnosticArchive.epoch();
        if (context == null || !DeepSeekConfig.displayTextDebugEnabled(context)) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean answered = response != null && !response.isEmpty();
            JSONObject item =
                    new JSONObject()
                            .put("at", System.currentTimeMillis())
                            .put("request", request)
                            .put("settings", redact(metadata, key, 700));
            JSONObject full = new JSONObject(item.toString());
            if (source != null) full.put("source", redact(source.toString(), key, 128_000));
            if (answered) full.put("response", redact(response, key, 128_000));
            CaptionDiagnosticArchive.appendAtEpoch(context, "quality", full.toString(), epoch);

            if (source != null) item.put("source", redact(source.toString(), key, 12_000));
            if (answered) item.put("response", redact(response, key, 12_000));
            if (item.toString().length() > MAX_CHARS) {
                if (item.has("source"))
                    item.put("source", redact(item.getString("source"), key, 2400));
                if (item.has("response"))
                    item.put("response", redact(item.getString("response"), key, 2400));
                item.put("truncated", true);
            }

            JSONArray old = new JSONArray(prefs.getString("records", "[]"));
            JSONArray next = new JSONArray().put(item);
            int chars = item.toString().length();
            for (int i = 0; i < old.length() && next.length() < MAX_RECORDS; i++) {
                JSONObject entry = old.optJSONObject(i);
                if (entry == null || stale(entry)) continue;
                chars += entry.toString().length();
                if (chars > MAX_CHARS) break;
                next.put(entry);
            }
            prefs.edit().putString("records", next.toString()).apply();
        } catch (JSONException | RuntimeException ignored) {
            // Evidence is best effort.
        }
    }

    private static boolean stale(JSONObject entry) {
        return System.currentTimeMillis() - entry.optLong("at", 0) > KEEP_MS;
    }

    /** [value] without the key, URLs and bearer tokens, and at most [limit] characters long. */
    static String redact(String value, String key, int limit) {
        String text = (value == null ? "" : value).replace("\\/", "/");
        if (key != null && !key.isEmpty()) {
            text = text.replace(key, "[redacted]");
            String escaped = JSONObject.quote(key);
            if (escaped.length() > 2)
                text = text.replace(escaped.substring(1, escaped.length() - 1), "[redacted]");
        }
        text =
                text.replaceAll("(?i)https?://[^\\s\\\"<>]+", "[URL redacted]")
                        .replaceAll(
                                "(?i)(bearer\\s+|sk-)[A-Za-z0-9_.-]{8,}", "[credential redacted]");
        return text.length() <= limit ? text : text.substring(0, limit) + " [truncated]";
    }

    /** The section of the report with the kept records, or nothing. */
    static synchronized String text(Context context) {
        if (context == null || !DeepSeekConfig.displayTextDebugEnabled(context)) return "";
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(prefs.getString("records", "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject row = old.optJSONObject(i);
                if (row != null && !stale(row)) keep.put(row);
            }
            if (keep.length() != old.length())
                prefs.edit().putString("records", keep.toString()).apply();
            return keep.length() == 0
                    ? ""
                    : "\n\n[Quality evidence / local, opt-in, bounded, 24h]\n" + keep.toString(2);
        } catch (JSONException | RuntimeException ignored) {
            return "";
        }
    }

    static synchronized void clear(Context context) {
        if (context != null)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
