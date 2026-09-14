package app.yydarlinker.deepseekcaptions;
import android.content.*;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;
/** Scrollable in-page transcript. Copy is explicit, not a side effect of opening the row. */
@SuppressWarnings("deprecation")
public final class DeepSeekDiagnosticsPreference extends android.preference.Preference {
    public DeepSeekDiagnosticsPreference(Context c){super(c);init();}
    public DeepSeekDiagnosticsPreference(Context c,AttributeSet a){super(c,a);init();}
    public DeepSeekDiagnosticsPreference(Context c,AttributeSet a,int d){super(c,a,d);init();}
    public DeepSeekDiagnosticsPreference(Context c,AttributeSet a,int d,int r){super(c,a,d,r);init();}
    private void init(){setPersistent(false);setSelectable(false);}
    @Override protected View onCreateView(ViewGroup parent){
        Context c=getContext();int dp=Math.round(c.getResources().getDisplayMetrics().density);LinearLayout box=new LinearLayout(c);box.setOrientation(1);box.setPadding(20*dp,8*dp,20*dp,12*dp);
        LinearLayout buttons=new LinearLayout(c);TextView title=new TextView(c);title.setText("字幕链路诊断");title.setTextSize(16);buttons.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView body=new TextView(c);body.setTextSize(13);body.setTextIsSelectable(true);body.setText(CaptionDiagnostics.uiText(c));
        ScrollView scroll=new ScrollView(c){@Override public boolean onInterceptTouchEvent(MotionEvent e){getParent().requestDisallowInterceptTouchEvent(true);return super.onInterceptTouchEvent(e);}};
        scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(true);scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        Button refresh=new Button(c,null,android.R.attr.borderlessButtonStyle);refresh.setText("刷新");refresh.setTextSize(13);refresh.setOnClickListener(v->{body.setText(CaptionDiagnostics.uiText(c));scroll.scrollTo(0,0);});buttons.addView(refresh);
        Button copy=new Button(c,null,android.R.attr.borderlessButtonStyle);copy.setText("复制");copy.setTextSize(13);copy.setOnClickListener(v->{ClipboardManager manager=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);if(manager!=null)manager.setPrimaryClip(ClipData.newPlainText("AI 字幕诊断",body.getText()));});buttons.addView(copy);
        box.addView(buttons);box.addView(scroll,new LinearLayout.LayoutParams(-1,Math.round(320*c.getResources().getDisplayMetrics().density)));return box;
    }
}
