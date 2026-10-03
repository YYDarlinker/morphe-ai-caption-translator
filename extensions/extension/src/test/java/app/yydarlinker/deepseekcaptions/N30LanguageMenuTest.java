package app.yydarlinker.deepseekcaptions;
import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.*;
import java.io.ByteArrayOutputStream;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,shadows={N30LanguageMenuTest.Flags.class,N30LanguageMenuTest.Host.class})
public class N30LanguageMenuTest {
 static boolean ai,memory;static int clones;
 static class Track {final String code,name,url,vss;Track(String c,String n){code=c;name=n;url="https://www.youtube.com/api/timedtext?v=menu-test&lang=en&tlang="+c+"&signature=KEEP";vss="t"+c+".source";}}
 @Implements(CaptionAddonSupport.class) public static class Flags {
  @Implementation public static boolean aiInstalled(){return ai;}
  @Implementation public static boolean memoryInstalled(){return memory;}
 }
 @Implements(NativeCaptionBridge.class) public static class Host {
  @Implementation public static String language(Object t){return ((Track)t).code;}
  @Implementation public static CharSequence displayName(Object t){return ((Track)t).name;}
  @Implementation public static String url(Object t){return ((Track)t).url;}
  @Implementation public static Object cloneTranslation(Object t,String c){clones++;return new Track(c,NativeCaptionBridge.translationLabel(c));}
 }
 Activity a;
 @Before public void before(){a=Robolectric.buildActivity(Activity.class).setup().get();CaptionAddonSupport.initialize(a);ai=true;memory=false;clones=0;
  a.getSharedPreferences(CaptionLanguageSelection.STORE,0).edit().clear().commit();DeepSeekConfig.saveEnabled(a,false);RememberedCaptionSelection.reset();}
 @After public void after(){a.finish();}
 @Test public void emptyByDefaultPersistsCanonicalCodesAndRejectsWrongStorage(){
  assertTrue(CaptionLanguageSelection.read(a).isEmpty());assertEquals(14,CaptionLanguageSelection.CODES.size());
  CaptionLanguageSelection.save(a,Arrays.asList("zh_CN","zh-Hans","fr-CA","fr","in-ID","en-US"));
  assertEquals(Arrays.asList("en","fr","id","zh-Hans"),new ArrayList<>(CaptionLanguageSelection.read(a)));
  assertFalse(DeepSeekConfig.enabled(a));assertEquals(-1,RememberedCaptionSelection.decision());
  try{CaptionLanguageSelection.save(a,Arrays.asList("French"));fail();}catch(IllegalArgumentException expected){assertEquals("unsupported_language_code",expected.getMessage());}
  a.getSharedPreferences(CaptionLanguageSelection.STORE,0).edit().putStringSet(CaptionLanguageSelection.KEY,new HashSet<>(Arrays.asList("bad-code"))).commit();
  assertTrue(CaptionLanguageSelection.read(a).isEmpty());assertTrue(CaptionDiagnostics.fullText(a).contains("unsupported_stored_code"));
  a.getSharedPreferences(CaptionLanguageSelection.STORE,0).edit().putString(CaptionLanguageSelection.KEY,"fr").commit();assertTrue(CaptionLanguageSelection.read(a).isEmpty());
 }
 @Test public void repeatsLocalesVideosAndAiSwitchesDoNotDuplicateOrModifyNativeObjects()throws Exception{
  CaptionLanguageSelection.save(a,Arrays.asList("fr","de","ar","zh-Hans","zh-Hant"));Set<String> saved=CaptionLanguageSelection.read(a);
  org.json.JSONArray evidence=new org.json.JSONArray();
  for(String tag:new String[]{"en","zh-CN","fr","ar"}){
   Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.setLocales(new LocaleList(Locale.forLanguageTag(tag)));a.getResources().updateConfiguration(cfg,a.getResources().getDisplayMetrics());
   List<Track> nativeTracks=new ArrayList<>();for(String code:new String[]{"en","zh-CN","ja","pt","zh-TW"})nativeTracks.add(new Track(code,LanguageMenuOrder.label(code)));
   java.text.Collator collator=java.text.Collator.getInstance(LanguageMenuOrder.locale());nativeTracks.sort((x,y)->collator.compare(LanguageMenuOrder.sortLabel(x.name),LanguageMenuOrder.sortLabel(y.name)));
   for(boolean enabled:new boolean[]{false,true,false}){DeepSeekConfig.saveEnabled(a,enabled);List<?> out=NativeCaptionBridge.augmentTranslations(nativeTracks);
    assertEquals(nativeTracks.size()+3,out.size());assertEquals(nativeTracks,new ArrayList<>(out).stream().filter(nativeTracks::contains).collect(java.util.stream.Collectors.toList()));
    assertSame(out,NativeCaptionBridge.augmentTranslations(out));Set<String> unique=new HashSet<>();for(Object track:out)assertTrue(unique.add(CaptionLanguageSelection.canonical(((Track)track).code)));
    for(int i=1;i<out.size();i++)assertTrue(collator.compare(LanguageMenuOrder.sortLabel(((Track)out.get(i-1)).name),LanguageMenuOrder.sortLabel(((Track)out.get(i)).name))<=0);
    assertEquals(saved,CaptionLanguageSelection.read(a));assertEquals("",RebuildController.activeUrl());
    org.json.JSONArray menu=new org.json.JSONArray();for(int p=0;p<out.size();p++){Track t=(Track)out.get(p);menu.put(new org.json.JSONObject().put("code",t.code).put("canonical_code",CaptionLanguageSelection.canonical(t.code)).put("display_name",t.name).put("position",p).put("native_object",nativeTracks.contains(t)));}
    evidence.put(new org.json.JSONObject().put("locale",tag).put("ai_enabled",enabled).put("menu",menu));
   }
  }
  N28CGeometryTest.export("n30-menu-runtime.json",new org.json.JSONObject().put("rows",evidence).put("host_model","verified_track_seam_with_test_host_models"));
 }
 static byte[] entry(String code,String label){ByteArrayOutputStream e=new ByteArrayOutputStream(),n=new ByteArrayOutputStream();CaptionLanguageMetadata.write(e,1,code.getBytes(java.nio.charset.StandardCharsets.UTF_8));CaptionLanguageMetadata.write(n,4,label.getBytes(java.nio.charset.StandardCharsets.UTF_8));CaptionLanguageMetadata.write(e,2,n.toByteArray());return e.toByteArray();}
 @Test public void metadataIsIdempotentPreservesNativeBytesAndUsesActualNativeLabel(){
  CaptionLanguageSelection.save(a,Arrays.asList("de","en","zh-Hans"));ByteArrayOutputStream root=new ByteArrayOutputStream();byte[] en=entry("en","Native English label");CaptionLanguageMetadata.write(root,3,en);byte[] before=root.toByteArray(),after=CaptionLanguageMetadata.addSimplified(before);
  assertArrayEquals(after,CaptionLanguageMetadata.addSimplified(after));assertEquals("Native English label",NativeCaptionBridge.translationLabel("en"));
  assertEquals("languages_existing",NativeCaptionBridge.languageStatus("en"));assertEquals("languages_new",NativeCaptionBridge.languageStatus("fr"));
  List<CaptionLanguageMetadata.Field> fields=CaptionLanguageMetadata.fields(after);assertEquals(3,fields.size());assertTrue(fields.stream().anyMatch(f->Arrays.equals(f.value,en)));
 }
 @Test public void nativeOnlyAndRememberOnlyNeverInjectLanguagesOrModifyStoredCodes(){
  CaptionLanguageSelection.save(a,Arrays.asList("ja","fr","zh-Hans"));Set<String> saved=CaptionLanguageSelection.read(a);ai=false;
  Track t=new Track("en","English");List<Track> before=Arrays.asList(t);byte[] metadata=entry("en","English");
  for(boolean remembered:new boolean[]{false,true}){memory=remembered;
   assertSame(before,NativeCaptionBridge.augmentTranslations(before));assertSame(metadata,CaptionLanguageMetadata.addSimplified(metadata));
   assertTrue(CaptionLanguageSelection.menuCodes().isEmpty());assertEquals(0,clones);assertEquals(saved,CaptionLanguageSelection.read(a));
   assertFalse(DeepSeekConfig.enabled(a));assertFalse(NativeCaptionBridge.enabled());assertEquals("",RebuildController.activeUrl());
  }
 }
 @Test public void installedAiOffAddsExactlySelectedTargetsIncludingSimplifiedWithNoApi()throws Exception{
  try(okhttp3.mockwebserver.MockWebServer server=new okhttp3.mockwebserver.MockWebServer()){
   server.start();DeepSeekConfig.saveBaseUrl(a,server.url("/v1").toString());
   CaptionLanguageSelection.save(a,Arrays.asList("fr","zh-Hans","zh_CN"));Set<String> saved=CaptionLanguageSelection.read(a);
   List<Track> input=Arrays.asList(new Track("en","English"));List<?> output=NativeCaptionBridge.augmentTranslations(input);
   assertEquals(3,output.size());List<String> added=new ArrayList<>();for(Object value:output)if(!input.contains(value))added.add(((Track)value).code);
   Collections.sort(added);assertEquals(Arrays.asList("fr","zh-Hans"),added);assertEquals(2,clones);
   assertSame(output,NativeCaptionBridge.augmentTranslations(output));assertEquals(2,clones);
   ByteArrayOutputStream root=new ByteArrayOutputStream();CaptionLanguageMetadata.write(root,3,entry("en","English"));
   byte[] metadata=CaptionLanguageMetadata.addSimplified(root.toByteArray());List<String> metadataCodes=new ArrayList<>();
   for(CaptionLanguageMetadata.Field field:CaptionLanguageMetadata.fields(metadata))if(field.number==3)
    for(CaptionLanguageMetadata.Field item:CaptionLanguageMetadata.fields(field.value))if(item.number==1)metadataCodes.add(new String(item.value,java.nio.charset.StandardCharsets.UTF_8));
   Collections.sort(metadataCodes);assertEquals(Arrays.asList("en","fr","zh-Hans"),metadataCodes);assertSame(metadata,CaptionLanguageMetadata.addSimplified(metadata));
   assertEquals(saved,CaptionLanguageSelection.read(a));assertEquals(-1,RememberedCaptionSelection.decision());assertFalse(DeepSeekConfig.enabled(a));
   assertEquals(0,server.getRequestCount());assertEquals("",RebuildController.activeUrl());
   org.json.JSONObject evidence=new org.json.JSONObject().put("ai_installed",ai).put("ai_enabled",DeepSeekConfig.enabled(a))
    .put("api_requests",server.getRequestCount()).put("memory_decision",RememberedCaptionSelection.decision())
    .put("stored_before",new org.json.JSONArray(saved)).put("stored_after",new org.json.JSONArray(CaptionLanguageSelection.read(a)))
    .put("added_codes",new org.json.JSONArray(added)).put("metadata_codes",new org.json.JSONArray(metadataCodes));
   CaptionLanguageSelection.save(a,Collections.emptySet());assertSame(input,NativeCaptionBridge.augmentTranslations(input));
   assertEquals(0,server.getRequestCount());
   N28CGeometryTest.export("n32-two-root-runtime.json",evidence.put("empty_selection_returns_native_input",NativeCaptionBridge.augmentTranslations(input)==input)
    .put("host_model","production_menu_and_metadata_with_test_host_clone_adapter"));
  }
 }
 @Test public void nativeDialogCancelDoesNotSaveAndSaveCanRemainEmptyWhileAiOff(){
  CaptionLanguagesPreference pref=new CaptionLanguagesPreference(a);AlertDialog dialog=pref.showLanguages();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();dialog.getListView().performItemClick(dialog.getListView().getChildAt(0),0,0);
  dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(CaptionLanguageSelection.read(a).isEmpty());
  dialog=pref.showLanguages();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();dialog.getListView().performItemClick(dialog.getListView().getChildAt(0),0,0);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,CaptionLanguageSelection.read(a).size());assertFalse(DeepSeekConfig.enabled(a));
 }
}
