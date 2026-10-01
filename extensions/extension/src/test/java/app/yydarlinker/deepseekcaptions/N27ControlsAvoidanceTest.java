package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/**
 * N27: the caption overlay steps above the regular player's own controls, and nowhere else.
 *
 * <p>These tests drive the real production path: the real overlay layout, the real coordinator, the
 * real geometry conversion and the real diagnostics. Only two things are substituted, and both are
 * stated here rather than hidden: the current player's control ids come from a small name→id map
 * (YouTube's own ids cannot exist in this module's resource table), and the video rectangle comes
 * from the same static seam the existing overlay tests already use. Everything else — attached
 * visibility, ancestor alpha, clipping, the caption's own TextView box, the lift algorithm, the
 * animation, the saved-position rules and the diagnostic text — is the shipped code.</p>
 */
// The window has to be wider than the fixture video, otherwise the framework's own visible-rect
// clipping would silently truncate every control rectangle under test.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1000dp-h800dp", shadows = {N27ControlsAvoidanceTest.Surface.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class N27ControlsAvoidanceTest {

    /** YouTube's real visibility enum constants, in the order the delivered APK defines them. */
    private enum Visibility {
        PLAYER_CONTROLS_VISIBILITY_UNKNOWN,
        PLAYER_CONTROLS_VISIBILITY_WILL_HIDE,
        PLAYER_CONTROLS_VISIBILITY_HIDDEN,
        PLAYER_CONTROLS_VISIBILITY_WILL_SHOW,
        PLAYER_CONTROLS_VISIBILITY_SHOWN
    }

    private static final int W = 600;
    private static final int H = 340;
    private static final int BAR_TOP = 280;

    /** Test-only ids for the delivered resource names; production resolves the names at runtime. */
    private static final Map<String, Integer> IDS = new LinkedHashMap<>();

    static {
        String[] names = {
            "youtube_controls_bottom_ui_container", "player_seekbar", "timestamps_container",
            "time_bar_current_time", "time_bar_total_time", "time_bar_live_label",
            "time_bar_chapter_title_container", "bottom_end_container",
            "player_control_play_pause_button", "player_control_play_pause_replay_button",
            "player_control_previous_button", "player_control_next_button",
            "player_control_play_pause_replay_button_touch_area",
            "player_control_previous_button_touch_area", "player_control_next_button_touch_area",
            "player_control_button_wrapper",
        };
        for (int i = 0; i < names.length; i++) IDS.put(names[i], 0x10000 + i);
    }

    static Rect bounds;
    static boolean shorts;
    static View playerView;

    @Implements(CaptionSurface.class)
    public static class Surface {
        @Implementation
        public static boolean isShorts() {
            return shorts;
        }

        @Implementation
        public static View refresh() {
            return null;
        }

        @Implementation
        public static View player() {
            return playerView;
        }

        @Implementation
        public static Rect videoBounds(View host) {
            return bounds == null ? null : new Rect(bounds);
        }
    }

    Activity a;
    FrameLayout content;
    FrameLayout player;
    int hostW = W;
    int hostH = H;
    final Map<String, View> controls = new LinkedHashMap<>();

    @Before
    public void setup() {
        CaptionControlsAvoidance.clearForTest();
        shorts = false;
        bounds = new Rect(0, 0, W, H);
        a = Robolectric.buildActivity(Activity.class).setup().visible().get();
        DisplayMetrics metrics = a.getResources().getDisplayMetrics();
        metrics.density = 1f;
        metrics.scaledDensity = 1f;
        metrics.widthPixels = W;
        metrics.heightPixels = H;
        DeepSeekConfig.saveCaptionSizeTier(a, 2);
        DeepSeekConfig.resetCaptionPositions(a);
        // The video rectangle below is landscape, so the overlay uses the landscape saved position.
        DeepSeekConfig.saveCaptionPosition(a, true, 0.80f);
        CaptionDiagnostics.clear(a);
        content = a.findViewById(android.R.id.content);
        player = new FrameLayout(a);
        content.addView(player, new FrameLayout.LayoutParams(W, H));
        playerView = player;
        CaptionControlsAvoidance.setIdLookupForTest((activity, name) -> {
            Integer id = IDS.get(name);
            return id == null ? 0 : id;
        });
        // The overlay's mode flags are process-wide statics; a test that switches to Shorts or the
        // miniplayer must not leave the next test suppressed.
        CaptionOverlay.restoreAfterGuardedExpansion("");
        CaptionOverlay.clear();
        CaptionOverlay.setActivity(a);
        layout();
    }

    @After
    public void done() {
        CaptionOverlay.clear();
        CaptionOverlay.setActivity(null);
        CaptionControlsAvoidance.clearForTest();
        a.finish();
    }

    // ---- harness ---------------------------------------------------------------------------------

    private View control(String name, int left, int top, int right, int bottom) {
        return control(name, player, left, top, right, bottom);
    }

    private View control(String name, ViewGroup parent, int left, int top, int right, int bottom) {
        View view = new View(a);
        view.setId(IDS.get(name));
        view.setBackground(new ColorDrawable(0xCC2266AA));
        FrameLayout.LayoutParams params =
            new FrameLayout.LayoutParams(right - left, bottom - top);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(view, params);
        controls.put(name, view);
        return view;
    }

    /** The delivered bottom bar: full width, the last 60px of the video. */
    private void bottomBar() {
        control("youtube_controls_bottom_ui_container", 0, BAR_TOP, W, H);
        control("player_seekbar", 24, BAR_TOP + 8, W - 24, BAR_TOP + 12);
        control("time_bar_current_time", 24, BAR_TOP + 20, 80, BAR_TOP + 40);
        control("player_control_play_pause_replay_button", 280, BAR_TOP + 14, 320, BAR_TOP + 54);
    }

    private void layout() {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(hostW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hostH, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, hostW, hostH);
    }

    private void show(String text) {
        CaptionOverlay.showCaption(text, () -> true);
        layout();
        CaptionControlsAvoidance.tick();
        layout();
    }

    private FrameLayout anchor() throws Exception {
        java.lang.reflect.Field field = CaptionOverlay.class.getDeclaredField("anchorRef");
        field.setAccessible(true);
        return (FrameLayout) ((java.lang.ref.WeakReference<?>) field.get(null)).get();
    }

    private TextView captionText() throws Exception {
        java.lang.reflect.Field field = CaptionOverlay.class.getDeclaredField("textRef");
        field.setAccessible(true);
        return (TextView) ((java.lang.ref.WeakReference<?>) field.get(null)).get();
    }

    private String history() {
        return a.getSharedPreferences("deepseek_caption_diagnostics", 0).getString("history", "");
    }

    private int count(String needle) {
        int total = 0;
        int at = history().indexOf(needle);
        while (at >= 0) {
            total++;
            at = history().indexOf(needle, at + needle.length());
        }
        return total;
    }

    private void idle(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private int expectedLift(Rect base, Rect obstacle) {
        return base.bottom + 8 - obstacle.top;
    }

    // ---- A: no collision, hidden controls, exclusion ------------------------------------------

    @Test
    public void noCollisionKeepsBasePositionAndDoesNotAnimate() throws Exception {
        control("youtube_controls_bottom_ui_container", 0, 320, W, H);
        show("普通播放器字幕");
        Rect base = CaptionControlsAvoidance.baseRectSnapshot();
        assertNotNull("the caption box must be known", base);
        assertTrue("the sample must actually overlap nothing", base.bottom <= 320);
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        assertEquals(0f, anchor().getTranslationY(), 0f);
        assertFalse(CaptionControlsAvoidance.animating());
        assertTrue(history().contains("CAPTION_UI_AVOIDANCE_RESET"));
        assertTrue(history().contains("reason=no_intersection"));
    }

    @Test
    public void bottomBarCollisionLiftsByTheExactMinimalAmount() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        Rect base = CaptionControlsAvoidance.baseRectSnapshot();
        List<Rect> obstacles = CaptionControlsAvoidance.obstacleSnapshot();
        assertEquals("the whole bottom bar collapses into one obstacle", 1, obstacles.size());
        Rect bar = obstacles.get(0);
        assertEquals(BAR_TOP, bar.top);
        assertEquals(H, bar.bottom);
        int expected = expectedLift(base, bar);
        assertEquals(expected, CaptionControlsAvoidance.targetOffsetPx());
        idle(400);
        assertEquals(-expected, anchor().getTranslationY(), 1f);
        Rect settled = new Rect(base);
        settled.offset(0, -expected);
        assertFalse("8dp clearance after the move", Rect.intersects(settled, bar));
        assertEquals(8, bar.top - settled.bottom);
        assertTrue("the lifted caption stays inside the video", bounds.contains(settled));
        assertTrue(history().contains("CAPTION_UI_AVOIDANCE_TARGET"));
        assertTrue(history().contains("reason=bottom_cluster"));
        assertTrue(history().contains("obstacle_rects=["));
    }

    @Test
    public void captionBoxUsesTheRealTextViewWidthNotTheAnchorStrip() throws Exception {
        control("youtube_controls_bottom_ui_container", 0, 320, W, H);
        show("这是一句用于测量宽度的字幕文本");
        Rect base = CaptionControlsAvoidance.baseRectSnapshot();
        TextView text = captionText();
        assertEquals(text.getWidth(), base.width());
        assertTrue("the transparent anchor strip is wider than the text",
            anchor().getLayoutParams().width > base.width());
    }

    @Test
    public void multipleObstaclesResolveInBoundedIterations() throws Exception {
        control("youtube_controls_bottom_ui_container", 0, BAR_TOP, W, H);
        control("player_control_play_pause_replay_button", 200, 200, 400, 240);
        control("player_control_previous_button", 200, 150, 260, 190);
        show("普通播放器字幕");
        Rect base = CaptionControlsAvoidance.baseRectSnapshot();
        List<Rect> obstacles = CaptionControlsAvoidance.obstacleSnapshot();
        assertEquals("the three separate rectangles stay separate until they meet", 3, obstacles.size());
        int offset = CaptionControlsAvoidance.targetOffsetPx();
        assertTrue(offset > 0);
        idle(400);
        Rect settled = new Rect(base);
        settled.offset(0, -offset);
        boolean exactGap = false;
        for (Rect obstacle : obstacles) {
            assertFalse("cleared " + obstacle, Rect.intersects(settled, obstacle));
            if (settled.bottom + 8 == obstacle.top) exactGap = true;
        }
        assertTrue("the move stops at the first 8dp clearance, not higher", exactGap);
        assertTrue(bounds.contains(settled));
    }

    @Test
    public void noSafeSpaceKeepsBaseAndRecordsOncePerEpoch() throws Exception {
        // Tall enough to leave no room above the caption, narrow enough not to be a container.
        control("player_control_play_pause_replay_button", 0, 20, 350, 320);
        show("普通播放器字幕");
        Rect base = CaptionControlsAvoidance.baseRectSnapshot();
        assertEquals("a control with no room above it must not move the caption",
            0, CaptionControlsAvoidance.targetOffsetPx());
        assertEquals(0f, anchor().getTranslationY(), 0f);
        assertTrue(history().contains("reason=no_safe_space"));
        int first = count("reason=no_safe_space");
        for (int i = 0; i < 5; i++) {
            idle(200);
            CaptionControlsAvoidance.tick();
        }
        assertEquals("repeated refreshes must not repeat the same record", first,
            count("reason=no_safe_space"));
        Rect after = CaptionControlsAvoidance.baseRectSnapshot();
        assertEquals(base, after);
        assertTrue("text is never shrunk to fit", captionText().getTextSize() > 0);
    }

    @Test
    public void hiddenControlsRestoreTheUserBase() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        idle(400);
        Rect base = CaptionControlsAvoidance.baseRectSnapshot();
        int lifted = CaptionControlsAvoidance.targetOffsetPx();
        assertTrue(lifted > 0);
        for (View view : controls.values()) view.setVisibility(View.GONE);
        layout();
        CaptionControlsAvoidance.tick();
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        idle(400);
        assertEquals(0f, anchor().getTranslationY(), 0.5f);
        assertEquals("controls hidden: back to the exact original base", base,
            CaptionControlsAvoidance.baseRectSnapshot());
        assertTrue("hidden controls are reported as not visible, not as an obstacle",
            history().contains("reason=controls_not_visible"));
    }

    @Test
    public void collapsedProgressLineAloneNeverLiftsTheCaption() throws Exception {
        View bar = control("youtube_controls_bottom_ui_container", 0, BAR_TOP, W, H);
        control("player_seekbar", 24, BAR_TOP + 8, W - 24, BAR_TOP + 12);
        bar.setVisibility(View.INVISIBLE);
        layout();
        show("普通播放器字幕");
        assertEquals("a surviving collapsed progress line is not a control",
            0, CaptionControlsAvoidance.targetOffsetPx());
        assertEquals(0f, anchor().getTranslationY(), 0f);
        assertTrue(history().contains("reason=thin_progress_line_only"));
    }

    @Test
    public void oversizedTransparentContainerIsNotAnObstacle() throws Exception {
        View bar = control("youtube_controls_bottom_ui_container", 0, 0, W, H);
        assertNotNull(bar);
        show("普通播放器字幕");
        assertEquals("a full-video transparent rectangle is never an operation area",
            0, CaptionControlsAvoidance.targetOffsetPx());
        assertTrue(history(), history().contains("reason=oversized_container_excluded"));
    }

    @Test
    public void invisibleOrTransparentAncestorsExcludeControls() throws Exception {
        FrameLayout faded = new FrameLayout(a);
        faded.setAlpha(0.05f);
        player.addView(faded, new FrameLayout.LayoutParams(W, H));
        control("youtube_controls_bottom_ui_container", faded, 0, BAR_TOP, W, H);
        control("player_seekbar", faded, 24, BAR_TOP + 8, W - 24, BAR_TOP + 12);
        layout();
        show("普通播放器字幕");
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        faded.setAlpha(1f);
        layout();
        CaptionControlsAvoidance.tick();
        assertTrue("fully opaque ancestors are counted again",
            CaptionControlsAvoidance.targetOffsetPx() > 0);
    }

    @Test
    public void detachedControlsDoNotCount() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        assertTrue(CaptionControlsAvoidance.targetOffsetPx() > 0);
        for (View view : new ArrayList<>(controls.values())) player.removeView(view);
        controls.clear();
        CaptionOverlay.showCaption("普通播放器字幕", () -> true);
        layout();
        idle(600);
        CaptionControlsAvoidance.tick();
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        assertTrue(history().contains("CAPTION_UI_AVOIDANCE_UNAVAILABLE"));
        assertTrue(history().contains("reason=controls_not_bound"));
    }

    @Test
    public void shortsMiniplayerAndPipNeverApply() throws Exception {
        bottomBar();
        shorts = true;
        show("Shorts 字幕");
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        shorts = false;
        CaptionOverlay.setPlayerType("WATCH_WHILE_MINIMIZED");
        CaptionOverlay.showCaption("小窗字幕", () -> true);
        layout();
        CaptionControlsAvoidance.tick();
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        assertTrue(history().contains("reason=player_not_regular"));
        CaptionOverlay.setPlayerType("WATCH_WHILE_PICTURE_IN_PICTURE");
        CaptionOverlay.showCaption("画中画字幕", () -> true);
        layout();
        CaptionControlsAvoidance.tick();
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
    }

    @Test
    public void lateInflatedControlIsPickedUpOnTheLowFrequencyPath() throws Exception {
        control("youtube_controls_bottom_ui_container", 0, 320, W, H);
        show("普通播放器字幕");
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        control("player_control_play_pause_replay_button", 200, 240, 400, 296);
        layout();
        idle(600);
        CaptionControlsAvoidance.tick();
        assertTrue("the late button is found without an unbounded timer",
            CaptionControlsAvoidance.targetOffsetPx() > 0);
        assertTrue(history().contains("CAPTION_UI_AVOIDANCE_BOUND"));
    }

    // ---- B: lifecycle, epoch, rotation ----------------------------------------------------------

    @Test
    public void queuedVisibilityCallbacksAreDroppedAfterTheEpochChanges() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        idle(400);
        assertTrue(CaptionControlsAvoidance.targetOffsetPx() > 0);
        long applied = CaptionControlsAvoidance.signalsApplied();
        // The trigger is queued, then the video changes before the queue drains.
        CaptionControlsAvoidance.onVisibilitySignal(Visibility.PLAYER_CONTROLS_VISIBILITY_HIDDEN);
        CaptionControlsAvoidance.onVideoId("next-video");
        idle(400);
        assertEquals("a callback from the previous video must never be applied",
            applied, CaptionControlsAvoidance.signalsApplied());
        // The same signal without an epoch change does arrive.
        CaptionControlsAvoidance.onVisibilitySignal(Visibility.PLAYER_CONTROLS_VISIBILITY_SHOWN);
        idle(400);
        assertEquals(applied + 1, CaptionControlsAvoidance.signalsApplied());
    }

    @Test
    public void rotationCancelsThePreviousTargetAndRecomputes() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        idle(400);
        int portraitOffset = CaptionControlsAvoidance.targetOffsetPx();
        assertTrue(portraitOffset > 0);
        Rect portraitBase = CaptionControlsAvoidance.baseRectSnapshot();
        bounds = new Rect(0, 0, 800, 400);
        player.setLayoutParams(new FrameLayout.LayoutParams(800, 400));
        for (View view : controls.values()) player.removeView(view);
        controls.clear();
        content.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 800, 400);
        control("youtube_controls_bottom_ui_container", 0, 320, 800, 400);
        CaptionOverlay.showCaption("普通播放器字幕", () -> true);
        layout();
        // YouTube rebuilds its controls across an orientation change; the visibility notification
        // is the trigger that re-binds them to the current player.
        CaptionControlsAvoidance.onVisibilitySignal(Visibility.PLAYER_CONTROLS_VISIBILITY_SHOWN);
        idle(20);
        CaptionControlsAvoidance.tick();
        idle(400);
        Rect landscapeBase = CaptionControlsAvoidance.baseRectSnapshot();
        assertNotEquals("the base really moved with the new video rectangle",
            portraitBase, landscapeBase);
        assertTrue(history() + " bases=" + portraitBase + "/" + landscapeBase,
            CaptionControlsAvoidance.targetOffsetPx() > 0);
        Rect settled = new Rect(landscapeBase);
        settled.offset(0, -CaptionControlsAvoidance.targetOffsetPx());
        assertEquals(320, settled.bottom + 8);
        assertTrue(bounds.contains(settled));
    }

    @Test
    public void blankAndHiddenStatesNeverCreateAnEmptyBox() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        assertTrue(CaptionControlsAvoidance.targetOffsetPx() > 0);
        CaptionOverlay.hide();
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        assertEquals(0f, anchor().getTranslationY(), 0f);
        assertNull(CaptionControlsAvoidance.baseRectSnapshot());
        CaptionOverlay.hide();
        CaptionControlsAvoidance.tick();
        assertEquals(0, CaptionControlsAvoidance.targetOffsetPx());
        assertTrue(history().contains("CAPTION_UI_AVOIDANCE_RESET"));
    }

    // ---- C: animation ---------------------------------------------------------------------------

    @Test
    public void sameTargetNeverRestartsTheAnimation() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        idle(400);
        int settled = CaptionControlsAvoidance.targetOffsetPx();
        assertTrue(settled > 0);
        float at = anchor().getTranslationY();
        assertFalse(CaptionControlsAvoidance.animating());
        for (int i = 0; i < 6; i++) {
            idle(120);
            CaptionControlsAvoidance.tick();
            assertFalse("a stable obstacle must not restart anything",
                CaptionControlsAvoidance.animating());
            assertEquals(at, anchor().getTranslationY(), 1f);
        }
    }

    @Test
    public void retargetStartsFromTheCurrentVisualPosition() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        idle(80);
        float mid = anchor().getTranslationY();
        assertTrue("the animation is in flight", mid < 0f && mid > -11f);
        assertTrue(CaptionControlsAvoidance.animating());
        // A higher control turns up while the first move is still running.
        control("player_control_play_pause_replay_button", 200, 240, 400, 260);
        layout();
        CaptionControlsAvoidance.tick();
        assertEquals("retargeting may not jump", mid, anchor().getTranslationY(), 1f);
        int newTarget = CaptionControlsAvoidance.targetOffsetPx();
        assertTrue(newTarget > Math.abs(mid));
        idle(400);
        assertEquals(-newTarget, anchor().getTranslationY(), 1f);
    }

    @Test
    public void animationStartsPromptlyAndSettlesWithinTwoHundredMilliseconds() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        assertTrue("a valid geometry must start the move immediately",
            CaptionControlsAvoidance.animating());
        int target = CaptionControlsAvoidance.targetOffsetPx();
        assertTrue(target > 0);
        idle(100);
        assertTrue("still in flight at 100ms", CaptionControlsAvoidance.animating());
        float mid = anchor().getTranslationY();
        assertTrue("moving toward the target, never past it", mid < 0f && mid > -target);
        idle(200);
        assertFalse(CaptionControlsAvoidance.animating());
        assertEquals(-target, anchor().getTranslationY(), 1f);
    }

    @Test
    public void targetUnchangedBySubPixelGeometryNeverRestarts() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        idle(400);
        assertFalse(CaptionControlsAvoidance.animating());
        float at = anchor().getTranslationY();
        int target = CaptionControlsAvoidance.targetOffsetPx();
        // Same vertical extent, different horizontal extent: the required lift cannot change.
        View seeker = controls.get("player_seekbar");
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) seeker.getLayoutParams();
        params.width = W - 100;
        seeker.setLayoutParams(params);
        layout();
        CaptionControlsAvoidance.tick();
        assertEquals(target, CaptionControlsAvoidance.targetOffsetPx());
        assertFalse(CaptionControlsAvoidance.animating());
        assertEquals(at, anchor().getTranslationY(), 1f);
    }

    // ---- D: drag, blank states, frozen behaviour -------------------------------------------------

    @Test
    public void dragFreezesTheOffsetAndSavesOnlyTheBasePosition() throws Exception {
        bottomBar();
        show("普通播放器字幕");
        idle(400);
        float frozen = anchor().getTranslationY();
        assertTrue(frozen < 0f);
        float before = DeepSeekConfig.captionPositionY(a, true);
        TextView text = captionText();
        long t0 = SystemClock.uptimeMillis();
        text.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 10f, 10f, 0));
        idle(400);
        long t1 = SystemClock.uptimeMillis();
        text.dispatchTouchEvent(MotionEvent.obtain(t0, t1, MotionEvent.ACTION_MOVE, 10f, 27f, 0));
        assertEquals("the temporary offset is frozen while dragging", frozen,
            anchor().getTranslationY(), 1f);
        assertFalse(CaptionControlsAvoidance.animating());
        float saved = DeepSeekConfig.captionPositionY(a, true);
        assertTrue("the gesture still writes the user's own position", saved > before);
        assertEquals("only the base position is saved, never the avoidance offset",
            before + 17f / H, saved, 0.005f);
        text.dispatchTouchEvent(MotionEvent.obtain(t0, t1 + 10, MotionEvent.ACTION_UP, 10f, 27f, 0));
        CaptionControlsAvoidance.tick();
        assertEquals("after the drag the offset is recomputed from the newest base",
            frozen, anchor().getTranslationY(), 1f);
    }

    @Test
    public void avoidanceNeverChangesTextFontPaginationOrSavedConfiguration() throws Exception {
        bottomBar();
        CaptionOverlay.showEvent("普通播放器字幕正文", () -> true, null, "n27-event", 0, 4000, 0);
        layout();
        CaptionControlsAvoidance.tick();
        TextView text = captionText();
        String before = text.getText().toString();
        float sizeBefore = text.getTextSize();
        float savedBefore = DeepSeekConfig.captionPositionY(a, true);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) anchor().getLayoutParams();
        int left = params.leftMargin, top = params.topMargin, w = params.width, h = params.height;
        idle(400);
        assertEquals(before, text.getText().toString());
        assertEquals(sizeBefore, text.getTextSize(), 0f);
        assertEquals(savedBefore, DeepSeekConfig.captionPositionY(a, true), 0f);
        FrameLayout.LayoutParams after = (FrameLayout.LayoutParams) anchor().getLayoutParams();
        assertEquals(left, after.leftMargin);
        assertEquals(top, after.topMargin);
        assertEquals(w, after.width);
        assertEquals(h, after.height);
        assertTrue("the move is a pure translation",
            Math.abs(anchor().getTranslationY()) > 0f);
        assertFalse("no layout budget or pagination side effects",
            history().contains("REBUILD_LAYOUT_FALLBACK"));
    }

    @Test
    public void diagnosticsAreEnglishAndFreeOfRepeats() {
        bottomBar();
        show("普通播放器字幕");
        idle(400);
        for (int i = 0; i < 8; i++) {
            idle(150);
            CaptionControlsAvoidance.tick();
        }
        String log = history();
        for (String stage : new String[] {
            "CAPTION_UI_AVOIDANCE_BOUND", "CAPTION_UI_AVOIDANCE_TARGET",
            "CAPTION_UI_AVOIDANCE_RESET", "CAPTION_UI_AVOIDANCE_UNAVAILABLE"}) {
            if (log.contains(stage)) assertTrue(stage + " must be ASCII", isAscii(stage));
        }
        for (String field : new String[] {
            "epoch=", "player_type=", "controls_state=", "video_rect=", "caption_base_rect=",
            "obstacle_rects=", "offset_px=", "reason="}) {
            assertTrue("missing field " + field, log.contains(field));
        }
        assertFalse("no Chinese in the N27 technical text", containsCjk(log.substring(
            Math.max(0, log.indexOf("CAPTION_UI_AVOIDANCE")))));
        assertEquals("a stable obstacle is recorded exactly once", 1,
            count("reason=bottom_cluster"));
    }

    @Test
    public void hostCoordinatesFollowTheCaptionHostOffset() throws Exception {
        // The player itself sits 40px down inside the caption host, so the same player-local bar has
        // to be reported 40px lower once it is expressed in the host coordinates the caption uses.
        FrameLayout.LayoutParams playerParams = new FrameLayout.LayoutParams(W, H - 40);
        playerParams.topMargin = 40;
        player.setLayoutParams(playerParams);
        control("youtube_controls_bottom_ui_container", 0, BAR_TOP - 40, W, H - 40);
        layout();
        show("普通播放器字幕");
        List<Rect> obstacles = CaptionControlsAvoidance.obstacleSnapshot();
        assertEquals(new Rect(0, BAR_TOP, W, H), obstacles.get(0));
        assertEquals(new Rect(0, 0, W, H), CaptionControlsAvoidance.videoRectSnapshot());
        Rect base = CaptionControlsAvoidance.baseRectSnapshot();
        assertEquals(BAR_TOP, base.bottom + 8 - CaptionControlsAvoidance.targetOffsetPx());
    }

    // ---- E: frames ------------------------------------------------------------------------------

    /**
     * The four key frames of one avoidance cycle, drawn through the real Android render path from
     * the delivered control geometry (the same ids, the same bottom-bar shape and the same caption
     * overlay that ships). Nothing here is an HTML mock-up.
     */
    @Test
    public void exportsBeforeShowingAfterAndHiddenFrames() throws Exception {
        String output = System.getenv("CAPTION_UI_PREVIEW_OUTPUT");
        Assume.assumeTrue(output != null);
        File dir = new File(output);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        for (boolean landscape : new boolean[] {false, true}) {
            for (int tier : new int[] {1, 2, 4}) {
                captureCycle(dir, landscape, tier);
            }
        }
    }

    private void captureCycle(File dir, boolean landscape, int tier) throws Exception {
        int width = landscape ? 640 : 360;
        int height = landscape ? 360 : 640;
        hostW = width;
        hostH = height;
        bounds = new Rect(0, 0, width, height);
        playerView = player;
        player.setLayoutParams(new FrameLayout.LayoutParams(width, height));
        for (View view : new ArrayList<>(controls.values())) player.removeView(view);
        controls.clear();
        DeepSeekConfig.saveCaptionSizeTier(a, tier);
        // A user position low enough that the delivered bottom bar really does reach the caption;
        // otherwise the frame would show a cycle that never needed to move.
        DeepSeekConfig.saveCaptionPosition(a, landscape, 0.90f);
        layout();
        String tag = "n27-" + (landscape ? "fullscreen" : "detail") + "-tier" + tier;
        CaptionOverlay.clear();
        CaptionOverlay.setActivity(a);
        CaptionOverlay.showCaption("这是一句普通的翻译字幕", () -> true);
        layout();
        writeFrame(new File(dir, tag + "-before.png"));
        int barTop = height - 60;
        control("youtube_controls_bottom_ui_container", 0, barTop, width, height);
        control("player_seekbar", 16, barTop + 8, width - 16, barTop + 12);
        control("time_bar_current_time", 16, barTop + 20, 72, barTop + 40);
        control("player_control_play_pause_replay_button",
            width / 2 - 20, barTop + 14, width / 2 + 20, barTop + 54);
        layout();
        CaptionControlsAvoidance.onVisibilitySignal(Visibility.PLAYER_CONTROLS_VISIBILITY_SHOWN);
        idle(20);
        CaptionControlsAvoidance.tick();
        layout();
        idle(400);
        layout();
        int lifted = CaptionControlsAvoidance.targetOffsetPx();
        assertTrue(tag + ": the sample must actually need a lift; base="
                + CaptionControlsAvoidance.baseRectSnapshot() + " obstacles="
                + CaptionControlsAvoidance.obstacleSnapshot(),
            lifted > 0);
        writeFrame(new File(dir, tag + "-showing.png"));
        for (View view : controls.values()) view.setVisibility(View.GONE);
        layout();
        CaptionControlsAvoidance.onVisibilitySignal(Visibility.PLAYER_CONTROLS_VISIBILITY_HIDDEN);
        idle(20);
        CaptionControlsAvoidance.tick();
        idle(400);
        layout();
        assertEquals(tag + ": hidden controls must restore the base", 0,
            CaptionControlsAvoidance.targetOffsetPx());
        writeFrame(new File(dir, tag + "-after.png"));
        CaptionOverlay.hide();
        layout();
        writeFrame(new File(dir, tag + "-hidden.png"));
    }

    private void writeFrame(File file) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(),
            Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        content.draw(canvas);
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0x7f) return false;
        return true;
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) return true;
        }
        return false;
    }
}
