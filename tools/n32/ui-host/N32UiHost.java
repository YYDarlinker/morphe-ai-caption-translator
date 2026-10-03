package app.yydarlinker.n32fixture;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.preference.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual ART/framework/WMS fixture. Uses unmodified composed host classes/XML. */
@SuppressWarnings("deprecation")
public final class N32UiHost extends Activity {
    final Handler main=new Handler(Looper.getMainLooper());
    JSONArray events=new JSONArray();
    static N32UiHost retired; static JSONArray retainedEvents; static Set<String> retainedSelection;
    static Button retiredSave; static Object retiredLease; static Runnable retiredGuard;
    static int staleGuardRuns; boolean resumedAfterRecreation;
    boolean pendingStop,stoppedOnce; Object stoppedSession; Dialog stoppedDialog;
    PreferenceFragment fragment; PreferenceScreen tree;
    String mode,runId; long start; boolean ended;
    final String[] overrides={"ZH","JA","EN","FR","AR","ES","DE","PT","RU","KO","HI","ID","VI","DEFAULT"};
    int localeIndex; Set<String> initialSelection; List<String> plannedOverrides;
    Object invoke(String klass,String method,Class<?>[] types,Object receiver,Object...args)throws Exception {
        Method m=Class.forName(klass).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(receiver,args);
    }
    void event(String type,Object...pairs){
        try{JSONObject e=new JSONObject().put("event",type).put("elapsed_ms",SystemClock.uptimeMillis()-start)
                .put("activity",getClass().getName()).put("activity_id",System.identityHashCode(this))
                .put("finishing",isFinishing()).put("destroyed",isDestroyed());
            for(int i=0;i<pairs.length;i+=2)e.put(String.valueOf(pairs[i]),pairs[i+1]);events.put(e);
            android.util.Log.i("N32_WMS",e.toString());save();
        }catch(Exception e){throw new RuntimeException(e);}
    }
    void save(){try(FileOutputStream out=openFileOutput("n32-ui-events.json",MODE_PRIVATE)){
        out.write(new JSONObject().put("mode",mode).put("run_id",runId).put("sdk",Build.VERSION.SDK_INT)
                .put("device",Build.MODEL).put("events",events).toString(2).getBytes("UTF-8"));
    }catch(Exception e){android.util.Log.e("N32_WMS","evidence write",e);}}
    void step(Runnable action){main.postDelayed(()->{if(ended)return;try{action.run();}catch(Throwable e){fail(e);}},600);}
    void fail(Throwable e){ended=true;event("FAIL","error",android.util.Log.getStackTraceString(e));}
    void pass(){ended=true;event("PASS","root",tree==null?"fallback-only":tree.getKey(),"windows",windows().size());}
    Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    @SuppressWarnings("unchecked") List<String> codes()throws Exception{Field f=Class.forName("app.yydarlinker.deepseekcaptions.CaptionLanguageSelection").getDeclaredField("CODES");f.setAccessible(true);return (List<String>)f.get(null);}
    void choose(ListView list,int index,boolean desired){
        if(list.isItemChecked(index)!=desired)list.performItemClick(list.getAdapter().getView(index,null,list),index,list.getAdapter().getItemId(index));
        check(list.isItemChecked(index)==desired,"Real multi-choice row must publish requested checked state");
    }
    void snapshot(View root,String name)throws Exception{
        if(root.getWidth()<=0 || root.getHeight()<=0)return;
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(image));
        try(FileOutputStream out=openFileOutput("n32-"+name+".png",MODE_PRIVATE)){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        event("actual_view_snapshot","file","n32-"+name+".png","width",root.getWidth(),"height",root.getHeight());
    }
    void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    @Override protected void attachBaseContext(Context original){
        Configuration cfg=new Configuration(original.getResources().getConfiguration());cfg.setLocale(Locale.CHINA);
        super.attachBaseContext(original.createConfigurationContext(cfg));
    }
    @Override public void onCreate(Bundle state){
        boolean dark=getIntent().getBooleanExtra("dark",false);
        Configuration themed=new Configuration(getBaseContext().getResources().getConfiguration());
        themed.uiMode=(themed.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | (dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
        getResources().updateConfiguration(themed,getResources().getDisplayMetrics());setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);start=SystemClock.uptimeMillis();mode=getIntent().getStringExtra("mode");runId=getIntent().getStringExtra("run-id");
        if(mode==null)mode="before";
        if(retired!=null && retainedEvents!=null && !"before".equals(mode)){events=retainedEvents;resumedAfterRecreation=true;start=retired.start;}
        if("fallback".equals(mode)){step(this::fallbackWindows);return;}
        try{
            invoke("app.morphe.extension.shared.Utils","setContext",new Class<?>[]{Context.class},null,getApplicationContext());
            invoke("app.morphe.extension.shared.Utils","setActivity",new Class<?>[]{Activity.class},null,this);
            for(String c:new String[]{"app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment",
                    "app.morphe.extension.shared.settings.preference.ToolbarPreferenceFragment",
                    "app.morphe.extension.youtube.settings.preference.YouTubePreferenceFragment",
                    "app.yydarlinker.deepseekcaptions.CaptionPreferenceBindings"}){
                Class<?> loaded=Class.forName(c);event("art_class_loaded","class",c,"methods",loaded.getDeclaredMethods().length);}
            if(!"before".equals(mode)){
                check(!getWindow().getDecorView().isAttachedToWindow(),"Activity onCreate owner decor is not yet attached");
                Object unopened=Class.forName("app.yydarlinker.deepseekcaptions.CaptionLanguagesPreference").getConstructor(Context.class).newInstance(this);
                check(invoke(unopened.getClass().getName(),"showLanguages",new Class<?>[0],unopened)==null,"Actual not-yet-attached Activity cannot show Dialog");
                event("unattached_activity_safe_rejection","context",getClass().getName(),"token",String.valueOf(getWindow().getDecorView().getWindowToken()));
            }
            Object icons=Class.forName("app.morphe.extension.shared.settings.SharedSettings").getField("SHOW_MENU_ICONS").get(null);
            icons.getClass().getMethod("save",Object.class).invoke(icons,false);
            fragment=(PreferenceFragment)Class.forName("app.morphe.extension.youtube.settings.preference.YouTubePreferenceFragment").getConstructor().newInstance();
            getFragmentManager().beginTransaction().replace(android.R.id.content,fragment).commit();
            getFragmentManager().executePendingTransactions();tree=fragment.getPreferenceScreen();
            check(tree!=null,"Actual official initialize must load settings XML");
            event("official_initialized","fragment",fragment.getClass().getName(),"root",tree.getKey(),"preference_count",tree.getPreferenceCount(),
                    "activity_locale",getResources().getConfiguration().getLocales().get(0).toLanguageTag());
            if(resumedAfterRecreation){step(this::verifyRetiredOwner);return;}
            if(!"before".equals(mode)){
                plannedOverrides=new ArrayList<>(Arrays.asList(overrides));
                if(plannedOverrides.get(0).equals(((Enum<?>)officialLanguage()).name()))Collections.swap(plannedOverrides,0,1);
                invoke("app.yydarlinker.deepseekcaptions.CaptionLanguageSelection","save",new Class<?>[]{Context.class,Collection.class},null,this,Arrays.asList("ja","fr"));
                initialSelection=selection();
            }
            event("host_scope","native_video_startup",false,"internet_permission",false,"owner_context",tree.getContext().getClass().getName(),
                    "actual_initialize_receiver",fragment.getClass().getName(),"tree_id",System.identityHashCode(tree),
                    "screen_width_dp",getResources().getConfiguration().screenWidthDp,"font_scale",getResources().getConfiguration().fontScale,"night_mode",getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
            step(this::openVideo);
        }catch(Throwable e){fail(e);}
    }
    List<View> windows(){return android.view.inspector.WindowInspector.getGlobalWindowViews();}
    JSONObject window(View root)throws Exception{
        ViewGroup.LayoutParams params=root.getLayoutParams();WindowManager.LayoutParams w=params instanceof WindowManager.LayoutParams?(WindowManager.LayoutParams)params:null;
        return new JSONObject().put("root_class",root.getClass().getName()).put("root_id",System.identityHashCode(root))
                .put("view_token",String.valueOf(root.getWindowToken())).put("attached",root.isAttachedToWindow())
                .put("params_token",w==null?"none":String.valueOf(w.token)).put("window_type",w==null?-1:w.type)
                .put("context",root.getContext().getClass().getName());
    }
    ListView screenList(PreferenceScreen screen){
        Dialog d=screen.getDialog();check(d!=null&&d.isShowing(),"screen must be visibly showing: "+screen.getKey());
        return d.findViewById(android.R.id.list);
    }
    void click(ListView list,String key){
        check(list!=null&&list.getAdapter()!=null,"Actual settings ListView/adapter required");
        for(int i=0;i<list.getAdapter().getCount();i++){
            Object item=list.getAdapter().getItem(i);if(item instanceof Preference&&key.equals(((Preference)item).getKey())){
                Preference p=(Preference)item;View row=list.getAdapter().getView(i,null,list);
                event("actual_list_click","key",key,"class",p.getClass().getName(),"row_title",String.valueOf(p.getTitle()),
                        "list",list.getClass().getName(),"adapter",list.getAdapter().getClass().getName(),
                        "listener",list.getOnItemClickListener().getClass().getName(),"root",tree.getKey(),"row_index",i);
                check(list.performItemClick(row,i,list.getAdapter().getItemId(i)),"listener must consume actual row click");return;
            }}throw new AssertionError("Missing actual row key: "+key);
    }
    PreferenceScreen video,ai;
    void openVideo(){
        video=(PreferenceScreen)tree.findPreference("morphe_settings_screen_12_video_sort_by_key");
        check(video!=null,"Actual official video screen key required");click(fragment.getView().findViewById(android.R.id.list),video.getKey());step(this::openAi);
    }
    void openAi(){
        ai=(PreferenceScreen)tree.findPreference("morphe_vot_screen__ai_captions");check(ai!=null,"AI nested PreferenceScreen required");
        click(screenList(video),ai.getKey());step(this::openLanguages);
    }
    void openLanguages(){
        click(screenList(ai),"deepseek_caption_languages");
        if("before".equals(mode))throw new AssertionError("Expected original resourceContext BadToken was not rejected");
        if(resumedAfterRecreation){step(this::recreatedLanguage);return;}
        step(()->{try{
            View root=verifyLanguageWindow("INITIAL-"+((Enum<?>)officialLanguage()).name());
            ListView list=languageList(root);boolean old=list.isItemChecked(0);
            choose(list,0,!old);
            Button cancel=root.findViewById(android.R.id.button2);check(cancel!=null,"Cancel action");cancel.performClick();
            step(()->{try{check(initialSelection.equals(selection()),"Cancel must retain original selected_codes");
                event("dialog_cancel","ai_still_showing",ai.getDialog().isShowing(),"selected",new JSONArray(selection()));
                click(screenList(ai),"deepseek_caption_languages");step(this::saveLanguage);
            }catch(Exception e){throw new RuntimeException(e);}});
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    @SuppressWarnings("unchecked") Set<String> selection()throws Exception{
        return new LinkedHashSet<>((Set<String>)invoke("app.yydarlinker.deepseekcaptions.CaptionLanguageSelection","read",new Class<?>[]{Context.class},null,this));
    }
    View verifyLanguageWindow(String override)throws Exception{
        View root=latestLanguageRoot();ListView list=languageList(root);check(list.getAdapter().getCount()==14,"14 candidate rows");
        check(root.isAttachedToWindow()&&root.getWindowToken()!=null,"Language window must have a real WMS token");
        JSONArray labels=new JSONArray();for(int i=0;i<14;i++){
            View row=list.getAdapter().getView(i,null,list);TextView text=row.findViewById(android.R.id.text1);
            check(text!=null&&text.getText().length()>0,"Each language row must contain its pure language name");
            Object ui=invoke("app.yydarlinker.deepseekcaptions.CaptionUiLocale","snapshot",new Class<?>[]{Context.class},null,this);
            List<String> codes=codes();
            String expected=(String)invoke("app.yydarlinker.deepseekcaptions.LanguageMenuOrder","label",new Class<?>[]{String.class,Locale.class},null,codes.get(i),(Locale)field(ui,"locale"));
            check(expected.contentEquals(text.getText()),"Pure localized language name only, without state: "+codes.get(i));labels.put(text.getText().toString());
        }
        event("language_dialog_visible","override",override,"window",window(root),"labels",labels,"list_count",14,
                "ai_still_showing",ai.getDialog()!=null&&ai.getDialog().isShowing(),
                "owner_token",String.valueOf(getWindow().getDecorView().getWindowToken()),"direction",root.getLayoutDirection(),
                "activity_locale",getResources().getConfiguration().getLocales().get(0).toLanguageTag());
        snapshot(root,"languages-"+override);
        check("zh-CN".equals(getResources().getConfiguration().getLocales().get(0).toLanguageTag()),"Original Activity remains Chinese");
        return root;
    }
    void saveLanguage(){try{
        View root=verifyLanguageWindow("INITIAL-"+((Enum<?>)officialLanguage()).name()+"-save");ListView list=languageList(root);
        choose(list,codes().indexOf("zh-Hans"),true);
        ((Button)root.findViewById(android.R.id.button1)).performClick();
        step(()->{try{
            check(selection().contains("zh-Hans")&&selection().containsAll(initialSelection),"Save must add checked language and preserve original set");
            initialSelection=selection();event("dialog_save","selected",new JSONArray(initialSelection),"ai_still_showing",ai.getDialog().isShowing());
            nextOverride();
        }catch(Exception e){throw new RuntimeException(e);}});
    }catch(Exception e){throw new RuntimeException(e);}}
    Object officialLanguage()throws Exception{Object setting=Class.forName("app.morphe.extension.shared.settings.BaseSettings").getField("MORPHE_LANGUAGE").get(null);return setting.getClass().getMethod("get").invoke(setting);}
    String nativeSystemText(String key)throws Exception{
        Object language=officialLanguage();Locale locale=(Locale)language.getClass().getMethod("getLocale").invoke(language);
        return (String)invoke("app.morphe.extension.shared.ResourceUtils","getSystemStringByLocale",new Class<?>[]{String.class,Locale.class},null,key,locale);
    }
    View auxiliaryRoot(){View result=null;for(View root:windows()){
        if(root==getWindow().getDecorView() || (video!=null&&video.getDialog()!=null&&root==video.getDialog().getWindow().getDecorView()) || (ai!=null&&ai.getDialog()!=null&&root==ai.getDialog().getWindow().getDecorView()))continue;
        if(root.isAttachedToWindow()&&root.getWindowToken()!=null)result=root;
    }return result;}
    JSONArray buttonLabels(View root){JSONArray labels=new JSONArray();for(TextView view:texts(root))if(view instanceof Button)labels.put(view.getText().toString());return labels;}
    void nextOverride(){try{
        if(localeIndex>=plannedOverrides.size()){step(this::traditionalLanguage);return;}
        String code=plannedOverrides.get(localeIndex++);Preference language=tree.findPreference("morphe_language");
        check(language instanceof ListPreference,"Actual official Morphe language ListPreference required");
        // Real preference persistence executes the actual official SharedPreferences listener.
        Object cached=officialLanguage();Field listenerField=Class.forName("app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment").getDeclaredField("listener");listenerField.setAccessible(true);
        ((ListPreference)language).setValue(code);
        event("official_language_preference_written","requested",code,"preference_key",language.getKey(),"preference_context",language.getContext().getClass().getName(),
                "manager_name",fragment.getPreferenceManager().getSharedPreferencesName(),"persisted",fragment.getPreferenceManager().getSharedPreferences().getString(language.getKey(),"<none>"),
                "cached_before",((Enum<?>)cached).name(),"listener",listenerField.get(fragment).getClass().getName());
        step(()->acceptLanguageConfirmation(code));
    }catch(Exception error){throw new RuntimeException(error);}}
    void acceptLanguageConfirmation(String code){try{
        Object actual=officialLanguage();
        if(!code.equals(((Enum<?>)actual).name())){
            View nativeConfirmation=auxiliaryRoot();check(nativeConfirmation!=null,"Existing official language user-confirmation must be visible before enum publication");
            event("official_language_confirmation","requested",code,"before",((Enum<?>)actual).name(),"buttons",buttonLabels(nativeConfirmation),"window",window(nativeConfirmation));
            pressText(nativeConfirmation,nativeSystemText("ok"));step(()->settleLanguageChange(code));
        }else settleLanguageChange(code);
    }catch(Exception error){throw new RuntimeException(error);}}
    void settleLanguageChange(String code){try{
        Object actual=officialLanguage();check(code.equals(((Enum<?>)actual).name()),"Confirmed native language callback must publish real setting: "+code+" vs "+actual);
        View restart=auxiliaryRoot();if(restart!=null){
            event("official_existing_restart_prompt_cancelled","requested",code,"buttons",buttonLabels(restart),"window",window(restart));
            pressText(restart,nativeSystemText("cancel"));
        }
        event("language_callback_executed","requested",code,"actual",actual.toString(),"title",String.valueOf(tree.findPreference("deepseek_caption_languages").getTitle()),
                "fragment",fragment.getClass().getName(),"tree_id",System.identityHashCode(tree));
        check(ai.getDialog().isShowing()&&!isFinishing(),"Confirmed Morphe override leaves actual AI screen open without Activity restart");
        click(screenList(ai),"deepseek_caption_languages");click(screenList(ai),"deepseek_caption_languages");
        step(()->{try{
            View root=verifyLanguageWindow(code);int windowCount=windows().size();
            click(screenList(ai),"deepseek_caption_languages");check(latestLanguageRoot()==root,"Repeated language operation coalesces");
            check(windows().size()==windowCount,"Exactly one language window for duplicate clicks");event("duplicate_open_coalesced","override",code,"windows",windowCount);
            if(code.equals("AR"))check(root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL,"Arabic dialog RTL");
            root.findViewById(android.R.id.button2).performClick();step(()->{try{check(initialSelection.equals(selection()),"All localized Cancel operations preserve saved selection");nextOverride();}
                catch(Exception error){throw new RuntimeException(error);}});
        }catch(Exception error){throw new RuntimeException(error);}});
    }catch(Exception error){throw new RuntimeException(error);}}
    String text(String key)throws Exception{return (String)invoke("app.yydarlinker.deepseekcaptions.CaptionStrings","settings",new Class<?>[]{Context.class,String.class},null,this,key);}
    List<TextView> texts(View root){List<TextView> found=new ArrayList<>();if(root instanceof TextView)found.add((TextView)root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)found.addAll(texts(((ViewGroup)root).getChildAt(i)));return found;}
    void pressText(View root,String label){for(TextView t:texts(root))if(label.contentEquals(t.getText())){check(t.performClick(),"Action must have listener: "+label);return;}
        throw new AssertionError("Visible action not found: "+label);}
    Dialog profileDialog()throws Exception{Object p=tree.findPreference("deepseek_caption_profiles");Field f=p.getClass().getDeclaredField("dialog");f.setAccessible(true);return (Dialog)f.get(p);}
    void sharedWindows(){try{
        click(screenList(ai),"deepseek_caption_profiles");step(()->{try{
            Dialog d=profileDialog();check(d!=null&&d.isShowing(),"Actual official CustomDialog profile list displayed");
            event("shared_profile_list","dialog",d.getClass().getName(),"window",window(d.getWindow().getDecorView()));
            pressText(d.getWindow().getDecorView(),text("profile_add"));step(()->{try{
                Dialog add=profileDialog();check(add!=null&&add.isShowing()&&add!=d,"Profile add official dialog displayed");
                event("shared_profile_add","dialog",add.getClass().getName(),"window",window(add.getWindow().getDecorView()));
                pressText(add.getWindow().getDecorView(),text("cancel"));step(this::modelPopup);
            }catch(Exception e){throw new RuntimeException(e);}});
        }catch(Exception e){throw new RuntimeException(e);}});
    }catch(Exception e){throw new RuntimeException(e);}}
    void confirmWindows(){try{
        click(screenList(ai),"deepseek_caption_delete_key");step(()->{try{
            Dialog d=(Dialog)invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","find",new Class<?>[]{Context.class,String.class},null,this,
                    "confirm:"+((Map<?,?>)invoke("app.yydarlinker.deepseekcaptions.ApiProfiles","list",new Class<?>[]{Context.class},null,this)).values().iterator().next());
            check(d!=null&&d.isShowing(),"Clear Key official CustomDialog confirm shown");
            event("shared_clear_key_confirm","dialog",d.getClass().getName(),"window",window(d.getWindow().getDecorView()));
            pressText(d.getWindow().getDecorView(),text("cancel"));step(this::diagnosticCopy);
        }catch(Exception e){throw new RuntimeException(e);}});
    }catch(Exception e){throw new RuntimeException(e);}}
    void diagnosticCopy(){try{
        invoke("app.yydarlinker.deepseekcaptions.DeepSeekDiagnosticsPreference","copyPages",new Class<?>[]{Context.class,String.class},null,this,"原始证据\n".repeat(15000));
        step(()->{try{
            Dialog d=(Dialog)invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","find",new Class<?>[]{Context.class,String.class},null,this,"diagnostic-copy");
            check(d!=null&&d.isShowing(),"Android9 copy-pages branch actual platform window displayed on SDK35 fixture");
            event("shared_diagnostic_copy_pages","dialog",d.getClass().getName(),"window",window(d.getWindow().getDecorView()));d.cancel();step(this::diagnosticConfirmation);
        }catch(Exception e){throw new RuntimeException(e);}});
    }catch(Exception e){throw new RuntimeException(e);}}
    void back(Dialog dialog){
        View root=dialog.getWindow().getDecorView();root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));
        root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
        check(!dialog.isShowing(),"Real Dialog Back key must close window");
    }
    void backChain(){try{
        click(screenList(ai),"deepseek_caption_languages");step(()->{try{
            Dialog lang=(Dialog)invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","find",new Class<?>[]{Context.class,String.class},null,this,"languages");
            back(lang);check(initialSelection.equals(selection()),"Back discards unsaved language draft");
            check(ai.getDialog().isShowing()&&!isFinishing(),"Back from languages stays on AI screen without restart");
            event("language_back","ai_showing",true,"selected",new JSONArray(selection()));
            back(ai.getDialog());check(video.getDialog().isShowing(),"AI Back returns to video");
            back(video.getDialog());event("settings_back_to_root","root",tree.getKey(),"fragment",fragment.getClass().getName());
            step(this::generalNavigation);
        }catch(Exception e){throw new RuntimeException(e);}});
    }catch(Exception e){throw new RuntimeException(e);}}
    void generalNavigation(){
        PreferenceScreen general=(PreferenceScreen)tree.findPreference("morphe_settings_screen_04_general_sort_by_title");
        check(general!=null,"Actual official General screen present");click(fragment.getView().findViewById(android.R.id.list),general.getKey());
        step(()->{try{
            check(general.getDialog()!=null&&general.getDialog().isShowing(),"Official General native route shows its screen");
            event("official_general_visible","key",general.getKey(),"title",String.valueOf(general.getTitle()),
                    "dialog",general.getDialog().getClass().getName(),"window",window(general.getDialog().getWindow().getDecorView()));
            back(general.getDialog());check(!isFinishing(),"General Back returns to root without finishing Activity");
            event("official_general_back","root",tree.getKey());stopRestart();
        }catch(Exception e){throw new RuntimeException(e);}});
    }

    public static final class StopHost extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);TextView view=new TextView(this);view.setText("N32 local lifecycle stop fixture");setContentView(view);new Handler(Looper.getMainLooper()).postDelayed(this::finish,1400);}
    }
    void stopRestart(){try{
        if("navigation".equals(mode)){event("epoch_rejection_deferred","scope","Provisional navigation fixture before final source rebuild");ownerNegatives();return;}
        stoppedSession=invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","acquire",new Class<?>[]{Context.class,String.class},null,this,"stop-restart-old");
        check(stoppedSession!=null,"Real live owner session acquired before onStop");
        stoppedDialog=new AlertDialog.Builder((Context)field(stoppedSession,"context")).setMessage("Old session must not show after stop/restart").create();
        pendingStop=true;event("stop_restart_requested","token",String.valueOf(getWindow().getDecorView().getWindowToken()));startActivity(new Intent(this,StopHost.class));
    }catch(Exception error){throw new RuntimeException(error);}}
    @Override public void onStop(){super.onStop();if(pendingStop){stoppedOnce=true;event("actual_activity_stopped");}}
    @Override public void onResume(){super.onResume();if(pendingStop&&stoppedOnce){pendingStop=false;step(()->{try{
        check(!(boolean)invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","show",new Class<?>[]{stoppedSession.getClass(),Dialog.class},null,stoppedSession,stoppedDialog),"Old session from before onStop must remain rejected after real onStart");
        check(!stoppedDialog.isShowing(),"Stale onStop session cannot publish WMS window");
        Object fresh=invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","acquire",new Class<?>[]{Context.class,String.class},null,this,"stop-restart-fresh");
        check(fresh!=null,"Restarted same Activity can acquire a fresh session");Dialog current=new AlertDialog.Builder((Context)field(fresh,"context")).setMessage("Fresh restarted owner").create();
        check((boolean)invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","show",new Class<?>[]{fresh.getClass(),Dialog.class},null,fresh,current),"Fresh post-onStart owner WMS show succeeds");
        step(()->{try{View realRoot=current.getWindow().getDecorView();check(realRoot.isAttachedToWindow()&&realRoot.getWindowToken()!=null,"Fresh restarted session actually attaches with WMS token");
            event("stop_restart_epoch_rejection","old_epoch",field(stoppedSession,"epoch"),"new_epoch",field(fresh,"epoch"),"window",window(realRoot));current.dismiss();ownerNegatives();
        }catch(Exception error){throw new RuntimeException(error);}});
    }catch(Exception error){throw new RuntimeException(error);}});}}

    void ownerNegatives(){try{
        Set<String> saved=selection();Object noOwner=Class.forName("app.yydarlinker.deepseekcaptions.CaptionLanguagesPreference")
                .getConstructor(Context.class).newInstance(new ContextWrapper(getApplicationContext()));
        Object d=invoke(noOwner.getClass().getName(),"showLanguages",new Class<?>[0],noOwner);
        check(d==null,"Application context without bound settings owner safely rejects window");check(saved.equals(selection()),"Owner rejection preserves selection");
        event("no_owner_safe_rejection","context","fresh ContextWrapper(Application)","selected",new JSONArray(saved));
        final Object[] worker=new Object[1];final Throwable[] workerError=new Throwable[1];
        Thread thread=new Thread(()->{try{worker[0]=invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","acquire",new Class<?>[]{Context.class,String.class},null,this,"worker");}catch(Throwable error){workerError[0]=error;}});thread.start();thread.join();
        check(workerError[0]==null&&worker[0]==null,"Worker thread cannot acquire UI session");event("worker_thread_safe_rejection");
        if("navigation".equals(mode)){event("provisional_navigation_complete","final_lifecycle_guards_verified",false);pass();return;}
        resumedAfterRecreation=false;step(this::beginRecreation);
    }catch(Exception e){throw new RuntimeException(e);}}

    void traditionalLanguage(){try{
        // Official 1.45 AppLanguage has only ZH, not a Traditional Chinese enum.
        // Exercise supported DEFAULT/context snapshot with an Activity-rooted override.
        Configuration config=new Configuration(getResources().getConfiguration());config.setLocale(Locale.TAIWAN);
        android.view.ContextThemeWrapper display=new android.view.ContextThemeWrapper(this,0);display.applyOverrideConfiguration(config);display.getTheme().setTo(getTheme());
        Object preference=Class.forName("app.yydarlinker.deepseekcaptions.CaptionLanguagesPreference").getConstructor(Context.class).newInstance(display);
        AlertDialog dialog=(AlertDialog)invoke(preference.getClass().getName(),"showLanguages",new Class<?>[0],preference);
        step(()->{try{
            check(dialog!=null&&dialog.isShowing(),"Traditional Chinese DEFAULT context snapshot actual dialog");ListView list=dialog.getListView();check(list.getAdapter().getCount()==14,"Traditional Chinese 14 candidates");
            JSONArray labels=new JSONArray();List<String> codeList=codes();for(int i=0;i<14;i++){
                TextView row=list.getAdapter().getView(i,null,list).findViewById(android.R.id.text1);
                String expected=(String)invoke("app.yydarlinker.deepseekcaptions.LanguageMenuOrder","label",new Class<?>[]{String.class,Locale.class},null,codeList.get(i),Locale.TAIWAN);
                check(expected.contentEquals(row.getText()),"Traditional pure language label");labels.put(row.getText());
            }
            View root=dialog.getWindow().getDecorView();check(root.isAttachedToWindow()&&root.getWindowToken()!=null,"Traditional snapshot real WMS token");
            event("traditional_chinese_context_snapshot","official_explicit_enum",false,"route","DEFAULT plus Activity-rooted zh-TW resource snapshot","labels",labels,"window",window(root),"activity_locale",getResources().getConfiguration().getLocales().get(0).toLanguageTag());
            snapshot(root,"languages-zh-TW-context");dialog.cancel();step(this::engineStates);
        }catch(Exception error){throw new RuntimeException(error);}});
    }catch(Exception error){throw new RuntimeException(error);}}
    int engineIndex;
    void engineStates(){try{
        if(engineIndex>=2){invoke("app.yydarlinker.deepseekcaptions.DeepSeekConfig","saveEnabled",new Class<?>[]{Context.class,boolean.class},null,this,false);step(this::sharedWindows);return;}
        boolean enabled=engineIndex++==1;
        invoke("app.yydarlinker.deepseekcaptions.DeepSeekConfig","saveEnabled",new Class<?>[]{Context.class,boolean.class},null,this,enabled);
        Object loaded=invoke("app.yydarlinker.deepseekcaptions.DeepSeekConfig","load",new Class<?>[]{Context.class},null,this);
        check((boolean)field(loaded,"enabled")==enabled,"Saved engine configuration state");
        Object p=tree.findPreference("deepseek_caption_languages");
        AlertDialog dialog=(AlertDialog)invoke(p.getClass().getName(),"showLanguages",new Class<?>[0],p);
        step(()->{try{
            check(dialog!=null&&dialog.isShowing()&&dialog.getListView().getAdapter().getCount()==14,"14 languages available with saved AI state "+enabled);
            View root=dialog.getWindow().getDecorView();check(root.isAttachedToWindow()&&root.getWindowToken()!=null,"Off/on language dialog real WMS token");
            event("engine_state_languages","enabled",enabled,"native_video_startup",false,"api_requests",0,"window",window(root));dialog.cancel();step(this::engineStates);
        }catch(Exception error){throw new RuntimeException(error);}});
    }catch(Exception error){throw new RuntimeException(error);}}
    int position(ListView list,String key){for(int i=0;i<list.getAdapter().getCount();i++){
        Object p=list.getAdapter().getItem(i);if(p instanceof Preference&&key.equals(((Preference)p).getKey()))return i;
    }throw new AssertionError("Actual preference missing: "+key);}
    void modelPopup(){try{
        ListView list=screenList(ai);list.setSelection(position(list,"deepseek_caption_model"));
        step(()->{try{
            Object model=tree.findPreference("deepseek_caption_model");
            invoke(model.getClass().getName(),"showModels",new Class<?>[]{List.class,boolean.class},model,Arrays.asList("fixture-model-a","fixture-model-b"),false);
            TextView anchor=(TextView)field(model,"choices");check(anchor!=null&&anchor.isAttachedToWindow(),"Actual attached inline model anchor");
            event("model_popup_before_click","attached",anchor.isAttachedToWindow(),"isCurrent",invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","isCurrent",new Class<?>[]{View.class},null,anchor),"width",anchor.getWidth(),"shown_models",field(model,"shownModels"),"bound_profile",field(model,"boundProfile"),"active_profile",invoke("app.yydarlinker.deepseekcaptions.ApiProfiles","active",new Class<?>[]{Context.class},null,this),"bound_revision",field(model,"boundRevision"),"profile_revision",invoke("app.yydarlinker.deepseekcaptions.ApiProfiles","revision",new Class<?>[0],null));
            check(anchor.performClick(),"Real model chooser anchor click");
            step(()->{try{
                PopupWindow popup=(PopupWindow)field(model,"modelMenu");event("model_popup_state","popup_null",popup==null,"popup_showing",popup!=null&&popup.isShowing(),"anchor_attached",anchor.isAttachedToWindow(),"anchor_token",String.valueOf(anchor.getWindowToken()),"app_token",String.valueOf(anchor.getApplicationWindowToken()),"content_attached",popup!=null&&popup.getContentView().isAttachedToWindow(),"content_token",popup==null?"null":String.valueOf(popup.getContentView().getWindowToken()),"global_windows",windows().size());check(popup!=null&&popup.isShowing(),"Model popup actual WMS visibility");
                event("shared_model_popup","cached_fixture_models",true,"remote_fetch",false,"anchor",window(anchor.getRootView()),"content_token",String.valueOf(popup.getContentView().getWindowToken()));
                snapshot(popup.getContentView(),"model-popup");popup.dismiss();step(this::confirmWindows);
            }catch(Exception error){throw new RuntimeException(error);}});
        }catch(Exception error){throw new RuntimeException(error);}});
    }catch(Exception error){throw new RuntimeException(error);}}
    void diagnosticConfirmation(){try{
        Dialog dialog=(Dialog)invoke("app.yydarlinker.deepseekcaptions.CaptionSettingsDialogs","confirm",new Class<?>[]{Context.class,String.class,String.class,String.class,Runnable.class},null,
                this,text("clear_diagnostics"),text("clear_diagnostics_confirm"),text("clear_diagnostics"),(Runnable)()->{throw new AssertionError("Cancel must never mutate diagnostics");});
        check(dialog!=null&&dialog.isShowing(),"Shared diagnostics confirmation actual WMS window");
        event("shared_diagnostics_confirm","dialog",dialog.getClass().getName(),"window",window(dialog.getWindow().getDecorView()));
        pressText(dialog.getWindow().getDecorView(),text("cancel"));step(this::backChain);
    }catch(Exception error){throw new RuntimeException(error);}}
    void beginRecreation(){
        click(fragment.getView().findViewById(android.R.id.list),"morphe_settings_screen_12_video_sort_by_key");
        step(()->{click(screenList(video),"morphe_vot_screen__ai_captions");step(()->{
            click(screenList(ai),"deepseek_caption_languages");step(()->{try{
                View root=verifyLanguageWindow("before-recreation");ListView list=languageList(root);choose(list,2,!list.isItemChecked(2));
                retainedSelection=selection();retiredSave=root.findViewById(android.R.id.button1);
                retiredLease=invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","capture",new Class<?>[]{View.class},null,getWindow().getDecorView());
                check(retiredLease!=null,"Attached real Activity decor lease captured");
                Object session=invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","acquire",new Class<?>[]{Context.class,String.class},null,this,"retired-confirm");
                AlertDialog guardedDialog=new AlertDialog.Builder((Context)field(session,"context")).setMessage("retired-owner-guard-fixture").create();
                check((boolean)invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","show",new Class<?>[]{session.getClass(),Dialog.class},null,session,guardedDialog),"Real guarded dialog registered");
                check((boolean)invoke(session.getClass().getName(),"current",new Class<?>[0],session),"Callback session is current before recreation");
                retiredGuard=(Runnable)invoke(session.getClass().getName(),"guard",new Class<?>[]{Runnable.class},session,(Runnable)()->staleGuardRuns++);
                retired=this;retainedEvents=events;event("activity_recreation_requested","old_token",String.valueOf(getWindow().getDecorView().getWindowToken()));recreate();
            }catch(Exception error){throw new RuntimeException(error);}});
        });});
    }
    void verifyRetiredOwner(){try{
        check(retired.isDestroyed(),"Actual Activity recreate must destroy previous settings owner");
        invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","bind",new Class<?>[]{PreferenceGroup.class,Activity.class},null,retired.tree,retired);
        Field stateField=Class.forName("app.yydarlinker.deepseekcaptions.CaptionUiWindows").getDeclaredField("states");stateField.setAccessible(true);
        check(!((Map<?,?>)stateField.get(null)).containsKey(retired),"Binding destroyed owner cannot recreate lifecycle state");
        event("destroyed_bind_does_not_resurrect_owner");
        check(!(boolean)invoke(retiredLease.getClass().getName(),"current",new Class<?>[0],retiredLease),"Lease from destroyed owner is stale");
        Object old=retired.tree.findPreference("deepseek_caption_languages");
        check(invoke(old.getClass().getName(),"showLanguages",new Class<?>[0],old)==null,"Retired preference cannot acquire a newer settings owner");
        retiredGuard.run();check(staleGuardRuns==0,"Retired shared callback guard cannot run");retiredSave.performClick();
        event("recreated_owner_rejection","old_destroyed",true,"stale_lease_current",false,"stale_guard_runs",staleGuardRuns,"old_owner",System.identityHashCode(retired),"new_token",String.valueOf(getWindow().getDecorView().getWindowToken()));
        step(()->{try{check(retainedSelection.equals(selection()),"Stale language Save preserves selected codes after recreation");
            initialSelection=selection();
            video=(PreferenceScreen)tree.findPreference("morphe_settings_screen_12_video_sort_by_key");
            ai=(PreferenceScreen)tree.findPreference("morphe_vot_screen__ai_captions");
            event("stale_save_preserves_selection","selected",new JSONArray(initialSelection));openVideo();
        }catch(Exception error){throw new RuntimeException(error);}});
    }catch(Exception error){throw new RuntimeException(error);}}
    void recreatedLanguage(){try{
        View root=verifyLanguageWindow("after-recreation");root.findViewById(android.R.id.button2).performClick();
        step(()->{try{
            check(initialSelection.equals(selection()),"Recreated owner Cancel preserves selection");
            Object p=tree.findPreference("deepseek_caption_languages");Set<String> saved=selection();finish();
            check(isFinishing(),"Activity finishing flag");
            check(invoke(p.getClass().getName(),"showLanguages",new Class<?>[0],p)==null,"Finishing owner cannot show window");event("finishing_owner_safe_rejection");
            main.postDelayed(()->{try{check(isDestroyed(),"Finished Activity is actually destroyed");
                check(invoke(p.getClass().getName(),"showLanguages",new Class<?>[0],p)==null,"Destroyed owner cannot show window");
                check(saved.equals(selection()),"Owner invalidation preserves selected codes");event("destroyed_owner_safe_rejection","selected",new JSONArray(saved));pass();
            }catch(Throwable error){fail(error);}},900);
        }catch(Exception error){throw new RuntimeException(error);}});
    }catch(Exception error){throw new RuntimeException(error);}}
    void fallbackWindows(){try{
        boolean absent=false;try{Class.forName("app.morphe.extension.shared.ui.CustomDialog");}catch(ClassNotFoundException expected){absent=true;}
        check(absent,"Fallback fixture uses standalone real addon DEX without official CustomDialog");
        LinearLayout body=new LinearLayout(this);TextView original=new TextView(this);original.setText("原始 source / provider / prompt");body.addView(original);
        Dialog display=(Dialog)invoke("app.yydarlinker.deepseekcaptions.CaptionSettingsDialogs","show",new Class<?>[]{Context.class,String.class,View.class,String.class},null,this,text("profiles_title"),body,text("cancel"));
        step(()->{try{
            check(display instanceof AlertDialog&&display.isShowing(),"Actual fallback AlertDialog WMS window");
            View root=display.getWindow().getDecorView();check(root.isAttachedToWindow()&&root.getWindowToken()!=null,"Fallback display real attached WMS token");
            event("fallback_show_actual","window",window(root),"dialog",display.getClass().getName());snapshot(root,"fallback-show");display.cancel();
            Dialog confirm=(Dialog)invoke("app.yydarlinker.deepseekcaptions.CaptionSettingsDialogs","confirm",new Class<?>[]{Context.class,String.class,String.class,String.class,Runnable.class},null,this,text("clear_diagnostics"),text("clear_diagnostics_confirm"),text("clear_diagnostics"),(Runnable)()->{throw new AssertionError("Fallback Cancel must not mutate data");});
            step(()->{try{
                check(confirm instanceof AlertDialog&&confirm.isShowing(),"Fallback confirm actual window");View confirmRoot=confirm.getWindow().getDecorView();
                check(confirmRoot.isAttachedToWindow()&&confirmRoot.getWindowToken()!=null,"Fallback confirm real attached WMS token");
                event("fallback_confirm_actual","window",window(confirmRoot));snapshot(confirmRoot,"fallback-confirm");confirm.cancel();pass();
            }catch(Exception error){throw new RuntimeException(error);}});
        }catch(Exception error){throw new RuntimeException(error);}});
    }catch(Exception error){throw new RuntimeException(error);}}
    ListView languageList(View root)throws Exception{
        AlertDialog dialog=(AlertDialog)invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","find",new Class<?>[]{Context.class,String.class},null,this,"languages");
        check(dialog!=null&&dialog.getWindow().getDecorView()==root,"Language root belongs to the actual registered AlertDialog");
        return dialog.getListView();
    }
    View latestLanguageRoot()throws Exception{
        Dialog dialog=(Dialog)invoke("app.yydarlinker.deepseekcaptions.CaptionUiWindows","find",new Class<?>[]{Context.class,String.class},null,this,"languages");
        check(dialog instanceof AlertDialog && dialog.isShowing(),"Real showing registered language AlertDialog");
        ListView list=((AlertDialog)dialog).getListView();check(list!=null&&list.getAdapter()!=null&&list.getAdapter().getCount()==14,"Actual AlertDialog.getListView has14 candidate rows");
        return dialog.getWindow().getDecorView();
    }
    @Override public void onDestroy(){event("activity_destroy");super.onDestroy();}
}
