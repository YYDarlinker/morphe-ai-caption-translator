package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Exact accounting of the tokens and the cost of the translation requests, on the device.
 *
 * <p>The state is one JSON document with the totals and one with the current viewing session; each
 * has counters, "metrics", and "buckets" by purpose. A request is counted in the buckets "all", its
 * purpose, its detail and its core, and in the session too if it began in it. The usage the
 * provider reports is used as it is, with both the DeepSeek cache counters and the
 * OpenAI-compatible {@code prompt_tokens_details.cached_tokens}. {@link TokenCostReport} turns the
 * state into text.
 */
final class TokenCostAudit {
    private static final String PREFS = "deepseek_caption_token_cost_audit";
    private static final String STATE = "state_v1";
    private static final int VERSION = 1;
    private static final String CORE = "event_rebuild_r2";
    private static final long WATCH_SAMPLE_MAX_DELTA_MS = 5_000L;
    private static final long WATCH_PERSIST_INTERVAL_MS = 5_000L;

    private static final Object LOCK = new Object();
    private static volatile Context appContext;
    private static JSONObject memoryState;
    private static long generation;
    private static String activeVideoId = "";
    private static String activeCore = CORE;
    private static long lastVideoTimeMs = -1L;
    private static long lastWatchPersistRealtimeMs;

    private TokenCostAudit() {}

    /** A request being counted; it belongs to the session that was current when it began. */
    static final class Request {
        final long generation;
        final String purpose, model, baseUrl, detailBucket, coreBucket;
        int attempts;

        Request(
                long generation,
                String purpose,
                String model,
                String baseUrl,
                String detailBucket,
                String core) {
            this.generation = generation;
            this.purpose = purpose == null || purpose.isEmpty() ? "background" : purpose;
            this.model = model == null ? "" : model;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.detailBucket = detailBucket == null ? "" : detailBucket;
            coreBucket = "core_" + normalizeCore(core);
        }
    }

    // ---- Session ----

