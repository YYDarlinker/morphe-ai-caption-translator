package app.yydarlinker.deepseekcaptions;

import android.media.session.PlaybackState;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/** Read-only adapter for the public Morphe/YouTube player time surface. */
final class OfficialPlayerClockAdapter {
    private static final String VIDEO_INFORMATION =
            "app.morphe.extension.youtube.patches.VideoInformation";
    private static final String VIDEO_STATE = "app.morphe.extension.youtube.shared.VideoState";

    /** A time that stops moving for this long is paused, not a coarse getter repeating itself. */
    private static final long STILL_MS = 500;

    private static volatile boolean resolved;
    private static Method getVideoTime, getVideoId, getPlaybackSpeed, getCurrent;

    private static String movedVideo = "";
    private static long movedPosition, movedAt;
    private static boolean advancing;

    private OfficialPlayerClockAdapter() {}

    /** The player clock if it belongs to [expectedVideo], else an unavailable sample. */
    static TranslationRuntime.ClockSample read(String expectedVideo) {
        long at = SystemClock.elapsedRealtime();
        resolve();
        if (getVideoTime == null) return unavailable(at);
        try {
            Object rawId = getVideoId == null ? null : getVideoId.invoke(null);
            String id = rawId == null ? "" : rawId.toString();
            if (expectedVideo == null || expectedVideo.isEmpty() || !expectedVideo.equals(id))
                return unavailable(at);
            Object time = getVideoTime.invoke(null);
            if (!(time instanceof Number) || ((Number) time).longValue() < 0)
                return unavailable(at);
            long position = ((Number) time).longValue();
            Object rate = getPlaybackSpeed == null ? null : getPlaybackSpeed.invoke(null);
            float speed = rate instanceof Number ? ((Number) rate).floatValue() : 1f;
            Object current = getCurrent == null ? null : getCurrent.invoke(null);
            int state =
                    current == null ? PlaybackState.STATE_PLAYING : mapState(current.toString());
            if (!Float.isFinite(speed) || speed <= 0 || speed > 4) return unavailable(at);
            return new TranslationRuntime.ClockSample(
                    true, id, position, at, observedState(id, position, at, speed, state), speed);
        } catch (Throwable failure) {
            return unavailable(at);
        }
    }

    private static TranslationRuntime.ClockSample unavailable(long at) {
        return new TranslationRuntime.ClockSample(false, "", -1, at, PlaybackState.STATE_NONE, 0);
    }

    /**
     * VideoState follows the regular watch player. In Shorts it can stay PAUSED while the Short
     * plays, which made every sample look like a paused seek. A "paused" time that keeps advancing
     * at the playback rate is playing; a paused jump (a real seek) or a time that stops moving
     * stays paused. The reference is the last sample whose time moved, so a coarse getter repeating
     * one value between updates is neither a pause nor a seek.
     */
    static synchronized int observedState(
            String video, long position, long at, float speed, int state) {
        long elapsed = at - movedAt, moved = position - movedPosition;
        boolean same = video.equals(movedVideo) && elapsed >= 0 && elapsed <= 1500;
        if (!same || moved != 0) {
            advancing =
                    same
                            && moved > 0
                            && Math.abs(moved - elapsed * speed)
                                    <= Math.max(120, elapsed * speed / 2);
            movedVideo = video;
            movedPosition = position;
            movedAt = at;
        } else if (elapsed > STILL_MS) advancing = false;
        return state == PlaybackState.STATE_PAUSED && advancing
                ? PlaybackState.STATE_PLAYING
                : state;
    }

    private static int mapState(String value) {
        String name = value.toUpperCase(Locale.ROOT);
        if (name.contains("PAUSED")) return PlaybackState.STATE_PAUSED;
        if (name.contains("BUFFER")) return PlaybackState.STATE_BUFFERING;
        if (name.contains("ENDED")) return PlaybackState.STATE_STOPPED;
        if (name.contains("NEW") || name.contains("ERROR")) return PlaybackState.STATE_NONE;
        return PlaybackState.STATE_PLAYING;
    }

    static synchronized void resetForTests() {
        resolved = false;
        getVideoTime = getVideoId = getPlaybackSpeed = getCurrent = null;
        movedVideo = "";
        movedPosition = movedAt = 0;
        advancing = false;
    }

    private static void resolve() {
        if (resolved) return;
        resolveOnce();
    }

    private static synchronized void resolveOnce() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> information = Class.forName(VIDEO_INFORMATION);
            getVideoTime = publicStatic(information, "getVideoTime");
            getVideoId = publicStatic(information, "getVideoId");
            getPlaybackSpeed = publicStatic(information, "getPlaybackSpeed");
        } catch (Throwable notOfficial) {
            // Without the official bundle there is no clock; read() reports it unavailable.
        }
        try {
            getCurrent = publicStatic(Class.forName(VIDEO_STATE), "getCurrent");
        } catch (Throwable noState) {
            // The state is optional: playing is assumed.
        }
    }

    private static Method publicStatic(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            return Modifier.isStatic(method.getModifiers()) ? method : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
