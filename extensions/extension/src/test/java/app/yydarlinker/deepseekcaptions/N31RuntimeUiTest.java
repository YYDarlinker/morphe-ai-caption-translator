package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.preference.*;
import android.view.*;
import android.widget.*;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.*;
import java.util.*;
import java.io.*;
import java.lang.reflect.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowDialog;

/** Primary matrix never changes the original Chinese Activity to the requested override. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class N31RuntimeUiTest {
    public static class Host extends PreferenceActivity {}
    static final String[] TAGS=N30LocalizationRuntimeTest.TAGS;
    Host a;PreferenceScreen tree;Locale system;JSONObject authored;LinearLayout page;
    final Map<String,View> rows=new LinkedHashMap<>();
    @Before public void setup()throws Exception {
        system=Locale.getDefault();Locale.setDefault(Locale.CHINA);
        RuntimeEnvironment.getApplication().getApplicationInfo().flags|=android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
        a=Robolectric.buildActivity(Host.class).setup().visible().get();ResourceUtils.activity=a;
        BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.OVERRIDE;AppLanguage.selected=Locale.CHINA;
        authored=new JSONObject(new String(getClass().getResourceAsStream("/n30/localization-expected.json").readAllBytes(),"UTF-8"));
        int xml=a.getResources().getIdentifier("n31_morphe_prefs","xml",a.getPackageName());assertNotEquals(0,xml);
        a.addPreferencesFromResource(xml);tree=a.getPreferenceScreen();
        CaptionDiagnostics.clear(a);CaptionDiagnostics.mark(a,"N31_RAW","source=原始证据;prompt=自定义;provider=原样错误");
    }
    @After public void done(){
        if(ShadowDialog.getLatestDialog()!=null)ShadowDialog.getLatestDialog().dismiss();
        BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.DEFAULT;ResourceUtils.activity=null;
        a.finish();Locale.setDefault(system);
    }
    void language(String tag){AppLanguage.selected=Locale.forLanguageTag(tag);CaptionPreferenceBindings.rebind(tree);}
    String folder(String tag){return tag.startsWith("zh")?(tag.contains("TW")||tag.contains("Hant")?"zh-rTW":"zh-rCN"):tag.split("-")[0].equals("in")?"id":tag.split("-")[0];}
    String expected(String tag,String key)throws Exception{return authored.getJSONObject(folder(tag)).getString(key);}
    Preference pref(String key){Preference p=tree.findPreference(key);assertNotNull(key,p);return p;}
    List<Preference> all(PreferenceGroup g){List<Preference> p=new ArrayList<>();for(int i=0;i<g.getPreferenceCount();i++){Preference n=g.getPreference(i);if(CaptionPreferenceBindings.owns(n))p.add(n);if(n instanceof PreferenceGroup)p.addAll(all((PreferenceGroup)n));}return p;}
    static List<TextView> texts(View v){List<TextView> out=new ArrayList<>();if(v instanceof TextView)out.add((TextView)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)out.addAll(texts(((ViewGroup)v).getChildAt(i)));return out;}
    static TextView label(View v,String text){for(TextView t:texts(v))if(text.contentEquals(t.getText()))return t;return null;}
    static EditText edit(View v){for(TextView t:texts(v))if(t instanceof EditText)return (EditText)t;throw new AssertionError("No actual editor");}
    void render(int widthDp,float font)throws Exception {
        Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.fontScale=font;
        // Font/density boundary only; original Activity and Application language remain Chinese.
        a.getResources().updateConfiguration(cfg,a.getResources().getDisplayMetrics());
        if(page!=null && page.getParent() instanceof ViewGroup)((ViewGroup)page.getParent()).removeView(page);
        page=new LinearLayout(a);page.setOrientation(1);page.setBackgroundColor(Color.WHITE);
        rows.clear();CaptionPreferenceBindings.rebind(tree);
        for(Preference p:all(tree)){
            View v=p.getView(null,new FrameLayout(a));if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);
            CaptionPreferenceBindings.view(p,v);rows.put(p.getKey(),v);page.addView(v,new LinearLayout.LayoutParams(-1,-2));
        }
        a.addContentView(page,new ViewGroup.LayoutParams(-1,-2));Shadows.shadowOf(Looper.getMainLooper()).idle();measure(page,widthDp);
    }
    static void measure(View v,int widthDp){int w=Math.round(widthDp*v.getResources().getDisplayMetrics().density);v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,w,v.getMeasuredHeight());}
    JSONArray snapshot(View v)throws Exception {JSONArray array=new JSONArray();for(TextView t:texts(v))array.put(new JSONObject().put("class",t.getClass().getName()).put("id",t.getId()).put("tag",String.valueOf(t.getTag())).put("text",t.getText().toString()).put("hint",String.valueOf(t.getHint())).put("description",String.valueOf(t.getContentDescription())).put("visibility",t.getVisibility()).put("width",t.getWidth()).put("height",t.getHeight()));return array;}
    void assertLabel(View v,String text){assertNotNull("actual View missing: "+text,label(v,text));}
    void checkActual(String tag)throws Exception {
        checkActual(tag,true);
    }
    void checkActual(String tag,boolean override)throws Exception {
        assertEquals("Chinese original Activity",Locale.CHINA,a.getResources().getConfiguration().getLocales().get(0));
        assertEquals("Chinese original Application",Locale.CHINA,RuntimeEnvironment.getApplication().getResources().getConfiguration().getLocales().get(0));
        assertEquals(Locale.CHINA,Locale.getDefault());if(override)assertEquals("morphe_override",CaptionUiLocale.snapshot(a).source);
        assertEquals(expected(tag,"ai_title"),pref("morphe_vot_screen__ai_captions").getTitle());
        assertEquals(expected(tag,"ai_summary"),pref("morphe_vot_screen__ai_captions").getSummary());
        assertEquals(expected(tag,"languages_summary"),pref("deepseek_caption_languages").getSummary());
        for(Preference p:all(tree))if(!(p instanceof SubtitleStylePreview))assertLabel(rows.get(p.getKey()),p.getTitle().toString());
        assertEquals(expected(tag,"default_prompt"),edit(rows.get("deepseek_caption_prompt")).getText().toString());
        assertLabel(rows.get("deepseek_caption_style_preview"),expected(tag,"preview_hint"));
        View preview=rows.get("deepseek_caption_style_preview").findViewWithTag("ai_style_preview_canvas");
        Bitmap image=Bitmap.createBitmap(preview.getWidth(),preview.getHeight(),Bitmap.Config.ARGB_8888);preview.draw(new Canvas(image));
        assertEquals(expected(tag,"preview_sample"),SubtitleStylePreview.LAST_DRAWN_SAMPLE);
        assertEquals(expected(tag,"preview"),preview.getContentDescription());assertNotNull(SubtitleStylePreview.LAST_CAPTION_BOX);
        LinearLayout names=rows.get("deepseek_caption_text_size").findViewWithTag("ai_size_tier_names");
        String[] tiers={"size_tier_xs","size_tier_s","size_tier_standard","size_tier_l","size_tier_xl"};
        for(int i=0;i<5;i++){TextView name=(TextView)names.getChildAt(i);assertEquals(expected(tag,tiers[i]),name.getText().toString());N30LocalizationRuntimeTest.check(name);if(i>0)assertTrue("tier overlap",name.getLeft()>=names.getChildAt(i-1).getRight());}
        View diagnostics=rows.get("deepseek_caption_diagnostics");
        diagnostics.findViewWithTag("ai_diagnostics_toggle").performClick();
        for(String[] binding:new String[][]{{"ai_diagnostics_toggle","collapse"},{"ai_diagnostics_refresh","refresh"},{"ai_diagnostics_copy","copy"},{"ai_diagnostics_save","save_diagnostics"},{"ai_diagnostics_clear","clear_diagnostics"}})
            assertEquals(expected(tag,binding[1]),((TextView)diagnostics.findViewWithTag(binding[0])).getText().toString());
        String body=((TextView)diagnostics.findViewWithTag("ai_diagnostics_body")).getText().toString();
        assertTrue(body.startsWith("Engine: "));assertTrue(body.contains("source=原始证据;prompt=自定义;provider=原样错误"));
    }
    void save(String name,Object json)throws Exception {String dir=System.getenv("N31_EVIDENCE_DIR");if(dir==null)return;try(FileOutputStream out=new FileOutputStream(new File(dir,name))){out.write(json.toString().getBytes("UTF-8"));}}
    void screenshot(String name,View v)throws Exception {String dir=System.getenv("N31_EVIDENCE_DIR");if(dir==null)return;JSONArray editors=new JSONArray();for(TextView t:texts(v))if(t instanceof EditText){editors.put(new JSONObject().put("text",t.getText().toString()).put("color",Integer.toHexString(t.getCurrentTextColor())).put("scroll_x",t.getScrollX()).put("scroll_y",t.getScrollY()).put("layout_text",t.getLayout()==null?"missing":t.getLayout().getText().toString()));t.setLayerType(View.LAYER_TYPE_SOFTWARE,null);t.scrollTo(0,0);}save(name+"-editors.json",editors);Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}

    @Test public void fourteenExplicitOverridesInflateActualGeneratedTreeAndDrawEveryControl()throws Exception {
        JSONArray matrix=new JSONArray();
        for(String tag:TAGS)for(int width:new int[]{320,420})for(float font:new float[]{1f,1.3f}){language(tag);render(width,font);checkActual(tag);measure(page,width);
            if(width==320 && font==1f){JSONObject values=authored.getJSONObject(folder(tag));Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();assertEquals(tag+":"+key,values.getString(key),CaptionStrings.settings(a,key));}}
            for(View row:rows.values())assertEquals(android.text.TextUtils.getLayoutDirectionFromLocale(CaptionUiLocale.snapshot(a).locale),row.getLayoutDirection());
            save("tree-"+tag+"-"+width+"-"+font+".json",snapshot(page));
            if(width==320&&font==1.3f){save("tree-"+tag+".json",snapshot(page));if(Arrays.asList("zh-CN","ja","en","fr","ar").contains(tag))screenshot("controls-"+tag+".png",page);}
            matrix.put(new JSONObject().put("override",tag).put("activity","zh-CN").put("application","zh-CN").put("owned_preferences",rows.size()).put("width_dp",width).put("font_scale",font).put("views",snapshot(page)));
        }save("n31-primary-matrix.json",matrix);
    }
    @Test public void sameTreeRebindKeepsPristineDefaultAndNeverWritesOrChangesRevision()throws Exception {
        render(420,1f);EditText prompt=edit(rows.get("deepseek_caption_prompt"));
        Map<String,?> before=new HashMap<>(ApiProfiles.values(a).getAll());long revision=ApiProfiles.revision();String business=DeepSeekConfig.load(a).fingerprint();
        JSONArray result=new JSONArray();
        for(String tag:new String[]{"zh-CN","ja","en","fr","ar","zh-CN"}){
            language(tag);measure(page,420);assertEquals(expected(tag,"default_prompt"),prompt.getText().toString());
            assertEquals(business,DeepSeekConfig.load(a).fingerprint());assertEquals(before,ApiProfiles.values(a).getAll());assertEquals(revision,ApiProfiles.revision());
            assertEquals(expected(tag,"preview_hint"),label(rows.get("deepseek_caption_style_preview"),expected(tag,"preview_hint")).getText().toString());
            result.put(new JSONObject().put("locale",tag).put("business_prompt",DeepSeekConfig.load(a).prompt).put("effective",DeepSeekConfig.load(a).effectivePreference).put("revision",revision));
        }Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertEquals(before,ApiProfiles.values(a).getAll());save("n31-default-layering.json",result);
    }
    @Test public void customChineseAndValidUnsavedDraftSurviveLanguageRefreshAndRecycling()throws Exception {
        String custom="自定义：字幕大小，已自动保存；http://配置.example/model";DeepSeekConfig.savePrompt(a,custom);ApiProfiles.rename(a,"default","字幕大小 中文方案");
        render(420,1f);EditText prompt=edit(rows.get("deepseek_caption_prompt"));prompt.setText(custom+" 草稿");
        language("ja");assertEquals(custom+" 草稿",prompt.getText().toString());assertEquals(custom,DeepSeekConfig.load(a).prompt);
        assertEquals("字幕大小 中文方案",ApiProfiles.list(a).get("default"));
        View recycled=pref("deepseek_caption_prompt").getView(rows.get("deepseek_caption_prompt"),page);assertSame(prompt,edit(recycled));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertEquals(custom+" 草稿",DeepSeekConfig.load(a).prompt);
        assertEquals("stored_custom",DeepSeekConfig.load(a).preferenceProvenance);
    }
    @Test public void explicitUserPasteEqualToJapaneseTemplateIsCustomAndRestoresOnlyOnExplicitClear()throws Exception {
        language("ja");render(420,1f);DeepSeekTextPreference p=(DeepSeekTextPreference)pref("deepseek_caption_prompt");EditText input=edit(rows.get("deepseek_caption_prompt"));String custom=input.getText().toString();
        input.setText(custom);assertTrue(p.flushProfile());assertEquals("stored_custom",DeepSeekConfig.load(a).preferenceProvenance);assertEquals(custom,ApiProfiles.values(a).getString("prompt",""));
        for(String tag:new String[]{"en","fr","ar"}){language(tag);assertEquals(custom,input.getText().toString());assertEquals(custom,DeepSeekConfig.load(a).prompt);}
        input.setText("");assertTrue(p.flushProfile());assertFalse(ApiProfiles.values(a).contains("prompt"));assertEquals("program_default",DeepSeekConfig.load(a).preferenceProvenance);language("en");assertEquals(expected("en","default_prompt"),input.getText().toString());
    }
    @Test public void allFourteenMultiChoiceDialogsShowOnlyLocalizedNamesAndRetainSelections()throws Exception {
        CaptionLanguagesPreference p=(CaptionLanguagesPreference)pref("deepseek_caption_languages");
        CaptionLanguageSelection.save(a,Arrays.asList("ja","fr"));Set<String> chosen=CaptionLanguageSelection.read(a);
        JSONArray matrix=new JSONArray();
        for(String tag:TAGS){language(tag);
            for(boolean enabled:new boolean[]{false,true}){DeepSeekConfig.saveEnabled(a,enabled);AlertDialog d=p.showLanguages();ListView list=d.getListView();assertEquals(14,list.getAdapter().getCount());
                for(int i=0;i<14;i++){String code=CaptionLanguageSelection.CODES.get(i);View row=list.getAdapter().getView(i,null,list);assertEquals(LanguageMenuOrder.label(code,CaptionUiLocale.snapshot(a).locale),((TextView)row.findViewById(android.R.id.text1)).getText().toString());assertEquals(chosen.contains(code),list.isItemChecked(i));assertTrue(row.isEnabled());}
                assertEquals(expected(tag,"languages_save"),d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                save("languages-"+tag+"-"+enabled+".json",snapshot(d.getWindow().getDecorView()));
                d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(chosen,CaptionLanguageSelection.read(a));
            }
            AlertDialog all=p.showLanguages();Shadows.shadowOf(Looper.getMainLooper()).idle();ListView list=all.getListView();
            for(int i=0;i<14;i++)if(!list.isItemChecked(i))list.performItemClick(list.getAdapter().getView(i,null,list),i,i);
            all.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(new LinkedHashSet<>(CaptionLanguageSelection.CODES),CaptionLanguageSelection.read(a));
            assertEquals(expected(tag,"languages_summary"),p.getSummary());
            all=p.showLanguages();Shadows.shadowOf(Looper.getMainLooper()).idle();for(int i=0;i<14;i++)assertTrue(all.getListView().isItemChecked(i));all.dismiss();CaptionLanguageSelection.save(a,chosen);
            matrix.put(new JSONObject().put("locale",tag).put("rows",14).put("all_fourteen_saved",true).put("video_state_calls",0));
        }save("n31-languages-dialogs.json",matrix);
    }
    @Test public void dialogsProfilesModelAndDiagnosticConfirmationUseCurrentOverride()throws Exception {
        String extra=ApiProfiles.create(a,"自定义中文方案","https://fixture.example");
        for(String tag:TAGS){language(tag);render(420,1f);
            ApiProfilesPreference profiles=(ApiProfilesPreference)pref("deepseek_caption_profiles");profiles.showProfiles();Dialog d=ShadowDialog.getLatestDialog();
            assertLabel(d.getWindow().getDecorView(),expected(tag,"profiles_title"));assertLabel(d.getWindow().getDecorView(),expected(tag,"profile_add"));
            View dialogView=d.getWindow().getDecorView();save("profiles-"+tag+".json",snapshot(dialogView));
            dialogView.findViewWithTag("profile_more:"+extra).performClick();assertLabel(dialogView,expected(tag,"profile_rename"));assertLabel(dialogView,expected(tag,"profile_delete"));
            label(dialogView,expected(tag,"profile_rename")).performClick();EditText rename=edit(dialogView);assertEquals("自定义中文方案",rename.getText().toString());assertEquals(expected(tag,"profile_name"),rename.getHint().toString());rename.setText("");label(dialogView,expected(tag,"profile_save")).performClick();assertEquals(expected(tag,"profile_name_error"),rename.getError().toString());save("profile-rename-"+tag+".json",snapshot(dialogView));
            View panel=dialogView.findViewWithTag("profile_panel:"+extra);label(panel,expected(tag,"cancel")).performClick();label(panel,expected(tag,"profile_delete")).performClick();assertLabel(panel,expected(tag,"profile_keep"));assertLabel(panel,expected(tag,"profile_confirm_delete"));save("profile-delete-"+tag+".json",snapshot(panel));d.dismiss();
            profiles.showProfiles();d=ShadowDialog.getLatestDialog();label(d.getWindow().getDecorView(),expected(tag,"profile_add")).performClick();d=ShadowDialog.getLatestDialog();assertLabel(d.getWindow().getDecorView(),expected(tag,"profile_new_summary"));save("profile-add-"+tag+".json",snapshot(d.getWindow().getDecorView()));d.dismiss();
            profiles.clearCurrentKey();d=ShadowDialog.getLatestDialog();assertLabel(d.getWindow().getDecorView(),expected(tag,"profile_clear_key_summary"));save("profile-key-clear-"+tag+".json",snapshot(d.getWindow().getDecorView()));d.dismiss();
            DeepSeekModelPreference model=(DeepSeekModelPreference)pref("deepseek_caption_model");Method show=DeepSeekModelPreference.class.getDeclaredMethod("showModels",List.class,boolean.class);show.setAccessible(true);show.invoke(model,Arrays.asList("模型ID 原样","fixture-model"),true);
            Field choices=DeepSeekModelPreference.class.getDeclaredField("choices");choices.setAccessible(true);((View)choices.get(model)).performClick();
            Field menu=DeepSeekModelPreference.class.getDeclaredField("modelMenu");menu.setAccessible(true);PopupWindow popup=(PopupWindow)menu.get(model);assertNotNull("actual model popup",popup);assertLabel(popup.getContentView(),"    模型ID 原样");save("models-"+tag+".json",snapshot(popup.getContentView()));popup.dismiss();
            rows.get("deepseek_caption_diagnostics").findViewWithTag("ai_diagnostics_clear").performClick();d=ShadowDialog.getLatestDialog();assertLabel(d.getWindow().getDecorView(),expected(tag,"clear_diagnostics_confirm"));save("diagnostic-confirm-"+tag+".json",snapshot(d.getWindow().getDecorView()));d.dismiss();
            try{assertEquals("official faulty system cancel lookup must never run",0,Class.forName("app.morphe.extension.shared.ui.CustomDialog").getField("fallbackCancelCalls").getInt(null));}catch(ClassNotFoundException absent){/* separately tested platform dialog fallback */}
        }
    }
    @Test public void defaultHostAliasesAndMissingOfficialClassesHaveSeparateFallbackMatrix()throws Exception {
        JSONArray matrix=new JSONArray();BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.DEFAULT;
        assertEquals("morphe_default_host_configuration",CaptionUiLocale.snapshot(a).source);assertEquals(Locale.CHINA,CaptionUiLocale.snapshot(a).locale);
        ClassLoader absent=new ClassLoader(a.getClassLoader()){@Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{if(name.startsWith("app.morphe.extension.shared.settings."))throw new ClassNotFoundException(name);return super.loadClass(name,resolve);}};
        for(String tag:TAGS){Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.setLocale(Locale.forLanguageTag(tag));Context base=a.createConfigurationContext(cfg);Context fallback=new ContextWrapper(base){@Override public ClassLoader getClassLoader(){return absent;}};
            assertEquals("context_fallback",CaptionUiLocale.snapshot(fallback).source);assertEquals(expected(tag,"preview_hint"),CaptionStrings.settings(fallback,"preview_hint"));
            JSONObject values=authored.getJSONObject(folder(tag));Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();assertEquals("fallback/"+tag+":"+key,values.getString(key),CaptionStrings.settings(fallback,key));}
            Method inflate=PreferenceManager.class.getDeclaredMethod("inflateFromResource",Context.class,int.class,PreferenceScreen.class);inflate.setAccessible(true);
            int xml=a.getResources().getIdentifier("n31_morphe_prefs","xml",a.getPackageName());
            PreferenceScreen original=tree;tree=(PreferenceScreen)inflate.invoke(a.getPreferenceManager(),fallback,xml,null);
            render(320,1.3f);checkActual(tag,false);measure(page,320);
            CaptionLanguagesPreference p=(CaptionLanguagesPreference)pref("deepseek_caption_languages");assertEquals(expected(tag,"languages_summary"),p.getSummary());
            AlertDialog dialog=p.showLanguages();assertEquals(14,dialog.getListView().getAdapter().getCount());dialog.dismiss();
            save("fallback-tree-"+tag+".json",snapshot(page));matrix.put(new JSONObject().put("locale",tag).put("source","context_fallback").put("owned_preferences",rows.size()));tree=original;
        }
        BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.OVERRIDE;
        for(String tag:new String[]{"zh-Hans","zh-Hant","en-US","pt-BR","id","in"}){language(tag);assertEquals(expected(tag,"preview_hint"),CaptionStrings.settings(a,"preview_hint"));}
        save("n31-fallback-matrix.json",matrix);
    }
    @Test public void staleContextAndMissingRebindAndStateLeakAreRejectedByExactChecks()throws Exception {
        language("ja");render(420,1f);String ja=expected("ja","preview_hint");
        assertNotEquals(ja,ResourceUtils.getString("cap_preview_hint"));
        try{assertEquals("legacy resolver must satisfy the same authored-value predicate",ja,ResourceUtils.getString("cap_preview_hint"));fail("legacy resolver incorrectly accepted");}catch(AssertionError rejected){assertTrue(rejected.getMessage().contains("legacy resolver"));}
        TextView hint=label(rows.get("deepseek_caption_style_preview"),ja);hint.setText(ResourceUtils.getString("cap_preview_hint"));
        try{assertLabel(rows.get("deepseek_caption_style_preview"),ja);fail("missing rebind was accepted");}catch(AssertionError rejected){assertTrue(rejected.getMessage().contains("actual View missing"));}
        CaptionPreferenceBindings.rebind(tree);assertLabel(rows.get("deepseek_caption_style_preview"),ja);
        String pure=LanguageMenuOrder.label("ja",Locale.JAPANESE);String leaked=pure+" · "+CaptionStrings.settings(a,"languages_unavailable");
        assertNotEquals("state leak must fail the same exact row predicate",pure,leaked);
        assertEquals("自定义字幕大小 原样",CaptionStrings.localize(a,"自定义字幕大小 原样"));
    }
    @Test public void dynamicStatesErrorsFiveTiersToastsAndAndroidNineCopyShellFollowAllFourteenOverrides()throws Exception {
        JSONArray states=new JSONArray();
        for(String tag:TAGS){language(tag);render(420,1f);
            DeepSeekModelPreference model=(DeepSeekModelPreference)pref("deepseek_caption_model");
            Field field=DeepSeekModelPreference.class.getDeclaredField("state");field.setAccessible(true);TextView state=(TextView)field.get(model);
            Method set=DeepSeekModelPreference.class.getDeclaredMethod("setState",String.class,boolean.class);set.setAccessible(true);
            for(String key:new String[]{"model_hint_manual_only","model_hint_key_first","model_hint_retry","model_loading_auto","model_loading_refresh","model_list_updated","model_pick_or_type","model_saved"}){set.invoke(model,key,false);assertEquals(expected(tag,key),state.getText().toString());}
            Method failure=DeepSeekModelPreference.class.getDeclaredMethod("setFailure",String.class,String.class,boolean.class);failure.setAccessible(true);failure.invoke(model,"model_load_failed","原始 provider 错误",true);
            assertEquals(String.format(Locale.ROOT,expected(tag,"model_load_failed"),"原始 provider 错误"),state.getText().toString());
            Method commit=DeepSeekModelPreference.class.getDeclaredMethod("commitNow",String.class,boolean.class);commit.setAccessible(true);commit.invoke(model,"",true);
            assertEquals(expected(tag,"model_empty"),edit(rows.get("deepseek_caption_model")).getError().toString());
            DeepSeekTextPreference url=(DeepSeekTextPreference)pref("deepseek_caption_base_url");EditText urlEditor=edit(rows.get("deepseek_caption_base_url"));String stored=DeepSeekConfig.load(a).baseUrl;urlEditor.setText("invalid-address");assertFalse(url.flushProfile());assertEquals(expected(tag,"message_bafa7b1ca6cb"),urlEditor.getError().toString());assertEquals(stored,DeepSeekConfig.load(a).baseUrl);urlEditor.setText(stored);url.flushProfile();
            for(String invalid:new String[]{"https://","https://user@example.org/v1","https://example.org/v1#fragment","https://example.org/messages"}){urlEditor.setText(invalid);assertFalse(url.flushProfile());assertEquals(expected(tag,"api_address_invalid"),urlEditor.getError().toString());assertEquals(stored,DeepSeekConfig.load(a).baseUrl);}urlEditor.setText(stored);assertTrue(url.flushProfile());assertNull(urlEditor.getError());
            SeekBar bar=rows.get("deepseek_caption_text_size").findViewWithTag("ai_size_tier_slider");
            String[] tiers={"size_tier_xs","size_tier_s","size_tier_standard","size_tier_l","size_tier_xl"};
            String[] detail={"34","39","44.5","50","56"},full={"42.4","48.6","55.5","62.4","69.8"};
            for(int i=0;i<5;i++){bar.setProgress(i);assertEquals(expected(tag,"size")+": "+expected(tag,tiers[i]),bar.getContentDescription().toString());assertLabel(rows.get("deepseek_caption_text_size"),String.format(Locale.ROOT,expected(tag,"size_tier_hint"),expected(tag,tiers[i]),detail[i],full[i]));}
            DeepSeekActionPreference action=(DeepSeekActionPreference)pref("deepseek_caption_test_api");Field summary=DeepSeekActionPreference.class.getDeclaredField("summaryKey");summary.setAccessible(true);
            for(String key:new String[]{"message_49562bf14c82","api_test_retry_hint"}){summary.set(action,key);action.rebindUi();assertEquals(expected(tag,key),action.getSummary().toString());View row=action.getView(null,new FrameLayout(a));assertLabel(row,expected(tag,key));}
            String report="原始 source / provider error / custom prompt\n".repeat(2000);Method copy=DeepSeekDiagnosticsPreference.class.getDeclaredMethod("copyPages",Context.class,String.class);copy.setAccessible(true);copy.invoke(null,a,report);AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            int count=(report.length()+59999)/60000;assertEquals(count,dialog.getListView().getAdapter().getCount());assertLabel(dialog.getWindow().getDecorView(),expected(tag,"copy_parts_title"));
            dialog.getListView().performItemClick(null,0,0);String clip=((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString();assertEquals(report.substring(0,Math.min(60000,report.length())),clip);
            assertEquals(String.format(Locale.ROOT,expected(tag,"copy_part_done"),1,count),org.robolectric.shadows.ShadowToast.getTextOfLatestToast());save("dynamic-states-"+tag+".json",snapshot(page));dialog.dismiss();
            states.put(new JSONObject().put("locale",tag).put("model_states",8).put("model_error","model_empty").put("provider_raw_preserved",true).put("tiers",5).put("url_error","api_url_scheme").put("android9_copy_parts",count));
        }save("n31-dynamic-states.json",states);
    }
}
