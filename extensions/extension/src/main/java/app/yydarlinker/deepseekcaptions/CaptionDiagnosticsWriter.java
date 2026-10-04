package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, credential-bound diagnostics writer.
 *
 * <p>N36 moves redaction, keystore access, SharedPreferences updates and file appends off every
 * playback, animation and preview path. A hot-path caller supplies only an already-known
 * {@link CaptionCredentialRef}; the writer keeps at most two short-lived credential snapshots, keyed
 * by that fingerprint, so a record produced under one profile is redacted with that profile's own key
 * and never with a newer one. Nothing is written unredacted and no plaintext is ever persisted.</p>
 */
final class CaptionDiagnosticsWriter {
    /** At most this many short-lived credential snapshots, each bounded by the retention window. */
    private static final int MAX_SNAPSHOTS = 2;
    /** A resolving snapshot is refreshed only for a record older than this. */
    private static final long SNAPSHOT_MAX_AGE_MS = 5 * 60_000L;
    private static final int MAX_BATCH = 64;
    /** Per-lane bound; both lanes are bounded, so a burst cannot grow memory without limit. */
    private static final int MAX_QUEUE = 512;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<Record> QUEUE = new ArrayDeque<>();
    /** Bounded lane for records that carry real lifecycle evidence; never starved by display noise. */
    private static final ArrayDeque<Record> DECISIONS_QUEUE = new ArrayDeque<>();
    private static final Map<String,String> SNAPSHOTS = new LinkedHashMap<>(4, 0.75f, true);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean drainPosted;
    private static long dropped, merged, written, redactions, seedOnlyRedactions;
    private static long epoch, lastResolvedAt;

    private CaptionDiagnosticsWriter() {}

    static final class Record {
        final String stage, detail;
        final CaptionCredentialRef credential;
        final long at;
        final String mergeKey;
        int repeats;
        Record(String stage, String detail, CaptionCredentialRef credential, String mergeKey) {
            this.stage = stage;
            this.detail = detail;
            this.credential = credential == null ? CaptionCredentialRef.NONE : credential;
            this.mergeKey = mergeKey == null ? "" : mergeKey;
            this.at = System.currentTimeMillis();
        }
    }

    static void enqueue(Context context, String stage, String detail, CaptionCredentialRef credential,
            String mergeKey) {
        if (context == null || stage == null || stage.isEmpty()) return;
        final Context app = context.getApplicationContext();
        Record record = new Record(stage, detail, credential, mergeKey);
        boolean post;
        boolean mandatory = CaptionDiagnostics.isDecision(stage);
        synchronized (LOCK) {
            ArrayDeque<Record> lane = mandatory ? DECISIONS_QUEUE : QUEUE;
            if (!record.mergeKey.isEmpty()) {
                Record last = lane.peekLast();
                if (last != null && last.mergeKey.equals(record.mergeKey) && last.stage.equals(stage)) {
                    last.repeats++;
                    merged++;
                    return;
                }
            }
            // Two bounded lanes. A decision record is only ever dropped when its own lane is full, so a
            // burst of repeated display observations can never evict real lifecycle evidence.
            if (lane.size() >= MAX_QUEUE) {
                dropped++;
                return;
            }
            lane.addLast(record);
            post = !drainPosted;
            if (post) drainPosted = true;
        }
        if (post) MAIN.post(() -> drain(app));
    }

