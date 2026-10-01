package app.yydarlinker.deepseekcaptions;

import android.app.Activity;import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the caption overlay clear of the regular player's own controls.
 *
 * <p>This coordinator owns exactly two things: a temporary vertical offset for the caption's anchor
 * view, and the evidence trail for it. It never touches the caption's text, font, page plan,
 * horizontal position, saved position or layout budget, and it never writes a saved preference.</p>
 *
 * <p>The offset is applied as a {@code translationY} on the caption anchor, so the base layout the
 * overlay recomputes every frame and the temporary avoidance offset stay independent: re-rendering
 * the caption cannot zero an in-flight animation, and an in-flight offset cannot drift the base
 * position the drag gesture saves.</p>
 *
 * <p>Geometry comes from the delivered player itself. Controls are resolved by resource
 * <em>name</em> only inside the current player's own subtree — never by an Activity-wide scan, and
 * never from a hardcoded {@code 0x7f} number. The visibility enum YouTube publishes is a re-check
 * trigger only; whether a control is really in the way is decided by that control's own
 * attached/visible/alpha/clipped rectangle inside the real video bounds.</p>
 */
final class CaptionControlsAvoidance {

    /** Vertical gap kept between the caption box and the control it steps above. */
    private static final int GAP_DP = 8;
    /** Temporary offset animation length. */
    private static final long ANIMATION_MS = 200L;
    /** Bind/rebind cadence on the overlay's existing low-frequency refresh path. */
    private static final long REBIND_INTERVAL_MS = 500L;
    /** A control faded below this effective alpha is not treated as being on screen. */
    private static final float MIN_CONTROL_ALPHA = 0.15f;
    /** A bottom cluster thinner than this is the collapsed progress line, not a control bar. */
    private static final int MIN_BOTTOM_CLUSTER_HEIGHT_DP = 12;
    /** A rectangle taller than this share of the video is a container, not an operation area. */
    private static final float MAX_OBSTACLE_VIDEO_RATIO = 0.6f;
    /** Operation areas thinner than this are decorative, not tappable controls. */
    private static final int MIN_CONTROL_HEIGHT_DP = 8;
    private static final String VISIBILITY_PREFIX = "PLAYER_CONTROLS_VISIBILITY_";

    private static final String BOTTOM_CONTAINER = "youtube_controls_bottom_ui_container";
    private static final String[] BOTTOM_CLUSTER = {
        BOTTOM_CONTAINER,
        "player_seekbar",
        "timestamps_container",
        "time_bar_current_time",
        "time_bar_total_time",
        "time_bar_live_label",
        "time_bar_chapter_title_container",
        "bottom_end_container",
    };
    private static final String[] CENTRE_CLUSTER = {
        "player_control_play_pause_button",
        "player_control_play_pause_replay_button",
        "player_control_previous_button",
        "player_control_next_button",
    };
    /**
     * Touch areas and wrappers count only when they are the real operation area: a wrapper whose own
     * visible rectangle exists is exactly what the user touches.
     */
    private static final String[] WRAPPER_CONTROLS = {
        "player_control_play_pause_replay_button_touch_area",
        "player_control_previous_button_touch_area",
        "player_control_next_button_touch_area",
        "player_control_button_wrapper",
    };

    /** Resolves a control id by name; replaced only by offline verification. */
    interface IdLookup {
        int id(Activity activity, String name);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, WeakReference<View>> BOUND = new HashMap<>();
    private static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator();
    private static final long FRAME_MS = 16L;
    private static final Runnable ANIMATION_STEP = CaptionControlsAvoidance::stepAnimation;

    private static volatile IdLookup idLookup;
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static WeakReference<FrameLayout> hostRef = new WeakReference<>(null);
    private static WeakReference<FrameLayout> anchorRef = new WeakReference<>(null);
    private static WeakReference<TextView> textRef = new WeakReference<>(null);
    private static WeakReference<View> playerRef = new WeakReference<>(null);
    private static String boundNames = "";

