package app.yydarlinker.deepseekcaptions;

import org.json.JSONObject;

/** Usage accounting port; provider behaviour is independent of the application's report storage. */
final class TranslationAudit {
    enum FailureCategory {
        CANCELLED,
        DEADLINE_EXPIRED,
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        NETWORK_IO,
        HTTP_CONFIG,
        OTHER
    }

    static final class Request {
        final Object handle;

        Request(Object handle) {
            this.handle = handle;
        }
    }

    interface Sink {
        default Request begin(
                TranslationSettings config,
                boolean priority,
                int targets,
                int contexts,
                int targetChars,
                int contextChars,
                String bucket) {
            return new Request(null);
        }

        default int attempt(Request request, int bytes) {
            return 0;
        }

        default void response(Request request, int attempt, JSONObject root) {}

        default void failure(Request request, int attempt, FailureCategory category, String kind) {}

        default void quality(Request request, int accepted, int rejected, int qualityRejected) {}

        default void batch(Request request, int count) {}

        default void cache(int total, int restored) {}
    }

    private static volatile Sink sink = new Sink() {};

    static void install(Sink value) {
        sink = java.util.Objects.requireNonNull(value);
    }

    static Request beginUnitBatch(
            TranslationSettings c, boolean p, int t, int n, int tc, int nc, String bucket) {
        return sink.begin(c, p, t, n, tc, nc, bucket);
    }

    static int beginAttempt(Request r, int bytes) {
        return sink.attempt(r, bytes);
    }

    static void recordResponse(Request r, int a, JSONObject root) {
        sink.response(r, a, root);
    }

    static void recordFailure(Request r, int a, String kind) {
        sink.failure(r, a, failureCategory(kind), kind);
    }

    static void recordFailure(Request r, int a, FailureCategory c, String kind) {
        sink.failure(r, a, c, kind);
    }

    static void recordUnitQualityOutcome(Request r, int a, int b, int c) {
        sink.quality(r, a, b, c);
    }

    static void recordUnitBatchOutcome(Request r, int count) {
        sink.batch(r, count);
    }

    static void recordUnitCacheOutcome(int total, int restored) {
        sink.cache(total, restored);
    }

    static FailureCategory failureCategory(String kind) {
        if (kind == null) return FailureCategory.OTHER;
        String s = kind.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("cancel")) return FailureCategory.CANCELLED;
        if (s.contains("deadline_expired")) return FailureCategory.DEADLINE_EXPIRED;
        if (s.startsWith("http")) return FailureCategory.HTTP_CONFIG;
        if (s.contains("timeout"))
            return s.contains("connect")
                    ? FailureCategory.CONNECT_TIMEOUT
                    : FailureCategory.READ_TIMEOUT;
        if (s.contains("network") || s.contains("socket") || s.contains("interrupt"))
            return FailureCategory.NETWORK_IO;
        return FailureCategory.OTHER;
    }
}
