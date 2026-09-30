package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class CaptionRatioSettingsTest {
    @Test public void sliderShowsPortraitPixelsInBothScreenOrientationsAndSavesOnlyOnRelease(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
        int originalWidth=metrics.widthPixels,originalHeight=metrics.heightPixels;
        try {
            for(boolean landscape:new boolean[]{false,true}){
                metrics.widthPixels=landscape?2736:1264;
                metrics.heightPixels=landscape?1264:2736;
                DeepSeekConfig.saveCaptionGlyphHeightRatioBps(activity,203);
                SubtitleStylePreview style=new SubtitleStylePreview(activity);
                View previewRoot=style.onCreateView(new FrameLayout(activity));
                SubtitleStylePreview.Preview preview=(SubtitleStylePreview.Preview)
                        previewRoot.findViewWithTag("ai_style_preview_canvas");
                DeepSeekSliderPreference preference=new DeepSeekSliderPreference(activity);
                preference.setKey(DeepSeekSliderPreference.KEY_TEXT_SIZE);
                preference.setTitle(CaptionStrings.settings(activity,"size"));
                LinearLayout root=(LinearLayout)preference.onCreateView(new FrameLayout(activity));
                SeekBar slider=(SeekBar)root.getChildAt(1);
                TextView value=(TextView)((LinearLayout)root.getChildAt(0)).getChildAt(1);
                assertEquals(150,slider.getMax());assertEquals(53,slider.getProgress());
                assertEquals("25.7 px · 2.03%",value.getText().toString());
                SeekBar.OnSeekBarChangeListener listener=Shadows.shadowOf(slider).getOnSeekBarChangeListener();
                slider.setProgress(150);
                listener.onProgressChanged(slider,150,true);
                assertEquals("37.9 px · 3.00%",value.getText().toString());
                assertEquals(300,preview.ratioBps);
                assertEquals(203,DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
                listener.onStopTrackingTouch(slider);
                assertEquals(300,DeepSeekConfig.displayStyle(activity).captionGlyphHeightRatioBps);
            }
        } finally {
            metrics.widthPixels=originalWidth;metrics.heightPixels=originalHeight;
            activity.finish();
        }
    }

    @Test public void shippedSizeDescriptionStatesPixelsAndScreenRatio(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            String summary=CaptionStrings.settings(activity,"size_hint");
            assertTrue(summary,summary.contains("px"));
            assertTrue(summary,summary.contains("2.03%"));
            assertFalse(summary,summary.contains("sp"));
            assertTrue(CaptionStrings.settings(activity,"size").contains("Shorts"));
        } finally { activity.finish(); }
    }
}
