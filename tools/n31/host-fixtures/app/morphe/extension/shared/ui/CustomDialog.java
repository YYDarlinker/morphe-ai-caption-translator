package app.morphe.extension.shared.ui;
import android.app.Dialog;
import android.content.Context;
import android.util.Pair;
import android.widget.*;
/** Test-only official 1.45 public ABI and last-child action-area shape, including the faulty cancel path. */
public final class CustomDialog {
    public static int fallbackCancelCalls;
    public static Pair<Dialog,LinearLayout> create(Context c,CharSequence title,CharSequence message,EditText input,
            CharSequence positive,Runnable confirm,Runnable cancel,CharSequence neutral,Runnable extra,boolean vertical,boolean dismiss) {
        Dialog d=new Dialog(c);LinearLayout main=new LinearLayout(c);main.setOrientation(1);
        if(title!=null){TextView t=new TextView(c);t.setText(title);main.addView(t);}
        if(message!=null){TextView t=new TextView(c);t.setText(message);main.addView(t);}
        if(input!=null)main.addView(input);
        LinearLayout actions=new LinearLayout(c);main.addView(actions);
        if(positive!=null)actions.addView(createButton(c,d,positive,confirm,true,dismiss));
        if(cancel!=null){fallbackCancelCalls++;actions.addView(createButton(c,d,
            app.morphe.extension.shared.ResourceUtils.activity.getString(android.R.string.cancel),cancel,false,true));}
        d.setContentView(main);return Pair.create(d,main);
    }
    public static Button createButton(Context c,Dialog d,CharSequence text,Runnable action,boolean primary,boolean dismiss) {
        Button b=new Button(c);b.setText(text);b.setAllCaps(false);b.setOnClickListener(v->{if(action!=null)action.run();if(d!=null&&dismiss)d.dismiss();});return b;
    }
}
