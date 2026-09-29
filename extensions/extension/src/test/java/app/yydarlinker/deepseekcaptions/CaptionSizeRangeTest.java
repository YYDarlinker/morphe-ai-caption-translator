package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CaptionSizeRangeTest {
    @Test public void legacyRelativeSizeIsNotInterpretedAsAbsoluteSp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            activity.getSharedPreferences("deepseek_caption_translator", 0)
                    .edit().putInt("caption_text_size", 15).apply();
            assertEquals(22.6f, DeepSeekConfig.displayStyle(activity).captionTextSize, .001f);
            DeepSeekConfig.saveCaptionTextSize(activity, 23.2f);
            assertEquals(23.2f, DeepSeekConfig.displayStyle(activity).captionTextSize, .001f);
        } finally {
            activity.finish();
        }
    }
    @Test public void absoluteSizeUsesTenthsAndClampsCandidateRange() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            assertEquals(22.6f, DeepSeekConfig.displayStyle(activity).captionTextSize, .001f);
            DeepSeekConfig.saveCaptionTextSize(activity, 1);
            assertEquals(18f, DeepSeekConfig.displayStyle(activity).captionTextSize, .001f);
            DeepSeekConfig.saveCaptionTextSize(activity, 99);
            assertEquals(27f, DeepSeekConfig.displayStyle(activity).captionTextSize, .001f);
            DeepSeekConfig.saveCaptionTextSize(activity, 23.18f);
            assertEquals(23.2f, DeepSeekConfig.displayStyle(activity).captionTextSize, .001f);
        } finally {
            activity.finish();
        }
    }
}
