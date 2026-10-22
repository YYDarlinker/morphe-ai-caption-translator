package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The unified caption layer and size contract, on a watch page whose player and comment sheet are
 * separate branches:
 *
 * <ul>
 *   <li>the overlay is a child of the video plane, so the sheet (a later sibling of the player) is
 *       drawn above it by construction;
 *   <li>opening the sheet may change the video rectangle, but never the glyph size: size follows the
 *       player mode and display width only;
 *   <li>the narrower rectangle still re-projects the same text into whole-clause pages.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w2736dp-h1264dp-mdpi", shadows = {CaptionLayerOverlayTest.Layer.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CaptionLayerOverlayTest {
    @Implements(CaptionSurface.class)
    public static class Layer {
        static FrameLayout host;
        static Rect video = new Rect(0, 0, 2247, 1264);
        static int globalWidth = 2247;
        static int globalHeight = 1264;

        @Implementation
        public static FrameLayout captionHost() {
            return host;
        }

        @Implementation
        public static int visibleVideoWidth() {
            return globalWidth;
        }

        @Implementation
        public static int visibleVideoHeight() {
            return globalHeight;
        }

        @Implementation
        public static View refresh() {
            return null;
        }

        @Implementation
        public static boolean isShorts() {
            return false;
        }

        @Implementation
        public static boolean shortsSurfaceNow() {
            return false;
        }

        @Implementation
        public static Rect currentVideoBounds(View h) {
            return new Rect(video);
        }
    }

    private Activity a;
    private FrameLayout content, watchPage, player, plane, sheet;

    @Before
    public void setUp() {
        CaptionPlayerAuthority.resetForTests();
        CaptionSurface.invalidateGeometry();
        a = Robolectric.buildActivity(Activity.class).setup().visible().get();
        a.getResources().getDisplayMetrics().widthPixels = 2736;
        a.getResources().getDisplayMetrics().heightPixels = 1264;
        DeepSeekConfig.saveCaptionSizeTier(a, 2);
        content = new FrameLayout(a);
        a.setContentView(content);
        watchPage = new FrameLayout(a);
        content.addView(watchPage, new FrameLayout.LayoutParams(2736, 1264));
        player = new FrameLayout(a);
        watchPage.addView(player, new FrameLayout.LayoutParams(2247, 1264));
        plane = new FrameLayout(a);
        player.addView(plane, new FrameLayout.LayoutParams(2247, 1264));
        // The comment sheet is drawn after the player branch: any child of the player is below it.
        sheet = new FrameLayout(a);
        watchPage.addView(sheet, new FrameLayout.LayoutParams(2736, 1264));
        layout();
        Layer.host = plane;
        Layer.video = new Rect(0, 0, 2247, 1264);
        Layer.globalWidth = 2247;
        Layer.globalHeight = 1264;
        CaptionActions.install(
                new app.yydarlinker.caption.contracts.CaptionContracts.CaptionPort() {
                    public void command(app.yydarlinker.caption.contracts.CaptionContracts.Action action, String value) {}
                    public boolean active() { return false; }
                    public boolean ownsNativeTrack() { return false; }
                    public String restoreTarget(String u) { return u; }
                    public String videoId() { return "fixture"; }
                    public void playback(long t) {}
                },
                new CaptionActions.SettingsServices() {
                    public String testApi() { return "fixture"; }
                    public List<String> models(String endpoint) { return Collections.emptyList(); }
                    public List<CaptionDisplayPage> displayPages(
                            String body,
                            long start,
                            long end,
                            CaptionPresentationBudget geometry,
                            CaptionRenderSpec spec) {
                        return TranslationPresentation.pages(body, start, end, geometry, spec);
                    }
                });
        CaptionOverlay.resetPresentationDedupForTests();
        CaptionOverlay.clear();
        CaptionOverlay.setActivity(a);
    }

    @After
    public void tearDown() {
        CaptionOverlay.clear();
        CaptionOverlay.setActivity(null);
        CaptionPlayerAuthority.resetForTests();
        CaptionSurface.invalidateGeometry();
        Layer.host = null;
        a.finish();
    }

    private void layout() {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(2736, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1264, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 2736, 1264);
    }

    private void show(String text, String id) {
        CaptionOverlay.showEvent(
                text,
                () -> true,
                id,
                1000,
                61000,
                2000,
                CaptionLanguageContext.explicit("en", "zh-Hans").renderSpec);
        layout();
    }

    private TextView text() throws Exception {
        return (TextView) field("textRef", true);
    }

    private FrameLayout anchor() throws Exception {
        return (FrameLayout) field("anchorRef", true);
    }

    @SuppressWarnings("unchecked")
    private List<CaptionPageLayout.Page> pages() throws Exception {
        return (List<CaptionPageLayout.Page>) field("pendingPages", false);
    }

    private Object field(String name, boolean weak) throws Exception {
        Field f = CaptionOverlay.class.getDeclaredField(name);
        f.setAccessible(true);
        Object value = f.get(null);
        return weak && value instanceof java.lang.ref.WeakReference
                ? ((java.lang.ref.WeakReference<?>) value).get()
                : value;
    }

    private void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
    }

    /** Robolectric does not always dispatch the host's pre-draw listener after a manual layout. */
    private void pump() throws Exception {
        Method geometry = CaptionOverlay.class.getDeclaredMethod("geometryFrame");
        geometry.setAccessible(true);
        geometry.invoke(null);
        Method render = CaptionOverlay.class.getDeclaredMethod("render");
        render.setAccessible(true);
        render.invoke(null);
    }

    private void awaitCaption() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            idle();
            TextView view = text();
            if (CaptionOverlay.anchorVisible() && view != null && view.getText().length() > 0) return;
            Thread.sleep(2);
        }
        fail("caption never became visible");
    }

    private void awaitPlan(int innerWidth) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            layout();
            idle();
            pump();
            CaptionPresentationBudget budget = CaptionOverlay.budget();
            if (budget != null && budget.width == innerWidth && !pages().isEmpty()) return;
            Thread.sleep(2);
        }
        fail(
                "plan for inner width "
                        + innerWidth
                        + " never became ready; budget="
                        + (CaptionOverlay.budget() == null ? -1 : CaptionOverlay.budget().width)
                        + ";box="
                        + (anchor() == null ? -1 : anchor().getWidth())
                        + ";plan="
                        + field("desiredLayout", false));
    }

    private int innerWidth(int videoWidth) throws Exception {
        TextView view = text();
        int padding = view.getPaddingLeft() + view.getPaddingRight();
        return Math.round(videoWidth * .92f) - padding;
    }

    @Test
    public void theCaptionIsInsideThePlayerBranchAndTheSheetIsDrawnAboveIt() throws Exception {
        show("剧透一下：下一期我会分享一份指南。", "layer");
        awaitCaption();

        FrameLayout anchor = anchor();
        assertSame(plane, anchor.getParent());
        assertNotSame(content, anchor.getParent());
        assertTrue(
                "the sheet must be drawn after the player branch",
                watchPage.indexOfChild(sheet) > watchPage.indexOfChild(player));
        View current = anchor;
        boolean insidePlayer = false;
        while (current != null) {
            if (current == player) insidePlayer = true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        assertTrue("the caption must be a descendant of the player", insidePlayer);
    }

    @Test
    public void theReadingSizeFollowsTheSheetScaleBeforeTheClassCommits() throws Exception {
        show("剧透一下：下一期我会分享一份指南。", "scale:together");
        awaitCaption();
        assertEquals(55.5f, measuredGlyphHeight(text()), 0.6f);
        Layer.globalWidth = 1850; // half-way through the sheet's on-screen scale
        Layer.globalHeight = 1050;
        layout();
        pump();
        assertEquals(
                "the binary class waits for the sheet endpoint",
                CaptionPresentationClass.Kind.FULL,
                field("presentationClass", false));
        float midScale = text().getScaleY();
        assertTrue("the reading size must already be shrinking: " + midScale, midScale < .96f);
        assertTrue("the scale must not jump to the full reduction: " + midScale, midScale > .8f);

        Layer.globalWidth = 1478;
        Layer.globalHeight = 831;
        Layer.video = new Rect(0, 0, 1478, 831);
        plane.setLayoutParams(new FrameLayout.LayoutParams(1478, 831));
        player.setLayoutParams(new FrameLayout.LayoutParams(1478, 831));
        layout();
        pump();
        awaitPlan(innerWidth(1478));
        assertEquals(
                CaptionPresentationClass.Kind.REDUCED,
                field("presentationClass", false));
        assertEquals("the endpoint commits the real detail font", 44.5f, measuredGlyphHeight(text()), 0.6f);
        assertEquals("the temporary scale is reset at the endpoint", 1f, text().getScaleY(), .01f);
    }

    @Test
    public void openingTheSheetSwitchesToDetailSizeAndRepaginatesOnce() throws Exception {
        show("剧透一下：下一期我会分享一份指南。", "size:wide");
        awaitCaption();
        float wideSize = text().getTextSize();
        int wideBudget = CaptionOverlay.budget().width;
        int wideBox = anchor().getWidth();
        Object widePlan = field("desiredLayout", false);
        assertEquals(55.5f, measuredGlyphHeight(text()), 0.6f);
        assertEquals(innerWidth(2247), wideBudget);

        Layer.video = new Rect(0, 0, 1478, 831);
        Layer.globalWidth = 1478;
        Layer.globalHeight = 831;
        plane.setLayoutParams(new FrameLayout.LayoutParams(1478, 831));
        player.setLayoutParams(new FrameLayout.LayoutParams(1478, 831));
        layout();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        layout();
        awaitPlan(innerWidth(1478));

        assertTrue(
                "a reduced landscape viewport must use the detail reading size",
                text().getTextSize() < wideSize);
        assertEquals(44.5f, measuredGlyphHeight(text()), 0.6f);
        assertEquals(
                "the class width must be the reduced viewport width",
                innerWidth(1478),
                CaptionOverlay.budget().width);
        assertNotEquals(
                "the class switch must commit exactly one new page plan",
                widePlan,
                field("desiredLayout", false));
        assertNotEquals(
                "the stable box must no longer be the fullscreen box",
                wideBox,
                anchor().getWidth());
        assertTrue(
                "the committed text must fit the reduced class width",
                (Integer) field("contentWidth", false) <= innerWidth(1478) + 12);
    }

    @Test
    public void aOneLinePageDoesNotInheritTheEventsTwoLineHeight() throws Exception {
        TextView view = new TextView(a);
        view.setLayoutParams(new FrameLayout.LayoutParams(1, 1));
        CaptionRenderSpec spec = CaptionLanguageContext.explicit("en", "zh-Hans").renderSpec;
        String text = "网上有很多视频声称，";
        Method apply =
                CaptionOverlay.class.getDeclaredMethod(
                        "applyVisibleText",
                        TextView.class,
                        String.class,
                        float.class,
                        int.class,
                        CaptionRenderSpec.class,
                        CaptionLinePlan.class,
                        int.class,
                        int.class);
        apply.setAccessible(true);
        CaptionLinePlan twoLines =
                new CaptionLinePlan(
                        text,
                        0,
                        text.length(),
                        text.length() / 2,
                        700f,
                        700f,
                        CaptionLinePlan.CutClass.MODEL_PHRASE);
        apply.invoke(null, view, text, 48.68f, 944, spec, twoLines, 764, 2);
        int twoLineHeight = view.getMinHeight();
        CaptionLinePlan oneLine =
                new CaptionLinePlan(
                        text,
                        0,
                        text.length(),
                        text.length(),
                        700f,
                        0f,
                        CaptionLinePlan.CutClass.MODEL_PHRASE);
        apply.invoke(null, view, text, 48.68f, 944, spec, oneLine, 764, 2);
        assertEquals(
                "one line of text plus padding",
                view.getLineHeight() + view.getPaddingTop() + view.getPaddingBottom(),
                view.getMinHeight());
        assertTrue(
                "the plan-wide two-line box must not survive into a one-line page",
                view.getMinHeight() < twoLineHeight);
    }

    @Test
    public void theNarrowerVideoReprojectsTheSameTextIntoWholeClausePages() throws Exception {
        String text =
                "中国的整体军事现代化，比仅让四辆坦克驶过红场更令人印象深刻。"
                        + "没有标点也能显示完整意思，而且这句话足够长，需要分成好几页显示。"
                        + "每一页都应该是一个完整的分句，不会被从中间切开。";
        show(text, "pages:wide");
        awaitCaption();
        awaitPlan(innerWidth(2247));
        int widePages = pages().size();
        assertTrue(widePages > 0);

        Layer.video = new Rect(0, 0, 700, 394);
        Layer.globalWidth = 700;
        Layer.globalHeight = 394;
        plane.setLayoutParams(new FrameLayout.LayoutParams(700, 394));
        layout();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        layout();
        awaitPlan(innerWidth(700));

        int narrowPages = pages().size();
        assertTrue(
                "expected more pages at the narrower width: wide=" + widePages + " narrow=" + narrowPages,
                narrowPages > widePages);
        StringBuilder joined = new StringBuilder();
        for (CaptionPageLayout.Page page : pages()) joined.append(page.text.replace("\n", ""));
        assertEquals(text, joined.toString());
    }

    private static float measuredGlyphHeight(TextView view) {
        return SubtitleStyleMetrics.measuredGlyphHeightPx(view.getPaint());
    }
}
