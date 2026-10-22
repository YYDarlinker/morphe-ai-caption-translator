package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one player authority: owner identity, player type and display permission.
 *
 * <p>Caption render revisions only invalidate drawing; the real player type is owned here and is
 * never filtered by a render identity. A caption clear, an AI toggle, a style change or a relayout
 * must not be able to discard a valid player notification.
 *
 * <p>States: {@link #UNKNOWN} (no proven owner or no authoritative type yet, never permission to
 * display), {@link #COMPACT} (miniplayer, PiP or hidden: display refused), {@link
 * #TRANSITIONING_TO_REGULAR} (compact ended, geometry not settled: display refused) and {@link
 * #REGULAR} (authoritative non-compact type on a valid owner: the only state that permits display).
 * {@link #CLOSED} means the owner or activity is gone.
 */
final class CaptionPlayerAuthority {
    static final int UNKNOWN = 0,
            COMPACT = 1,
            TRANSITIONING_TO_REGULAR = 2,
            REGULAR = 3,
            CLOSED = 4;

    interface Listener {
        /** Called on the main thread after the state or owner changed. */
        void onAuthorityChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static String video = "";
    private static volatile long ownerEpoch;
    private static long notificationSequence;
    private static int state = UNKNOWN;
    private static String playerType = "";
    private static boolean typeAuthoritative;

    /** Set by a real notification so the next surface pass may search despite its throttle. */
    private static boolean surfaceDirty;

    private CaptionPlayerAuthority() {}

    static long ownerEpoch() {
        return ownerEpoch;
    }

    static String playerType() {
        return playerType;
    }

    static int state() {
        return state;
    }

    static String stateName() {
        switch (state) {
            case COMPACT:
                return "COMPACT";
            case TRANSITIONING_TO_REGULAR:
                return "TRANSITIONING_TO_REGULAR";
            case REGULAR:
                return "REGULAR";
            case CLOSED:
                return "CLOSED";
            default:
                return "UNKNOWN";
        }
    }

    static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    static Activity activity() {
        return activity.get();
    }

    /** The decor view of the current owner, or null once the owner is not displayable. */
    static View decorView() {
        Activity current = activity.get();
        if (current == null || current.isFinishing() || current.getWindow() == null) return null;
        return current.getWindow().getDecorView();
    }

    static boolean ownerValid() {
        Activity current = activity.get();
        return current != null
                && !current.isFinishing()
                && !current.isDestroyed()
                && current.getWindow() != null;
    }

    /** True only for a proven non-compact player whose geometry was confirmed on this owner. */
    static boolean displayPermitted() {
        return state == REGULAR && ownerValid();
    }

    static boolean hasAuthoritativeType() {
        return typeAuthoritative && !playerType.isEmpty();
    }

    /** Bounded, non-sensitive identity for diagnostics. */
    static String shortId() {
        Activity current = activity.get();
        return "activity="
                + (current == null ? "none" : Integer.toHexString(System.identityHashCode(current)))
                + ";video="
                + (video.isEmpty() ? "none" : video)
                + ";authority_epoch="
                + ownerEpoch
                + ";notification_seq="
                + notificationSequence;
    }

    // ---- Owner and video ----

    /** Runs [again] on the main thread instead; true when the caller must return. */
    private static boolean toMain(Runnable again) {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        MAIN.post(again);
        return true;
    }

    /** A new owner or video starts in [next] with no inherited type. */
    private static void restart(int next) {
        state = next;
        playerType = "";
        typeAuthoritative = false;
        surfaceDirty = false;
    }

    static void setOwner(Activity next) {
        if (toMain(() -> setOwner(next))) return;
        if (activity.get() == next) {
            // Re-declaring the same owner after a close re-opens it as unproven: a new session on
            // the same Activity starts from UNKNOWN instead of staying closed.
            if (state == CLOSED && next != null) {
                restart(UNKNOWN);
                notifyListeners();
            }
            return;
        }
        activity = new WeakReference<>(next);
        ownerEpoch++;
        restart(next == null ? CLOSED : UNKNOWN);
        notifyListeners();
    }

    static void setVideo(String id) {
        if (toMain(() -> setVideo(id))) return;
        String next = id == null ? "" : id.trim();
        if (next.equals(video)) return;
        video = next;
        ownerEpoch++;
        restart(UNKNOWN);
        notifyListeners();
    }

    static void close() {
        if (toMain(CaptionPlayerAuthority::close)) return;
        restart(CLOSED);
        // A closed engine invalidates every notification queued before it, so a stale off-main
        // callback cannot reopen a new owner's quarantine.
        ownerEpoch++;
        notifyListeners();
    }

    /**
     * Consumes the "a real player notification arrived" flag. Exactly one surface search is then
     * allowed even inside the ordinary throttle window.
     */
    static boolean consumeSurfaceDirty() {
        boolean owed = surfaceDirty;
        surfaceDirty = false;
        return owed;
    }

    // ---- Player type ----

    private static String normalized(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * One player notification, on the main thread. It never scans a tree, measures, calls the
     * network or reads the keystore: it only merges the authoritative type and updates the state.
     */
    static void onNotification(String rawType) {
        if (toMain(() -> onNotification(rawType))) return;
        String type = normalized(rawType);
        notificationSequence++;
        surfaceDirty = true;
        String previous = playerType;
        if (!type.isEmpty()) {
            playerType = type;
            typeAuthoritative = true;
        }
        int previousState = state;
        applyState();
        // A real type change inside REGULAR (fullscreen <-> detail) keeps the state value but is
        // still a genuine mode change; an identical type on a settled owner stays silent.
        if (state == previousState && state == REGULAR && !type.isEmpty() && !type.equals(previous))
            notifyListeners();
    }

    /**
     * Reads the current player type on re-bind, resume or an explicit AI re-enable, so recovery
     * never waits for a type callback that may legitimately never repeat.
     */
    static String reconcileCurrentPlayerType() {
        String resolved = OfficialPlayerTypeReader.read();
        if (!resolved.isEmpty()) onNotification(resolved);
        return resolved;
    }

    private static void moveTo(int next) {
        int previous = state;
        state = next;
        if (previous != next) notifyListeners();
    }

    private static void applyState() {
        if (!ownerValid()) moveTo(CLOSED);
        else if (!typeAuthoritative) moveTo(UNKNOWN);
        else if (isCompactType(playerType)
                && !CaptionSurface.isShorts()
                && !("NONE".equals(playerType) && CaptionSurface.shortsSurfaceNow()))
            // A verified Shorts surface is never the compact miniplayer state.
            moveTo(COMPACT);
        else if (state == COMPACT || state == TRANSITIONING_TO_REGULAR)
            moveTo(TRANSITIONING_TO_REGULAR);
        else moveTo(REGULAR);
    }

    /**
     * A verified Shorts surface is its own display permission: Shorts reports an inner player type
     * and may never deliver the regular one, but it was never compact in the first place.
     */
    static void noteShortsSurface() {
        if (!ownerValid()) {
            close();
            return;
        }
        typeAuthoritative = true;
        moveTo(REGULAR);
    }

    /**
     * A Short that looped or was entered again can leave the authority compact (type NONE with a
     * stale surface). Nothing renders while compact, so the engine tick asks here.
     */
    static boolean recoverShorts() {
        if (state != COMPACT || !"NONE".equals(playerType) || !CaptionSurface.shortsSurfaceNow())
            return false;
        noteShortsSurface();
        return state == REGULAR;
    }

    /**
     * Called by the transition coordinator once geometry settled on a valid, non-compact surface.
     */
    static void settleRegular() {
        if (!ownerValid()) close();
        else if (!isCompactType(playerType)) moveTo(REGULAR);
    }

    /**
     * The official enum has two different meanings that share a word: a player that <em>is</em>
     * minimized (miniplayer, PiP, hidden) and a regular player that is <em>sliding</em> between
     * sizes. The sliding types are animation states of the regular player; treating them as compact
     * retracts the caption in the middle of YouTube's own detail &lt;-&gt; fullscreen drag and leaves
     * it hidden when the gesture is cancelled. Only the exact non-playing states are compact.
     */
    static boolean isCompactType(String rawType) {
        switch (normalized(rawType)) {
            case "NONE":
            case "HIDDEN":
            case "WATCH_WHILE_MINIMIZED":
            case "WATCH_WHILE_PICTURE_IN_PICTURE":
            case "WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED":
            case "INLINE_MINIMAL":
                return true;
            default:
                // WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED and WATCH_WHILE_SLIDING_MAXIMIZED_
                // FULLSCREEN are regular-player transitions, not the compact state.
                return false;
        }
    }

    private static void notifyListeners() {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        for (Listener listener : listeners) {
            try {
                listener.onAuthorityChanged();
            } catch (RuntimeException ignored) {
                // One broken listener must not starve the others.
            }
        }
    }

    static void resetForTests() {
        listeners.clear();
        activity = new WeakReference<>(null);
        video = "";
        ownerEpoch = 0;
        notificationSequence = 0;
        restart(UNKNOWN);
        OfficialPlayerTypeReader.resetForTests();
    }

    /**
     * Read-only adapter for the official public player-type surface: the Kotlin companion accessor
     * is a public static method in the shipped extension library, so nothing official is modified.
     */
    static final class OfficialPlayerTypeReader {
        private static final String PLAYER_TYPE = "app.morphe.extension.youtube.shared.PlayerType";
        private static volatile boolean resolved;
        private static Method accessor;
        private static Field field;

        private OfficialPlayerTypeReader() {}

        static String read() {
            resolve();
            try {
                Object value = accessor == null ? null : accessor.invoke(null);
                if (value == null && field != null) value = field.get(null);
                if (value == null) return "";
                return normalized(
                        value instanceof Enum ? ((Enum<?>) value).name() : value.toString());
            } catch (Throwable failure) {
                return "";
            }
        }

        static void resetForTests() {
            resolved = false;
            accessor = null;
            field = null;
        }

        private static synchronized void resolve() {
            if (resolved) return;
            resolved = true;
            try {
                Class<?> type = Class.forName(PLAYER_TYPE);
                accessor = publicStatic(type, "access$getCurrentPlayerType$cp");
                if (accessor == null) accessor = publicStatic(type, "getCurrentPlayerType");
                if (accessor == null) {
                    field = type.getDeclaredField("currentPlayerType");
                    field.setAccessible(true);
                }
            } catch (Throwable unavailable) {
                field = null; // Not the official bundle, or a version without the accessor.
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
}
