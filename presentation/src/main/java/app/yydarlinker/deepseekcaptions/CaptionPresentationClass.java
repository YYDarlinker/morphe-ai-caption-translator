package app.yydarlinker.deepseekcaptions;

import android.graphics.Rect;

/**
 * One stable presentation class for the caption. It drives glyph size, pagination width and the
 * visible box; geometry only moves the already committed box.
 *
 * <p>The class is derived from the real video viewport, not from the display orientation. A
 * landscape screen with the comments sheet open is still a landscape display, but its video
 * viewport is the reduced one and must use the detail reading size. Hysteresis prevents the class
 * from flipping back and forth while YouTube animates the sheet.
 *
 * <p>The full-viewport reference is a fixed 16:9 width, not the current surface aspect. A video
 * Surface's visible height can change while its width stays the same (letterboxing, controls,
 * rounded corners), and deriving the ratio from that height made FULL and REDUCED alternate on a
 * stable viewport. Width is the reading dimension; height is only a crop.
 */
final class CaptionPresentationClass {
    enum Kind {
        FULL,
        REDUCED,
        DETAIL,
        SHORTS_FULL,
        SHORTS_REDUCED
    }

    /** Reference width used by the measured detail/full glyph heights. */
    static final float REFERENCE_SHORT_SIDE_PX = 1264f;

    /** Enter REDUCED below this share of the full video width; leave above EXIT. */
    static final float ENTER_REDUCED = .80f;

    static final float EXIT_REDUCED = .82f;

    /** The aspect of the full-viewport reference width, independent of the displayed crop. */
    private static final float REFERENCE_ASPECT = 16f / 9f;

    private CaptionPresentationClass() {}

    /**
     * Classify the current viewport. [previous] is the last class (nullable); [screenWidth] and
     * [screenHeight] are the display metrics, [shorts] the verified Shorts surface.
     */
    static Kind classify(
            Kind previous,
            Rect video,
            int screenWidth,
            int screenHeight,
            boolean shorts) {
        return classify(previous, video, 0, screenWidth, screenHeight, shorts);
    }

    /**
     * [observedVideoWidth] is the video's on-screen width, which follows a player-container scale
     * even when the local rectangle does not. Comments-sheet decisions use it so the class can
     * commit at the sheet's endpoint instead of waiting for the local rectangle to be rebuilt.
     */
    static Kind classify(
            Kind previous,
            Rect video,
            int observedVideoWidth,
            int screenWidth,
            int screenHeight,
            boolean shorts) {
        if (video == null || video.width() <= 0 || video.height() <= 0)
            return previous == null ? Kind.DETAIL : previous;
        if (shorts) {
            // Shorts always reads at the detail size. Opening the comments sheet changes the
            // viewport height and position, never the reading size or the page width, so one
            // class also means one plan and no re-layout during the sheet animation.
            return Kind.SHORTS_FULL;
        }
        // Portrait watch page. A rotation can expose the old landscape-sized surface for a few
        // frames; that is not a detail viewport yet, and calling it DETAIL here captured a
        // fullscreen frame width that was later used on the 1264 px detail page.
        if (screenWidth <= screenHeight) {
            if (previous != null && video.width() > screenWidth * 1.02f) return previous;
            // A swipe from fullscreen to the detail page still shows a tall video for most of
            // the gesture. Keep the fullsize reading until the viewport has actually become the
            // short detail rectangle, so the caption cannot shrink half-way down.
            if (previous != null
                    && previous != Kind.DETAIL
                    && video.height() > screenHeight * .62f) return previous;
            return Kind.DETAIL;
        }
        int reference = referenceVideoWidth(screenWidth, screenHeight);
        int width =
                observedVideoWidth > 0
                        ? observedVideoWidth
                        : Math.max(1, video.width());
        width = Math.min(width, Math.max(1, screenWidth));
        float ratio = width / (float) reference;
        if (previous == Kind.REDUCED) return ratio >= EXIT_REDUCED ? Kind.FULL : Kind.REDUCED;
        if (previous == Kind.FULL) return ratio > ENTER_REDUCED ? Kind.FULL : Kind.REDUCED;
        return ratio <= ENTER_REDUCED ? Kind.REDUCED : Kind.FULL;
    }

    /**
     * The stable full-viewport video width: 16:9 fitted into the display. It never depends on the
     * surface's current visible height, which changes when controls or letterboxing animate.
     */
    static int referenceVideoWidth(int screenWidth, int screenHeight) {
        if (screenWidth <= screenHeight) return Math.max(1, screenWidth);
        return Math.max(
                1, Math.min(screenWidth, Math.round(screenHeight * REFERENCE_ASPECT)));
    }

    /** Kept for diagnostics/tests: the reference width, never the transient surface aspect. */
    static int fullVideoWidth(Rect video, int screenWidth, int screenHeight) {
        return referenceVideoWidth(screenWidth, screenHeight);
    }

    /**
     * Full-screen reading size only for a full viewport. Shorts uses the detail size, and so does
     * every reduced or portrait viewport.
     */
    static boolean detailSize(Kind kind) {
        return kind != Kind.FULL;
    }

    /**
     * Absolute glyph ink height for the class. The reference heights are physical sizes measured
     * on a 1264 px short side; scale them with the actual short side so a 1080 px phone does not
     * get a caption calibrated for a tablet.
     */
    static float glyphHeightPx(int tier, int screenWidth, int screenHeight, Kind kind) {
        float shortSide = Math.min(screenWidth, screenHeight);
        float base =
                detailSize(kind)
                        ? CaptionFontSize.detailGlyphHeightPx(tier)
                        : CaptionFontSize.fullScreenGlyphHeightPx(tier);
        return base * (shortSide / REFERENCE_SHORT_SIDE_PX);
    }

    /**
     * The video width a class should lay its pages out against. FULL uses the stable full-viewport
     * width, not a transient overscan; REDUCED uses the remembered reduced width when one exists,
     * capped at the reduced threshold; DETAIL and Shorts use the displayed video width, capped by
     * the display width so a rotated frame can never create an off-screen box.
     */
    static int layoutVideoWidth(
            Kind kind, Rect video, int screenWidth, int screenHeight, int rememberedReducedWidth) {
        int width = Math.max(1, video == null ? 1 : video.width());
        int reference = referenceVideoWidth(screenWidth, screenHeight);
        if (kind == Kind.FULL) return Math.min(width, reference);
        if (kind == Kind.REDUCED) {
            int cap = Math.max(1, Math.round(reference * ENTER_REDUCED));
            int target =
                    rememberedReducedWidth > 0
                            ? Math.min(rememberedReducedWidth, cap)
                            : Math.round(reference * .66f);
            return Math.max(1, Math.min(width, target));
        }
        return Math.max(1, Math.min(width, Math.max(1, screenWidth)));
    }
}
