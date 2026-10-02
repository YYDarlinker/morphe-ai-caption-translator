package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Keeps extension work out of YouTube's player transition critical path.
 *
 * <p>Miniplayer expansion owns an explicit quarantine. The overlay is hidden and unbound before the
 * transition, may update only its pending text while YouTube animates, and is restored exactly once
 * after geometry stabilizes. No multi-frame extension measurement/polling follows the restore.</p>
 *
 * <p>The same transition signal also temporarily silences caption-button accessibility scanning,
 * native-renderer discovery, and protects the active AI Timed Text target during fullscreen /
 * orientation transitions.</p>
 */
final class CaptionPlayerTransitionGuard {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String[] PLAYER_IDS = {
            "inset_overlay_view_layout", "player_overlays", "player_overlay", "watch_player"
    };
    private static final int MIN_OBSERVATION_FRAMES = 3;
    private static final int NO_MOTION_FALLBACK_FRAMES = 3;
    private static final int STABLE_FRAMES_REQUIRED = 2;
    private static final int MAX_OBSERVATION_FRAMES = 12;
    private static final int PIXEL_TOLERANCE = 1;

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static boolean compactPlayer;
    /**
     * True from the moment compact-player quarantine starts until a non-compact probe actually
     * completes the one-shot overlay restore. This is intentionally separate from compactPlayer:
     * YouTube can emit several non-compact player-type callbacks while one expansion animation is
     * still in flight. A later callback must not erase the restore responsibility of the first one.
     */
    private static boolean expansionRestorePending;
    private static long generation;
    private static Probe pendingProbe;
    private static String latestType="";
    private static WeakReference<View> playerRectView=new WeakReference<>(null);
    static long observedFrameCount, probeNanos;
    private static WeakReference<View> recoveryLayoutRoot=new WeakReference<>(null);
    private static final View.OnLayoutChangeListener RECOVERY_LAYOUT=(v,l,t,r,b,ol,ot,or,ob)->{
        if(expansionRestorePending&&!compactPlayer&&pendingProbe==null)startReadOnlyProbe(latestType,true);
    };
    private static void removeRecoveryLayout(){View v=recoveryLayoutRoot.get();if(v!=null)v.removeOnLayoutChangeListener(RECOVERY_LAYOUT);recoveryLayoutRoot.clear();}
    private static void awaitValidLayout(Activity activity){View root=decor(activity);if(root==null)return;removeRecoveryLayout();recoveryLayoutRoot=new WeakReference<>(root);root.addOnLayoutChangeListener(RECOVERY_LAYOUT);}

    private CaptionPlayerTransitionGuard() {}

    static void setActivity(Activity activity) {
        removeRecoveryLayout();
        activityRef = new WeakReference<>(activity);
        generation++;pendingProbe=null;playerRectView.clear();
    }

    static void onPlayerType(String rawType) {
        String type = rawType == null ? "" : rawType.trim();
        boolean nextCompact = !CaptionSurface.isShorts() && compact(type);
        boolean same=type.equals(latestType);latestType=type;
        if(same && pendingProbe!=null)return;

        // Renderer discovery may walk a bounded part of the player View tree. Keep that work out of
        // the same animation-critical window already used for overlay geometry and CC state guards.
        CaptionMusicSuppressor.pauseDiscoveryForPlayerTransition(true);
        CaptionMusicSuppressor.beginNativeRendererTransition();
        CaptionButtonController.onPlayerTransition(type);
        CaptionLifecycleRestore.onPlayerTransition(type);

        if (nextCompact) {
            generation++;pendingProbe=null;playerRectView.clear();removeRecoveryLayout();
            compactPlayer = true;
            expansionRestorePending = true;
            // Start the quarantine before YouTube's compact/expand choreography can issue layouts.
            CaptionOverlay.beginGuardedExpansion();
            return;
        }

        // Do not infer restore ownership only from the immediately previous player type. YouTube can
        // report several ordinary/non-compact types during one miniplayer expansion. The first one
        // starts a delayed restore probe; subsequent ones invalidate that probe via generation++.
        // Keep carrying the restore obligation until the newest probe actually finishes it.
        boolean expandingFromCompact = compactPlayer || expansionRestorePending;
        compactPlayer = false;

        if (!expandingFromCompact) {
            // Fullscreen/orientation changes remain visible and use real layout events only.
            CaptionOverlay.setPlayerType(type);
            generation++;
            CaptionButtonController.onPlayerStable();
            CaptionMusicSuppressor.endNativeRendererTransition();
            return;
        }

        if (!DynamicCaptionController.isVisibleActive()) {
            if (expandingFromCompact) {
                CaptionOverlay.restoreAfterGuardedExpansion(type);
                expansionRestorePending = false;
            }
            CaptionButtonController.onPlayerStable();
            CaptionMusicSuppressor.endNativeRendererTransition();
            return;
        }

        // Non-compact intermediate types update the restore target, never restart the observation.
        if(pendingProbe==null)startReadOnlyProbe(type, expandingFromCompact);
    }

    private static void startReadOnlyProbe(String targetType, boolean delayedOverlayRestore) {
        final long token = ++generation;
        Activity activity = activityRef.get();
        View frameClock = decor(activity);
        if (activity == null || frameClock == null) {
            // Missing geometry is not permission to display at an unverified location.
            CaptionMusicSuppressor.endNativeRendererTransition();
            awaitValidLayout(activity);
            return;
        }

        Probe probe = new Probe(token, targetType, delayedOverlayRestore);
        pendingProbe=probe;
        android.view.Choreographer.getInstance().postFrameCallback(probe);
    }

