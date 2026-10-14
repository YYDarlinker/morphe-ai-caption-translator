package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/** Verified player geometry only. No Activity-wide surface search and no unrelated previews. */
final class CaptionSurface {
    /** The containers of the regular player, from the outer overlay layout to the player itself. */
    static final String[] PLAYER_IDS = {
        "inset_overlay_view_layout", "player_overlays", "player_overlay", "watch_player"
    };

    private static final String[] SHORTS_IDS = {
        "reel_player_overlay_root",
        "reel_watch_player",
        "shorts_player_view_container",
        "reel_player_page_container",
        "reel_watch_fragment_root"
    };
    private static final long SHORTS_PROBE_MS = 250;
    private static final int MAX_SHORTS_VIEWS = 1800, MAX_SURFACE_VIEWS = 900;

    private static final WeakHashMap<View, WeakReference<View>> renderedSurfaces =
            new WeakHashMap<>();

    /** Any change of the player's layout makes the cached geometry stale. */
    private static final View.OnLayoutChangeListener GEOMETRY_LAYOUT =
            (v, l, t, r, b, ol, ot, or, ob) -> {
                if (l != ol || t != ot || r != or || b != ob) invalidateGeometry();
            };

    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static WeakReference<View> shorts = new WeakReference<>(null);
    private static WeakReference<View> player = new WeakReference<>(null);
    private static WeakReference<View> observedRoot = new WeakReference<>(null);
    private static boolean geometryInvalid = true;
    private static long shortsProbeAt;

    /** Counters of the tree searches, so tests and diagnostics can see that caching works. */
    static long refreshSearchCount, renderedSearchCount;

    private CaptionSurface() {}

    static void invalidateGeometry() {
        geometryInvalid = true;
        renderedSurfaces.clear();
    }

    static void activity(Activity value) {
        stopObserving();
        invalidateGeometry();
        activity = new WeakReference<>(value);
        shorts = new WeakReference<>(null);
        player = new WeakReference<>(null);
    }

    private static void stopObserving() {
        View old = observedRoot.get();
        if (old != null) old.removeOnLayoutChangeListener(GEOMETRY_LAYOUT);
        observedRoot.clear();
    }

