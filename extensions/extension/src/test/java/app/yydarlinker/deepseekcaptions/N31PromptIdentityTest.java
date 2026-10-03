package app.yydarlinker.deepseekcaptions;
import static org.junit.Assert.*;
import android.app.Activity;
import app.morphe.extension.shared.settings.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="zh-rCN",shadows={RebuildIntegrationTest.Keys.class})
public class N31PromptIdentityTest {
    @After public void reset(){BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.DEFAULT;}
    @Test public void actualPromptProviderJsonHashAndCacheScopeStayStableAcrossUiLanguages()throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.OVERRIDE;
        DeepSeekConfig.saveBaseUrl(a,"https://fixture.example/v1");DeepSeekConfig.saveModel(a,"自定义模型ID");
        ApiProfiles.rename(a,"default","中文用户方案");CaptionLanguageSelection.save(a,Arrays.asList("ja","fr"));
        RebuildSource source=new RebuildSource(Arrays.asList(new RebuildSource.Word("A",0,200,0,RebuildSource.Precision.NATIVE),new RebuildSource.Word("small",200,400,0,RebuildSource.Precision.NATIVE),new RebuildSource.Word("world.",400,900,0,RebuildSource.Precision.NATIVE)));
        JSONArray result=new JSONArray(),beforeDrift=new JSONArray();
        for(String custom:new String[]{"","自定义字幕大小；已自动保存；API原样"}){
            DeepSeekConfig.savePrompt(a,custom);Map<String,?> values=new HashMap<>(ApiProfiles.values(a).getAll());long revision=ApiProfiles.revision();
            for(String target:new String[]{"zh-Hans","fr","ar"}){
                CaptionLanguageContext context=CaptionLanguageContext.explicit("en",target);RebuildPlanner.Block block=RebuildPlanner.plan(source,context).get(0);
                String first=null,firstKey=null,firstPrompt=null;
                for(String ui:new String[]{"zh-CN","ja","en","fr","ar"}){
                    AppLanguage.selected=Locale.forLanguageTag(ui);DeepSeekConfig.Snapshot cfg=DeepSeekConfig.load(a);
                    String prompt=RebuildApi.prompt(cfg,target,context);
                    String request=ProviderRequestPolicy.request(cfg,prompt,RebuildProtocol.payload(source,block,target,null,context),1000).toString();
                    String key=RebuildCache.identity(source,cfg,target,context);
                    if(first==null){first=request;firstKey=key;firstPrompt=prompt;}
                    assertEquals(first,request);assertEquals(firstKey,key);assertEquals(firstPrompt,prompt);
                    assertEquals(values,ApiProfiles.values(a).getAll());assertEquals(revision,ApiProfiles.revision());
                    assertEquals("local-fixture-key",cfg.apiKey);assertEquals("https://fixture.example/v1",cfg.baseUrl);assertEquals("自定义模型ID",cfg.model);
                    assertEquals(new LinkedHashSet<>(Arrays.asList("fr","ja")),CaptionLanguageSelection.read(a));assertEquals("中文用户方案",ApiProfiles.list(a).get("default"));
                    if(!custom.isEmpty())assertEquals(custom,cfg.prompt);
                    result.put(new JSONObject().put("ui",ui).put("target",target).put("provenance",cfg.preferenceProvenance).put("prompt_sha",RebuildCache.hash(prompt)).put("request_sha",RebuildCache.hash(request)).put("cache_scope",key));
                    if(custom.isEmpty()&&target.equals("zh-Hans")){
                        // Captured N30 load semantics used the display template as Snapshot.prompt.
                        DeepSeekConfig.Snapshot legacy=new DeepSeekConfig.Snapshot(cfg.enabled,cfg.baseUrl,cfg.model,DeepSeekConfig.defaultPrompt(a),cfg.captionSizeTier,cfg.backgroundOpacity,cfg.apiKey,"program_default");
                        beforeDrift.put(new JSONObject().put("ui",ui).put("n30_prompt",legacy.prompt).put("n30_request_prompt_sha",RebuildCache.hash(RebuildApi.prompt(legacy,target,context))).put("provenance","source-captured N30 load semantics; controlled display-template reconstruction"));
                    }
                }
            }
        }
        assertNotEquals(beforeDrift.getJSONObject(0).getString("n30_request_prompt_sha"),beforeDrift.getJSONObject(1).getString("n30_request_prompt_sha"));
        N28CGeometryTest.export("n31-prompt-request-cache-matrix.json",new JSONObject().put("rows",result).put("before_n30_drift",beforeDrift));a.finish();
    }
}
