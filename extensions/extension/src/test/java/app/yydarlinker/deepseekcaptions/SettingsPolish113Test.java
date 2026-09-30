package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import static org.junit.Assert.*;

/** Actual native Android view rasterization in an isolated fixture, not a YouTube/device screenshot. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=28) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsPolish113Test {
    @Test public void previewScalesTheScreenRatioAndVideoIntoTheSameMiniature(){
        for(float width:new float[]{320,360,420,800})for(float height:new float[]{400,800}){
            float frame=SubtitleStylePreview.frameWidth(width,height,1);
            float wide=SubtitleStylePreview.stageHeight(width,height,1,false);
            float tall=SubtitleStylePreview.stageHeight(width,height,1,true);
            assertTrue(frame<=width);assertTrue(tall>=wide);
            assertEquals(frame*16f/9f,tall,.001f);
            assertTrue(tall<=height*.75f+1);
            assertTrue(wide>=frame*9f/16f);
            for(int ratioBps:new int[]{150,203,300}){
                float portrait=SubtitleStyleMetrics.previewGlyphHeightPx(ratioBps,width,width,frame);
                float landscape=SubtitleStyleMetrics.previewGlyphHeightPx(ratioBps,height,height,frame);
                assertEquals(ratioBps*frame/10000f,portrait,.001f);
                assertEquals(ratioBps*frame/10000f,landscape,.001f);
            }
        }
    }
    private void heading(LinearLayout root,String title){TextView label=new TextView(root.getContext());label.setText(title);CaptionSettingsStyle.caption(label);label.setTextSize(14);label.setPadding(20,20,20,6);root.addView(label);}
    private View row(android.preference.Preference preference,LinearLayout root){View row=preference.getView(null,new ListView(root.getContext()));root.addView(row,new LinearLayout.LayoutParams(-1,-2));return row;}
    @Test @Config(qualifiers="w420dp-h900dp") public void renderNativeLightAndDarkSettingsFixtures()throws Exception{
        for(boolean dark:new boolean[]{false,true})for(boolean portrait:new boolean[]{false,true}){
            Activity activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
            LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(dark?0xff0f0f0f:Color.WHITE);root.setLayoutParams(new FrameLayout.LayoutParams(420,-2));
            TextView top=new TextView(activity);top.setText("AI 字幕翻译");CaptionSettingsStyle.title(top);top.setTextSize(22);top.setPadding(20,20,20,20);root.addView(top);
            DeepSeekEnabledPreference enabled=new DeepSeekEnabledPreference(activity);enabled.setTitle("启用 AI 字幕翻译");row(enabled,root);
            heading(root,"API 配置");DeepSeekTextPreference url=new DeepSeekTextPreference(activity);url.setKey(DeepSeekTextPreference.KEY_BASE_URL);url.setTitle("API 地址");url.setSummary("兼容接口地址，停止输入后自动保存");View urlRow=row(url,root);
            assertTrue(((EditText)urlRow.findViewById(android.R.id.edit)).getMinimumHeight()>=CaptionSettingsStyle.dp(activity,48));
            ApiKeyPreference key=new ApiKeyPreference(activity);key.setKey(DeepSeekTextPreference.KEY_API_KEY);key.setTitle("API Key");row(key,root);
            DeepSeekModelPreference model=new DeepSeekModelPreference(activity);model.setKey(DeepSeekModelPreference.KEY_MODEL);model.setTitle("模型");row(model,root);
            heading(root,"字幕样式");SubtitleStylePreview pref=new SubtitleStylePreview(activity);View previewRow=row(pref,root);SubtitleStylePreview.Preview preview=(SubtitleStylePreview.Preview)previewRow.findViewWithTag("ai_style_preview_canvas");if(portrait)preview.performClick();
            for(String field:new String[]{DeepSeekSliderPreference.KEY_TEXT_SIZE,DeepSeekSliderPreference.KEY_OPACITY}){DeepSeekSliderPreference slider=new DeepSeekSliderPreference(activity);slider.setKey(field);slider.setTitle(field.equals(DeepSeekSliderPreference.KEY_TEXT_SIZE)?CaptionStrings.settings(activity,"size"):"背景不透明度");slider.setSummary(field.equals(DeepSeekSliderPreference.KEY_TEXT_SIZE)?CaptionStrings.settings(activity,"size_hint"):"0% 为透明，100% 为不透明；松手保存");row(slider,root);}
            heading(root,"缓存与诊断");row(new DeepSeekDiagnosticsPreference(activity),root);
            root.measure(View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));root.layout(0,0,420,root.getMeasuredHeight());
            Bitmap bitmap=Bitmap.createBitmap(420,root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
            assertTrue(root.getHeight()>500);assertEquals(portrait,preview.portrait);
            String output=System.getenv("CAPTION_UI_PREVIEW_OUTPUT");if(output!=null){File file=new File(output,"settings-"+(dark?"dark":"light")+"-"+(portrait?"portrait":"landscape")+".png");file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
            activity.finish();
        }
    }
    @Test public void previewHitsGlyphTargetsWithoutDensityOrFontScaleAssumptions(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
        float originalDensity=metrics.density,originalScaledDensity=metrics.scaledDensity;
        try {
            for(float screenWidth:new float[]{1264,2736}){
                metrics.density=1f;metrics.scaledDensity=1f;
                TextView baseline=SubtitleStylePreview.sampleLabel(activity,"这是字幕样式预览",
                        203,70,screenWidth,1163,true);
                float actual=SubtitleStyleMetrics.measuredGlyphHeightPx(baseline.getPaint());
                assertEquals(screenWidth==1264?25.6f:55.5f,actual,1.5f);
                metrics.density=3.25f;metrics.scaledDensity=5.75f;
                TextView changed=SubtitleStylePreview.sampleLabel(activity,"这是字幕样式预览",
                        203,70,screenWidth,600,true);
                assertEquals(baseline.getTextSize(),changed.getTextSize(),.001f);
                assertEquals(actual,SubtitleStyleMetrics.measuredGlyphHeightPx(changed.getPaint()),.001f);
            }
        } finally {
            metrics.density=originalDensity;metrics.scaledDensity=originalScaledDensity;
            activity.finish();
        }
    }
}