    static void install(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            stateLocked();
        }
    }

    static void onCoreSelected(Context context, String rawCore) {
        install(context);
        synchronized (LOCK) {
            stateLocked();
            boolean changed = !CORE.equals(activeCore);
            activeCore = CORE;
            put(sessionLocked(), "active_core", CORE);
            if (changed) lastVideoTimeMs = -1L;
            persistLocked();
        }
    }

    /** A new video starts a new session. */
    static void onVideoId(Context context, String rawVideoId) {
        install(context);
        String videoId = rawVideoId == null ? "" : rawVideoId.trim();
        if (videoId.isEmpty()) return;
        synchronized (LOCK) {
            stateLocked();
            if (videoId.equals(activeVideoId)) return;
            activeVideoId = videoId;
            generation++;
            put(memoryState, "session", freshSession(videoId, generation));
            lastVideoTimeMs = -1L;
            lastWatchPersistRealtimeMs = SystemClock.elapsedRealtime();
            persistLocked();
        }
    }

    /** Counts the time the AI captions were visible and playing, between two position reports. */
    static void onVideoTime(Context context, long timeMs, boolean aiVisible) {
        install(context);
        synchronized (LOCK) {
            stateLocked();
            if (!aiVisible) {
                lastVideoTimeMs = -1L;
                return;
            }
            long time = Math.max(0L, timeMs);
            long delta = time - lastVideoTimeMs;
            if (lastVideoTimeMs >= 0L && delta > 0L && delta <= WATCH_SAMPLE_MAX_DELTA_MS) {
                String viewed = "viewed_ms_" + activeCore;
                add(sessionLocked(), "viewed_ms", delta);
                add(memoryState, "viewed_ms", delta);
                add(metricsLocked(sessionLocked()), viewed, delta);
                add(metricsLocked(memoryState), viewed, delta);
            }
            lastVideoTimeMs = time;
            long now = SystemClock.elapsedRealtime();
            if (now - lastWatchPersistRealtimeMs >= WATCH_PERSIST_INTERVAL_MS) {
                lastWatchPersistRealtimeMs = now;
                persistLocked();
            }
        }
    }

    static void clear(Context context) {
        install(context);
        synchronized (LOCK) {
            memoryState = freshState();
            generation++;
            put(memoryState, "session", freshSession(activeVideoId, generation));
            lastVideoTimeMs = -1L;
            persistLocked();
        }
    }

    // ---- Requests ----

    /** Counts a request for a batch of units, with the amount of text around the target. */
    static Request beginUnitBatch(
            DeepSeekConfig.ProviderSettings config,
            boolean priority,
            int targetUnits,
            int contextUnits,
            int targetChars,
            int contextChars,
            String detailBucket) {
        synchronized (LOCK) {
            stateLocked();
            boolean rebuild =
                    detailBucket != null
                            && (detailBucket.startsWith("event-rebuild-")
                                    || detailBucket.startsWith("semantic-units-"));
            Request request =
                    new Request(
                            generation,
                            priority ? "priority" : "background",
                            config == null ? "" : config.model,
                            config == null ? "" : config.baseUrl,
                            detailBucket,
                            rebuild ? CORE : "contextual_unit_v1");
            String model = request.model.trim();
            if (!model.isEmpty()) put(sessionLocked(), "model", model);
            updateBuckets(request, bucket -> add(bucket, "logical_requests", 1L));
            addMetric("unit_batch_requests", 1L);
            addMetric("unit_target_units", Math.max(0, targetUnits));
            addMetric("unit_context_units", Math.max(0, contextUnits));
            addMetric("unit_target_chars", Math.max(0, targetChars));
            addMetric("unit_context_chars", Math.max(0, contextChars));
            return request;
        }
    }

    static void recordUnitCacheOutcome(int totalUnits, int restoredUnits) {
        synchronized (LOCK) {
            stateLocked();
            int total = Math.max(0, totalUnits);
            int restored = Math.max(0, Math.min(total, restoredUnits));
            addMetric("unit_cache_lookups", 1L);
            addMetric("unit_cache_hit_units", restored);
            addMetric("unit_cache_miss_units", total - restored);
            persistLocked();
        }
    }

    static void recordUnitBatchOutcome(Request request, int translatedUnits) {
        update(
                request,
                bucket -> {
                    add(bucket, "unit_batch_results", 1L);
                    add(bucket, "unit_translations", Math.max(0, translatedUnits));
                });
    }

    static void recordUnitQualityOutcome(
            Request request, int accepted, int rejected, int qualityRejected) {
        update(
                request,
                bucket -> {
                    add(bucket, "accepted_caption_units", Math.max(0, accepted));
                    add(bucket, "rejected_caption_units", Math.max(0, rejected));
                    add(bucket, "quality_rejected_units", Math.max(0, qualityRejected));
                });
    }

    /** Counts one HTTP attempt of a request; the number of the attempt, 1 for the first. */
    static int beginAttempt(Request request, int requestBytes) {
        if (request == null) return 0;
        synchronized (LOCK) {
            stateLocked();
            int attempt = ++request.attempts;
            updateBuckets(
                    request,
                    bucket -> {
                        add(bucket, "attempts", 1L);
                        add(bucket, "request_bytes", Math.max(0, requestBytes));
                        if (attempt > 1) add(bucket, "internal_retries", 1L);
                    });
            return attempt;
        }
    }

    /** Counts the answer of the provider: its usage, cache hits and the cost where it is known. */
    static void recordResponse(Request request, int attempt, JSONObject root) {
        if (request == null || attempt <= 0) return;
        JSONObject usage = root == null ? null : root.optJSONObject("usage");
        update(
                request,
                bucket -> {
                    add(bucket, "http_ok", 1L);
                    if (usage == null) {
                        add(bucket, "responses_without_usage", 1L);
                        return;
                    }
                    long prompt = nonNegative(usage.optLong("prompt_tokens", 0L));
                    long completion = nonNegative(usage.optLong("completion_tokens", 0L));
                    long total = nonNegative(usage.optLong("total_tokens", prompt + completion));
                    long[] cache = cacheTokens(usage, prompt);
                    long cost =
                            cache == null
                                    ? -1L
                                    : priceNanoCny(
                                            request.model,
                                            request.baseUrl,
                                            cache[0],
                                            cache[1],
                                            completion);
                    add(bucket, "usage_responses", 1L);
                    add(bucket, "prompt_tokens", prompt);
                    add(bucket, "completion_tokens", completion);
                    add(bucket, "total_tokens", total);
                    if (cache != null) {
                        add(bucket, "cache_hit_tokens", cache[0]);
                        add(bucket, "cache_miss_tokens", cache[1]);
                        add(bucket, "cache_known_responses", 1L);
                    }
                    if (cost >= 0L) {
                        add(bucket, "priced_responses", 1L);
                        add(bucket, "cost_nano_cny", cost);
                    }
                });
    }

    /** {hit, miss} tokens of the prompt, or null if the provider does not report a cache. */
    private static long[] cacheTokens(JSONObject usage, long prompt) {
        if (usage.has("prompt_cache_hit_tokens") || usage.has("prompt_cache_miss_tokens")) {
            long hit = nonNegative(usage.optLong("prompt_cache_hit_tokens", 0L));
            long miss =
                    usage.has("prompt_cache_miss_tokens")
                            ? nonNegative(usage.optLong("prompt_cache_miss_tokens", 0L))
                            : Math.max(0L, prompt - hit);
            return new long[] {hit, miss};
        }
        JSONObject details = usage.optJSONObject("prompt_tokens_details");
        if (details != null && details.has("cached_tokens")) {
            long hit = Math.min(nonNegative(details.optLong("cached_tokens", 0L)), prompt);
            return new long[] {hit, Math.max(0L, prompt - hit)};
        }
        return null;
    }

    enum FailureCategory {
        CANCELLED,
        DEADLINE_EXPIRED,
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        NETWORK_IO,
        HTTP_CONFIG,
        OTHER
    }

    static FailureCategory failureCategory(String kind) {
        String value = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (value.contains("cancel")) return FailureCategory.CANCELLED;
        if (value.contains("deadline_expired")) return FailureCategory.DEADLINE_EXPIRED;
        if (value.startsWith("http")) return FailureCategory.HTTP_CONFIG;
        if (value.contains("timeout"))
            return value.contains("connect")
                    ? FailureCategory.CONNECT_TIMEOUT
                    : FailureCategory.READ_TIMEOUT;
        if (value.contains("network") || value.contains("socket") || value.contains("interrupt"))
            return FailureCategory.NETWORK_IO;
        return FailureCategory.OTHER;
    }

    static void recordFailure(Request request, int attempt, String kind) {
        recordFailure(request, attempt, failureCategory(kind), kind);
    }

    /** Counts a failed attempt under its category; earlier counts are never rewritten. */
    static void recordFailure(Request request, int attempt, FailureCategory category, String kind) {
        if (request == null || attempt <= 0) return;
        String clean = kind == null ? "unknown" : kind.toLowerCase(Locale.ROOT);
        update(
                request,
                bucket -> {
                    add(bucket, "failures", 1L);
                    add(bucket, "category_" + category.name().toLowerCase(Locale.ROOT), 1L);
                    switch (category) {
                        case CANCELLED:
                            add(bucket, "cancelled_failures", 1L);
                            break;
                        case DEADLINE_EXPIRED:
                            add(bucket, "deadline_expired_failures", 1L);
                            add(bucket, "timeout_failures", 1L);
                            break;
                        case CONNECT_TIMEOUT:
                        case READ_TIMEOUT:
                            add(bucket, "timeout_failures", 1L);
                            break;
                        case NETWORK_IO:
                            add(bucket, "network_failures", 1L);
                            break;
                        case HTTP_CONFIG:
                            add(bucket, "http_failures", 1L);
                            add(bucket, httpClass(clean), 1L);
                            break;
                        default:
                            add(bucket, "other_failures", 1L);
                    }
                });
    }

    /** The counter of an HTTP failure by its status, which ends the kind as in "http_503". */
    private static String httpClass(String kind) {
        int code = -1;
        int underscore = kind.lastIndexOf('_');
        if (underscore >= 0 && underscore + 1 < kind.length()) {
            try {
                code = Integer.parseInt(kind.substring(underscore + 1));
            } catch (NumberFormatException notACode) {
                // Not a status.
            }
        }
        if (kind.contains("429")) return "http_429_failures";
        if (code >= 500 && code <= 599) return "http_5xx_failures";
        if (code >= 400 && code <= 499) return "http_4xx_failures";
        return "http_other_failures";
    }

    // ---- Price ----

    /**
     * The cost in nano-yuan of a response, or -1 where the price is not known: only
     * deepseek-v4-flash with a reported cache, on the official endpoint (cached input 0.02 yuan per
     * million tokens) or Alibaba Cloud Model Studio (0.20); uncached input costs 1 and output 2
     * yuan per million tokens on both.
     */
    private static long priceNanoCny(
            String model, String baseUrl, long hit, long miss, long completion) {
        String base = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        if (model == null || !model.toLowerCase(Locale.ROOT).contains("deepseek-v4-flash"))
            return -1L;
        long hitPrice;
        if (base.contains("api.deepseek.com")) hitPrice = 20L;
        else if (base.contains("dashscope.aliyuncs.com") || base.contains("maas.aliyuncs.com"))
            hitPrice = 200L;
        else return -1L;
        return times(hit, hitPrice) + times(miss, 1_000L) + times(completion, 2_000L);
    }

    private static long times(long value, long multiplier) {
        if (value <= 0L) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    // ---- Report ----

    static String uiText(Context context) {
        return uiText(context, true);
    }

    /** The panel text; with {@code localized=false} the English text of the saved export. */
    static String uiText(Context context, boolean localized) {
        install(context);
        synchronized (LOCK) {
            return new TokenCostReport(context, localized, stateLocked(), sessionLocked()).build();
        }
    }

    // ---- State ----

    /** Applies a change to every bucket a request counts in, and saves. */
    private static void update(Request request, Consumer<JSONObject> change) {
        if (request == null) return;
        synchronized (LOCK) {
            stateLocked();
            updateBuckets(request, change);
            persistLocked();
        }
    }

    private static void updateBuckets(Request request, Consumer<JSONObject> change) {
        JSONObject state = stateLocked();
        for (JSONObject root :
                request.generation == generation
                        ? new JSONObject[] {state, sessionLocked()}
                        : new JSONObject[] {state}) {
            change.accept(bucketLocked(root, "all"));
            change.accept(bucketLocked(root, request.purpose));
            if (!request.detailBucket.isEmpty())
                change.accept(bucketLocked(root, request.detailBucket));
            change.accept(bucketLocked(root, request.coreBucket));
        }
    }

    /** Adds to a metric of the totals and of the session. */
    private static void addMetric(String key, long delta) {
        add(metricsLocked(memoryState), key, delta);
        add(metricsLocked(sessionLocked()), key, delta);
    }

    private static JSONObject stateLocked() {
        if (memoryState != null) return memoryState;
        JSONObject loaded = null;
        Context context = appContext;
        if (context != null) {
            try {
                String raw =
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .getString(STATE, "");
                if (!raw.trim().isEmpty()) loaded = new JSONObject(raw);
            } catch (JSONException | RuntimeException unreadable) {
                // A fresh state.
            }
        }
        if (loaded == null || loaded.optInt("version", 0) != VERSION) loaded = freshState();
        memoryState = loaded;
        JSONObject session = loaded.optJSONObject("session");
        if (session == null) {
            generation++;
            put(loaded, "session", freshSession(activeVideoId, generation));
        } else {
            generation = Math.max(generation, session.optLong("generation", 0L));
            if (activeVideoId.isEmpty()) activeVideoId = session.optString("video_id", "").trim();
            activeCore = normalizeCore(session.optString("active_core", activeCore));
        }
        return loaded;
    }

    private static JSONObject freshState() {
        JSONObject state = new JSONObject();
        put(state, "version", VERSION);
        put(state, "cleared_at", System.currentTimeMillis());
        put(state, "viewed_ms", 0L);
        put(state, "metrics", new JSONObject());
        put(state, "buckets", freshBuckets());
        return state;
    }

    private static JSONObject freshSession(String videoId, long generation) {
        JSONObject session = new JSONObject();
        put(session, "generation", generation);
        put(session, "video_id", videoId == null ? "" : videoId);
        put(session, "model", "");
        put(session, "active_core", activeCore);
        put(session, "started_at", System.currentTimeMillis());
        put(session, "viewed_ms", 0L);
        put(session, "metrics", new JSONObject());
        put(session, "buckets", freshBuckets());
        return session;
    }

    private static JSONObject freshBuckets() {
        JSONObject buckets = new JSONObject();
        for (String name : new String[] {"all", "priority", "background", "display"})
            put(buckets, name, new JSONObject());
        return buckets;
    }

    private static JSONObject sessionLocked() {
        JSONObject state = stateLocked();
        JSONObject session = state.optJSONObject("session");
        if (session == null) {
            generation++;
            session = freshSession(activeVideoId, generation);
            put(state, "session", session);
        }
        return session;
    }

    static JSONObject metricsLocked(JSONObject parent) {
        JSONObject metrics = parent.optJSONObject("metrics");
        if (metrics == null) put(parent, "metrics", metrics = new JSONObject());
        return metrics;
    }

    static JSONObject bucketLocked(JSONObject parent, String key) {
        JSONObject buckets = parent.optJSONObject("buckets");
        if (buckets == null) put(parent, "buckets", buckets = freshBuckets());
        JSONObject bucket = buckets.optJSONObject(key);
        if (bucket == null) put(buckets, key, bucket = new JSONObject());
        return bucket;
    }

    private static String normalizeCore(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return value.equals(CORE) || value.equals("contextual_unit_v1")
                ? value
                : "semantic_ledger_v2";
    }

    private static void persistLocked() {
        Context context = appContext;
        if (context == null || memoryState == null) return;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(STATE, memoryState.toString())
                    .apply();
        } catch (RuntimeException ignored) {
            // The audit is best effort.
        }
    }

    /** Adds to a counter, without overflow. */
    private static void add(JSONObject object, String key, long delta) {
        if (delta == 0L) return;
        long old = object.optLong(key, 0L);
        long next = old + delta;
        if (delta > 0L && next < old) next = Long.MAX_VALUE;
        else if (delta < 0L && next > old) next = Long.MIN_VALUE;
        put(object, key, next);
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
            // The key is never null.
        }
    }

    // ---- Formatting, for the report ----

    static long value(JSONObject object, String key) {
        return object == null ? 0L : nonNegative(object.optLong(key, 0L));
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    static String format(long value) {
        return String.format(Locale.US, "%,d", Math.max(0L, value));
    }

    static String percent(long numerator, long denominator) {
        return denominator <= 0L
                ? "0.0%"
                : String.format(Locale.US, "%.1f%%", numerator * 100d / denominator);
    }

    static String money(double value) {
        return String.format(
                Locale.US, value < 0.01d ? "%.5f" : value < 1d ? "%.4f" : "%.3f", value);
    }

    static String seconds1(double seconds) {
        return String.format(Locale.US, seconds < 10d ? "%.1f" : "%.0f", seconds);
    }
}
