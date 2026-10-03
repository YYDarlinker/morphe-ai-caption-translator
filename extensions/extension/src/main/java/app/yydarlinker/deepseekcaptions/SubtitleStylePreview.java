package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.*;

/** Theme-aware 16:9 preview scaled from the calibrated full-screen reference. */
@SuppressWarnings("deprecation")
public final class SubtitleStylePreview extends android.preference.Preference {
    static final float LANDSCAPE_REFERENCE_WIDTH_PX=2736f;
    /** Catalog key holding the one-line sample each supported interface language renders. */
    static final String SAMPLE_KEY="preview_sample";
    /**
     * The preview is a true-scale model: the caption is laid out over the 2736px full-screen reference
     * and the whole frame is then scaled down exactly once, so the rasterized glyphs keep the measured
     * full-screen ratio. The sample is therefore a genuine full sentence rather than a short label; it
     * is read from {@link #SAMPLE_KEY} through the settings catalog, so it follows the interface
     * language (never the video source or the auto-translate target) and is verified to hold one
     * unbroken line at the reference width by {@code SettingsPolish113Test}.
     */
    static final int MAX_SAMPLE_LINES=1;
    /** Rasterization hook: when false the preview renders the raw source line instead of a localized one. */
    static boolean LOCALIZE_SAMPLE=true;
    /**
     * Measurement hook: the caption box of the most recent {@link Preview#onDraw}, in the frame's own
     * coordinate system ({@code 0..frameWidth} by {@code 0..stageHeight}). Read-only evidence for the
     * tests that prove a localized sample stays inside the video frame; nothing in production reads it.
     */
    static RectF LAST_CAPTION_BOX;

