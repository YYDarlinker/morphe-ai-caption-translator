package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The viewport class is the only size/pagination authority; display orientation is not. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w2736dp-h1264dp-mdpi")
public class CaptionPresentationClassTest {
    private static final int TIER = 2;

    @Test
    public void landscapeFullscreenUsesTheFullReadingSize() {
        Rect video = new Rect(0, 0, 2247, 1264);
        CaptionPresentationClass.Kind kind =
                CaptionPresentationClass.classify(null, video, 2736, 1264, false);
        assertEquals(CaptionPresentationClass.Kind.FULL, kind);
        assertEquals(55.5f, CaptionPresentationClass.glyphHeightPx(TIER, 2736, 1264, kind), .01f);
    }

    @Test
    public void commentsSheetInLandscapeUsesTheDetailReadingSize() {
        Rect video = new Rect(0, 0, 1478, 831);
        CaptionPresentationClass.Kind kind =
                CaptionPresentationClass.classify(null, video, 2736, 1264, false);
        assertEquals(CaptionPresentationClass.Kind.REDUCED, kind);
        assertEquals(44.5f, CaptionPresentationClass.glyphHeightPx(TIER, 2736, 1264, kind), .01f);
    }

    @Test
    public void portraitDetailUsesTheDetailReadingSize() {
        Rect video = new Rect(0, 0, 1264, 711);
        CaptionPresentationClass.Kind kind =
                CaptionPresentationClass.classify(null, video, 1264, 2736, false);
        assertEquals(CaptionPresentationClass.Kind.DETAIL, kind);
        assertEquals(44.5f, CaptionPresentationClass.glyphHeightPx(TIER, 1264, 2736, kind), .01f);
    }

    @Test
    public void hysteresisPreventsASheetAnimationFromFlippingBackAndForth() {
        Rect stillReduced = new Rect(0, 0, 1800, 1012);
        Rect clearlyFull = new Rect(0, 0, 2100, 1181);
        Rect reducedFromFull = new Rect(0, 0, 1750, 984);
        assertEquals(
                CaptionPresentationClass.Kind.REDUCED,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.REDUCED, stillReduced, 2736, 1264, false));
        assertEquals(
                CaptionPresentationClass.Kind.FULL,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.REDUCED, clearlyFull, 2736, 1264, false));
        assertEquals(
                CaptionPresentationClass.Kind.FULL,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.FULL, clearlyFull, 2736, 1264, false));
        assertEquals(
                CaptionPresentationClass.Kind.REDUCED,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.FULL, reducedFromFull, 2736, 1264, false));
    }

    @Test
    public void shortsAlwaysUsesTheDetailReadingSizeEvenWithCommentsOpen() {
        Rect full = new Rect(0, 0, 1264, 2500);
        Rect reduced = new Rect(0, 0, 1264, 1700);
        assertEquals(
                CaptionPresentationClass.Kind.SHORTS_FULL,
                CaptionPresentationClass.classify(null, full, 1264, 2736, true));
        assertEquals(
                "opening Shorts comments changes the viewport, not the reading size",
                CaptionPresentationClass.Kind.SHORTS_FULL,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.SHORTS_FULL, reduced, 1264, 2736, true));
        assertEquals(
                44.5f,
                CaptionPresentationClass.glyphHeightPx(
                        TIER, 1264, 2736, CaptionPresentationClass.Kind.SHORTS_FULL),
                .01f);
        assertEquals(
                44.5f,
                CaptionPresentationClass.glyphHeightPx(
                        TIER, 1264, 2736, CaptionPresentationClass.Kind.SHORTS_REDUCED),
                .01f);
    }

    @Test
    public void aRotatedLandscapeSurfaceDoesNotBecomeTheDetailFrameWidth() {
        Rect stillLandscape = new Rect(231, 0, 2366, 853);
        assertEquals(
                "the old landscape frame must not be relabelled DETAIL during rotation",
                CaptionPresentationClass.Kind.FULL,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.FULL, stillLandscape, 1264, 2736, false));
        assertEquals(
                "the detail layout width is capped by the portrait display",
                1264,
                CaptionPresentationClass.layoutVideoWidth(
                        CaptionPresentationClass.Kind.DETAIL,
                        stillLandscape,
                        1264,
                        2736,
                        0));
        assertEquals(
                "a tall mid-swipe viewport is not the detail page yet",
                CaptionPresentationClass.Kind.FULL,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.FULL,
                        new Rect(0, 0, 1264, 2000),
                        1264,
                        2736,
                        false));
    }

    @Test
    public void letterboxHeightChangesDoNotFlipTheStableClass() {
        // The same 2135 px wide surface with the visible height changing between frames used to
        // yield ratios .798 and .884 because the reference came from the current aspect.
        Rect shortCrop = new Rect(231, 0, 2366, 1009);
        Rect tallCrop = new Rect(231, 0, 2366, 1118);
        assertEquals(
                CaptionPresentationClass.Kind.FULL,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.FULL, shortCrop, 2736, 1264, false));
        assertEquals(
                CaptionPresentationClass.Kind.FULL,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.FULL, tallCrop, 2736, 1264, false));
        assertEquals(
                CaptionPresentationClass.Kind.FULL,
                CaptionPresentationClass.classify(
                        CaptionPresentationClass.Kind.REDUCED, tallCrop, 2736, 1264, false));
    }

    @Test
    public void firstReducedLayoutUsesAConservativeStableWidth() {
        Rect reduced = new Rect(0, 0, 1478, 831);
        assertEquals(
                1478,
                CaptionPresentationClass.layoutVideoWidth(
                        CaptionPresentationClass.Kind.REDUCED, reduced, 2736, 1264, 0));
        assertEquals(
                1400,
                CaptionPresentationClass.layoutVideoWidth(
                        CaptionPresentationClass.Kind.REDUCED, reduced, 2736, 1264, 1400));
    }

    @Test
    public void fullLayoutClampsTransientOverscanToTheRealFullWidth() {
        Rect overscan = new Rect(0, 0, 2599, 1462);
        assertEquals(
                2247,
                CaptionPresentationClass.layoutVideoWidth(
                        CaptionPresentationClass.Kind.FULL, overscan, 2736, 1264, 0));
    }
}
