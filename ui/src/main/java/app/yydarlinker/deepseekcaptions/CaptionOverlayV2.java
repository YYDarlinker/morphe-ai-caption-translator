package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.yydarlinker.caption.contracts.CaptionContracts;
import app.yydarlinker.caption.contracts.CaptionContracts.Basis;
import app.yydarlinker.caption.contracts.CaptionContracts.Unit;
import app.yydarlinker.caption.contracts.CaptionContracts.Window;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Draws one caption event in one view. Layout never edits a translation or creates timeline events:
 * the pages come from the application, the player authority decides whether anything may show.
 */
final class CaptionOverlay {
    /** What the overlay needs to know about the session that owns the text it shows. */
    interface RenderGuard {
        boolean isValid();

        default void onApplied() {}

        default void beforeDraw() {}

        default long displayPosition(long supplied) {
            return supplied;
        }

        default String identity() {
            return "";
        }

        default String session() {
            return "";
        }

        default long windowStart() {
            return -1;
        }

        default long windowEnd() {
            return -1;
        }

        default String blankReason() {
            return "";
        }

        /** Known when the guard was bound; never resolved on the render path. */
        default CaptionCredentialRef credential() {
            return CaptionCredentialRef.NONE;
        }
    }

    /**
     * Adjacent captions change text inside a box that stays up. A finished last page stays visible
     * for a short media time while the engine's next unit (or its hide) arrives, and a shown
     * caption stays while the next unit's pages are laid out off the main thread. Explicit hides,
     * seeks, owner changes and longer pauses still clear the box at once.
     */
    private static final long END_HOLD_MS = 300, LAYOUT_HOLD_MS = 500;

    private static final long SCAN_INTERVAL_MS = 500, DRAG_AFTER_MS = 350;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong COMMAND = new AtomicLong();
    private static final CaptionPageExecutor LAYOUTS = new CaptionPageExecutor();

    // ---- The views ----

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static WeakReference<FrameLayout> hostRef = new WeakReference<>(null),
            anchorRef = new WeakReference<>(null);
    private static WeakReference<TextView> textRef = new WeakReference<>(null);
    private static CaptionHorizontalPlacement horizontalPlacement;

    // ---- What is to be shown ----

    private static RenderGuard currentGuard;
    private static Unit pendingUnit;
    private static String pendingText = "", pendingIdentity = "", pendingCanonicalHash = "";
    private static long pendingStart = -1, pendingEnd = -1, pendingPosition = -1;
    private static boolean pendingStatus, pendingWaiting;
    private static CaptionRenderSpec pendingRenderSpec = CaptionRenderSpec.LEGACY;
    private static List<CaptionPageLayout.Page> pendingPages = Collections.emptyList();
    private static int shownPage = -1;

    // ---- Layout and render bookkeeping ----

    /** Immutable geometry; background translation can measure without touching Views. */
    private static volatile CaptionPresentationBudget layoutBudget;

    private static String desiredLayout = "", failedLayout = "";
    private static int measurementMismatch;
    private static boolean dirty = true, reconcilingDisplayTime;
    private static long lastScan, lastLayout, layoutHoldSince = -1;
    private static String lastBlankIdentity, lastNotice = "", lastDisplayResult = "";
    private static String lastPresentationNotice = "";

    private static final Rect previous = new Rect();
    private static int previousScreenWidth, normalVideoWidth;
    private static boolean previousShorts, previousFullScreen;
    private static boolean referenceShorts, referenceLandscape;

    /** One frame-aligned UI request; never postpones model authority, clear or quarantine. */
    private static volatile long playerRenderEpoch;

    private static Runnable playerRenderTask;
    private static WeakReference<View> playerFrameClock = new WeakReference<>(null);
    private static boolean playerRenderPending, applyingPlayerRender;
    private static float downY, initial;
    private static long downAt;
    private static boolean dragging;

    private static final Runnable HOLD_EXPIRY =
            () -> {
                dirty = true;
                render();
            };

    private static final ViewTreeObserver.OnPreDrawListener WATCH =
            () -> {
                advancePreparedPage();
                long now = SystemClock.uptimeMillis();
                if (now - lastLayout >= 100 && !blocked() && !nothingPending()) {
                    lastLayout = now;
                    render();
                }
                TextView pending = textRef.get();
                return !anchorVisible() || pending == null || !pending.isLayoutRequested();
            };

    private CaptionOverlay() {}

    static CaptionPresentationBudget budget() {
        return layoutBudget;
    }

    // ---- Authority ----

    /** Display permission and the compact quarantine live in the one player authority. */
    private static boolean suppressed() {
        return !CaptionPlayerAuthority.displayPermitted()
                && CaptionPlayerAuthority.state() != CaptionPlayerAuthority.UNKNOWN;
    }

    private static boolean guardedExpansion() {
        int state = CaptionPlayerAuthority.state();
        return state == CaptionPlayerAuthority.COMPACT
                || state == CaptionPlayerAuthority.TRANSITIONING_TO_REGULAR;
    }

    private static boolean blocked() {
        return suppressed() || guardedExpansion();
    }

    private static boolean nothingPending() {
        return pendingText.isEmpty() && pendingIdentity.isEmpty();
    }

    private static boolean guardInvalid() {
        return currentGuard != null && !currentGuard.isValid();
    }

