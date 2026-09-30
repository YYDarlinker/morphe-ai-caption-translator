package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the final AI overlay clean without changing translation semantics.
 *
 * <p>Besides stripping non-speech music labels, this class masks YouTube's dedicated native
 * subtitle renderer while the custom AI overlay owns caption display. dev30/dev31 proved that
 * replacing Timed Text responses alone is insufficient on some renderer states: the native
 * SubtitleWindowView can keep drawing already-held source captions even after its network track
 * has been replaced by an invisible document. Hiding the renderer View leaves YouTube's logical
 * CC state and long-press menu untouched, so explicit native-track selection remains authoritative.</p>
 */
final class CaptionMusicSuppressor {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long TICK_MS = 40L;
    private static final long INITIAL_NATIVE_SCAN_MS = 180L;
    private static final long FALLBACK_NATIVE_SCAN_MS = 400L;
    private static final long NATIVE_NOT_FOUND_LOG_MS = 1_800L;
    private static final int MAX_NATIVE_SCAN_VIEWS = 1_200;
    private static final String[] PLAYER_IDS = {
            "inset_overlay_view_layout", "player_overlays", "player_overlay", "watch_player"
    };
    private static final String LEGACY_SUBTITLE_WINDOW =
            "com.google.android.libraries.youtube.player.subtitles.ui.subtitlewindowview";

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static final WeakHashMap<View, Float> maskedRenderers = new WeakHashMap<>();
    private static Field textField;
    private static Field statusField;
    private static boolean ready;
    private static boolean posted;
    private static boolean forceNativeRescan;
    private static long nativeSearchStartedAtMs;
    private static long nextNativeScanAtMs;
    private static boolean nativeNotFoundLogged;

    private static final Runnable TICK = () -> {
        posted = false;
        if (!RebuildController.ownsNativeTrack()) {
            restoreNativeRenderers();
            return;
        }
        sanitize();
        maskNativeRenderer();
        kick();
    };

    private CaptionMusicSuppressor() {}

    static void setActivity(Activity activity) {
        restoreNativeRenderers();
        activityRef = new WeakReference<>(activity);
        ready = false;
        resetNativeSearch();
    }