    /**
     * Resolves and releases credential snapshots on the main thread, then hands the batch to the
     * archive lane. A snapshot for a credential the hot path cannot resolve is taken here, off the
     * playback path, only after everything the user can see has already been applied.
     */
    private static void drain(Context context) {
        Record[] batch;
        int batchSize;
        synchronized (LOCK) {
            drainPosted = false;
            java.util.ArrayList<Record> selected = new java.util.ArrayList<>(MAX_BATCH);
            // Both bounded lanes are represented in every batch, with the decision lane first, so a
            // flood of ordinary display observations can never starve the records that carry the real
            // request, cancellation, repair, owner-recovery and first-display evidence.
            int half = MAX_BATCH / 2;
            for (int i = 0; i < half && !DECISIONS_QUEUE.isEmpty(); i++) selected.add(DECISIONS_QUEUE.pollFirst());
            for (int i = 0; i < MAX_BATCH - selected.size() && !QUEUE.isEmpty(); i++) selected.add(QUEUE.pollFirst());
            for (int i = 0; i < MAX_BATCH - selected.size() && !DECISIONS_QUEUE.isEmpty(); i++) selected.add(DECISIONS_QUEUE.pollFirst());
            batchSize = selected.size();
            batch = selected.toArray(new Record[0]);
        }
        StringBuilder archive = new StringBuilder();
        String latestStage = "", latestDetail = "";
        long latestAt = 0;
        StringBuilder decisions = new StringBuilder();
        for (int i = 0; i < batchSize; i++) {
            Record record = batch[i];
            String detail = redact(context, record);
            String line = record.at + " | " + sanitize(record.stage, 80)
                    + (detail.isEmpty() ? "" : " | " + detail);
            archive.append(line).append('\n');
            if (record.repeats > 0) archive.append("  (repeated ").append(record.repeats)
                    .append(" times; repeated observation merged; total_count=")
                    .append(record.repeats + 1).append(")\n");
            latestStage = sanitize(record.stage, 80);
            latestDetail = detail;
            latestAt = record.at;
            if (CaptionDiagnostics.isDecision(record.stage)) decisions.append(line).append('\n');
        }
        if (archive.length() > 0) {
            CaptionDiagnosticArchive.append(context, "history", trim(archive.toString()));
            written += batchSize;
        }
        String decisionText = decisions.length() == 0 ? "" : trim(decisions.toString());
        if (latestAt > 0) {
            CaptionDiagnosticArchive.appendSummary(context, latestStage, latestDetail, latestAt, decisionText, epoch());
        }
        synchronized (LOCK) {
            if (!QUEUE.isEmpty() || !DECISIONS_QUEUE.isEmpty()) {
                drainPosted = true;
                MAIN.post(() -> drain(context));
            }
        }
    }

    /**
     * Plaintext is used only for a record whose own credential fingerprint has a resolving snapshot.
     * If the key cannot be resolved, the record is written through the pattern redactor and marked, so
     * an unknown credential can never leak a raw value into the archive.
     */
    private static String redact(Context context, Record record) {
        String key = record.credential.known() ? snapshotFor(context, record.credential) : "";
        String value = CaptionQualityTrace.redact(record.detail, key, 1600);
        String clean = sanitize(value, 1700);
        synchronized (LOCK) {
            if (!record.credential.known()) return clean;
            if (key.isEmpty()) {
                seedOnlyRedactions++;
                clean = clean + ";redaction=credential_unavailable";
            } else {
                redactions++;
            }
        }
        return clean;
    }

