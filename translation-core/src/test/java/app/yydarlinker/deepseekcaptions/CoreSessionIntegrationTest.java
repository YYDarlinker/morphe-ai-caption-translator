package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Real engine and HTTP stack, fake platform inputs, no application or UI on this classpath. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CoreSessionIntegrationTest {
    final Context context=RuntimeEnvironment.getApplication();
    MockWebServer server;Recorder recorder;AtomicInteger calls=new AtomicInteger();
    volatile int failureCode;volatile boolean block;
    CountDownLatch received,release;
    final String url="https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&tlang=zh-Hans";
    @Before public void setup() throws Exception {
        RebuildController.stop();RebuildApi.reset();RebuildCache.clear(context);
        server=new MockWebServer();received=new CountDownLatch(1);release=new CountDownLatch(1);
        server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){
            int number=calls.incrementAndGet();received.countDown();
            try {
                if(block)release.await(4,TimeUnit.SECONDS);
                if(failureCode!=0&&(failureCode!=429||number==1))return new MockResponse().setResponseCode(failureCode).setHeader("Retry-After","0").setBody("{\"error\":{\"message\":\"fixture failure\"}}");
                JSONObject input=new JSONObject(new JSONObject(request.getBody().readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content"));
                JSONArray tokens=input.getJSONArray("owned_tokens");
                String text="ja".equals(input.optString("language"))?"こんにちは、世界。":"你好，世界。";
                JSONObject content=new JSONObject().put("block",input.getString("block")).put("events",new JSONArray().put(new JSONObject()
                    .put("from",tokens.getJSONArray(0).getInt(0)).put("to",tokens.getJSONArray(tokens.length()-1).getInt(0))
                    .put("source",input.getString("source_text")).put("text",text)));
                return new MockResponse().setBody(new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("content",content.toString())))).toString());
            }catch(Exception e){return new MockResponse().setResponseCode(500).setBody("fixture error");}
        }});server.start();
        TranslationRuntime.install(new TranslationRuntime.Host(){
            TranslationSettings settings(Context c){return new TranslationSettings(true,server.url("/v1").toString(),"fixture-model","faithful","fixture-key","stored_custom");}
            TranslationSourceAccess.Source load(Context c,String u,boolean translate,DeepSeekApiClient.RequestControl control) throws Exception {
                TranslationSourceAccess.checkActive(control);
                byte[] body="{\"events\":[{\"tStartMs\":0,\"dDurationMs\":5000,\"segs\":[{\"utf8\":\"Hello \",\"tOffsetMs\":0},{\"utf8\":\"world.\",\"tOffsetMs\":1000}]}]}".getBytes(StandardCharsets.UTF_8);
                return new TranslationSourceAccess.Source(body,CaptionDocument.parse(body,"application/json"),"",0,null);
            }
            String target(){return "zh-Hans";}
        });
        recorder=new Recorder();TranslationOutput.install(recorder);TranslationAudit.install(new TranslationAudit.Sink(){});
        RebuildController.activity(context);RebuildController.video("abcdefghijk");RebuildController.time(0);
    }
    @After public void teardown() throws Exception {release.countDown();RebuildController.stop();server.shutdown();TranslationOutput.install(new TranslationOutput.Sink(){});}
    void await(BooleanSupplier done) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!done.getAsBoolean()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));Thread.sleep(10);}
        assertTrue("engine did not reach the expected observable state",done.getAsBoolean());
    }
    @Test public void sourceThroughPinnedProtocolProducesProgressAndAcceptedResult() throws Exception {
        RebuildController.activate(context,url,false,true);await(()->!recorder.results.isEmpty());
        assertTrue(recorder.waits.get()>0);assertEquals("你好，世界。",recorder.results.get(0));assertEquals(1,calls.get());
        assertTrue(recorder.last.isValid());assertFalse(recorder.last.session().isEmpty());
    }
    @Test public void repeatedActivationUsesOneSessionAndOnePaidRequest() throws Exception {
        block=true;RebuildController.activate(context,url,false,true);assertTrue(received.await(3,TimeUnit.SECONDS));
        for(int i=0;i<5;i++)RebuildController.activate(context,url,false,true);
        assertEquals(1,calls.get());release.countDown();await(()->!recorder.results.isEmpty());assertEquals(1,calls.get());
    }
    @Test public void cancelRevokesPendingAndRenderedGuardsAndLateResponseCannotPublish() throws Exception {
        block=true;RebuildController.activate(context,url,false,true);assertTrue(received.await(3,TimeUnit.SECONDS));
        TranslationOutput.RenderGuard pending=recorder.last;assertNotNull(pending);
        RebuildController.stop();assertFalse(pending.isValid());assertFalse(RebuildController.visible());
        release.countDown();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));Thread.sleep(150);
        assertTrue(recorder.results.isEmpty());
    }
    @Test public void videoAndTargetReplacementRejectsTheOldSession() throws Exception {
        block=true;RebuildController.activate(context,url,false,true);assertTrue(received.await(3,TimeUnit.SECONDS));
        TranslationOutput.RenderGuard old=recorder.last;
        RebuildController.video("zyxwvutsrqp");block=false;release.countDown();
        RebuildController.activate(context,url.replace("abcdefghijk","zyxwvutsrqp").replace("zh-Hans","ja"),false,true);
        await(()->recorder.results.contains("こんにちは、世界。"));assertFalse(old.isValid());
        assertFalse(recorder.results.contains("你好，世界。"));assertEquals(2,calls.get());
    }
    @Test public void authenticationFailureIsTerminalAndReportsStatusWithoutRetryLoop() throws Exception {
        failureCode=401;RebuildController.activate(context,url,false,true);await(()->!recorder.errors.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));Thread.sleep(50);
        assertEquals(1,calls.get());assertTrue(recorder.results.isEmpty());
    }
    @Test public void rateLimitUsesTheUpstreamRetryPolicyAndEventuallyPublishes() throws Exception {
        failureCode=429;RebuildController.activate(context,url,false,true);await(()->!recorder.results.isEmpty());assertEquals(2,calls.get());
    }
    @Test public void originalSourceSelectionHasNoTranslationRequests() throws Exception {
        RebuildController.activate(context,url.replace("&tlang=zh-Hans",""),true,true);
        await(()->!recorder.results.isEmpty());assertEquals("Hello world.",recorder.results.get(0));assertEquals(0,calls.get());assertFalse(RebuildController.ownsNativeTrack());
    }
    @Test public void cacheClearRetranslatesAndKeepsTheCurrentSelection() throws Exception {
        RebuildController.activate(context,url,false,true);await(()->!recorder.results.isEmpty());
        RebuildController.clearCache(context);await(()->calls.get()==2&&!recorder.results.isEmpty()&&RebuildController.visible());
        assertTrue(RebuildController.ownsNativeTrack());
    }
    @Test public void stopAfterCacheClearPreventsItsQueuedRestart() throws Exception {
        RebuildController.activate(context,url,false,true);await(()->!recorder.results.isEmpty());
        RebuildController.clearCache(context);RebuildController.stop();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));Thread.sleep(100);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertFalse(RebuildController.visible());assertEquals(1,calls.get());
    }
    static final class Recorder implements TranslationOutput.Sink {
        final List<String> results=new CopyOnWriteArrayList<>(),errors=new CopyOnWriteArrayList<>();
        final AtomicInteger waits=new AtomicInteger();volatile TranslationOutput.RenderGuard last;
        public void showWaitingEvent(String text,TranslationOutput.RenderGuard g,String id,long start,long end,long at){if(g.isValid()){last=g;waits.incrementAndGet();}}
        public void showStatus(String text,TranslationOutput.RenderGuard g){if(g.isValid()){last=g;errors.add(text);}}
        public void showEvent(String text,TranslationOutput.RenderGuard g,Supplier<String> f,String id,long start,long end,long at,CaptionRenderSpec spec,CaptionDisplayCandidate candidate){if(g.isValid()){last=g;results.add(text);g.onApplied();}}
    }
}
