package app.yydarlinker.deepseekcaptions;

import android.preference.*;
import android.view.View;
import java.util.*;

/** Only explicit addon identities are bound; no title matching and no host settings mutation. */
@SuppressWarnings("deprecation")
public final class CaptionPreferenceBindings {
    private static final Map<String,String[]> KEYS=new LinkedHashMap<>();
    static {
        put("morphe_vot_screen__ai_captions","ai_title","ai_summary");
        put("deepseek_caption_enabled","enable_ai",null);
        put("deepseek_caption_languages","languages_title","languages_summary");
        put("deepseek_caption_flyout_menu","flyout_title","flyout_summary");
        put("deepseek_caption_shorts_flyout_menu","shorts_flyout_title","shorts_flyout_summary");
        put("deepseek_caption_profiles","profiles_title",null);
        put("deepseek_caption_base_url","api_address","autosave");
        put("deepseek_caption_api_key","api_key",null);
        put("deepseek_caption_model","model","model_hint");
        put("deepseek_caption_test_api","test_api",null);
        put("deepseek_caption_delete_key","profile_clear_key",null);
        put("deepseek_caption_prompt","translation_requirements","prompt_summary");
        put("deepseek_caption_style_preview","preview",null);
        put("deepseek_caption_text_size","size","size_hint");
        put("deepseek_caption_background_opacity","opacity","opacity_hint");
        put("deepseek_caption_reset_position","reset_position","message_fd1ada5395a0");
        put("deepseek_caption_clear_cache","clear_cache",null);
        put("deepseek_caption_display_text_debug","text_debug",null);
        put("deepseek_caption_diagnostics","diagnostics","diagnostic_hint");
        for(String key:new String[]{"api_config","translation","style","cache_diagnostics"})
            put("cap_ui_category_"+key,key,null);
    }
    private static void put(String key,String title,String summary){KEYS.put(key,new String[]{title,summary});}
    public static boolean owns(Preference p){return KEYS.containsKey(p.getKey());}
    static void bind(Preference p){
        String[] keys=KEYS.get(p.getKey());if(keys==null)return;
        String title=CaptionStrings.settings(p.getContext(),keys[0]);
        if(!android.text.TextUtils.equals(p.getTitle(),title))p.setTitle(title);
        if(keys[1]!=null){String summary=CaptionStrings.settings(p.getContext(),keys[1]);
            if(!android.text.TextUtils.equals(p.getSummary(),summary))p.setSummary(summary);}
        if(p instanceof CaptionUiPreference)((CaptionUiPreference)p).rebindUi();
    }
    public static void rebind(PreferenceGroup tree){
        if(tree==null)return;CaptionUiWindows.bind(tree);bind(tree);
        if(tree instanceof PreferenceScreen){android.app.Dialog dialog=((PreferenceScreen)tree).getDialog();
            if(dialog!=null && dialog.isShowing() && owns(tree)){
                dialog.setTitle(tree.getTitle());CaptionUiLocale.direction(dialog.getWindow().getDecorView(),tree.getContext());
                onSettingsView(dialog.findViewById(android.R.id.list));
            }}
        for(int i=0;i<tree.getPreferenceCount();i++){
            Preference p=tree.getPreference(i);bind(p);
            if(p instanceof PreferenceGroup)rebind((PreferenceGroup)p);
        }
    }
    public static void onSettingsLoaded(PreferenceFragment fragment){
        if(fragment==null)return;
        CaptionUiWindows.bind(fragment.getPreferenceScreen(),fragment.getActivity());
        rebind(fragment.getPreferenceScreen());
    }
    private static final Set<View> observed=Collections.newSetFromMap(new WeakHashMap<View,Boolean>());
    public static void onSettingsView(View view){
        if(!(view instanceof android.widget.ListView) || !observed.add(view))return;
        java.lang.ref.WeakReference<android.widget.ListView> weak=new java.lang.ref.WeakReference<>((android.widget.ListView)view);
        android.view.ViewTreeObserver.OnGlobalLayoutListener layout=()->{
            android.widget.ListView list=weak.get();if(list==null)return;
            android.widget.ListAdapter adapter=list.getAdapter();if(adapter==null)return;
            for(int i=0;i<list.getChildCount();i++){
                int position=list.getFirstVisiblePosition()+i;if(position>=adapter.getCount())continue;
                Object item=adapter.getItem(position);
                // The adapter's actual Preference identity determines ownership, never a row number/title.
                if(item instanceof Preference && owns((Preference)item))view((Preference)item,list.getChildAt(i));
            }
        };
        view.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View attached){}
            @Override public void onViewDetachedFromWindow(View detached){
                if(detached.getViewTreeObserver().isAlive())detached.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
                observed.remove(detached);detached.removeOnAttachStateChangeListener(this);
            }
        });
    }
    public static void view(Preference preference,View view){
        CaptionUiWindows.bind(preference,view);
        if(owns(preference)){CaptionUiLocale.direction(view,preference.getContext());CaptionLanguagesPreference.wrap(view);
            android.widget.TextView title=view.findViewById(android.R.id.title),summary=view.findViewById(android.R.id.summary);
            if(title!=null && !android.text.TextUtils.equals(title.getText(),preference.getTitle()))title.setText(preference.getTitle());
            if(summary!=null && !android.text.TextUtils.equals(summary.getText(),preference.getSummary()))summary.setText(preference.getSummary());
        }
    }
}
