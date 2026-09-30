package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.*;

/** Theme-aware settings preview measured in the same content-frame coordinates as the live overlay. */
@SuppressWarnings("deprecation")
public final class SubtitleStylePreview extends android.preference.Preference {
    static final float LANDSCAPE_REFERENCE_WIDTH_PX=2736f;
    private static final Set<Preview> views=Collections.newSetFromMap(new WeakHashMap<Preview,Boolean>());
    public SubtitleStylePreview(Context c){super(c);init();}
    public SubtitleStylePreview(Context c,AttributeSet a){super(c,a);init();}
    public SubtitleStylePreview(Context c,AttributeSet a,int d){super(c,a,d);init();}
    private void init(){setPersistent(false);setSelectable(false);}
    @Override protected View onCreateView(ViewGroup parent){
        Context c=getContext();LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);CaptionSettingsStyle.row(root);
        TextView title=new TextView(c);title.setText(CaptionStrings.settings(c,"preview"));CaptionSettingsStyle.title(title);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        Preview preview=new Preview(c);preview.setTag("ai_style_preview_canvas");views.add(preview);
        root.addView(preview,new LinearLayout.LayoutParams(-1,-2));
        TextView hint=new TextView(c);hint.setText(CaptionStrings.localize(c,"字号与背景设置实时预览"));CaptionSettingsStyle.caption(hint);hint.setPadding(0,CaptionSettingsStyle.dp(c,8),0,0);root.addView(hint);return root;
    }
    static void update(String key,int value){for(Preview p:new ArrayList<>(views)){if(key.equals(DeepSeekSliderPreference.KEY_TEXT_SIZE))p.ratioBps=value;else p.opacity=value;p.invalidate();}}
    // Use all available row width; video and captions share one 16:9 coordinate system.
    static float frameWidth(float width){
        return Math.max(1,width);
    }
    static float stageHeight(float width){
        return frameWidth(width)*9f/16f;
    }
    static TextView sampleLabel(Context c,String sample,int ratioBps,int opacity,float screenWidthPx,float contentWidth){
        android.util.DisplayMetrics d=c.getResources().getDisplayMetrics();TextView label=new TextView(c);
        label.setIncludeFontPadding(false);label.setGravity(Gravity.CENTER);label.setTextColor(Color.WHITE);label.setText(sample);label.setMaxLines(2);label.setEllipsize(null);
        label.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);label.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        int padX=Math.round(6*d.density),padY=Math.round(4*d.density);label.setPadding(padX,padY,padX,padY);
        int maximum=Math.max(1,Math.round(contentWidth*.92f)-2*padX);
        label.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);
        float sizePx=SubtitleStyleMetrics.textSizePxForGlyphHeight(label.getPaint(),
                SubtitleStyleMetrics.targetGlyphHeightPx(ratioBps,screenWidthPx));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sizePx);label.setShadowLayer(d.density,0,d.density,0xD0000000);
        GradientDrawable bg=new GradientDrawable();bg.setColor(SubtitleStyleMetrics.alpha(opacity)<<24);bg.setCornerRadius(4*d.density);label.setBackground(bg);
        int compact=CaptionOverlay.compactWidthPx(sample,sizePx,maximum)+2*padX;label.measure(View.MeasureSpec.makeMeasureSpec(compact,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));label.layout(0,0,compact,label.getMeasuredHeight());return label;
    }
    static final class Preview extends View {
        int ratioBps;int opacity;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Preview(Context c){super(c);DeepSeekConfig.Snapshot s=DeepSeekConfig.displayStyle(c);ratioBps=s.captionGlyphHeightRatioBps;opacity=s.backgroundOpacity;setContentDescription(CaptionStrings.settings(c,"preview"));}
        @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec);int height=Math.round(stageHeight(width));setMeasuredDimension(width,resolveSize(height,heightSpec));}
        @Override protected void onDraw(Canvas c){
            android.util.DisplayMetrics d=getResources().getDisplayMetrics();float radius=12*d.density;paint.setColor(CaptionSettingsStyle.tint(CaptionSettingsStyle.primary(getContext()),7));c.drawRoundRect(0,0,getWidth(),getHeight(),radius,radius,paint);
            float w=frameWidth(getWidth());
            float h=w*9f/16f;
            c.save();c.translate((getWidth()-w)/2f,(getHeight()-h)/2f);Path clip=new Path();clip.addRoundRect(new RectF(0,0,w,h),radius,radius,Path.Direction.CW);c.clipPath(clip);
            paint.setAlpha(255);paint.setShader(new LinearGradient(0,0,w,h,new int[]{0xff354650,0xff9faeae},null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);paint.setShader(null);paint.setColor(0xff536866);Path hill=new Path();hill.moveTo(0,h);hill.lineTo(w*.3f,h*.38f);hill.lineTo(w*.6f,h*.70f);hill.lineTo(w*.82f,h*.48f);hill.lineTo(w,h*.64f);hill.lineTo(w,h);hill.close();c.drawPath(hill,paint);
            float contentW=LANDSCAPE_REFERENCE_WIDTH_PX;
            float scale=w/contentW,contentH=h/scale;
            String sample=CaptionStrings.localize(getContext(),"这是字幕样式预览");
            // Simulate the full-size content frame first; shrink the entire view exactly once.
            TextView label=sampleLabel(getContext(),sample,ratioBps,opacity,
                    LANDSCAPE_REFERENCE_WIDTH_PX,contentW);
            float pos=DeepSeekConfig.captionPositionY(getContext(),true);
            c.save();c.scale(scale,scale);
            c.translate((contentW-label.getMeasuredWidth())/2f,Math.max(0,Math.min(contentH-label.getMeasuredHeight(),contentH*pos-label.getMeasuredHeight()/2f)));label.draw(c);c.restore();
            paint.setTextSize(12*d.scaledDensity);paint.setTextAlign(Paint.Align.LEFT);paint.setColor(Color.WHITE);c.drawText("16:9",12*d.density,24*d.density,paint);c.restore();
        }
    }
}
