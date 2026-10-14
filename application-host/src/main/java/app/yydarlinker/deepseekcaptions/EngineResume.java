package app.yydarlinker.deepseekcaptions;

import android.os.SystemClock;

/**
 * Remembers the AI session of the foreground video so it can come back when YouTube shows that same
 * video again without announcing a caption track (a Short that was left and entered once more).
 *
 * <p>The session is only brought back when the user did not turn captions off: the owner is armed
 * by a departure (the video id was cleared, or YouTube reset its caption model on its own), and
 * disarmed by any activation, by an explicit off, and by every other video.
 */
final class EngineResume {
    /** Player time ticks that resume after this much silence mean the video was shown again. */
    private static final long GAP_MS = 1200;

    /** A stop this soon after an automatic reset is part of that reset, not the user's. */
    private static final long AUTOMATIC_MS = 500;

    /** How to start a remembered session again. */
    static final class Session {
        final String url, video;
        final boolean sourceOnly;

        Session(String url, String video, boolean sourceOnly) {
            this.url = url;
            this.video = video;
            this.sourceOnly = sourceOnly;
        }
    }

    private static String owner = "", url = "";
    private static boolean sourceOnly, intent, armed;
    private static long lastTick, automaticAt;

    private EngineResume() {}

    /** An AI session was started for a video: remember how to start it again. */
    static synchronized void started(String video, String sessionUrl, boolean original) {
        if (video == null || video.isEmpty()) return;
        owner = video;
        url = sessionUrl;
        sourceOnly = original;
        intent = true;
        armed = false;
    }

    /** The user turned captions off (button, native menu): do not bring the session back. */
    static synchronized void declined() {
        intent = false;
        armed = false;
    }

    /** The video left the screen or YouTube reset its captions: the session may return with it. */
    static synchronized void departed() {
        armed = intent && !owner.isEmpty();
    }

    /**
     * YouTube reset its caption model on its own; the stop that follows at once is not the user's.
     */
    static synchronized void automaticReset() {
        departed();
        automaticAt = SystemClock.elapsedRealtime();
    }

    /** True once if the stop being handled follows an automatic reset. */
    static synchronized boolean takeAutomatic() {
        boolean automatic =
                automaticAt != 0 && SystemClock.elapsedRealtime() - automaticAt < AUTOMATIC_MS;
        automaticAt = 0;
        return automatic;
    }

    /** Any other video makes the old memory meaningless. */
    static synchronized void video(String id) {
        if (id != null && !id.isEmpty() && !id.equals(owner)) {
            owner = url = "";
            intent = armed = false;
        }
    }

    /** True when player ticks resume after a silence long enough to be a new showing. */
    static synchronized boolean reappeared(long now) {
        boolean gap = lastTick != 0 && now - lastTick >= GAP_MS;
        lastTick = now;
        return gap;
    }

    static synchronized void reset() {
        owner = url = "";
        intent = armed = false;
        lastTick = automaticAt = 0;
    }

    /** The session to start for [video] (any when unknown), once; null if none waits to return. */
    static synchronized Session take(String video) {
        if (!armed || !intent || owner.isEmpty() || (!video.isEmpty() && !owner.equals(video)))
            return null;
        armed = false;
        return new Session(url, owner, sourceOnly);
    }
}
