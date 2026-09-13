package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import android.util.AttributeSet;
import android.view.*;
import android.graphics.*;
import java.util.*;
/** Fullscreen 16:9 preview, updates while dragging; no network, no API key access. */
@SuppressWarnings("deprecation")
public final class SubtitleStylePreview extends android.preference.Preference {
    private static final Set<Preview> views=Collections.newSetFromMap(new WeakHashMap<Preview,Boolean>());
    public SubtitleStylePreview(Context c){super(c);init();}
    public SubtitleStylePreview(Context c,AttributeSet a){super(c,a);init();}
    public SubtitleStylePreview(Context c,AttributeSet a,int d){super(c,a,d);init();}
    private void init(){setPersistent(false);setSelectable(false);}
    @Override protected View onCreateView(ViewGroup parent){Preview p=new Preview(getContext());views.add(p);return p;}
    static void update(String key,int value){for(Preview p:new ArrayList<>(views)){if(key.equals(DeepSeekSliderPreference.KEY_TEXT_SIZE))p.size=value;else p.opacity=value;p.invalidate();}}
    static final class Preview extends View {
        int size,opacity; final Paint paint=new Paint(3);
        Preview(Context c){super(c);DeepSeekConfig.Snapshot s=DeepSeekConfig.displayStyle(c);size=s.captionTextSize;opacity=s.backgroundOpacity;setContentDescription("横屏字幕示例，字号及背景透明度实时预览");}
        @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);setMeasuredDimension(width,(int)(width*9f/16f));}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight();
            paint.setShader(new LinearGradient(0,0,w,h,new int[]{0xff325a80,0xffc4b18b},null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);paint.setShader(null);
            paint.setColor(0xff496859);Path hill=new Path();hill.moveTo(0,h);hill.lineTo(w*.3f,h*.35f);hill.lineTo(w*.6f,h*.72f);hill.lineTo(w*.82f,h*.4f);hill.lineTo(w,h*.65f);hill.lineTo(w,h);hill.close();c.drawPath(hill,paint);
            android.util.DisplayMetrics d=getResources().getDisplayMetrics();float device=Math.min(d.widthPixels,d.heightPixels);
            float px=SubtitleStyleMetrics.previewTextPx(size,Math.max(1,device),d.density,d.scaledDensity,h);
            paint.setTextSize(px);paint.setTypeface(Typeface.DEFAULT);paint.setTextAlign(Paint.Align.CENTER);
            String a="这是字幕样式的示例",b="Subtitle preview";float tw=Math.max(paint.measureText(a),paint.measureText(b));
            float bottom=h*.88f,top=bottom-px*2.7f;
            paint.setColor(Color.argb(SubtitleStyleMetrics.alpha(opacity),0,0,0));c.drawRoundRect(w/2-tw/2-px*.45f,top,w/2+tw/2+px*.45f,bottom,px*.2f,px*.2f,paint);
            paint.setColor(Color.WHITE);c.drawText(a,w/2,top+px*1.1f,paint);c.drawText(b,w/2,top+px*2.3f,paint);
            paint.setTextSize(h*.055f);paint.setTextAlign(Paint.Align.LEFT);c.drawText("16:9 横屏示例",w*.04f,h*.1f,paint);
        }
    }
}
