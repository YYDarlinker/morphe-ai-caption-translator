package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import android.view.*;
import android.widget.TextView;
import java.util.*;
import java.util.function.Supplier;
/** Weak view identities hold stable key/state renderers, never translated values or user text. */
final class CaptionUiViewBindings {
    private static final Map<View,Map<String,Runnable>> bindings=new WeakHashMap<>();
    private static void register(View v,String property,Runnable action){
        bindings.computeIfAbsent(v,k->new HashMap<>()).put(property,action);action.run();
    }
    static void text(TextView v,Context c,String key){
        render(v,()->CaptionStrings.settings(c,key));
    }
    static void render(TextView v,Supplier<CharSequence> value){
        java.lang.ref.WeakReference<TextView> ref=new java.lang.ref.WeakReference<>(v);
        register(v,"text",()->{TextView view=ref.get();if(view!=null){CharSequence text=value.get();
            if(!android.text.TextUtils.equals(view.getText(),text))view.setText(text);}});
    }
    static void hint(TextView v,Context c,String key){
        java.lang.ref.WeakReference<TextView> ref=new java.lang.ref.WeakReference<>(v);
        register(v,"hint",()->{TextView view=ref.get();if(view!=null)view.setHint(CaptionStrings.settings(c,key));});
    }
    static void description(View v,Context c,String key){
        description(v,()->CaptionStrings.settings(c,key));
    }
    static void description(View v,Supplier<CharSequence> value){
        java.lang.ref.WeakReference<View> ref=new java.lang.ref.WeakReference<>(v);
        register(v,"description",()->{View view=ref.get();if(view!=null)view.setContentDescription(value.get());});
    }
    static void refresh(View v,Context c){
        CaptionUiLocale.direction(v,c);
        Map<String,Runnable> entries=bindings.get(v);if(entries!=null)for(Runnable action:new ArrayList<>(entries.values()))action.run();
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)refresh(((ViewGroup)v).getChildAt(i),c);
        if(v instanceof SubtitleStylePreview.Preview)v.invalidate();
    }
}
