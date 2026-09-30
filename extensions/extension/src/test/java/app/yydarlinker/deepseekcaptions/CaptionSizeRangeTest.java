package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CaptionSizeRangeTest {
    @Test public void bothLegacyUnitsAreObsoleteAndNeverReadAsScreenRatios() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            activity.getSharedPreferences("deepseek_caption_translator", 0)
                    .edit().putInt("caption_text_size", 15)
                    .putInt("caption_text_size_tenths", 270).apply();
            assertEquals(203, DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
            assertEquals(203, DeepSeekConfig.load(activity).captionGlyphHeightRatioBps);
            assertEquals(203, new SubtitleStylePreview.Preview(activity).ratioBps);
            assertFalse(activity.getSharedPreferences("deepseek_caption_translator", 0)
                    .contains("caption_glyph_height_ratio_bps"));
            // Even legacy values of incompatible preference types must not be accessed.
            activity.getSharedPreferences("deepseek_caption_translator", 0)
                    .edit().putFloat("caption_text_size", 13f)
                    .putString("caption_text_size_tenths", "226").apply();
            assertEquals(203, DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
            DeepSeekConfig.saveCaptionGlyphHeightRatioBps(activity, 232);
            assertEquals(232, DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
            assertEquals("226", activity.getSharedPreferences("deepseek_caption_translator", 0)
                    .getString("caption_text_size_tenths", ""));
        } finally {
            activity.finish();
        }
    }
    @Test public void screenRatioUsesIntegerBasisPointsAndClampsApprovedRange() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            assertEquals(203, DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
            DeepSeekConfig.saveCaptionGlyphHeightRatioBps(activity, 1);
            assertEquals(150, DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
            SubtitleStylePreview.Preview minimumPreview = new SubtitleStylePreview.Preview(activity);
            assertEquals(150, minimumPreview.ratioBps);
            assertEquals(41.04f, SubtitleStyleMetrics.targetGlyphHeightPx(minimumPreview.ratioBps,
                    SubtitleStylePreview.LANDSCAPE_REFERENCE_WIDTH_PX), .001f);
            DeepSeekConfig.saveCaptionGlyphHeightRatioBps(activity, 999);
            assertEquals(300, DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
            SubtitleStylePreview.Preview maximumPreview = new SubtitleStylePreview.Preview(activity);
            assertEquals(300, maximumPreview.ratioBps);
            assertEquals(82.08f, SubtitleStyleMetrics.targetGlyphHeightPx(maximumPreview.ratioBps,
                    SubtitleStylePreview.LANDSCAPE_REFERENCE_WIDTH_PX), .001f);
            DeepSeekConfig.saveCaptionGlyphHeightRatioBps(activity, 232);
            assertEquals(232, DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
            assertEquals(232, activity.getSharedPreferences("deepseek_caption_translator", 0)
                    .getInt("caption_glyph_height_ratio_bps", -1));
            activity.getSharedPreferences("deepseek_caption_translator", 0)
                    .edit().putInt("caption_glyph_height_ratio_bps", -1).apply();
            assertEquals(150, DeepSeekConfig.load(activity).captionGlyphHeightRatioBps);
        } finally {
            activity.finish();
        }
    }
}
