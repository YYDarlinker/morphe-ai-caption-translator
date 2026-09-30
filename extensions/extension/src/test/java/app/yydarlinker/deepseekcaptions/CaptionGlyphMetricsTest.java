package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.graphics.Paint;
import android.graphics.Typeface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CaptionGlyphMetricsTest {
    @Test public void currentFontCalibrationHitsBothPixelAnchorsWithoutChangingPaint() {
        for (Typeface font : new Typeface[]{Typeface.DEFAULT,Typeface.SERIF,Typeface.MONOSPACE}) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(font);paint.setTextSize(37);
            for (float target : new float[]{25.6f,55.5f}) {
                float size = SubtitleStyleMetrics.textSizePxForGlyphHeight(paint,target);
                assertEquals(37,paint.getTextSize(),.001f);
                Paint rendered = new Paint(paint);rendered.setTextSize(size);
                assertEquals(target,SubtitleStyleMetrics.measuredGlyphHeightPx(rendered),.5f);
                assertTrue(SubtitleStyleMetrics.fontMetricsHeightPx(rendered)>0);
            }
        }
    }

    @Test public void ratioPrecisionAndContractionHaveNoAbsoluteSizeFloor() {
        assertEquals(25.6592f,SubtitleStyleMetrics.targetGlyphHeightPx(203,1264),.0001f);
        assertEquals(55.5408f,SubtitleStyleMetrics.targetGlyphHeightPx(203,2736),.0001f);
        assertEquals(55.5408f,SubtitleStyleMetrics.renderedGlyphHeightPx(203,2736,2188.8f,2736),.0001f);
        assertEquals(11.10816f,SubtitleStyleMetrics.renderedGlyphHeightPx(203,2736,547.2f,2736),.0001f);
    }
}
