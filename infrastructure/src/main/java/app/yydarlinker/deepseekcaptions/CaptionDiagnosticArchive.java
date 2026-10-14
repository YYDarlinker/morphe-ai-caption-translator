package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The private journal of the diagnostics: a few channels of rotating log files, and one slot with
 * the latest record. All disk access runs on one lane, so no playback thread waits for it.
 */
final class CaptionDiagnosticArchive {
    static final int SEGMENT_BYTES = 256 * 1024, SEGMENTS = 32;
    static final long RETENTION_MS = 24 * 60 * 60 * 1000L;
    private static final int RECORD_CHARS = 60_000;
    private static final String ROOT = "caption-diagnostics-r25/";
    private static final String[] CHANNELS = {"history", "quality", "timing", "batch", "summary"};

    /** Hard-bounded, so a burst of records cannot grow memory without limit. */
    private static final ThreadPoolExecutor IO =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(4096),
                    runnable -> {
                        Thread thread = new Thread(runnable, "caption-diagnostics");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static final AtomicLong dropped = new AtomicLong();

    /** Work queued before a clear is discarded: its epoch is no longer the current one. */
    private static final AtomicLong clearEpoch = new AtomicLong();

    static {
        IO.allowCoreThreadTimeOut(true);
    }

    private CaptionDiagnosticArchive() {}

    static long epoch() {
        return clearEpoch.get();
    }

    private static File dir(Context context, String channel) {
        return new File(context.getFilesDir(), ROOT + channel);
    }

    private static String capped(String value) {
        return value.length() > RECORD_CHARS
                ? value.substring(0, RECORD_CHARS)
                        + " [record truncated at "
                        + RECORD_CHARS
                        + " chars]"
                : value;
    }

    private static void submit(long generation, Runnable work) {
        try {
            IO.execute(
                    () -> {
                        if (generation == clearEpoch.get()) work.run();
                    });
        } catch (RejectedExecutionException full) {
            dropped.incrementAndGet();
        }
    }

    static void append(Context context, String channel, String value) {
        appendAtEpoch(context, channel, value, clearEpoch.get());
    }

    static void appendAtEpoch(Context context, String channel, String value, long epoch) {
        File dir = dir(context, channel);
        String entry = capped(value);
        submit(
                epoch,
                () -> {
                    try {
                        appendNow(dir, entry);
                    } catch (IOException failed) {
                        dropped.incrementAndGet();
                    }
                });
    }

    private static File[] files(File dir) {
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".log"));
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    private static boolean expired(File file, long now) {
        return now - file.lastModified() > RETENTION_MS;
    }

    /** Appends to the newest segment of [dir], starting a new one when it is full. */
    static void appendNow(File dir, String value) throws IOException {
        dir.mkdirs();
        long now = System.currentTimeMillis();
        for (File file : files(dir)) if (expired(file, now)) file.delete();
        File[] all = files(dir);
        File last = all.length == 0 ? null : all[all.length - 1];
        byte[] bytes = (capped(value) + "\n").getBytes(StandardCharsets.UTF_8);
        if (last == null || last.length() + bytes.length > SEGMENT_BYTES) {
            long id =
                    last == null
                            ? now
                            : Math.max(now, Long.parseLong(last.getName().replace(".log", "")) + 1);
            last = new File(dir, String.format(Locale.ROOT, "%019d.log", id));
        }
        try (FileOutputStream out = new FileOutputStream(last, true)) {
            out.write(bytes);
        }
        all = files(dir);
        for (int i = 0; i < all.length - SEGMENTS; i++) all[i].delete();
    }

    /** Everything a channel kept, oldest first; waits for the records queued before the call. */
    static String read(Context context, String channel) {
        File dir = dir(context, channel);
        try {
            return IO.submit(() -> readNow(dir)).get(30, TimeUnit.SECONDS);
        } catch (Exception failed) {
            return "[archive read failed: " + failed.getClass().getSimpleName() + "]";
        }
    }

    private static String readNow(File dir) throws IOException {
        StringBuilder out = new StringBuilder();
        long now = System.currentTimeMillis();
        for (File file : files(dir)) {
            if (expired(file, now)) {
                file.delete();
                continue;
            }
            try (InputStream in = new FileInputStream(file)) {
                out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        if (dropped.get() > 0)
            out.append("\n[archive dropped records: ").append(dropped.get()).append("]\n");
        return out.toString();
    }

    static void clear(Context context) {
        long generation = clearEpoch.incrementAndGet();
        File root = new File(context.getFilesDir(), ROOT);
        submit(
                generation,
                () -> {
                    for (String channel : CHANNELS)
                        for (File file : files(new File(root, channel))) file.delete();
                    new AtomicFile(new File(root, "summary/latest.slot")).delete();
                    dropped.set(0);
                });
    }

    private static File slot(Context context) {
        return new File(dir(context, "summary"), "latest.slot");
    }

    /**
     * Replaces the latest-record slot: clearing epoch, stage, detail, time and the preserved
     * decision lines, separated by U+0001. The decisions are asked for on the lane, so they are
     * current when the slot is written.
     */
    static void appendSummary(
            Context context,
            String stage,
            String detail,
            long at,
            Supplier<String> decisions,
            long epoch) {
        File slot = slot(context);
        submit(
                clearEpoch.get(),
                () -> {
                    String entry =
                            epoch
                                    + "\u0001"
                                    + stage
                                    + "\u0001"
                                    + (detail == null ? "" : detail)
                                    + "\u0001"
                                    + at
                                    + "\u0001"
                                    + decisions.get();
                    slot.getParentFile().mkdirs();
                    AtomicFile atomic = new AtomicFile(slot);
                    FileOutputStream stream = null;
                    try {
                        stream = atomic.startWrite();
                        stream.write(entry.getBytes(StandardCharsets.UTF_8));
                        atomic.finishWrite(stream);
                    } catch (IOException failed) {
                        if (stream != null) atomic.failWrite(stream);
                        dropped.incrementAndGet();
                    }
                });
    }

    static void readSummaryAsync(Context context, long epoch, Consumer<String> callback) {
        File slot = slot(context);
        IO.execute(() -> callback.accept(readSummaryNow(slot, epoch)));
    }

    static String readSummary(Context context, long epoch) {
        File slot = slot(context);
        try {
            return IO.submit(() -> readSummaryNow(slot, epoch)).get(30, TimeUnit.SECONDS);
        } catch (Exception failed) {
            return null;
        }
    }

    /** The slot, if it belongs to the clearing epoch [epoch]. */
    private static String readSummaryNow(File slot, long epoch) {
        if (!slot.isFile() || slot.length() > 64_000) return null;
        try {
            String text = new String(new AtomicFile(slot).readFully(), StandardCharsets.UTF_8);
            return text.startsWith(epoch + "\u0001") ? text : null;
        } catch (IOException unreadable) {
            return null;
        }
    }
}
