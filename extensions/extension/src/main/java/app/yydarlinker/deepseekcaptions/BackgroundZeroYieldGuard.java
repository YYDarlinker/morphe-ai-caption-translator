package app.yydarlinker.deepseekcaptions;

import android.os.SystemClock;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny in-process circuit breaker for stubborn background semantic gaps.
 *
 * <p>A background request is considered zero-yield when the model returns valid complete units but
 * none of them covers the request focus (the first unresolved atom). Two such misses trip a short
 * local deferral. Priority/current-cue requests never consult this guard, so playback safety and
 * translation quality are unchanged; the guard only prevents the background worker from repeatedly
 * buying the same unproductive window while it still has plenty of lead time.</p>
 */
final class BackgroundZeroYieldGuard {
    private static final int TRIP_AFTER_MISSES = 2;
    private static final long BASE_BLOCK_MS = 18_000L;
    private static final long BLOCK_STEP_MS = 6_000L;
    private static final long MAX_BLOCK_MS = 45_000L;
    private static final int MAX_ENTRIES = 512;

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private BackgroundZeroYieldGuard() {}

    static String key(
            List<SourceAtomTimeline.Atom> atoms,
            int focus,
            DeepSeekConfig.Snapshot config,
            TargetLanguage target
    ) {
        if (atoms == null || atoms.isEmpty()) return "";
        int cleanFocus = Math.max(0, Math.min(atoms.size() - 1, focus));
        StringBuilder out = new StringBuilder(256);
        out.append(config == null ? "" : normalize(config.baseUrl)).append('|')
                .append(config == null ? "" : normalize(config.model)).append('|')
                .append(target == null ? "" : normalize(target.code)).append('|');
        int from = Math.max(0, cleanFocus - 2);
        int to = Math.min(atoms.size() - 1, cleanFocus + 2);
        for (int i = from; i <= to; i++) {
            SourceAtomTimeline.Atom atom = atoms.get(i);
            out.append(atom.startMs).append(':').append(atom.endMs).append(':')
                    .append(normalize(atom.text)).append('|');
        }
        return out.toString();
    }

    static boolean shouldDefer(String key) {
        if (key == null || key.isEmpty()) return false;
        State state = STATES.get(key);
        if (state == null) return false;
        long now = SystemClock.elapsedRealtime();
        synchronized (state) {
            if (state.blockedUntilMs > now) return true;
            state.blockedUntilMs = 0L;
            return false;
        }
    }

    static void observe(String key, boolean focusCovered) {
        if (key == null || key.isEmpty()) return;
        if (focusCovered) {
            STATES.remove(key);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        State state = STATES.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            state.misses++;
            state.lastTouchedMs = now;
            if (state.misses >= TRIP_AFTER_MISSES) {
                long block = Math.min(
                        MAX_BLOCK_MS,
                        BASE_BLOCK_MS + (long) (state.misses - TRIP_AFTER_MISSES) * BLOCK_STEP_MS
                );
                state.blockedUntilMs = now + block;
            }
        }
        if (STATES.size() > MAX_ENTRIES) prune(now);
    }

    private static void prune(long now) {
        long staleBefore = now - 180_000L;
        for (Map.Entry<String, State> entry : STATES.entrySet()) {
            State state = entry.getValue();
            if (state == null) {
                STATES.remove(entry.getKey());
                continue;
            }
            boolean stale;
            synchronized (state) {
                stale = state.lastTouchedMs < staleBefore && state.blockedUntilMs <= now;
            }
            if (stale) STATES.remove(entry.getKey(), state);
            if (STATES.size() <= MAX_ENTRIES / 2) break;
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String one = value.trim().toLowerCase(Locale.ROOT);
        return one.length() <= 96 ? one : one.substring(0, 96);
    }

    private static final class State {
        int misses;
        long blockedUntilMs;
        long lastTouchedMs;
    }
}
