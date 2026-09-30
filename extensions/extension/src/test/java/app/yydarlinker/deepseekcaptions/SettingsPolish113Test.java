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
    @Test public void landscapePreviewFillsAvailableWidthAndKeepsVideoAndGlyphScale(){
        float reference=SubtitleStylePreview.LANDSCAPE_REFERENCE_WIDTH_PX;
        assertEquals(2736f,reference,0);
        for(float width:new float[]{320,360,420,800,1600}){
            float frame=SubtitleStylePreview.frameWidth(width);
            assertEquals(width,frame,.001f);
            assertEquals(width*9f/16f,SubtitleStylePreview.stageHeight(width),.001f);
            for(int tier=0;tier<CaptionFontSize.COUNT;tier++){
                float landscape=SubtitleStyleMetrics.previewGlyphHeightPx(tier,frame);
                assertEquals(CaptionFontSize.fullScreenGlyphHeightPx(tier)/2736f*frame,landscape,.001f);
            }
        }
    }
    private int countViews(View view,Class<?> type){
        int count=type.isInstance(view)?1:0;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=countViews(group.getChildAt(i),type);}
        return count;
    }
    @Test public void previewContainsOneStaticLandscapeCanvasWithoutAnOrientationControl(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            SubtitleStylePreview preference=new SubtitleStylePreview(activity);
            View root=preference.onCreateView(new FrameLayout(activity));
            SubtitleStylePreview.Preview preview=(SubtitleStylePreview.Preview)root.findViewWithTag("ai_style_preview_canvas");
            assertEquals(1,countViews(root,SubtitleStylePreview.Preview.class));
            assertEquals(0,countViews(root,Button.class));
            assertFalse(preference.isSelectable());assertFalse(preview.isClickable());assertFalse(preview.isFocusable());
            assertFalse(preview.performClick());
            assertEquals(CaptionStrings.settings(activity,"preview"),preview.getContentDescription());
            for(int width:new int[]{320,420,960}){
                preview.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                assertEquals(width,preview.getMeasuredWidth());
                assertEquals(Math.round(width*9f/16f),preview.getMeasuredHeight());
            }
        } finally { activity.finish(); }
    }
    private void heading(LinearLayout root,String title){TextView label=new TextView(root.getContext());label.setText(title);CaptionSettingsStyle.caption(label);label.setTextSize(14);label.setPadding(20,20,20,6);root.addView(label);}
    private View row(android.preference.Preference preference,LinearLayout root){View row=preference.getView(null,new ListView(root.getContext()));root.addView(row,new LinearLayout.LayoutParams(-1,-2));return row;}
    @Test @Config(qualifiers="zh-rCN-w420dp-h900dp") public void renderNativeLightAndDarkSettingsFixtures()throws Exception{
        for(int tier:new int[]{CaptionFontSize.DEFAULT_TIER,4}) for(boolean dark:new boolean[]{false,true}){
            Activity activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
            DeepSeekConfig.saveCaptionSizeTier(activity,tier);
            LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(dark?0xff0f0f0f:Color.WHITE);root.setLayoutParams(new FrameLayout.LayoutParams(420,-2));
            TextView top=new TextView(activity);top.setText("AI 字幕翻译");CaptionSettingsStyle.title(top);top.setTextSize(22);top.setPadding(20,20,20,20);root.addView(top);
            DeepSeekEnabledPreference enabled=new DeepSeekEnabledPreference(activity);enabled.setTitle("启用 AI 字幕翻译");row(enabled,root);
            heading(root,"API 配置");DeepSeekTextPreference url=new DeepSeekTextPreference(activity);url.setKey(DeepSeekTextPreference.KEY_BASE_URL);url.setTitle("API 地址");url.setSummary("兼容接口地址，停止输入后自动保存");View urlRow=row(url,root);
            assertTrue(((EditText)urlRow.findViewById(android.R.id.edit)).getMinimumHeight()>=CaptionSettingsStyle.dp(activity,48));
            ApiKeyPreference key=new ApiKeyPreference(activity);key.setKey(DeepSeekTextPreference.KEY_API_KEY);key.setTitle("API Key");row(key,root);
            DeepSeekModelPreference model=new DeepSeekModelPreference(activity);model.setKey(DeepSeekModelPreference.KEY_MODEL);model.setTitle("模型");row(model,root);
            heading(root,"字幕样式");SubtitleStylePreview pref=new SubtitleStylePreview(activity);View previewRow=row(pref,root);SubtitleStylePreview.Preview preview=(SubtitleStylePreview.Preview)previewRow.findViewWithTag("ai_style_preview_canvas");
            for(String field:new String[]{DeepSeekSliderPreference.KEY_TEXT_SIZE,DeepSeekSliderPreference.KEY_OPACITY}){DeepSeekSliderPreference slider=new DeepSeekSliderPreference(activity);slider.setKey(field);slider.setTitle(field.equals(DeepSeekSliderPreference.KEY_TEXT_SIZE)?CaptionStrings.settings(activity,"size"):"背景不透明度");slider.setSummary(field.equals(DeepSeekSliderPreference.KEY_TEXT_SIZE)?String.format(CaptionStrings.settings(activity,"size_tier_hint"),CaptionStrings.settings(activity,tier==2?"size_tier_standard":"size_tier_xl"),tier==2?"44.5":"56",tier==2?"55.5":"69.8"):"0% 为透明，100% 为不透明；松手保存");row(slider,root);}
            heading(root,"缓存与诊断");row(new DeepSeekDiagnosticsPreference(activity),root);
            root.measure(View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));root.layout(0,0,420,root.getMeasuredHeight());
            Bitmap bitmap=Bitmap.createBitmap(420,root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
            assertTrue(root.getHeight()>500);assertEquals(previewRow.getWidth()-previewRow.getPaddingLeft()-previewRow.getPaddingRight(),preview.getWidth());assertEquals(Math.round(preview.getWidth()*9f/16f),preview.getHeight());
            String output=System.getenv("CAPTION_UI_PREVIEW_OUTPUT");if(output!=null){File file=new File(output,"settings-"+(dark?"dark":"light")+"-"+(tier==2?"standard":"xl")+"-landscape.png");file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
            activity.finish();
        }
    }
    @Test public void previewHitsGlyphTargetsWithoutDensityOrFontScaleAssumptions(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
        float originalDensity=metrics.density,originalScaledDensity=metrics.scaledDensity;
        try {
            float reference=SubtitleStylePreview.LANDSCAPE_REFERENCE_WIDTH_PX;
            for(int tier=0;tier<CaptionFontSize.COUNT;tier++){
                metrics.density=1f;metrics.scaledDensity=1f;
                TextView baseline=SubtitleStylePreview.sampleLabel(activity,"这是字幕样式预览",
                        tier,70,reference,reference);
                float actual=SubtitleStyleMetrics.measuredGlyphHeightPx(baseline.getPaint());
                float target=CaptionFontSize.fullScreenGlyphHeightPx(tier);
                assertEquals(target,actual,1.5f);
                for(float previewWidth:new float[]{320,420,800}){
                    float scale=previewWidth/reference;
                    assertEquals(SubtitleStyleMetrics.previewGlyphHeightPx(tier,previewWidth),actual*scale,1.5f*scale);
                }
                metrics.density=3.25f;metrics.scaledDensity=5.75f;
                TextView changed=SubtitleStylePreview.sampleLabel(activity,"这是字幕样式预览",
                        tier,70,reference,reference);
                assertEquals(baseline.getTextSize(),changed.getTextSize(),.001f);
                assertEquals(actual,SubtitleStyleMetrics.measuredGlyphHeightPx(changed.getPaint()),.001f);
            }
        } finally {
            metrics.density=originalDensity;metrics.scaledDensity=originalScaledDensity;
            activity.finish();
        }
    }
    private Bitmap renderPreview(SubtitleStylePreview.Preview preview){
        preview.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        preview.layout(0,0,preview.getMeasuredWidth(),preview.getMeasuredHeight());
        Bitmap bitmap=Bitmap.createBitmap(preview.getWidth(),preview.getHeight(),Bitmap.Config.ARGB_8888);preview.draw(new Canvas(bitmap));return bitmap;
    }
    private float backgroundCenterY(Bitmap bitmap){
        int top=bitmap.getHeight(),bottom=-1;
        for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if(bitmap.getPixel(x,y)==Color.BLACK){top=Math.min(top,y);bottom=Math.max(bottom,y);}
        assertTrue("Opaque subtitle background must be rendered",bottom>=top);return (top+bottom)/2f;
    }
    @Test public void landscapePreviewUsesLandscapeCaptionPosition(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        float landscape=DeepSeekConfig.captionPositionY(activity,true),shorts=DeepSeekConfig.shortsPosition(activity);
        try {
            SubtitleStylePreview.Preview preview=new SubtitleStylePreview.Preview(activity);preview.opacity=100;preview.sizeTier=2;
            DeepSeekConfig.saveCaptionPosition(activity,true,.30f);DeepSeekConfig.saveShortsPosition(activity,.85f);
            Bitmap upper=renderPreview(preview);
            assertEquals(upper.getHeight()*.30f,backgroundCenterY(upper),2f);
            DeepSeekConfig.saveShortsPosition(activity,.10f);
            assertTrue("Shorts position must not affect the landscape preview",upper.sameAs(renderPreview(preview)));
            DeepSeekConfig.saveCaptionPosition(activity,true,.70f);
            Bitmap lower=renderPreview(preview);
            assertEquals(lower.getHeight()*.70f,backgroundCenterY(lower),2f);
        } finally {
            DeepSeekConfig.saveCaptionPosition(activity,true,landscape);DeepSeekConfig.saveShortsPosition(activity,shorts);activity.finish();
        }
    }
}