    private static long epoch;
    private static String playerType = "";
    private static String controlsState = "UNKNOWN";
    private static boolean dragging;
    private static boolean rebindRequested = true;
    private static Rect baseRect;
    private static Rect videoRect;
    private static List<Rect> obstacles = Collections.emptyList();
    private static int targetOffsetPx;
    private static boolean animationRunning;
    private static long animationStarted;
    private static float animationFrom;
    private static float animationTo;
    private static long signalsApplied;    private static long lastBind;
    private static String lastBoundKey = "";
    private static String lastTargetKey = "";
    private static String lastResetKey = "";
    private static String lastUnavailableKey = "";

    private CaptionControlsAvoidance() {}

    // ---- inputs ---------------------------------------------------------------------------------

    /**
     * Called from the entity-model constructor, on whatever thread YouTube builds it. The argument is
     * an already-initialised instance of YouTube's own visibility type, or {@code null} when the
     * model carries no state at all; null means the same thing YouTube's own reader means by it.
     */
    static void onVisibilitySignal(Object state) {
        controlsState = stateName(state);
        rebindRequested = true;
        final long postedEpoch = epoch;
        MAIN.post(
            () -> {
                // A queued callback belongs to the Activity/video/player mode it was created in.
                if (postedEpoch != epoch) return;
                signalsApplied++;
                evaluate("controls_" + controlsState.toLowerCase(Locale.ROOT));
            });
    }

    static void onActivity(Activity activity) {
        if (activityRef.get() == activity) return;
        activityRef = new WeakReference<>(activity);
        hostRef = new WeakReference<>(null);
        anchorRef = new WeakReference<>(null);
        textRef = new WeakReference<>(null);
        bumpEpoch("activity_changed");
    }

    static void onPlayerType(String type) {
        String next = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (next.equals(playerType)) return;
        playerType = next;
        bumpEpoch("player_type_changed");
    }

    static void onVideoId(String videoId) {
        bumpEpoch("video_changed");
    }

    /**
     * The overlay has placed the caption's base layout for this frame. {@code base} is the caption
     * box the overlay is applying right now - the real TextView with its background and padding,
     * centred in its anchor - not a laid-out position that may still belong to the previous frame.
     * Keeping it as the one source of truth for the base is what makes a rotation or a video change
     * measure against the new geometry immediately, and what stops the base from ever absorbing the
     * temporary offset.
     */
    static void onCaptionPlaced(
        FrameLayout host, FrameLayout anchor, TextView text, Rect video, Rect base) {
        hostRef = new WeakReference<>(host);
        anchorRef = new WeakReference<>(anchor);
        textRef = new WeakReference<>(text);
        if (video != null && !video.isEmpty()) {
            boolean moved = videoRect != null && !videoRect.equals(video);
            videoRect = new Rect(video);
            if (moved) {
                // A new video rectangle invalidates the previous direction of travel.
                cancelAnimation();
                targetOffsetPx = 0;
            }
        }
        if (base != null && !base.isEmpty()) baseRect = new Rect(base);
        if (dragging) return;
        evaluate("caption_placed");
    }

    /** The overlay hid, blanked, detached or dropped the caption: no offset may survive. */
    static void onCaptionCleared(String reason) {
        hostRef = new WeakReference<>(null);
        anchorRef = new WeakReference<>(null);
        textRef = new WeakReference<>(null);
        release(reason);
    }

    /**
     * The overlay's existing ~10 Hz refresh path is the only periodic entry point: control lookup is
     * rate limited here, and view discovery never happens per frame.
     */
    static void tick() {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        if (hostRef.get() == null || anchorRef.get() == null || textRef.get() == null) return;
        long now = SystemClock.uptimeMillis();
        if (rebindRequested || now - lastBind >= REBIND_INTERVAL_MS) ensureBound(now);
        evaluate("refresh");
    }