    private static void finish(
            long token,
            String targetType,
            boolean delayedOverlayRestore,
            int observedFrames
    ) {
        if (token != generation) return;
        long frameSpan=pendingProbe==null?0:Math.max(0,pendingProbe.lastFrameNanos-pendingProbe.firstFrameNanos);
        pendingProbe=null;removeRecoveryLayout();
        targetType=latestType;
        if (delayedOverlayRestore) {
            // One atomic restore. CaptionOverlay no longer starts a 20/36-frame geometry tail here.
            CaptionOverlay.restoreAfterGuardedExpansion(targetType);
            expansionRestorePending = false;
        }
        CaptionButtonController.onPlayerStable();
        CaptionMusicSuppressor.endNativeRendererTransition();

        Activity activity = activityRef.get();
        if (activity != null && DynamicCaptionController.isVisibleActive()) {
            CaptionDiagnostics.mark(
                    activity,
                    "PLAYER_TRANSITION_STABLE",
                    "type="+targetType+";observed_frames="+observedFrames+";observed_frame_ns="+frameSpan
                            +";geometry_probe_total_us="+(probeNanos/1000)+";overlay_restore="+delayedOverlayRestore
            );
        }
    }

    private static View decor(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) return null;
        return activity.getWindow().getDecorView();
    }

    private static Rect readPlayerRect(Activity activity) {
        View root = decor(activity);
        if (root == null) return null;

        View cached=playerRectView.get();
        if(cached!=null && cached.isAttachedToWindow() && cached.isShown()) {Rect r=new Rect();if(cached.getGlobalVisibleRect(r)&&r.width()>1&&r.height()>1)return r;}
        Rect best = null;
        long bestArea = -1L;
        for (String name : PLAYER_IDS) {
            int id;
            try {
                id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            } catch (Throwable ignored) {
                continue;
            }
            if (id == 0) continue;
            View candidate = root.findViewById(id);
            if (candidate == null || !candidate.isAttachedToWindow() ||
                    !candidate.isShown() || candidate.getAlpha() <= 0.01f) {
                continue;
            }
            Rect rect = new Rect();
            if (!candidate.getGlobalVisibleRect(rect) || rect.width() <= 1 || rect.height() <= 1) {
                continue;
            }
            long area = (long) rect.width() * rect.height();
            if (area > bestArea) {
                bestArea = area;
                best = rect;
                playerRectView=new WeakReference<>(candidate);
            }
        }
        return best;
    }

    private static boolean nearlySame(Rect a, Rect b) {
        if (a == null || b == null) return false;
        return Math.abs(a.left - b.left) <= PIXEL_TOLERANCE &&
                Math.abs(a.top - b.top) <= PIXEL_TOLERANCE &&
                Math.abs(a.right - b.right) <= PIXEL_TOLERANCE &&
                Math.abs(a.bottom - b.bottom) <= PIXEL_TOLERANCE;
    }

    private static boolean compact(String rawType) {
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        if (type.isEmpty()) return false;
        return type.equals("NONE") || type.equals("HIDDEN") || type.equals("INLINE_MINIMAL") ||
                type.equals("WATCH_WHILE_PICTURE_IN_PICTURE") || type.contains("MINIMAL") ||
                type.contains("MINIMIZED") || type.contains("PICTURE_IN_PICTURE") ||
                type.contains("DISMISSED");
    }

    private static final class Probe implements Runnable, android.view.Choreographer.FrameCallback {
        final long token;
        final String targetType;
        final boolean delayedOverlayRestore;
        int frames;
        int stableFrames;
        boolean sawMotion;
        long firstFrameNanos,lastFrameNanos;
        Rect previous;

        Probe(long token, String targetType, boolean delayedOverlayRestore) {
            this.token = token;
            this.targetType = targetType;
            this.delayedOverlayRestore = delayedOverlayRestore;
        }

        @Override public void doFrame(long frameTimeNanos){lastFrameNanos=frameTimeNanos;if(firstFrameNanos==0)firstFrameNanos=frameTimeNanos;run();}
        @Override
        public void run() {
            if (token != generation) return;
            Activity activity = activityRef.get();
            View frameClock = decor(activity);
            if (activity == null || frameClock == null) {
                CaptionMusicSuppressor.endNativeRendererTransition();
                return;
            }

            long started=System.nanoTime();
            Rect current = readPlayerRect(activity);
            probeNanos+=System.nanoTime()-started;
            frames++;observedFrameCount++;
            if (current != null && previous != null) {
                if (nearlySame(previous, current)) {
                    stableFrames++;
                } else {
                    sawMotion = true;
                    stableFrames = 0;
                }
            } else {
                stableFrames = 0;
            }
            previous = current == null ? null : new Rect(current);

            boolean enoughFrames = frames >= MIN_OBSERVATION_FRAMES;
            boolean stable = enoughFrames && stableFrames >= STABLE_FRAMES_REQUIRED &&
                    (sawMotion || frames >= NO_MOTION_FALLBACK_FRAMES);
            if (stable || frames >= MAX_OBSERVATION_FRAMES) {
                if (!stable) {
                    pendingProbe=null;awaitValidLayout(activity);
                    CaptionMusicSuppressor.endNativeRendererTransition();
                    CaptionButtonController.onPlayerStable();
                    CaptionDiagnostics.mark(activity,"PLAYER_TRANSITION_SAFE_BLANK",
                        "reason=geometry_not_stable;observed_frames="+frames);
                    return; // Keep quarantine; no forced restore at the observation ceiling.
                }
                finish(token, targetType, delayedOverlayRestore, frames);
                return;
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
