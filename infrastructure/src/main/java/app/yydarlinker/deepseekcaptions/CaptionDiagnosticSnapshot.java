package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The report of the diagnostics panel, made on its own thread. What the panel shows is the last
 * finished snapshot, so no caller of the UI waits for the writer or the archive. A snapshot is
 * valid for one {@link Key}: the same app, clearing generation, profile and language.
 */
final class CaptionDiagnosticSnapshot {
    private static final int MAX_REPORT_CHARS = 64_000;
    private static final int PREVIEW_CHARS = 4096, PREVIEW_HEAD = 1200, PREVIEW_TAIL = 2800;
    private static final int MAX_PENDING = 2, MAX_CALLBACKS = 16;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final List<Pending> pending = new ArrayList<>();
    private static volatile Snapshot latest;

    private static final ThreadPoolExecutor REPORT =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2),
                    runnable -> {
                        Thread thread = new Thread(runnable, "caption-diagnostic-report");
                        thread.setDaemon(true);
                        return thread;
                    });

    static {
        REPORT.allowCoreThreadTimeOut(true);
    }

    private CaptionDiagnosticSnapshot() {}

    interface Callback {
        void ready(Snapshot snapshot);
    }

    /** What a snapshot is valid for. */
    static final class Key {
        final Context app;
        final long epoch, profile;
        final String locale;

        Key(Context context) {
            Context application = context.getApplicationContext();
            app = application == null ? context : application;
            epoch = CaptionDiagnosticsWriter.epoch();
            profile = ApiProfiles.revision();
            locale = CaptionTextResolver.locale(context).toLanguageTag();
        }

        boolean same(Key other) {
            return app == other.app
                    && epoch == other.epoch
                    && profile == other.profile
                    && locale.equals(other.locale);
        }

        boolean current(Context context) {
            return same(new Key(context));
        }
    }

    static final class Snapshot {
        final Key key;
        final String text, preview;

        Snapshot(Key key, String text) {
            this.key = key;
            this.text = text;
            preview = boundedPreview(text);
        }
    }

    /** A long report as its start and its end, cut at line ends and not inside a character. */
    static String boundedPreview(String report) {
        if (report.length() <= PREVIEW_CHARS) return report;
        int head = report.lastIndexOf('\n', PREVIEW_HEAD);
        if (head < 0) head = PREVIEW_HEAD;
        int tail = report.indexOf('\n', report.length() - PREVIEW_TAIL);
        if (tail < 0) tail = report.length() - PREVIEW_TAIL;
        if (head > 0 && Character.isHighSurrogate(report.charAt(head - 1))) head--;
        if (tail < report.length() && Character.isLowSurrogate(report.charAt(tail))) tail++;
        return report.substring(0, head) + "\n…\n" + report.substring(tail);
    }

    /** A report being made, and those waiting for it. Listeners are weak. */
    private static final class Pending implements Runnable {
        final Key key;
        final WeakReference<Context> caller;
        final List<WeakReference<Callback>> callbacks = new ArrayList<>();

        Pending(Context context, Key key) {
            this.key = key;
            caller = new WeakReference<>(context);
        }

        @Override
        public void run() {
            compute(this);
        }
    }

    /** The finished snapshot if it is still valid for [context]. */
    static Snapshot peek(Context context) {
        Snapshot value = latest;
        return value != null && value.key.current(context) ? value : null;
    }

    static String peekText(Context context) {
        Snapshot value = peek(context);
        return value == null ? CaptionStrings.get(context, "diagnostics_loading") : value.text;
    }

    static void invalidate() {
        latest = null;
    }

    /** Makes a snapshot in the background and calls [callback] on the main thread with it. */
    static void request(Context context, Callback callback) {
        Key key = new Key(context);
        Pending work;
        synchronized (LOCK) {
            for (Pending existing : pending) {
                if (!existing.key.same(key)) continue;
                existing.callbacks.removeIf(ref -> ref.get() == null);
                if (existing.callbacks.size() >= MAX_CALLBACKS) existing.callbacks.remove(0);
                existing.callbacks.add(new WeakReference<>(callback));
                return;
            }
            // Work for an older language, clearing or profile must not crowd out the current one.
            pending.removeIf(old -> !old.key.current(context));
            REPORT.getQueue()
                    .removeIf(
                            task ->
                                    task instanceof Pending
                                            && !((Pending) task).key.current(context));
            if (pending.size() >= MAX_PENDING) return;
            work = new Pending(context, key);
            work.callbacks.add(new WeakReference<>(callback));
            pending.add(work);
        }
        try {
            REPORT.execute(work);
        } catch (RejectedExecutionException full) {
            synchronized (LOCK) {
                pending.remove(work);
            }
        }
    }

    private static void compute(Pending work) {
        Context context = work.caller.get();
        if (context == null) context = work.key.app;
        Snapshot result = null;
        try {
            // Nothing is published that was made while the language, profile or clearing changed.
            if (work.key.current(context)) {
                String text = CaptionDiagnostics.reportText(context, true);
                if (text.length() <= MAX_REPORT_CHARS && work.key.current(context))
                    result = new Snapshot(work.key, text);
            }
        } finally {
            List<WeakReference<Callback>> callbacks;
            synchronized (LOCK) {
                pending.remove(work);
                callbacks = new ArrayList<>(work.callbacks);
            }
            if (result != null) {
                Snapshot snapshot = result;
                latest = snapshot;
                MAIN.post(
                        () -> {
                            for (WeakReference<Callback> ref : callbacks) {
                                Callback callback = ref.get();
                                if (callback != null) callback.ready(snapshot);
                            }
                        });
            }
        }
    }
}