    static void onDragStart() {
        dragging = true;
        // The animation stops where it is: the text keeps the offset it had when the finger landed.
        cancelAnimation();
    }

    static void onDragEnd() {
        dragging = false;
        evaluate("drag_finished");
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    private static void bumpEpoch(String reason) {
        epoch++;
        rebindRequested = true;
        release(reason);
    }

    private static void release(String reason) {
        cancelAnimation();
        FrameLayout anchor = anchorRef.get();
        if (anchor != null) anchor.setTranslationY(0f);
        targetOffsetPx = 0;
        baseRect = null;
        videoRect = null;
        obstacles = Collections.emptyList();
        lastBoundKey = "";
        lastTargetKey = "";
        markReset(reason);
    }

    // ---- binding --------------------------------------------------------------------------------

    private static void ensureBound(long now) {
        lastBind = now;
        rebindRequested = false;
        Activity activity = activityRef.get();
        if (activity == null) return;
        View player = null;
        try {
            player = CaptionSurface.player();
        } catch (Throwable ignored) {
        }
        if (player == null) {
            BOUND.clear();
            boundNames = "";
            playerRef = new WeakReference<>(null);
            markUnavailable(activity, "player_not_bound");
            return;
        }
        // A full, name-based re-scan of the current player's own subtree. Callers rate limit it to the
        // overlay's low-frequency refresh path plus the visibility triggers, so a control that
        // inflates late, is rebuilt, or leaves the tree is picked up without an unbounded timer.
        playerRef = new WeakReference<>(player);
        BOUND.clear();
        StringBuilder names = new StringBuilder();
        if (player != null) {
            for (String name : allControlNames()) {
                int id = resolveId(activity, name);
                if (id == 0) continue;
                View view = null;
                try {
                    view = player.findViewById(id);
                } catch (Throwable ignored) {
                }
                if (view == null) continue;
                BOUND.put(name, new WeakReference<>(view));
                if (names.length() > 0) names.append(',');
                names.append(name);
            }
        }
        boundNames = names.toString();
        String boundKey = epoch + "|" + playerType + "|" + boundNames;
        if (!boundKey.equals(lastBoundKey)) {
            lastBoundKey = boundKey;
            if (boundNames.isEmpty()) {
                markUnavailable(activity, player == null ? "player_not_bound" : "controls_not_bound");
            } else {
                mark(
                    activity,
                    "CAPTION_UI_AVOIDANCE_BOUND",
                    "epoch=" + epoch
                        + ";player_type=" + playerTypeValue()
                        + ";controls_state=" + controlsState
                        + ";obstacle_ids=" + boundNames
                        + ";reason=bound");
            }
        }
    }

    private static boolean allAttached() {
        for (WeakReference<View> ref : BOUND.values()) {
            View view = ref.get();
            if (view == null || !view.isAttachedToWindow()) return false;
        }
        return true;
    }

    /** True when every cached control is still attached to the current player. */
    static boolean boundAndAttached() {
        return !BOUND.isEmpty() && allAttached();
    }

    private static String[] allControlNames() {
        String[] names =
            new String[BOTTOM_CLUSTER.length + CENTRE_CLUSTER.length + WRAPPER_CONTROLS.length];
        int at = 0;
        for (String name : BOTTOM_CLUSTER) names[at++] = name;
        for (String name : CENTRE_CLUSTER) names[at++] = name;
        for (String name : WRAPPER_CONTROLS) names[at++] = name;
        return names;
    }

    static int resolveId(Activity activity, String name) {
        IdLookup custom = idLookup;
        if (custom != null) {
            try {
                return custom.id(activity, name);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        if (activity == null) return 0;
        try {
            return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Offline verification seam; production always resolves ids by name through the host. */
    static void setIdLookup(IdLookup lookup) {
        idLookup = lookup;
        BOUND.clear();
        playerRef = new WeakReference<>(null);
        boundNames = "";
        lastBoundKey = "";
        lastUnavailableKey = "";
        rebindRequested = true;
    }

    private static View lookup(String name) {
        WeakReference<View> ref = BOUND.get(name);
        return ref == null ? null : ref.get();
    }

    // ---- geometry and algorithm -----------------------------------------------------------------

    private static void evaluate(String trigger) {
        Activity activity = activityRef.get();
        FrameLayout host = hostRef.get();
        FrameLayout anchor = anchorRef.get();
        TextView text = textRef.get();
        if (activity == null || host == null || anchor == null || text == null) return;
        if (dragging) return;
        if (!regularPlayerActive()) {
            reset(activity, "player_not_regular", trigger);
            return;
        }
        // Without a committed video rectangle there is nothing to avoid against; it comes back on
        // the overlay's next placement, which always follows a video or orientation change.
        if (videoRect == null) return;
        if (baseRect == null || baseRect.isEmpty() || videoRect.width() < 50
            || videoRect.height() < 50) {
            reset(activity, "caption_hidden", trigger);
            return;
        }
        if (BOUND.isEmpty()) {
            markUnavailable(activity,
                CaptionSurface.player() == null ? "player_not_bound" : "controls_not_bound");
            reset(activity, "controls_not_bound", trigger);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (rebindRequested || now - lastBind >= REBIND_INTERVAL_MS) ensureBound(now);
        List<Rect> found = new ArrayList<>();
        Collect notes = new Collect();
        collect(host, found, notes);
        obstacles = merge(found);
        if (notes.thinLine && obstacles.isEmpty()) {
            reset(activity, "thin_progress_line_only", trigger);
            return;
        }
        if (obstacles.isEmpty()) {
            String reason = notes.oversized
                ? "oversized_container_excluded"
                : notes.visible == 0 ? "controls_not_visible" : "no_intersection";
            reset(activity, reason, trigger);
            return;
        }
        int offset = solve(baseRect, videoRect, obstacles);
        if (offset < 0) {
            target(activity, 0, "no_safe_space", trigger);
            return;
        }
        String reason = offset == 0
            ? (notes.thinLine ? "thin_progress_line_only"
                : notes.oversized ? "oversized_container_excluded" : "no_intersection")
            : "bottom_cluster";
        target(activity, offset, reason, trigger);
    }

    private static final class Collect {
        boolean thinLine;
        boolean oversized;
        /** How many bound controls passed the attached/visible/alpha/clipped test at all. */
        int visible;
    }

    private static void collect(View host, List<Rect> out, Collect notes) {
        View bottomContainer = lookup(BOTTOM_CONTAINER);
        if (visibleEnough(bottomContainer)) {
            notes.visible++;
            List<Rect> cluster = new ArrayList<>();
            for (String name : BOTTOM_CLUSTER) {
                View view = lookup(name);
                if (visibleEnough(view)) notes.visible++;
                Rect rect = visibleEnough(view) ? rectInHost(view, host, notes) : null;
                if (rect != null) cluster.add(rect);
            }
            for (Rect rect : merge(cluster)) {
                if (rect.height() >= dp(MIN_BOTTOM_CLUSTER_HEIGHT_DP)) {
                    out.add(rect);
                } else {
                    // The collapsed progress line left behind when the controls are hidden.
                    notes.thinLine = true;
                }
            }
        } else {
            // Controls hidden, but the progress line can survive on its own. It is not a control.
            View seeker = lookup("player_seekbar");
            if (visibleEnough(seeker)) notes.visible++;
            Rect line = visibleEnough(seeker) ? rectInHost(seeker, host, notes) : null;
            if (line != null && line.height() < dp(MIN_BOTTOM_CLUSTER_HEIGHT_DP)) {
                notes.thinLine = true;
            }
        }
        for (String name : CENTRE_CLUSTER) {
            View view = lookup(name);
            if (visibleEnough(view)) notes.visible++;
            Rect rect = visibleEnough(view) ? rectInHost(view, host, notes) : null;
            if (rect != null && rect.height() >= dp(MIN_CONTROL_HEIGHT_DP)) out.add(rect);
        }
        for (String name : WRAPPER_CONTROLS) {
            View view = lookup(name);
            if (visibleEnough(view)) notes.visible++;
            Rect rect = visibleEnough(view) ? rectInHost(view, host, notes) : null;
            if (rect != null && rect.height() >= dp(MIN_CONTROL_HEIGHT_DP)) out.add(rect);
        }
    }

    /**
     * Smallest lift that clears every obstacle the caption currently touches, repeated while the
     * lifted box meets a higher control. Iterations are bounded by the number of obstacles, so the
     * search always terminates and never oscillates. A result that cannot stay inside the video is
     * reported as no safe space and the caption keeps its own position: text is never shrunk and
     * never pushed outside the video.
     */
    private static int solve(Rect base, Rect video, List<Rect> obstacles) {
        int gap = dp(GAP_DP);
        int offset = 0;
        for (int guard = 0; guard <= obstacles.size(); guard++) {
            Rect moved = new Rect(base);
            moved.offset(0, -offset);
            int lift = 0;
            for (Rect obstacle : obstacles) {
                if (!Rect.intersects(moved, obstacle)) continue;
                lift = Math.max(lift, moved.bottom + gap - obstacle.top);
            }
            if (lift <= 0) break;
            offset += lift;
            Rect lifted = new Rect(base);
            lifted.offset(0, -offset);
            if (lifted.top < video.top) return -1;
            if (guard == obstacles.size()) return -1;
        }
        if (offset == 0) return 0;
        Rect result = new Rect(base);
        result.offset(0, -offset);
        return video.contains(result) ? offset : -1;
    }

    private static List<Rect> merge(List<Rect> input) {
        List<Rect> out = new ArrayList<>();
        for (Rect rect : input) {
            Rect current = new Rect(rect);
            boolean joined = true;
            while (joined) {
                joined = false;
                for (int i = 0; i < out.size(); i++) {
                    if (Rect.intersects(out.get(i), current)) {
                        current.union(out.remove(i));
                        joined = true;
                        break;
                    }
                }
            }
            out.add(current);
        }
        return out;
    }

    private static boolean visibleEnough(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) return false;
        float alpha = 1f;
        View current = view;
        while (current != null) {
            if (current.getVisibility() != View.VISIBLE) return false;
            alpha *= current.getAlpha();
            if (alpha < MIN_CONTROL_ALPHA) return false;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        Rect rect = new Rect();
        return view.getGlobalVisibleRect(rect) && !rect.isEmpty();
    }

    private static Rect rectInHost(View view, View host, Collect notes) {
        if (view == null || host == null) return null;
        Rect rect = new Rect();
        if (!view.getGlobalVisibleRect(rect) || rect.isEmpty()) return null;
        int[] origin = new int[2];
        host.getLocationOnScreen(origin);
        rect.offset(-origin[0], -origin[1]);
        Rect video = videoRect;
        if (video != null) {
            if (!rect.intersect(video)) return null;
            if (rect.height() > video.height() * MAX_OBSTACLE_VIDEO_RATIO
                && rect.width() > video.width() * MAX_OBSTACLE_VIDEO_RATIO) {
                // A rectangle that large in both directions is a transparent container, never an
                // operation area.
                notes.oversized = true;
                return null;
            }
        }
        return rect;
    }

    private static boolean regularPlayerActive() {
        try {
            if (CaptionSurface.isShorts()) return false;
        } catch (Throwable ignored) {
        }
        String type = playerType;
        if (type.isEmpty()) return true;
        return !(type.contains("MINIM")
            || type.contains("HIDDEN")
            || type.contains("DISMISSED")
            || type.contains("PICTURE_IN_PICTURE")
            || type.contains("INLINE_")
            || type.contains("VIRTUAL_REALITY")
            || type.equals("NONE"));
    }

    // ---- offset and animation -------------------------------------------------------------------

    private static void target(Activity activity, int offset, String reason, String trigger) {
        FrameLayout anchor = anchorRef.get();
        if (anchor == null) return;
        String key = epoch + "|" + offset + "|" + reason;
        if (key.equals(lastTargetKey)) return;
        lastTargetKey = key;
        mark(
            activity,
            offset == 0 ? "CAPTION_UI_AVOIDANCE_RESET" : "CAPTION_UI_AVOIDANCE_TARGET",
            "epoch=" + epoch
                + ";player_type=" + playerTypeValue()
                + ";controls_state=" + controlsState
                + ";video_rect=" + rect(videoRect)
                + ";caption_base_rect=" + rect(baseRect)
                + ";obstacle_rects=" + rects(obstacles)
                + ";offset_px=" + offset
                + ";reason=" + reason
                + ";trigger=" + trigger);
        aim(anchor, offset);
    }

    /**
     * Re-aims from the current visual position. A target that is already being animated is never
     * restarted, so a stable obstacle does not produce a chain of restarted animations. The lift is
     * stored as a positive number of pixels; the visual offset it drives is negative, because the
     * caption moves up.
     */
    private static void aim(FrameLayout anchor, int offset) {
        float wanted = -offset;
        if (offset == targetOffsetPx) {
            if (animationRunning) return;
            if (Math.abs(anchor.getTranslationY() - wanted) < 1f) return;
        }
        targetOffsetPx = offset;
        startAnimation(anchor, wanted);
    }

    /**
     * The move is stepped from the main handler against {@link SystemClock}, so its progress is a
     * function of a clock the offline verification can advance one frame at a time, and a retarget
     * always starts from the position the user can actually see.
     */
    private static void startAnimation(FrameLayout anchor, float wanted) {
        cancelAnimation();
        float current = anchor.getTranslationY();
        if (Math.abs(wanted - current) < 1f) {
            anchor.setTranslationY(wanted);
            return;
        }
        animationFrom = current;
        animationTo = wanted;
        animationStarted = SystemClock.uptimeMillis();
        animationRunning = true;
        MAIN.removeCallbacks(ANIMATION_STEP);
        MAIN.post(ANIMATION_STEP);
    }

    private static void stepAnimation() {
        if (!animationRunning) return;
        FrameLayout anchor = anchorRef.get();
        if (anchor == null) {
            animationRunning = false;
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - animationStarted;
        float fraction = ANIMATION_MS <= 0 ? 1f : Math.min(1f, elapsed / (float) ANIMATION_MS);
        if (fraction >= 1f) {
            animationRunning = false;
            anchor.setTranslationY(animationTo);
            return;
        }
        float eased = DECELERATE.getInterpolation(fraction);
        anchor.setTranslationY(animationFrom + (animationTo - animationFrom) * eased);
        MAIN.postDelayed(ANIMATION_STEP, FRAME_MS);
    }

    private static void cancelAnimation() {
        if (animationRunning) MAIN.removeCallbacks(ANIMATION_STEP);
        animationRunning = false;
    }

    private static void reset(Activity activity, String reason, String trigger) {
        cancelAnimation();
        FrameLayout anchor = anchorRef.get();
        if (anchor != null) anchor.setTranslationY(0f);
        targetOffsetPx = 0;
        obstacles = Collections.emptyList();
        markReset(reason, trigger);
    }

    private static void markReset(String reason) {
        markReset(reason, "release");
    }

    private static void markReset(String reason, String trigger) {
        String key = epoch + "|" + reason;
        if (key.equals(lastResetKey)) return;
        lastResetKey = key;
        mark(
            activityRef.get(),
            "CAPTION_UI_AVOIDANCE_RESET",
            "epoch=" + epoch
                + ";player_type=" + playerTypeValue()
                + ";controls_state=" + controlsState
                + ";video_rect=" + rect(videoRect)
                + ";caption_base_rect=" + rect(baseRect)
                + ";obstacle_rects=" + rects(obstacles)
                + ";offset_px=0"
                + ";reason=" + reason
                + ";trigger=" + trigger);
    }

    private static void markUnavailable(Activity activity, String reason) {
        String key = epoch + "|" + reason;
        if (key.equals(lastUnavailableKey)) return;
        lastUnavailableKey = key;
        mark(
            activity,
            "CAPTION_UI_AVOIDANCE_UNAVAILABLE",
            "epoch=" + epoch
                + ";player_type=" + playerTypeValue()
                + ";controls_state=" + controlsState
                + ";obstacle_ids=" + (boundNames.isEmpty() ? "none" : boundNames)
                + ";offset_px=0"
                + ";reason=" + reason);
    }

    // ---- small helpers --------------------------------------------------------------------------
    private static void mark(Activity activity, String stage, String detail) {
        if (activity == null) return;
        try {
            CaptionDiagnostics.mark(activity, stage, detail);
        } catch (Throwable ignored) {
        }
    }

    private static String playerTypeValue() {
        return playerType.isEmpty() ? "unknown" : playerType;
    }

    private static String rect(Rect value) {
        return value == null
            ? "none"
            : value.left + "," + value.top + "," + value.right + "," + value.bottom;
    }

    private static String rects(List<Rect> values) {
        if (values == null || values.isEmpty()) return "none";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(';');
            out.append(rect(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static String stateName(Object state) {
        if (state instanceof Enum<?>) {
            String name = ((Enum<?>) state).name();
            return name.startsWith(VISIBILITY_PREFIX)
                ? name.substring(VISIBILITY_PREFIX.length())
                : name;
        }
        return "UNKNOWN";
    }

    private static int dp(int value) {
        Activity activity = activityRef.get();
        if (activity == null) return value;
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    // ---- offline verification accessors ----------------------------------------------------------

    static int targetOffsetPx() {
        return targetOffsetPx;
    }

    static long epochValue() {
        return epoch;
    }

    static Rect baseRectSnapshot() {
        return baseRect == null ? null : new Rect(baseRect);
    }

    static Rect videoRectSnapshot() {
        return videoRect == null ? null : new Rect(videoRect);
    }

    static List<Rect> obstacleSnapshot() {
        List<Rect> copy = new ArrayList<>();
        for (Rect rect : obstacles) copy.add(new Rect(rect));
        return copy;
    }

    static String controlsStateSnapshot() {
        return controlsState;
    }

    static String obstacleNamesSnapshot() {
        return boundNames;
    }

    static boolean animating() {
        return animationRunning;
    }

    /** How many queued visibility callbacks actually reached the current epoch. */
    static long signalsApplied() {
        return signalsApplied;
    }

    static void resetSignalsAppliedForTest() {
        signalsApplied = 0;
    }

    /** Replaces only the id-by-name lookup; the rest of the production path stays in place. */
    static void setIdLookupForTest(IdLookup lookup) {
        setIdLookup(lookup);
    }

    static void clearForTest() {
        cancelAnimation();
        MAIN.removeCallbacks(ANIMATION_STEP);
        idLookup = null;
        activityRef = new WeakReference<>(null);
        hostRef = new WeakReference<>(null);
        anchorRef = new WeakReference<>(null);
        textRef = new WeakReference<>(null);
        playerRef = new WeakReference<>(null);
        BOUND.clear();
        boundNames = "";
        epoch = 0;
        playerType = "";
        controlsState = "UNKNOWN";
        dragging = false;
        rebindRequested = true;
        baseRect = null;
        videoRect = null;
        obstacles = Collections.emptyList();
        targetOffsetPx = 0;
        animationRunning = false;
        signalsApplied = 0;
        lastBind = 0;
        lastBoundKey = "";
        lastTargetKey = "";
        lastResetKey = "";
        lastUnavailableKey = "";
    }
}