    /**
     * Returns the plaintext for a credential fingerprint, resolving it at most once per snapshot and
     * refreshing a stale entry. Retention is bounded by entry count, so the extension holds no more
     * than two profile secrets for at most the retention window.
     */
    private static String snapshotFor(Context context, CaptionCredentialRef reference) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            String cached = SNAPSHOTS.get(reference.fingerprint);
            if (cached != null && now - lastResolvedAt <= SNAPSHOT_MAX_AGE_MS) return cached;
        }
        String plaintext = SecureApiKey.load(context);
        CaptionCredentialRef resolved = CaptionCredentialRef.of(plaintext);
        synchronized (LOCK) {
            lastResolvedAt = now;
            if (!resolved.fingerprint.equals(reference.fingerprint)) return "";
            SNAPSHOTS.put(reference.fingerprint, plaintext);
            while (SNAPSHOTS.size() > MAX_SNAPSHOTS) {
                String oldest = SNAPSHOTS.keySet().iterator().next();
                SNAPSHOTS.remove(oldest);
            }
            return plaintext;
        }
    }

    /** Bounded export barrier: waits for records produced before it started, never longer than 400 ms. */
    static void flush(Context context) {
        if (context == null) return;
        for (int attempt = 0; attempt < 40; attempt++) {
            synchronized (LOCK) {
                if (QUEUE.isEmpty() && DECISIONS_QUEUE.isEmpty() && !drainPosted) return;
            }
            try { Thread.sleep(10L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Verification entry: drains every queued record on the calling thread. Production reaches the
     * same writer through the main-thread drain; tests use this so a record is observable immediately.
     */
    static void drainNow(Context context) {
        if (context == null) return;
        for (int guard = 0; guard < 64; guard++) {
            boolean empty;
            synchronized (LOCK) { empty = QUEUE.isEmpty() && DECISIONS_QUEUE.isEmpty(); }
            if (empty) return;
            drain(context);
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            QUEUE.clear();
            DECISIONS_QUEUE.clear();
            SNAPSHOTS.clear();
            lastResolvedAt = 0;
            epoch++;
        }
    }

    /** Immutable identity of the current clearing epoch; records from an older epoch are not revived. */
    static long epoch() { synchronized (LOCK) { return epoch; } }

    static long droppedCount() { synchronized (LOCK) { return dropped; } }
    static long mergedCount() { synchronized (LOCK) { return merged; } }
    static long writtenCount() { synchronized (LOCK) { return written; } }
    static long redactedCount() { synchronized (LOCK) { return redactions; } }
    static long credentialUnavailableCount() { synchronized (LOCK) { return seedOnlyRedactions; } }
    static int pendingCount() { synchronized (LOCK) { return QUEUE.size() + DECISIONS_QUEUE.size(); } }

    /**
     * Latest queued stage/detail before a drain, as a bounded in-memory fallback for a report read in
     * the same instant as its drain. It never blocks and never performs credential work.
     */
    static String[] pendingSummary() {
        synchronized (LOCK) {
            Record last = QUEUE.peekLast();
            if (last == null) return null;
            StringBuilder decisions = new StringBuilder();
            if (CaptionDiagnostics.isDecision(last.stage)) {
                decisions.append(last.at).append(" | ").append(last.stage);
                if (!last.detail.isEmpty()) decisions.append(" | ").append(last.detail);
            }
            return new String[]{last.stage, last.detail, String.valueOf(last.at), decisions.toString()};
        }
    }

    /** Bounded one-line queue accounting, appended to the report so drops and merges are auditable. */
    static String stats(String label) {
        long droppedNow, mergedNow, writtenNow, unavailableNow;
        int pending;
        synchronized (LOCK) {
            droppedNow = dropped; mergedNow = merged; writtenNow = written;
            unavailableNow = seedOnlyRedactions; pending = QUEUE.size();
        }
        if (writtenNow == 0 && droppedNow == 0 && mergedNow == 0) return "";
        return "\n\n[" + label + ": written=" + writtenNow + "; merged_repeats=" + mergedNow
                + "; dropped=" + droppedNow + "; pending=" + pending
                + "; credential_unavailable=" + unavailableNow + "]\n";
    }

    static void resetForTests() {
        synchronized (LOCK) {
            QUEUE.clear();
            DECISIONS_QUEUE.clear();
            SNAPSHOTS.clear();
            drainPosted = false;
            dropped = 0;
            merged = 0;
            written = 0;
            redactions = 0;
            seedOnlyRedactions = 0;
            lastResolvedAt = 0;
            epoch++;
        }
    }

    private static String sanitize(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String trim(String value) {
        return value.length() <= 60_000 ? value : value.substring(0, 60_000) + " [batch truncated]";
    }
}