    /**
     * Where the caption lands once the full-screen reference frame has been scaled into the preview. The
     * caption is laid out and centred in reference coordinates and the whole frame is then scaled exactly
     * once, so a box narrower than the reference can still start outside the scaled frame — this both
     * computes the real on-screen rectangle and reports whether the fit is exact. Read-only evidence for
     * the fixtures; nothing in production reads it.
     */
    static RectF captionBoxInFrame(float labelWidth,float labelHeight,float frameWidth,float frameHeight,
            float scale,float positionY){
        float boxX=(LANDSCAPE_REFERENCE_WIDTH_PX-labelWidth)/2f;
        float boxY=Math.max(0,Math.min(frameHeight/scale-labelHeight,
                (frameHeight/scale)*positionY-labelHeight/2f));
        float left=boxX*scale,top=boxY*scale;
        float right=left+labelWidth*scale,bottom=top+labelHeight*scale;
        return new RectF(Math.min(left,frameWidth),Math.min(top,frameHeight),
                Math.min(right,frameWidth),Math.min(bottom,frameHeight));
    }
    private static final Set<Preview> views=Collections.newSetFromMap(new WeakHashMap<Preview,Boolean>());
    public SubtitleStylePreview(Context c){super(c);init();}
    public SubtitleStylePreview(Context c,AttributeSet a){super(c,a);init();}
    public SubtitleStylePreview(Context c,AttributeSet a,int d){super(c,a,d);init();}
    private void init(){setPersistent(false);setSelectable(false);}
    /**
     * The section heading ("Caption style") already names this block, so the row renders no second,
     * redundant caption title: the canvas follows the heading directly and the localized
     * "landscape full-screen preview" text survives only as the canvas content description.
     */
    @Override protected View onCreateView(ViewGroup parent){
        Context c=getContext();LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);CaptionSettingsStyle.row(root);
        Preview preview=new Preview(c);preview.setTag("ai_style_preview_canvas");views.add(preview);
        LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(-1,-2);
        previewParams.topMargin=CaptionSettingsStyle.dp(c,6);
        root.addView(preview,previewParams);
        TextView hint=new TextView(c);hint.setText(CaptionStrings.settings(c,"preview_hint"));CaptionSettingsStyle.caption(hint);
        LinearLayout.LayoutParams hintParams=new LinearLayout.LayoutParams(-1,-2);
        hintParams.topMargin=CaptionSettingsStyle.dp(c,2);
        root.addView(hint,hintParams);
        return root;
    }
    static void update(String key,int value){for(Preview p:new ArrayList<>(views)){if(key.equals(DeepSeekSliderPreference.KEY_TEXT_SIZE))p.sizeTier=CaptionFontSize.clampTier(value);else p.opacity=value;p.invalidate();}}
    /** The sample line resolved through the settings catalog for whatever interface language is active. */
    static String sample(Context c){return CaptionStrings.settings(c,SAMPLE_KEY);}
    // Use all available row width; video and captions share one 16:9 coordinate system.
    static float frameWidth(float width){
        return Math.max(1,width);
    }
    static float stageHeight(float width){
        return frameWidth(width)*9f/16f;
    }
    /** Widest text run the caption frame can hold, matching the capped label width used when drawing. */
    static int sampleTextWidthPx(Context c,float contentWidth){
        float density=c.getResources().getDisplayMetrics().density;
        int padX=Math.round(6*density);
        return Math.max(1,Math.round(contentWidth*.92f)-2*padX);
    }
    /**
     * The caption line as the preview draws it. The box is capped at the widest run the frame can hold,
     * so the caption background itself never reaches past the video frame; the tier name and the sample
     * are the only things the caller supplies.
     */
    static TextView sampleLabel(Context c,String sample,int sizeTier,int opacity,float screenWidthPx,float contentWidth){
        return sampleLabel(c,sample,sizeTier,opacity,screenWidthPx,contentWidth,RebuildController.previewRenderSpec());
    }
    static TextView sampleLabel(Context c,String sample,int sizeTier,int opacity,float screenWidthPx,float contentWidth,
            CaptionRenderSpec spec){
        android.util.DisplayMetrics d=c.getResources().getDisplayMetrics();TextView label=new TextView(c);
        spec.apply(label);
        label.setIncludeFontPadding(false);label.setGravity(Gravity.CENTER);label.setTextColor(Color.WHITE);label.setText(sample);label.setMaxLines(MAX_SAMPLE_LINES);label.setEllipsize(null);
        label.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);label.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        int padX=Math.round(6*d.density),padY=Math.round(4*d.density);label.setPadding(padX,padY,padX,padY);
        int maximum=sampleTextWidthPx(c,contentWidth);
        float sizePx=SubtitleStyleMetrics.textSizePxForGlyphHeight(label.getPaint(),
                SubtitleStyleMetrics.targetGlyphHeightPx(sizeTier,screenWidthPx,true));
        // The box is measured over the full-screen reference and the whole frame is scaled once afterwards,
        // so the authored sample has to fit the reference budget, not the on-screen pixel width.
        int compact=Math.min(maximum,CaptionOverlay.compactWidthPx(sample,sizePx,maximum,spec))+2*padX;
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sizePx);label.setShadowLayer(d.density,0,d.density,0xD0000000);
        GradientDrawable bg=new GradientDrawable();bg.setColor(SubtitleStyleMetrics.alpha(opacity)<<24);bg.setCornerRadius(4*d.density);label.setBackground(bg);
        label.measure(View.MeasureSpec.makeMeasureSpec(compact,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        label.layout(0,0,compact,label.getMeasuredHeight());return label;
    }
    static final class Preview extends View {
        int sizeTier;int opacity;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Preview(Context c){super(c);DeepSeekConfig.Snapshot s=DeepSeekConfig.displayStyle(c);sizeTier=s.captionSizeTier;opacity=s.backgroundOpacity;setContentDescription(CaptionStrings.settings(c,"preview"));}
        @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec);int height=Math.round(stageHeight(width));setMeasuredDimension(width,resolveSize(height,heightSpec));}
        @Override protected void onDraw(Canvas c){
            android.util.DisplayMetrics d=getResources().getDisplayMetrics();float radius=12*d.density;paint.setColor(CaptionSettingsStyle.tint(CaptionSettingsStyle.primary(getContext()),7));c.drawRoundRect(0,0,getWidth(),getHeight(),radius,radius,paint);
            float w=frameWidth(getWidth());
            float h=w*9f/16f;
            c.save();c.translate((getWidth()-w)/2f,(getHeight()-h)/2f);Path clip=new Path();clip.addRoundRect(new RectF(0,0,w,h),radius,radius,Path.Direction.CW);c.clipPath(clip);
            paint.setAlpha(255);paint.setShader(new LinearGradient(0,0,w,h,new int[]{0xff354650,0xff9faeae},null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);paint.setShader(null);paint.setColor(0xff536866);Path hill=new Path();hill.moveTo(0,h);hill.lineTo(w*.3f,h*.38f);hill.lineTo(w*.6f,h*.70f);hill.lineTo(w*.82f,h*.48f);hill.lineTo(w,h*.64f);hill.lineTo(w,h);hill.close();c.drawPath(hill,paint);
            float contentW=LANDSCAPE_REFERENCE_WIDTH_PX;
            float scale=w/contentW,contentH=h/scale;
            // The sample is the localized line the user would read in full screen; the frame is the exact
            // 2736x1264 full-screen reference scaled once, so the glyphs stay at the full-screen ratio.
            String sample=LOCALIZE_SAMPLE?sample(getContext()):CaptionStrings.get(getContext(),SAMPLE_KEY);
            // Simulate the full-size content frame first; shrink the entire view exactly once.
            TextView label=sampleLabel(getContext(),sample,sizeTier,opacity,
                    LANDSCAPE_REFERENCE_WIDTH_PX,contentW);
            float pos=DeepSeekConfig.captionPositionY(getContext(),true);
            float boxX=(contentW-label.getMeasuredWidth())/2f;
            float boxY=Math.max(0,Math.min(contentH-label.getMeasuredHeight(),contentH*pos-label.getMeasuredHeight()/2f));
            LAST_CAPTION_BOX=captionBoxInFrame(label.getMeasuredWidth(),label.getMeasuredHeight(),w,h,scale,pos);
            c.save();c.scale(scale,scale);
            c.translate(boxX,boxY);label.draw(c);c.restore();
            paint.setTextSize(12*d.scaledDensity);paint.setTextAlign(Paint.Align.LEFT);paint.setColor(Color.WHITE);c.drawText("16:9",12*d.density,24*d.density,paint);c.restore();
        }
    }
}
