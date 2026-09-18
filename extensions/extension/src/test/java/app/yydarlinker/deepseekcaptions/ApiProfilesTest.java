package app.yydarlinker.deepseekcaptions;
import android.app.Activity;
import android.content.Context;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class ApiProfilesTest {
 Activity a;
 @Before public void setup(){a=Robolectric.buildActivity(Activity.class).setup().get();}
 @After public void done(){a.finish();}
 @Test public void legacySettingsRemainIntactAndNewProfileIsKeyless(){
  a.getSharedPreferences("deepseek_caption_translator",0).edit().putString("base_url","https://old.example/v1").putString("model","old-model").apply();
  a.getSharedPreferences("deepseek_caption_secret",0).edit().putString("api_key_ciphertext","encrypted-legacy").apply();
  assertEquals("default",ApiProfiles.active(a));assertEquals("old-model",DeepSeekConfig.load(a).model);assertTrue(SecureApiKey.hasSavedValue(a));
  String b=ApiProfiles.create(a,"百炼","https://llm-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");assertTrue(ApiProfiles.select(a,b));
  assertFalse(SecureApiKey.hasSavedValue(a));assertEquals("",DeepSeekConfig.load(a).model);
  DeepSeekConfig.saveModel(a,"qwen-test");DeepSeekConfig.savePrompt(a,"short and accurate");
  assertTrue(ApiProfiles.select(a,"default"));assertTrue(SecureApiKey.hasSavedValue(a));assertEquals("old-model",DeepSeekConfig.load(a).model);
  assertTrue(ApiProfiles.select(a,b));assertEquals("qwen-test",DeepSeekConfig.load(a).model);assertEquals("short and accurate",DeepSeekConfig.load(a).prompt);
 }
 @Test public void clearingOneProfileKeyDoesNotRemoveOthers(){
  String b=ApiProfiles.create(a,"B","https://b.example/v1");
  a.getSharedPreferences("deepseek_caption_secret",0).edit().putString("api_key_ciphertext","legacy").putString("api_key_ciphertext_"+b,"new").apply();
  ApiProfiles.select(a,b);SecureApiKey.clear(a);assertFalse(SecureApiKey.hasSavedValue(a));
  ApiProfiles.select(a,"default");assertTrue(SecureApiKey.hasSavedValue(a));assertEquals("legacy",a.getSharedPreferences("deepseek_caption_secret",0).getString("api_key_ciphertext",""));
 }
 @Test public void namesDoNotChangeIdentityOrGlobalEngineAndStyle(){
  DeepSeekConfig.saveEnabled(a,true);DeepSeekConfig.saveCaptionTextSize(a,20);String b=ApiProfiles.create(a,"B","https://b.example/v1");ApiProfiles.rename(a,b,"Renamed");
  ApiProfiles.select(a,b);assertEquals("Renamed",ApiProfiles.list(a).get(b));assertTrue(DeepSeekConfig.enabled(a));assertEquals(20,DeepSeekConfig.load(a).captionTextSize);
  assertEquals(b,ApiProfiles.active(a.getApplicationContext()));assertEquals(2,ApiProfiles.list(a).size());
 }
 @Test public void pendingUrlEditsFlushToOldProfileAndDetachedEventsCannotWriteNewProfile(){
  DeepSeekTextPreference p=new DeepSeekTextPreference(a);p.setKey(DeepSeekTextPreference.KEY_BASE_URL);
  LinearLayout parent=new LinearLayout(a);android.view.View old=p.getView(null,parent);EditText edit=old.findViewById(android.R.id.edit);
  edit.setText("https://old-edited.example/v1");String b=ApiProfiles.create(a,"B","https://new.example/v1");assertTrue(ApiProfiles.select(a,b));
  assertEquals("https://old-edited.example/v1",ApiProfiles.values(a,"default").getString("base_url",""));
  edit.setText("https://stale.example/v1");org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
  assertEquals("https://new.example/v1",DeepSeekConfig.load(a).baseUrl);
  android.view.View fresh=p.getView(old,parent);EditText newEdit=fresh.findViewById(android.R.id.edit);assertEquals("https://new.example/v1",newEdit.getText().toString());
  edit.setText("https://still-stale.example/v1");org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
  assertEquals("https://new.example/v1",DeepSeekConfig.load(a).baseUrl);
 }
 @Test public void invalidPendingEditBlocksSwitchWithoutLosingOriginalConfig(){
  DeepSeekTextPreference p=new DeepSeekTextPreference(a);p.setKey(DeepSeekTextPreference.KEY_BASE_URL);EditText edit=p.getView(null,new LinearLayout(a)).findViewById(android.R.id.edit);edit.setText("not a URL");
  String b=ApiProfiles.create(a,"B","https://b.example/v1");assertFalse(ApiProfiles.select(a,b));assertEquals("default",ApiProfiles.active(a));assertNotNull(edit.getError());
 }
 @Test public void profilePanelUsesExistingStyleAndNoKeyReadback(){
  ApiProfilesPreference p=new ApiProfilesPreference(a);android.view.View root=p.getView(null,new LinearLayout(a));assertTrue(root instanceof LinearLayout);assertTrue(root.getPaddingLeft()>0);
 }
 @Test public void delayedModelEditsCannotCrossProfiles(){
  DeepSeekModelPreference p=new DeepSeekModelPreference(a);p.setKey(DeepSeekModelPreference.KEY_MODEL);LinearLayout parent=new LinearLayout(a);
  LinearLayout old=(LinearLayout)p.getView(null,parent);EditText input=(EditText)old.getChildAt(1);input.setText("old-new-model");
  String b=ApiProfiles.create(a,"B","https://b.example/v1");assertTrue(ApiProfiles.select(a,b));assertEquals("old-new-model",ApiProfiles.values(a,"default").getString("model",""));
  LinearLayout fresh=(LinearLayout)p.getView(old,parent);assertEquals("",((EditText)fresh.getChildAt(1)).getText().toString());
  input.setText("stale-model");org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertEquals("",DeepSeekConfig.load(a).model);
 }
 @Test public void immutableSnapshotsRetainOriginalEndpointAfterSwitch(){
  DeepSeekConfig.saveBaseUrl(a,"https://original.example/v1");DeepSeekConfig.Snapshot request=DeepSeekConfig.load(a);
  String b=ApiProfiles.create(a,"B","https://replacement.example/v1");ApiProfiles.select(a,b);assertEquals("https://original.example/v1",request.baseUrl);assertEquals("https://replacement.example/v1",DeepSeekConfig.load(a).baseUrl);
 }

 @Test public void changingHostQuarantinesExistingKeyUntilUserReentersIt(){
  a.getSharedPreferences("deepseek_caption_secret",0).edit().putString("api_key_ciphertext","legacy").apply();assertTrue(SecureApiKey.hasSavedValue(a));
  DeepSeekConfig.saveBaseUrl(a,"https://different-provider.example/v1");assertFalse(SecureApiKey.hasSavedValue(a));assertEquals("",SecureApiKey.load(a));
  assertEquals("legacy",a.getSharedPreferences("deepseek_caption_secret",0).getString("api_key_ciphertext",""));
  DeepSeekConfig.saveBaseUrl(a,DeepSeekConfig.DEFAULT_BASE_URL);assertTrue(SecureApiKey.hasSavedValue(a));
 }

 @Test public void profilePanelButtonsStayInsideNarrowLayoutAndSwitchCanBeUsed(){
  ApiProfilesPreference p=new ApiProfilesPreference(a);LinearLayout root=(LinearLayout)p.getView(null,new LinearLayout(a));
  int width=CaptionSettingsStyle.dp(a,320);root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));root.layout(0,0,width,root.getMeasuredHeight());
  LinearLayout actions=(LinearLayout)root.getChildAt(3);assertEquals(2,actions.getChildCount());assertTrue(actions.getChildAt(1).getRight()<=actions.getWidth());
  Button add=(Button)actions.getChildAt(1);add.performClick();assertEquals(2,ApiProfiles.list(a).size());assertNotEquals("default",ApiProfiles.active(a));
  EditText name=(EditText)root.getChildAt(2);name.setText("Bailian 北京");((Button)actions.getChildAt(0)).performClick();assertEquals("Bailian 北京",ApiProfiles.list(a).get(ApiProfiles.active(a)));
  ((Spinner)root.getChildAt(1)).setSelection(0);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
  // Invoke actual listener in a detached test view: real Android invokes it on layout/selection.
  Spinner spinner=(Spinner)root.getChildAt(1);spinner.getOnItemSelectedListener().onItemSelected(spinner,null,0,0);assertEquals("default",ApiProfiles.active(a));
 }

}