    private static void main(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    // ---- Commands ----

    static void setActivity(Activity a) {
        CaptionPlayerAuthority.setOwner(a);
        main(
                () -> {
                    if (activityRef.get() != a) {
                        invalidatePlayerRender();
                        detach();
                        normalVideoWidth = 0;
                    }
                    activityRef = new WeakReference<>(a);
                    CaptionSurface.activity(a);
                    dirty = true;
                    render();
                });
    }

    static void showStatus(String s, RenderGuard g) {
        show(s, true, g, "", -1, -1, -1, false, CaptionRenderSpec.LEGACY, null, "");
    }

    static void showWaitingEvent(
            String s, RenderGuard g, String id, long start, long end, long position) {
        show(s, false, g, id, start, end, position, true, CaptionRenderSpec.LEGACY, null, "");
    }

    static void showEvent(
            String text,
            RenderGuard guard,
            String id,
            long start,
            long end,
            long at,
            CaptionRenderSpec spec) {
        // A failed or withheld event carries no text: it clears the box (the contract Unit rejects
        // blanks).
        if (text == null || text.trim().isEmpty()) {
            hide(guard);
            return;
        }
        Unit unit =
                start >= 0 && end > start
                        ? new Unit(
                                id,
                                0,
                                1,
                                text,
                                Collections.singletonList(new Window(start, end)),
                                Basis.ESTIMATED,
                                0)
                        : null;
        show(
                text,
                false,
                guard,
                id,
                start,
                end,
                at,
                false,
                spec,
                unit,
                CaptionContracts.hash(text));
    }

    private static void show(
            String s,
            boolean status,
            RenderGuard g,
            String id,
            long start,
            long end,
            long position,
            boolean waiting,
            CaptionRenderSpec spec,
            Unit sourceUnit,
            String canonicalHash) {
        if (g != null && !g.isValid()) return;
        long command = g == null ? COMMAND.incrementAndGet() : COMMAND.get();
        final long renderEpoch = playerRenderEpoch;
        main(
                () -> {
                    if (!accepts(g, command, renderEpoch)) return;
                    pendingRenderSpec = spec;
                    pendingCanonicalHash = canonicalHash;
                    pendingUnit = sourceUnit;
                    pendingText = s == null ? "" : s;
                    pendingIdentity = id;
                    pendingStart = start;
                    pendingEnd = end;
                    pendingPosition = g == null ? position : g.displayPosition(position);
                    pendingPages = Collections.emptyList();
                    shownPage = -1;
                    pendingStatus = status;
                    pendingWaiting = waiting;
                    currentGuard = g;
                    dirty = true;
                    render();
                    if (g != null) g.onApplied();
                });
    }

    /**
     * Is this queued command still current? A guarded command is current while its guard is valid;
     * an unguarded one only until a newer command arrived.
     */
    private static boolean accepts(RenderGuard guard, long command, long renderEpoch) {
        if (renderEpoch != playerRenderEpoch) return false;
        if (guard == null) return command == COMMAND.get();
        if (!guard.isValid()) return false;
        COMMAND.incrementAndGet();
        return true;
    }

    static void hide(RenderGuard g) {
        if (g != null && !g.isValid()) return;
        long command = g == null ? COMMAND.incrementAndGet() : COMMAND.get();
        final long renderEpoch = playerRenderEpoch;
        main(
                () -> {
                    if (!accepts(g, command, renderEpoch)) return;
                    final long applyStarted = SystemClock.uptimeMillis();
                    invalidatePlayerRender();
                    forgetPending();
                    currentGuard = g;
                    hideView();
                    if (g == null) return;
                    // The identity is diagnostic only: a blank is not a pending render.
                    pendingIdentity = g.identity();
                    pendingStart = g.windowStart();
                    pendingEnd = g.windowEnd();
                    pendingPosition = g.displayPosition(pendingPosition);
                    displayResult(
                            activityRef.get(),
                            "empty",
                            g.blankReason(),
                            false,
                            0,
                            0,
                            "",
                            applyStarted);
                    pendingIdentity = "";
                    pendingStart = -1;
                    pendingEnd = -1;
                    g.onApplied();
                });
    }

    static void clear() {
        clear(null);
    }

    /** Session cleanup cannot invalidate a newer session's queued render command. */
    static void clear(RenderGuard guard) {
        if (guard != null && !guard.isValid()) return;
        long command = guard == null ? COMMAND.incrementAndGet() : COMMAND.get();
        main(
                () -> {
                    if (command != COMMAND.get() || guard != null && !guard.isValid()) return;
                    forgetPending();
                    invalidatePlayerRender();
                    pendingStatus = false;
                    currentGuard = null;
                    normalVideoWidth = 0;
                    hideView();
                });
    }

    private static void forgetPending() {
        pendingText = "";
        pendingUnit = null;
        pendingStart = -1;
        pendingEnd = -1;
        pendingIdentity = "";
        pendingPages = Collections.emptyList();
        shownPage = -1;
        desiredLayout = "";
        failedLayout = "";
        measurementMismatch = 0;
    }

    static void position(long position, RenderGuard owner) {
        if (owner != null && !owner.isValid()) return;
        main(
                () -> {
                    if (owner != null && !owner.isValid()) return;
                    long latest =
                            currentGuard != null && currentGuard.isValid()
                                    ? currentGuard.displayPosition(position)
                                    : owner == null ? position : owner.displayPosition(position);
                    long before = pendingPosition;
                    pendingPosition = latest;
                    boolean timed = pendingStart >= 0 && pendingEnd > pendingStart;
                    if (CaptionPageLayout.indexAt(pendingPages, latest) != shownPage
                            || pendingUnit != null
                                    && (!ownsDisplayPosition(latest)
                                            || !ownsDisplayPosition(before))
                            || timed && (!inWindow(latest) || !inWindow(before))) {
                        dirty = true;
                        render();
                    }
                });
    }

    private static boolean inWindow(long at) {
        return at >= pendingStart && at < pendingEnd;
    }

    static void refreshStyle(Context c) {
        main(
                () -> {
                    // An explicit style refresh is not a player notification.
                    invalidatePlayerRender();
                    dirty = true;
                    render();
                });
    }

    /**
     * Geometry refresh only, and never during a player transition: the transition coordinator is
     * the single place that decides when a new surface may be discovered. Shorts keep their own
     * isolation, which is a surface fact rather than a player-transition fact.
     */
    static void refreshSurface() {
        main(
                () -> {
                    CaptionPlayerAuthority.reconcileCurrentPlayerType();
                    if (CaptionPlayerAuthority.state() == CaptionPlayerAuthority.COMPACT) return;
                    boolean surfaceDirty = CaptionPlayerAuthority.consumeSurfaceDirty();
                    if (CaptionSurface.isShorts()) CaptionPlayerAuthority.noteShortsSurface();
                    else if (CaptionPlayerAuthority.state()
                            == CaptionPlayerAuthority.TRANSITIONING_TO_REGULAR) return;
                    // The throttle bounds repeated searches; a genuinely new player notification is
                    // a real surface change and replaces the throttled search instead of adding.
                    long now = SystemClock.uptimeMillis();
                    if (!surfaceDirty && now >= lastScan && now - lastScan < SCAN_INTERVAL_MS)
                        return;
                    CaptionSurface.refresh();
                    lastScan = SystemClock.uptimeMillis();
                    CaptionSurface.invalidateGeometry();
                    dirty = true;
                    render();
                });
    }

    /** Routes a raw player type into the authority, which alone decides display permission. */
    static void setPlayerType(String type) {
        CaptionPlayerAuthority.onNotification(type);
        main(
                () -> {
                    final long applyStarted = SystemClock.uptimeMillis();
                    int state = CaptionPlayerAuthority.state();
                    if (state == CaptionPlayerAuthority.COMPACT
                            || state == CaptionPlayerAuthority.CLOSED) {
                        invalidatePlayerRender();
                        hideView();
                        displayResult(
                                activityRef.get(),
                                "suppressed",
                                "player_suppressed",
                                false,
                                0,
                                0,
                                "",
                                applyStarted);
                        return;
                    }
                    normalVideoWidth = 0;
                    CaptionSurface.invalidateGeometry();
                    dirty = true;
                    requestPlayerRender();
                });
    }

    /** Main-thread constant-time retraction; accepted plans and positions are retained. */
    static void denyDisplay(long ownerEpoch) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> denyDisplay(ownerEpoch));
            return;
        }
        if (ownerEpoch != CaptionPlayerAuthority.ownerEpoch()) return;
        invalidatePlayerRender();
        COMMAND.incrementAndGet();
        dragging = false;
        TextView text = textRef.get();
        if (text != null) {
            text.cancelPendingInputEvents();
            text.clearFocus();
        }
        View anchor = anchorRef.get();
        if (anchor != null) {
            anchor.cancelPendingInputEvents();
            anchor.clearFocus();
            anchor.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            anchor.setVisibility(View.INVISIBLE);
        }
    }

    /** Lets a cleared session be observed again. */
    static void resetPresentationDedupForTests() {
        lastDisplayResult = "";
        lastNotice = "";
        lastPresentationNotice = "";
        lastBlankIdentity = null;
        lastScan = -SCAN_INTERVAL_MS;
        lastLayout = 0;
        reconcilingDisplayTime = false;
    }

    // ---- Frame-aligned render ----

    private static void invalidatePlayerRender() {
        playerRenderEpoch++;
        View clock = playerFrameClock.get();
        if (playerRenderTask != null) {
            if (clock != null) clock.removeCallbacks(playerRenderTask);
            MAIN.removeCallbacks(playerRenderTask);
        }
        playerRenderTask = null;
        playerFrameClock.clear();
        playerRenderPending = false;
    }

    /** One render on the next frame; an authoritative transition supersedes a queued one. */
    static void requestPlayerRender() {
        if (blocked() || nothingPending()) return;
        if (playerRenderTask != null) invalidatePlayerRender();
        Activity owner = activityRef.get();
        if (!usable(owner)) return;
        View clock = owner.getWindow().getDecorView();
        final long epoch = playerRenderEpoch;
        playerRenderPending = true;
        playerRenderTask =
                () -> {
                    // A stale callback has no attach/render/hide side effects.
                    if (epoch != playerRenderEpoch || activityRef.get() != owner || !usable(owner))
                        return;
                    playerRenderTask = null;
                    playerFrameClock.clear();
                    playerRenderPending = false;
                    if (blocked() || guardInvalid() || nothingPending()) return;
                    applyingPlayerRender = true;
                    try {
                        render();
                    } finally {
                        applyingPlayerRender = false;
                    }
                    CaptionDiagnostics.mark(
                            owner,
                            "PLAYER_TRANSITION_RENDER_DISPATCH",
                            "type="
                                    + CaptionPlayerAuthority.playerType()
                                    + ";state="
                                    + CaptionPlayerAuthority.stateName()
                                    + ";geometry_search_count="
                                    + CaptionSurface.refreshSearchCount
                                    + ";native_scan_count="
                                    + CaptionMusicSuppressor.scanCount);
                };
        playerFrameClock = new WeakReference<>(clock);
        clock.postOnAnimation(playerRenderTask);
    }

    private static boolean usable(Activity owner) {
        return owner != null
                && !owner.isFinishing()
                && !owner.isDestroyed()
                && owner.getWindow() != null;
    }

    // ---- Attaching ----

    private static void hideView() {
        layoutHoldSince = -1;
        if (horizontalPlacement != null) horizontalPlacement.cancel();
        lastBlankIdentity = null;
        FrameLayout a = anchorRef.get();
        if (a != null) a.setVisibility(View.GONE);
    }

    /** Is the overlay anchor visible right now? */
    static boolean anchorVisible() {
        FrameLayout a = anchorRef.get();
        return a != null && a.getVisibility() == View.VISIBLE;
    }

    private static boolean shownText() {
        TextView view = textRef.get();
        return anchorVisible() && view != null && view.getText().length() > 0;
    }

    private static void detach() {
        if (horizontalPlacement != null) horizontalPlacement.cancel();
        horizontalPlacement = null;
        FrameLayout h = hostRef.get(), a = anchorRef.get();
        if (h != null && h.getViewTreeObserver().isAlive())
            h.getViewTreeObserver().removeOnPreDrawListener(WATCH);
        if (a != null) {
            if (a.getParent() instanceof ViewGroup) ((ViewGroup) a.getParent()).removeView(a);
            // A detached overlay never keeps a visible state: a later owner renders only after it
            // has proven a permitted player surface.
            a.setVisibility(View.GONE);
        }
        hostRef = new WeakReference<>(null);
        anchorRef = new WeakReference<>(null);
        textRef = new WeakReference<>(null);
        previous.setEmpty();
        previousScreenWidth = 0;
        lastScan = -SCAN_INTERVAL_MS;
        lastLayout = 0;
        lastNotice = "";
        lastBlankIdentity = null;
        layoutBudget = null;
        pendingPages = Collections.emptyList();
        desiredLayout = "";
        failedLayout = "";
        measurementMismatch = 0;
        shownPage = -1;
    }

    private static boolean attach(Activity a) {
        FrameLayout h = hostRef.get();
        if (h != null && h.isAttachedToWindow() && anchorRef.get() != null) return true;
        detach();
        if (!(a.findViewById(android.R.id.content) instanceof FrameLayout)) return false;
        h = (FrameLayout) a.findViewById(android.R.id.content);
        FrameLayout anchor = new FrameLayout(a);
        // A fresh overlay starts hidden: only a render that passed the authority, guard and
        // geometry checks may show it, so a transition can never expose an unverified overlay.
        anchor.setVisibility(View.GONE);
        anchor.setTag("yydarlinker.deepseek.caption.anchor");
        // Only the outer coordinate system is physical LTR; the TextView keeps its target spec.
        anchor.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        anchor.setClipChildren(false);
        anchor.setClipToPadding(false);
        anchor.setElevation(dp(a, 12));
        TextView text = new CaptionText(a);
        text.setTag("yydarlinker.deepseek.caption.overlay");
        text.setTextColor(Color.WHITE);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        text.setPadding(dp(a, 6), dp(a, 4), dp(a, 6), dp(a, 4));
        text.setShadowLayer(dp(a, 1), 0, dp(a, 1), 0xD0000000);
        text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        text.setSingleLine(false);
        text.setMaxLines(2);
        text.setEllipsize(null);
        text.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        text.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
        text.setOnTouchListener((v, e) -> drag(v, e));
        anchor.addView(
                text,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        h.addView(anchor, new FrameLayout.LayoutParams(1, 1));
        hostRef = new WeakReference<>(h);
        anchorRef = new WeakReference<>(anchor);
        textRef = new WeakReference<>(text);
        horizontalPlacement = new CaptionHorizontalPlacement(h, anchor, text);
        h.getViewTreeObserver().addOnPreDrawListener(WATCH);
        dirty = true;
        return true;
    }

    /** The caption view: the page shown must still own the media time at the moment of painting. */
    private static final class CaptionText extends TextView {
        CaptionText(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            RenderGuard guard = currentGuard;
            if (guard != null) guard.beforeDraw();
            guard = currentGuard;
            long paintAt = positionOf(guard);
            if (guard != null && pendingUnit != null && !pageOwns(paintAt)) {
                guard.beforeDraw();
                guard = currentGuard;
                paintAt = positionOf(guard);
            }
            if (guard != null
                    && (!guard.isValid()
                            || pendingUnit != null
                                    && !pageOwns(paintAt)
                                    && layoutHoldSince < 0
                                    && !holdingEnd(paintAt))) {
                setText("");
                hideView();
                return;
            }
            super.onDraw(canvas);
            // Time-dependent content cannot rely on a 50 ms state tick to invalidate a cached
            // display list: only a visible, owned caption stays on the compositor cadence.
            if (currentGuard != null && currentGuard.isValid() && pendingUnit != null && isShown())
                postInvalidateOnAnimation();
        }
    }

    private static long positionOf(RenderGuard guard) {
        return guard == null ? pendingPosition : guard.displayPosition(pendingPosition);
    }

    private static boolean ownsDisplayPosition(long at) {
        return pendingUnit != null && pendingUnit.audible(at);
    }

    private static boolean pageContains(long at) {
        return shownPage >= 0
                && shownPage < pendingPages.size()
                && pendingPages.get(shownPage).contains(at);
    }

    private static boolean pageOwns(long at) {
        return ownsDisplayPosition(at) && pageContains(at);
    }

    // ---- Holds ----

    private static boolean holdingEnd(long at) {
        boolean held =
                currentGuard != null
                        && currentGuard.isValid()
                        && pendingUnit != null
                        && !pendingStatus
                        && !pendingWaiting
                        && pendingEnd > pendingStart
                        && at >= pendingEnd
                        && at - pendingEnd < END_HOLD_MS
                        && shownPage >= 0
                        && shownPage == pendingPages.size() - 1
                        && shownText();
        if (held) {
            MAIN.removeCallbacks(HOLD_EXPIRY);
            MAIN.postDelayed(HOLD_EXPIRY, END_HOLD_MS + 20);
        }
        return held;
    }

    private static boolean holdingLayout() {
        if (!shownText()) return false;
        long now = SystemClock.uptimeMillis();
        if (layoutHoldSince < 0) {
            layoutHoldSince = now;
            MAIN.removeCallbacks(HOLD_EXPIRY);
            MAIN.postDelayed(HOLD_EXPIRY, LAYOUT_HOLD_MS + 20);
        }
        return now - layoutHoldSince < LAYOUT_HOLD_MS;
    }

    // ---- Pages ----

    /** The pages of the pending event; laid out off the main thread, empty until they are ready. */
    private static List<CaptionPageLayout.Page> currentPlan(
            CaptionPresentationBudget budget, CaptionRenderSpec spec) {
        String key =
                pendingIdentity
                        + "|"
                        + pendingCanonicalHash
                        + "|"
                        + pendingStart
                        + "|"
                        + pendingEnd
                        + "|"
                        + spec.measurementKey()
                        + "|"
                        + budget.width
                        + "|"
                        + Float.floatToIntBits(budget.preferredPx);
        if (!key.equals(desiredLayout)) {
            desiredLayout = key;
            failedLayout = "";
            measurementMismatch = 0;
        }
        List<CaptionPageLayout.Page> ready = LAYOUTS.get(key);
        if (ready != null) return ready;
        final String body = pendingText, identity = pendingIdentity;
        final long start = pendingStart, end = pendingEnd;
        final RenderGuard guard = currentGuard;
        LAYOUTS.request(
                key,
                () -> CaptionActions.displayPages(body, start, end, budget, spec),
                pages ->
                        MAIN.post(
                                () -> {
                                    if (!key.equals(desiredLayout)
                                            || !identity.equals(pendingIdentity)
                                            || guard != currentGuard
                                            || guard != null && !guard.isValid()) return;
                                    if (pages.isEmpty()) failedLayout = key;
                                    dirty = true;
                                    lastBlankIdentity = null;
                                    render();
                                }));
        return Collections.emptyList();
    }

    /** Advances an already prepared page on paint entry, with the clock of frame selection. */
    static void advancePreparedPage() {
        // Pages still being laid out belong to render(), which also holds the previous text.
        if (currentGuard == null
                || !currentGuard.isValid()
                || pendingUnit == null
                || !anchorVisible()
                || pendingPages.isEmpty()) return;
        long at = currentGuard.displayPosition(pendingPosition);
        int next = CaptionPageLayout.indexAt(pendingPages, at);
        pendingPosition = at;
        TextView text = textRef.get();
        if (next < 0 || !ownsDisplayPosition(at)) {
            if (holdingEnd(at)) return;
            if (text != null) text.setText("");
            hideView();
            shownPage = -1;
            return;
        }
        if (next == shownPage) return;
        CaptionPageLayout.Page page = pendingPages.get(next);
        CaptionPresentationBudget budget = layoutBudget;
        if (text == null || budget == null) return;
        final long applyStarted = SystemClock.uptimeMillis();
        if (applyVisibleText(
                        text,
                        page.text,
                        budget.preferredPx,
                        budget.width,
                        pendingRenderSpec,
                        page.linePlan)
                < 0) {
            text.setText("");
            hideView();
            return;
        }
        placeVisibleText(
                activityRef.get(),
                hostRef.get(),
                anchorRef.get(),
                text,
                previous,
                budget.width + text.getPaddingLeft() + text.getPaddingRight());
        text.invalidate();
        displayResult(
                activityRef.get(),
                "caption_page",
                "",
                true,
                budget.width,
                budget.preferredPx,
                "",
                applyStarted);
        shownPage = next;
    }

    // ---- Render ----

    private static void render() {
        final long applyStarted = SystemClock.uptimeMillis();
        Activity a = activityRef.get();
        if (guardInvalid()) {
            hideView();
            displayResult(a, "suppressed", "owner_invalid", false, 0, 0, "", applyStarted);
            return;
        }
        if (currentGuard != null) pendingPosition = currentGuard.displayPosition(pendingPosition);
        boolean timed = pendingStart >= 0 && pendingEnd > pendingStart;
        if (timed && !inWindow(pendingPosition)) {
            if (holdingEnd(pendingPosition)) return;
            TextView stale = textRef.get();
            if (stale != null) stale.setText("");
            shownPage = -1;
            hideView();
            dirty = true;
            displayResult(a, "empty", "outside_owned_window", false, 0, 0, "", applyStarted);
            return;
        }
        // A time expiry is authoritative even while a geometry frame is queued.
        if (playerRenderPending && !applyingPlayerRender) {
            requestPlayerRender();
            return;
        }
        if (a == null
                || a.isFinishing()
                || a.isDestroyed()
                || nothingPending()
                || blocked()
                || guardInvalid()) {
            hideView();
            if (a != null && blocked())
                displayResult(a, "suppressed", "player_suppressed", false, 0, 0, "", applyStarted);
            return;
        }
        if (!attach(a)) return;
        FrameLayout host = hostRef.get(), anchor = anchorRef.get();
        TextView text = textRef.get();
        CaptionRenderSpec displaySpec = pendingRenderSpec;
        if (pendingStatus || pendingWaiting) {
            String code = LanguageMenuOrder.locale().toLanguageTag();
            displaySpec = new CaptionRenderSpec(code, CaptionLanguageProfile.fromCode(code), false);
        }
        displaySpec.apply(text);
        long now = SystemClock.uptimeMillis();
        if (now - lastScan >= SCAN_INTERVAL_MS) {
            CaptionSurface.refresh();
            lastScan = now;
        }
        Rect b = CaptionSurface.videoBounds(host);
        if (b == null || b.width() < 50 || b.height() < 50) {
            layoutBudget = null;
            hideView();
            return;
        }
        boolean shorts = CaptionSurface.isShorts();
        DisplayMetrics metrics = a.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        // Mode callbacks can precede the orientation layout: current screen geometry decides the
        // calibrated portrait-details / landscape-full-screen modes.
        boolean fullScreen = !shorts && metrics.widthPixels > metrics.heightPixels;
        if (!dirty
                && b.equals(previous)
                && screenWidth == previousScreenWidth
                && shorts == previousShorts
                && fullScreen == previousFullScreen
                && (anchor.getVisibility() == View.VISIBLE
                        || anchor.getVisibility() == View.GONE
                                && pendingIdentity.equals(lastBlankIdentity))) return;
        previousShorts = shorts;
        previousFullScreen = fullScreen;
        dirty = false;
        previous.set(b);
        // A new surface is committed only outside a player transition.
        if (guardedExpansion() && !shorts) {
            hideView();
            return;
        }
        previousScreenWidth = screenWidth;
        DeepSeekConfig.Appearance cfg = DeepSeekConfig.displayStyle(a);
        boolean landscapeHost = host.getWidth() > host.getHeight();
        if (normalVideoWidth == 0
                || shorts != referenceShorts
                || landscapeHost != referenceLandscape) {
            normalVideoWidth = b.width();
            referenceShorts = shorts;
            referenceLandscape = landscapeHost;
        } else {
            normalVideoWidth = Math.max(normalVideoWidth, b.width());
        }
        float targetGlyphHeight =
                SubtitleStyleMetrics.renderedGlyphHeightPx(
                        cfg.captionSizeTier, screenWidth, fullScreen, b.width(), normalVideoWidth);
        float preferred =
                SubtitleStyleMetrics.textSizePxForGlyphHeight(text.getPaint(), targetGlyphHeight);
        float minimum =
                SubtitleStyleMetrics.textSizePxForGlyphHeight(
                        text.getPaint(),
                        SubtitleStyleMetrics.renderedGlyphHeightPx(
                                0, screenWidth, fullScreen, b.width(), normalVideoWidth));
        int width = Math.max(1, Math.round(b.width() * (shorts ? .78f : .92f)));
        int inner = Math.max(1, width - text.getPaddingLeft() - text.getPaddingRight());
        CaptionRenderSpec measuredSpec = displaySpec.withPaint(text);
        layoutBudget = new CaptionPresentationBudget(inner, minimum, preferred, measuredSpec);
        float size = preferred;
        String shown = pendingText;
        String mode = pendingStatus ? "status" : "caption";
        boolean ownedCaption = !pendingStatus && !pendingWaiting && !shown.isEmpty() && timed;
        pendingPages =
                ownedCaption ? currentPlan(layoutBudget, measuredSpec) : Collections.emptyList();
        shownPage =
                pendingUnit != null && !ownsDisplayPosition(pendingPosition)
                        ? -1
                        : CaptionPageLayout.indexAt(pendingPages, pendingPosition);
        if (shownPage >= 0) {
            shown = pendingPages.get(shownPage).text;
            if (pendingPages.size() > 1) mode = "caption_page";
        } else if (!ownedCaption) {
            while (size > minimum && lineCount(shown, size, inner, measuredSpec) > 2)
                size = Math.max(minimum, size - .5f);
        }
        String reason = shown.isEmpty() && currentGuard != null ? currentGuard.blankReason() : "";
        if (ownedCaption
                && shownPage < 0
                && pendingPages.isEmpty()
                && !failedLayout.equals(desiredLayout)
                && holdingLayout()) {
            displayResult(
                    a, "caption_hold", "preparing_layout", true, inner, size, "", applyStarted);
            return;
        }
        if (ownedCaption && shownPage < 0) {
            shown = "";
            mode = "overflow_status";
            reason =
                    pendingPages.isEmpty()
                            ? (failedLayout.equals(desiredLayout)
                                    ? "unprojectable_pixel_width"
                                    : "preparing_layout")
                            : "outside_owned_window";
        } else if (!ownedCaption && lineCount(shown, size, inner, measuredSpec) > 2) {
            shown = "";
            mode = "original_fallback";
        }
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        text.setSingleLine(false);
        text.setMaxLines(2);
        text.setEllipsize(null);
        text.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        text.setTextColor(pendingStatus ? 0xE6FFFFFF : Color.WHITE);
        int compact = 0;
        if (!shown.isEmpty()) {
            compact =
                    applyVisibleText(
                            text,
                            shown,
                            size,
                            inner,
                            measuredSpec,
                            ownedCaption && shownPage >= 0
                                    ? pendingPages.get(shownPage).linePlan
                                    : null);
            if (compact < 0) {
                shown = "";
                mode = "overflow_status";
                reason = "measurement_mismatch";
                if (ownedCaption && !retryLayout()) reason = "unprojectable_pixel_width";
            }
        }
        String detail =
                "id="
                        + pendingIdentity
                        + ";mode="
                        + mode
                        + (DeepSeekConfig.displayTextDebugEnabled(a)
                                ? debugDetail(
                                        text,
                                        shown,
                                        size,
                                        targetGlyphHeight,
                                        screenWidth,
                                        b.width(),
                                        cfg.captionSizeTier,
                                        fullScreen)
                                : "")
                        + ";available_width_px="
                        + inner
                        + ";measured_width_px="
                        + Math.max(0, compact);
        if (shown.isEmpty()) {
            text.setText("");
            hideView();
            lastBlankIdentity = pendingIdentity;
            if (ownedCaption && !reason.isEmpty())
                presentationDiagnostics(
                        a, pendingText, preferred, inner, pendingEnd - pendingStart, reason);
            if (mode.equals("overflow_status") && !detail.equals(lastNotice))
                CaptionDiagnostics.mark(a, "REBUILD_LAYOUT_FALLBACK", detail + ";reason=" + reason);
            lastNotice = detail;
            displayResult(a, mode, reason, false, inner, size, detail, applyStarted);
            return;
        }
        lastBlankIdentity = null;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SubtitleStyleMetrics.alpha(cfg.backgroundOpacity) << 24);
        bg.setCornerRadius(dp(a, 4));
        text.setBackground(bg);
        // Time and owner are sampled again at UI application, after the potentially expensive
        // layout.
        if (currentGuard != null) {
            if (!currentGuard.isValid()) {
                text.setText("");
                hideView();
                displayResult(
                        a, "empty", "owner_invalid", false, inner, size, detail, applyStarted);
                return;
            }
            long latest = currentGuard.displayPosition(pendingPosition);
            if (timed && !inWindow(latest)) {
                pendingPosition = latest;
                text.setText("");
                shownPage = -1;
                hideView();
                dirty = true;
                displayResult(
                        a, "empty", "outside_owned_window", false, inner, size, "", applyStarted);
                return;
            }
            pendingPosition = latest;
            if (ownedCaption && CaptionPageLayout.indexAt(pendingPages, latest) != shownPage) {
                dirty = true;
                if (!reconcilingDisplayTime) {
                    reconcilingDisplayTime = true;
                    try {
                        render();
                    } finally {
                        reconcilingDisplayTime = false;
                    }
                } else {
                    text.setText("");
                    hideView();
                    requestPlayerRender();
                }
                return;
            }
        }
        if (ownedCaption && text.getMeasuredHeight() > b.height()) {
            text.setText("");
            hideView();
            presentationDiagnostics(
                    a, shown, size, inner, pendingEnd - pendingStart, "hard_textview_geometry");
            displayResult(
                    a, "empty", "hard_textview_geometry", false, inner, size, detail, applyStarted);
            return;
        }
        if (ownedCaption) {
            CaptionPageLayout.Page page = pendingPages.get(shownPage);
            presentationDiagnostics(
                    a,
                    shown,
                    size,
                    compact - text.getPaddingLeft() - text.getPaddingRight(),
                    page.end - page.start,
                    "");
        }
        placeVisibleText(a, host, anchor, text, b, width);
        layoutHoldSince = -1;
        if (anchor.getVisibility() != View.VISIBLE) {
            anchor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            anchor.setVisibility(View.VISIBLE);
        }
        final long appliedOwner = CaptionPlayerAuthority.ownerEpoch(),
                appliedRender = playerRenderEpoch;
        final String appliedIdentity = pendingIdentity;
        final RenderGuard appliedGuard = currentGuard;
        horizontalPlacement.observe(
                a,
                b,
                shorts ? "shorts" : fullScreen ? "fullscreen" : "detail",
                appliedGuard == null ? "" : appliedGuard.session(),
                appliedOwner,
                appliedRender,
                () ->
                        appliedOwner == CaptionPlayerAuthority.ownerEpoch()
                                && appliedRender == playerRenderEpoch
                                && appliedIdentity.equals(pendingIdentity)
                                && appliedGuard == currentGuard
                                && (appliedGuard == null || appliedGuard.isValid())
                                && !blocked());
        if ((mode.equals("original_fallback") || mode.equals("overflow_status"))
                && !detail.equals(lastNotice))
            CaptionDiagnostics.mark(a, "REBUILD_LAYOUT_FALLBACK", detail);
        lastNotice = detail;
        displayResult(
                a,
                mode,
                pendingWaiting ? "pending_translation" : "",
                true,
                inner,
                size,
                detail,
                applyStarted);
    }

    /**
     * A page whose real measurement does not fit gets one fresh layout; a second failure marks it
     * unprojectable so the box stays honestly empty.
     *
     * @return false when it gave up
     */
    private static boolean retryLayout() {
        if (measurementMismatch != 0) {
            failedLayout = desiredLayout;
            return false;
        }
        measurementMismatch = 1;
        LAYOUTS.remove(desiredLayout);
        dirty = true;
        final String identity = pendingIdentity;
        MAIN.post(
                () -> {
                    if (identity.equals(pendingIdentity)) {
                        dirty = true;
                        lastBlankIdentity = null;
                        render();
                    }
                });
        return true;
    }

    private static String debugDetail(
            TextView text,
            String shown,
            float size,
            float targetGlyphHeight,
            int screenWidth,
            int videoWidth,
            int tier,
            boolean fullScreen) {
        CaptionPageLayout.Page page = shownPage >= 0 ? pendingPages.get(shownPage) : null;
        return ";text_size_px="
                + size
                + ";target_glyph_height_px="
                + targetGlyphHeight
                + ";glyph_height_px="
                + SubtitleStyleMetrics.measuredGlyphHeightPx(text.getPaint())
                + ";screen_width_px="
                + screenWidth
                + ";video_width_px="
                + videoWidth
                + ";normal_video_width_px="
                + normalVideoWidth
                + ";size_tier="
                + tier
                + ";size_mode="
                + (fullScreen ? "full_screen" : "detail")
                + ";lines="
                + (shown.isEmpty() ? 0 : text.getLineCount())
                + (page == null
                        ? ";pagination_unresolved=true"
                        : ";page="
                                + (shownPage + 1)
                                + "/"
                                + pendingPages.size()
                                + ";page_range="
                                + page.start
                                + "-"
                                + page.end
                                + ";page_basis="
                                + page.timingBasis);
    }

    // ---- Measuring and placing ----

    /**
     * Both the ordinary render and the prepared-page advance apply the same content geometry.
     *
     * @return the box width in pixels, or -1 when the text does not fit even at full width
     */
    private static int applyVisibleText(
            TextView text,
            String shown,
            float size,
            int maximumInnerPx,
            CaptionRenderSpec spec,
            CaptionLinePlan line) {
        boolean same =
                CaptionSettingPreference.sameText(text.getText(), shown)
                        && text.getTextSize() == size
                        && text.getPaint().equalsForTextMeasurement(spec.paint(size));
        spec.apply(text);
        if (text.getTextSize() != size) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        if (!CaptionSettingPreference.sameText(text.getText(), shown)) text.setText(shown);
        if (text.getMaxLines() != 2) {
            text.setSingleLine(false);
            text.setMaxLines(2);
        }
        if (text.getEllipsize() != null) text.setEllipsize(null);
        if (text.getAutoSizeTextType() != TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            text.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        if (text.getMinEms() != -1) text.setMinEms(-1);
        if (text.getMaxEms() != Integer.MAX_VALUE) text.setMaxEms(Integer.MAX_VALUE);
        if (text.getMinWidth() != 0) text.setMinWidth(0);
        if (text.getMinimumWidth() != 0) text.setMinimumWidth(0);
        int padding = text.getPaddingLeft() + text.getPaddingRight();
        float contentWidthPx =
                line != null
                        ? Math.max(line.line1Px, line.line2Px)
                        : same && text.getLayout() != null && text.getLayout().getLineCount() == 1
                                ? text.getLayout().getLineMax(0)
                                : spec.actualWidth(spec.layout(shown, size, maximumInnerPx));
        int boxWidthPx =
                Math.min(maximumInnerPx, Math.max(1, (int) Math.ceil(contentWidthPx) + 2))
                        + padding;
        for (int pass = 0; pass < 2; pass++) {
            ViewGroup.LayoutParams params = text.getLayoutParams();
            if (same
                    && !text.isLayoutRequested()
                    && params.width == boxWidthPx
                    && params.height == ViewGroup.LayoutParams.WRAP_CONTENT
                    && text.getMeasuredWidth() == boxWidthPx
                    && spec.fits(shown, text.getLayout(), boxWidthPx - padding, 2))
                return boxWidthPx;
            if (text.getMaxWidth() != boxWidthPx) text.setMaxWidth(boxWidthPx);
            if (params.width != boxWidthPx
                    || params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.width = boxWidthPx;
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                text.setLayoutParams(params);
            }
            text.measure(
                    View.MeasureSpec.makeMeasureSpec(boxWidthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (spec.fits(shown, text.getLayout(), boxWidthPx - padding, 2)) return boxWidthPx;
            if (boxWidthPx == maximumInnerPx + padding) break;
            boxWidthPx = maximumInnerPx + padding;
            Activity owner = activityRef.get();
            if (owner != null && DeepSeekConfig.displayTextDebugEnabled(owner))
                CaptionDiagnostics.mark(
                        owner,
                        "CAPTION_COMPACT_MEASURE_RETRY",
                        "reason=font_geometry_mismatch;maximum_inner_px=" + maximumInnerPx);
        }
        return -1;
    }

    private static void placeVisibleText(
            Activity owner,
            FrameLayout host,
            FrameLayout anchor,
            TextView text,
            Rect bounds,
            int anchorWidthPx) {
        if (owner == null || host == null || anchor == null || bounds.isEmpty()) return;
        int height = text.getMeasuredHeight();
        float y =
                CaptionSurface.isShorts()
                        ? DeepSeekConfig.shortsPosition(owner)
                        : DeepSeekConfig.captionPositionY(owner, bounds.width() > bounds.height());
        int top =
                Math.max(
                        bounds.top,
                        Math.min(
                                bounds.bottom - height,
                                bounds.top + Math.round(bounds.height() * y) - height / 2));
        CaptionHorizontalPlacement.place(host, anchor, bounds, anchorWidthPx, height, top);
    }

    static int compactWidthPx(String value, float sizePx, int maximum, CaptionRenderSpec spec) {
        return Math.min(
                maximum,
                Math.max(
                        1,
                        (int) Math.ceil(spec.actualWidth(spec.layout(value, sizePx, maximum)))
                                + 2));
    }

    private static int lineCount(String value, float sizePx, int width, CaptionRenderSpec spec) {
        return spec.layout(value, sizePx, width).getLineCount();
    }

    // ---- Diagnostics ----

    private static void displayResult(
            Activity a,
            String mode,
            String reason,
            boolean visible,
            int width,
            float size,
            String detail,
            long applyStarted) {
        if (a == null) return;
        TextView view = textRef.get();
        String shown = visible && view != null ? view.getText().toString() : "";
        String projection =
                "|font="
                        + (layoutBudget == null ? "" : layoutBudget.renderSpec.measurementKey())
                        + "|event="
                        + pendingIdentity
                        + "|owner="
                        + CaptionPlayerAuthority.ownerEpoch();
        String key =
                pendingIdentity
                        + "|"
                        + mode
                        + "|"
                        + reason
                        + "|"
                        + visible
                        + "|"
                        + shown
                        + "|"
                        + shownPage
                        + "|"
                        + width
                        + "|"
                        + size
                        + projection;
        if (key.equals(lastDisplayResult)) return;
        lastDisplayResult = key;
        long elapsed = Math.max(0, SystemClock.uptimeMillis() - applyStarted);
        String result =
                (detail.isEmpty() ? "id=" + pendingIdentity + ";mode=" + mode : detail)
                        + ";ui_applied=true;visible="
                        + visible
                        + ";reason="
                        + reason
                        + ";render_position="
                        + pendingPosition
                        + ";window="
                        + pendingStart
                        + "-"
                        + pendingEnd
                        + ";applied_wall_ms="
                        + System.currentTimeMillis()
                        + ";applied_uptime_ms="
                        + SystemClock.uptimeMillis()
                        + ";dispatch_uptime_ms="
                        + applyStarted
                        + ";main_apply_elapsed_ms="
                        + elapsed
                        + ";desired_box_width_px="
                        + (view == null ? 0 : view.getLayoutParams().width)
                        + ";measured_box_width_px="
                        + (view == null ? 0 : view.getMeasuredWidth())
                        + ";laid_out_box_width_px="
                        + (view == null ? 0 : view.getWidth())
                        + ";presentation_revision=upstream-ac65fe8"
                        + ";text="
                        + shown;
        CaptionCredentialRef credential =
                currentGuard == null ? CaptionCredentialRef.NONE : currentGuard.credential();
        // Records for the same event, page and geometry merge in the bounded queue and are
        // exported once with their count, keeping the first display and any real change exact.
        String recordKey =
                "DISPLAY|"
                        + pendingIdentity
                        + "|"
                        + shownPage
                        + "|"
                        + mode
                        + "|"
                        + reason
                        + "|"
                        + visible
                        + "|"
                        + width
                        + "|"
                        + size
                        + projection;
        if (DeepSeekConfig.displayTextDebugEnabled(a))
            CaptionDiagnostics.mark(a, "REBUILD_PRESENTED", result, credential, recordKey);
        CaptionDiagnostics.mark(a, "REBUILD_DISPLAY_RESULT", result, credential, recordKey);
    }

    private static void presentationDiagnostics(
            Activity a, String shown, float size, int width, long duration, String reason) {
        String notice =
                pendingIdentity
                        + "|"
                        + pendingRenderSpec.targetCode
                        + "|"
                        + shown
                        + "|"
                        + size
                        + "|"
                        + width
                        + "|"
                        + duration
                        + "|"
                        + reason
                        + "|"
                        + shownPage;
        if (notice.equals(lastPresentationNotice)) return;
        lastPresentationNotice = notice;
        CaptionPageLayout.Page page =
                shownPage >= 0 && shownPage < pendingPages.size()
                        ? pendingPages.get(shownPage)
                        : null;
        CaptionLanguageProfile profile = pendingRenderSpec.profile;
        String detail =
                "id="
                        + pendingIdentity
                        + ";target_code="
                        + pendingRenderSpec.targetCode
                        + ";presentation_policy="
                        + pendingRenderSpec.presentationPolicy
                        + ";duration_ms="
                        + duration
                        + ";available_width_px="
                        + width
                        + ";reading_units="
                        + pendingRenderSpec.readingUnits(shown)
                        + ";reading_rate_limit="
                        + profile.referenceCps
                        + ";minimum_page_wall_ms="
                        + profile.minimumPageMs
                        + ";maximum_recommended_page_wall_ms="
                        + profile.softPageDurationMs
                        + ";page_duration_above_recommendation="
                        + (duration > profile.softPageDurationMs)
                        + (page == null
                                ? ""
                                : ";line1_px="
                                        + page.linePlan.line1Px
                                        + ";line2_px="
                                        + page.linePlan.line2Px);
        if (reason.isEmpty())
            CaptionDiagnostics.mark(a, "REBUILD_PRESENTATION", detail + ";hard_reject=false");
        else
            CaptionDiagnostics.mark(
                    a, "REBUILD_PRESENTATION_HARD_REJECT", detail + ";reason=" + reason);
    }

    // ---- Dragging the caption vertically ----

    private static boolean drag(View v, MotionEvent e) {
        Activity a = activityRef.get();
        if (a == null || previous.height() <= 0 || !anchorVisible() || blocked()) {
            dragging = false;
            return false;
        }
        boolean landscape = previous.width() > previous.height();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY();
                downAt = SystemClock.uptimeMillis();
                dragging = false;
                initial =
                        CaptionSurface.isShorts()
                                ? DeepSeekConfig.shortsPosition(a)
                                : DeepSeekConfig.captionPositionY(a, landscape);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && SystemClock.uptimeMillis() - downAt >= DRAG_AFTER_MS) {
                    dragging = true;
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                if (dragging) {
                    float y = initial + (e.getRawY() - downY) / previous.height();
                    y = Math.max(.08f, Math.min(.92f, y));
                    if (CaptionSurface.isShorts()) DeepSeekConfig.saveShortsPosition(a, y);
                    else DeepSeekConfig.saveCaptionPosition(a, landscape, y);
                    dirty = true;
                    render();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return false;
        }
    }

    private static int dp(Context c, float x) {
        return Math.round(x * c.getResources().getDisplayMetrics().density);
    }
}