    static void kick() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(CaptionMusicSuppressor::kick);
            return;
        }
        if (posted) return;
        if (!RebuildController.ownsNativeTrack() && maskedRenderers.isEmpty()) return;
        posted = true;
        MAIN.postDelayed(TICK, TICK_MS);
    }

    /** Force one fresh player-local lookup after a video/player rebuild without adding a new loop. */
    static void forceNativeRendererScan() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(CaptionMusicSuppressor::forceNativeRendererScan);
            return;
        }
        forceNativeRescan = true;
        nextNativeScanAtMs = 0L;
        nativeNotFoundLogged = false;
        if (RebuildController.ownsNativeTrack()) { maskNativeRenderer(); kick(); }
    }

    /** Keep known windows hidden while discovery continues at its bounded cadence. */
    static void beginNativeRendererTransition() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(CaptionMusicSuppressor::beginNativeRendererTransition);
            return;
        }
        keepKnownRenderersMasked();
    }

    /** Reapply immediately after the geometry guard declares the rebuilt player stable. */
    static void endNativeRendererTransition() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(CaptionMusicSuppressor::endNativeRendererTransition);
            return;
        }
        forceNativeRescan = true;
        nextNativeScanAtMs = 0L;
        nativeNotFoundLogged = false;
        if (RebuildController.ownsNativeTrack()) { maskNativeRenderer(); kick(); }
    }

    private static void sanitize() { /* Text belongs exclusively to the accepted event plan. */ }

    /**
     * Keep cached windows transparent every 40 ms and repeat bounded discovery for replacement
     * windows on the same tick. The fast scan exists during initial AI takeover; it then backs off
     * to 400 ms. Discovery continues during blank/waiting states and player rebuilds.
     */
    private static void maskNativeRenderer() {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;

        boolean forced = forceNativeRescan;
        forceNativeRescan = false;
        boolean knownAttached = keepKnownRenderersMasked();
        long now = SystemClock.uptimeMillis();
        if (nativeSearchStartedAtMs == 0L) nativeSearchStartedAtMs = now;
        if (!forced && now < nextNativeScanAtMs) return;

        long age = now - nativeSearchStartedAtMs;
        nextNativeScanAtMs = now +
                (age <= NATIVE_NOT_FOUND_LOG_MS ? INITIAL_NATIVE_SCAN_MS : FALLBACK_NATIVE_SCAN_MS);

        boolean found = scanPlayerRoots(activity);
        if (!found) {
            // Player IDs vary across builds; fall back to role identity in the bounded decor tree.
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            found = scanTree(activity, decor, true);
        }

        if (found || knownAttached) {
            nativeNotFoundLogged = false;
            return;
        }

        if (!nativeNotFoundLogged && age >= NATIVE_NOT_FOUND_LOG_MS) {
            nativeNotFoundLogged = true;
            CaptionDiagnostics.mark(
                    activity,
                    "NATIVE_RENDERER_VIEW_NOT_FOUND",
                    "TimedText is invisible, but no YouTube native caption window was found to hide in this player"
            );
            String summary = treeSummary(activity.getWindow() == null ? null : activity.getWindow().getDecorView());
            for (int offset = 0; offset < summary.length(); offset += 1400)
                CaptionDiagnostics.mark(activity, "NATIVE_RENDERER_VIEW_TREE",
                        "offset=" + offset + ";" + summary.substring(offset, Math.min(offset + 1400, summary.length())));
        }
    }

    private static boolean keepKnownRenderersMasked() {
        boolean attached = false;
        Iterator<Map.Entry<View, Float>> iterator = maskedRenderers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<View, Float> entry = iterator.next();
            View view = entry.getKey();
            if (view == null || !view.isAttachedToWindow()) {
                if (view != null) view.setAlpha(entry.getValue());
                iterator.remove();
                continue;
            }
            attached = true;
            if (view.getAlpha() != 0f) view.setAlpha(0f);
        }
        return attached;
    }

    private static boolean scanPlayerRoots(Activity activity) {
        if (activity.getWindow() == null) return false;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return false;

        boolean found = false;
        WeakHashMap<View, Boolean> visited = new WeakHashMap<>();
        for (String name : PLAYER_IDS) {
            int id;
            try {
                id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            } catch (Throwable ignored) {
                continue;
            }
            if (id == 0) continue;
            View root = decor.findViewById(id);
            if (root == null || visited.put(root, Boolean.TRUE) != null) continue;
            found |= scanTree(activity, root, true);
        }
        return found;
    }

    private static boolean scanTree(Activity activity, View root, boolean allowPlayerLocalFallback) {
        if (root == null) return false;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int seen = 0;
        boolean found = false;

        while (!pending.isEmpty() && seen++ < MAX_NATIVE_SCAN_VIEWS) {
            View view = pending.removeFirst();
            if (isNativeSubtitleRenderer(activity, view, allowPlayerLocalFallback)) {
                maskRenderer(activity, view);
                found = true;
                // A renderer container owns all of its caption children. Do not spend time walking
                // those children after the parent itself has been made transparent.
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child != null) pending.addLast(child);
                }
            }
        }
        return found;
    }

    private static boolean isNativeSubtitleRenderer(
            Activity activity,
            View view,
            boolean allowPlayerLocalFallback
    ) {
        if (view == null || !view.isAttachedToWindow()) return false;
        Object tag = view.getTag();
        if (tag != null && tag.toString().startsWith("yydarlinker.deepseek.caption")) return false;

        Class<?> type = view.getClass();
        while (type != null && type != Object.class) {
            String name = type.getName().toLowerCase(Locale.ROOT);
            if (name.equals(LEGACY_SUBTITLE_WINDOW) ||
                    name.contains(".youtube.player.subtitles.") ||
                    name.endsWith("subtitlewindowview") || name.endsWith("captionwindowview")
                    || name.endsWith("subtitlesview")) {
                return true;
            }
            type = type.getSuperclass();
        }

        if (!allowPlayerLocalFallback || !(view instanceof ViewGroup) || view.isClickable()) {
            return false;
        }
        int id = view.getId();
        if (id == View.NO_ID) return false;
        try {
            String entry = activity.getResources().getResourceEntryName(id)
                    .toLowerCase(Locale.ROOT);
            if (entry.contains("button") || entry.contains("menu") || entry.contains("settings")) {
                return false;
            }
            return entry.contains("subtitle_window") || entry.contains("caption_window") ||
                    (entry.contains("subtitle") &&
                            (entry.contains("overlay") || entry.contains("container")));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void maskRenderer(Activity activity, View view) {
        CaptionSurface.nativeRenderer(view);
        if (maskedRenderers.containsKey(view)) {
            if (view.getAlpha() != 0f) view.setAlpha(0f);
            return;
        }
        float originalAlpha = view.getAlpha();
        maskedRenderers.put(view, originalAlpha);
        view.setAlpha(0f);
        CaptionDiagnostics.mark(
                activity,
                "NATIVE_RENDERER_VIEW_MASKED",
                "YouTube native caption window hidden directly;class=" + view.getClass().getName()
        );
    }

    private static void restoreNativeRenderers() {
        if (maskedRenderers.isEmpty()) {
            resetNativeSearch();
            return;
        }
        for (Map.Entry<View, Float> entry : maskedRenderers.entrySet()) {
            View view = entry.getKey();
            Float alpha = entry.getValue();
            if (view == null || alpha == null) continue;
            try { view.setAlpha(alpha); } catch (Throwable ignored) {}
        }
        maskedRenderers.clear();
        resetNativeSearch();
    }

    private static void resetNativeSearch() {
        forceNativeRescan = false;
        nativeSearchStartedAtMs = 0L;
        nextNativeScanAtMs = 0L;
        nativeNotFoundLogged = false;
    }

    private static String treeSummary(View root) {
        if (root == null) return "root=null";
        StringBuilder out = new StringBuilder();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int seen = 0;
        while (!pending.isEmpty() && seen++ < MAX_NATIVE_SCAN_VIEWS && out.length() < 16000) {
            View view = pending.removeFirst();
            out.append("parent=").append(view.getParent() == null ? "null" : view.getParent().getClass().getName())
                    .append(";child=").append(view.getClass().getName())
                    .append(";visibility=").append(view.getVisibility())
                    .append(";size=").append(view.getWidth()).append('x').append(view.getHeight()).append('\n');
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.addLast(group.getChildAt(i));
            }
        }
        return out.append(";visited=").append(seen).append(";truncated=").append(!pending.isEmpty()).toString();
    }

    private static boolean prepare() {
        if (ready && textField != null && statusField != null) return true;
        try {
            textField = CaptionOverlay.class.getDeclaredField("pendingText");
            statusField = CaptionOverlay.class.getDeclaredField("pendingStatus");
            textField.setAccessible(true);
            statusField.setAccessible(true);
            ready = true;
            return true;
        } catch (Throwable ignored) {
            ready = false;
            return false;
        }
    }
}