    /** Is [view] on screen, opaque enough and big enough to be a player or a caption host? */
    static boolean visible(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown() || view.getAlpha() <= .01)
            return false;
        Rect rect = new Rect();
        return view.getGlobalVisibleRect(rect) && rect.width() > 40 && rect.height() > 40;
    }

    static boolean isShorts() {
        return visible(shorts.get());
    }

    /**
     * Shorts reports the inner player type NONE, which looks like a closed miniplayer, so the
     * surface decides. The cached Short view goes stale when the Short loops or is entered again,
     * so look for the visible Shorts container once more (at most every 250 ms, a bounded walk).
     */
    static boolean shortsSurfaceNow() {
        if (isShorts()) return true;
        long now = SystemClock.uptimeMillis();
        if (now - shortsProbeAt < SHORTS_PROBE_MS) return false;
        shortsProbeAt = now;
        Activity current = activity.get();
        if (current == null || current.getWindow() == null) return false;
        Set<Integer> ids = shortsIds(current);
        return !ids.isEmpty() && discover(current.getWindow().getDecorView(), ids) != null;
    }

    private static Set<Integer> shortsIds(Activity current) {
        Set<Integer> ids = new HashSet<>();
        for (String name : SHORTS_IDS) {
            int id = current.getResources().getIdentifier(name, "id", current.getPackageName());
            if (id != 0) ids.add(id);
        }
        return ids;
    }

    /** Finds the player view again: a visible Shorts container, else the best regular container. */
    static View refresh() {
        Activity current = activity.get();
        if (current == null || current.getWindow() == null) return null;
        View root = current.getWindow().getDecorView();
        if (observedRoot.get() != root) {
            stopObserving();
            observedRoot = new WeakReference<>(root);
            root.addOnLayoutChangeListener(GEOMETRY_LAYOUT);
            invalidateGeometry();
        }
        if (!geometryInvalid && visible(player.get())) return player.get();
        geometryInvalid = false;
        refreshSearchCount++;

        View shortView = discover(root, shortsIds(current));
        if (shortView != null) {
            player = new WeakReference<>(shortView);
            return shortView;
        }
        // The regular player: prefer a container with a rendered video surface, then the smallest.
        View best = null;
        long area = 0;
        boolean proven = false;
        for (String name : PLAYER_IDS) {
            int id = current.getResources().getIdentifier(name, "id", current.getPackageName());
            View candidate = id == 0 ? null : root.findViewById(id);
            if (!visible(candidate)) continue;
            Rect rect = new Rect();
            candidate.getGlobalVisibleRect(rect);
            boolean hasSurface = renderedBounds(candidate, root) != null;
            long size = area(rect);
            if (best == null || (hasSurface && !proven) || (hasSurface == proven && size < area)) {
                best = candidate;
                area = size;
                proven = hasSurface;
            }
        }
        player = new WeakReference<>(best);
        return best;
    }

    static View player() {
        View view = player.get();
        return !geometryInvalid && visible(view) ? view : refresh();
    }

    /** The largest visible view with one of [ids]; it becomes the cached Shorts view. */
    static View discover(View root, Set<Integer> ids) {
        View best = null;
        long largest = 0;
        ArrayDeque<View> pending = new ArrayDeque<>();
        if (root != null) pending.add(root);
        for (int visited = 0; !pending.isEmpty() && visited < MAX_SHORTS_VIEWS; visited++) {
            View view = pending.remove();
            Object tag = view.getTag();
            if (tag != null && tag.toString().startsWith("yydarlinker.deepseek.caption")) continue;
            if (ids.contains(view.getId()) && visible(view)) {
                Rect rect = new Rect();
                view.getGlobalVisibleRect(rect);
                if (area(rect) > largest) {
                    largest = area(rect);
                    best = view;
                }
            }
            if (view instanceof ViewGroup && view.getVisibility() == View.VISIBLE)
                addChildren((ViewGroup) view, pending);
        }
        shorts = new WeakReference<>(best);
        return best;
    }

    private static void addChildren(ViewGroup group, ArrayDeque<View> pending) {
        for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
    }

    private static long area(Rect rect) {
        return (long) rect.width() * rect.height();
    }

    /** The bounds of the Shorts view inside [host]. */
    static Rect bounds(View host) {
        return relative(shorts.get(), host);
    }

    /** The visible part of [view] in the coordinates of [host], or null. */
    private static Rect relative(View view, View host) {
        if (!visible(view) || host == null) return null;
        Rect bounds = new Rect(), hostBounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds)
                || !host.getGlobalVisibleRect(hostBounds)
                || !bounds.intersect(hostBounds)) return null;
        int[] origin = new int[2];
        host.getLocationOnScreen(origin);
        bounds.offset(-origin[0], -origin[1]);
        return bounds;
    }

    /** The bounds, inside [host], of the largest video surface under [root]. */
    static Rect renderedBounds(View root, View host) {
        if (root == null || host == null) return null;
        WeakReference<View> cached = renderedSurfaces.get(root);
        if (cached != null && visible(cached.get())) return relative(cached.get(), host);

        renderedSearchCount++;
        View selected = null;
        Rect best = null;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        for (int visited = 0; !pending.isEmpty() && visited < MAX_SURFACE_VIEWS; visited++) {
            View view = pending.remove();
            if (!view.isShown() || view.getAlpha() <= .01) continue;
            if (view instanceof SurfaceView || view instanceof TextureView) {
                Rect rect = relative(view, host);
                if (rect != null && (best == null || area(rect) > area(best))) {
                    best = rect;
                    selected = view;
                }
            }
            if (view instanceof ViewGroup) addChildren((ViewGroup) view, pending);
        }
        if (selected != null) {
            renderedSurfaces.put(root, new WeakReference<>(selected));
            selected.removeOnLayoutChangeListener(GEOMETRY_LAYOUT);
            selected.addOnLayoutChangeListener(GEOMETRY_LAYOUT);
        }
        return best;
    }

    /** Read-only direction evidence from the cached player; never triggers a search. */
    static int playerLayoutDirection() {
        View view = player.get();
        return view == null ? View.LAYOUT_DIRECTION_INHERIT : view.getLayoutDirection();
    }

    static Rect videoBounds(View host) {
        View view = player();
        Rect rendered = renderedBounds(view, host);
        return rendered == null ? relative(view, host) : rendered;
    }
}
