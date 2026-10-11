package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.widget.TextView;
import app.yydarlinker.caption.contracts.CaptionContracts.*;
import java.util.*;
/** Independent preview fixture. It is absent from production MPE. */
public final class MeaningTextView extends TextView {
    private Frame frame;private String target="zh-Hans",key="",owner="";private long intent=-1,epoch=-1;
    public long lastDrawNanos;public String lastDrawUnit="";public int lastFrom,lastTo;
    public MeaningTextView(Context c){super(c);setGravity(Gravity.CENTER);setMaxLines(2);setIncludeFontPadding(false);setTextSize(24);}
    public boolean apply(Frame next,String target){if(next.intent<intent||next.cacheEpoch<epoch||next.intent==intent&&!owner.isEmpty()&&!owner.equals(next.contentId))return false;
        intent=next.intent;epoch=next.cacheEpoch;owner=next.contentId;frame=next;this.target=target;project();return true;}
    private void project(){if(frame==null||frame.unit==null||!frame.unit.audible(frame.positionMs)){setText("");return;}
        CaptionRenderSpec spec=new CaptionRenderSpec(target,CaptionLanguageProfile.fromCode(target),target.startsWith("zh"));spec.apply(this);
        String body=frame.unit.text;int[] cuts=CaptionUnicode.characterBoundaries(body,spec.locale);int begin=(int)(frame.positionMs/5000)%Math.max(1,cuts.length-1),end=Math.min(cuts.length-1,begin+20);
        lastFrom=cuts[begin];lastTo=cuts[end];setText(body.substring(lastFrom,lastTo));}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);project();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);if(frame!=null&&frame.unit!=null&&getText().length()>0){lastDrawNanos=System.nanoTime();lastDrawUnit=frame.unit.id;}}
}
