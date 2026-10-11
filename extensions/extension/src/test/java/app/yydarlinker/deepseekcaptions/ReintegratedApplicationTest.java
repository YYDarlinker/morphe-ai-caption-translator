package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import android.widget.TextView;
import app.yydarlinker.caption.contracts.CaptionContracts.Action;
import java.lang.reflect.Field;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Actual application port, source transport, provider protocol and retained native TextView. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows={N39UiGeometryFixture.Geometry.class,ReintegratedApplicationTest.Keys.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class ReintegratedApplicationTest extends N39UiGeometryFixture {
    @Implements(SecureApiKey.class) public static class Keys {@Implementation public static String load(Context c){return "fixture-key";}}
    MockWebServer server;Object previousCronet;AtomicInteger posts=new AtomicInteger();
    CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
    volatile boolean delayFirst;volatile String lastModel="";
    final String url="https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&tlang=zh-Hans";
    public static final class Cronet {
        private final MockWebServer server;Cronet(MockWebServer server){this.server=server;}
        public HttpURLConnection openConnection(URL ignored) throws Exception{return (HttpURLConnection)server.url("/source").url().openConnection();}
    }
    @Before public void start() throws Exception {
        CaptionPlayerTransitionGuard.resetForTests();CaptionPlayerAuthority.resetForTests();super.setup();
        server=new MockWebServer();server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){
            try {
                if(request.getPath().startsWith("/source"))return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"events\":[{\"tStartMs\":0,\"dDurationMs\":30000,\"segs\":[{\"utf8\":\"Hello \",\"tOffsetMs\":0},{\"utf8\":\"world.\",\"tOffsetMs\":1000}]}]}");
                if(request.getPath().endsWith("/models"))return new MockResponse().setBody("{\"data\":[{\"id\":\"fixture-model\"}]}");
                JSONObject root=new JSONObject(request.getBody().readUtf8());lastModel=root.getString("model");
                int ordinal=posts.incrementAndGet();received.countDown();if(delayFirst&&ordinal==1)release.await(4,TimeUnit.SECONDS);
                JSONObject p=new JSONObject(root.getJSONArray("messages").getJSONObject(1).getString("content"));JSONArray tokens=p.getJSONArray("owned_tokens");
                JSONObject event=new JSONObject().put("from",tokens.getJSONArray(0).getInt(0)).put("to",tokens.getJSONArray(tokens.length()-1).getInt(0)).put("source",p.getString("source_text")).put("text","你好，世界。");
                String body=new JSONObject().put("block",p.getString("block")).put("events",new JSONArray().put(event)).toString();
                return new MockResponse().setBody(new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("content",body)))).toString());
            }catch(Exception e){return new MockResponse().setResponseCode(500);}
        }});server.start();
        Field engine=DeepSeekCaptionHook.class.getDeclaredField("youtubeCronetEngine");engine.setAccessible(true);previousCronet=engine.get(null);engine.set(null,new Cronet(server));
        DeepSeekConfig.saveEnabled(a,true);DeepSeekConfig.saveBaseUrl(a,server.url("/v1").toString());DeepSeekConfig.saveModel(a,"fixture-model");
        SourceCaptionCache.clear(a);RebuildCache.clear(a);RebuildApi.reset();TokenCostAudit.clear(a);
        DeepSeekCaptionHookV2.setMainActivity(a);DeepSeekCaptionHookV2.onVideoId("abcdefghijk");CaptionChoice.select("zh-Hans",true);CaptionActions.onVideoTime(0);
    }
    @After public void finish() throws Exception {
        release.countDown();CaptionActions.command(Action.DEACTIVATE,"");
        Field engine=DeepSeekCaptionHook.class.getDeclaredField("youtubeCronetEngine");engine.setAccessible(true);engine.set(null,previousCronet);
        server.shutdown();super.done();CaptionChoice.reset();
    }
    void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));Thread.sleep(15);}
        assertTrue("application/display did not reach expected state",condition.getAsBoolean());
    }
    boolean translated(){try{TextView view=text();return CaptionOverlay.anchorVisible()&&view!=null&&view.getText().toString().contains("你好，世界。");}catch(Exception e){return false;}}
    @Test public void localUiCommandUsesPinnedCoreAndDisplaysInTheActualRetainedView() throws Exception {
        CaptionActions.activate(a,url);await(this::translated);assertEquals(1,posts.get());assertTrue(CaptionActions.ownsNativeTrack());
        assertEquals("fixture-model",lastModel);assertEquals(2,text().getMaxLines());
        assertTrue(CaptionDiagnostics.history(a).contains("REBUILD_SOURCE_READY"));
        assertTrue(CaptionDiagnostics.history(a).contains("REBUILD_HTTP_RESPONSE"));
        DeepSeekConfig.saveCaptionSizeTier(a,4);DeepSeekConfig.saveBackgroundOpacity(a,35);CaptionActions.appearanceChanged();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));assertEquals(1,posts.get());assertTrue(translated());
    }
    @Test public void stoppingThroughTheUiPortRejectsDelayedProviderOutput() throws Exception {
        delayFirst=true;CaptionActions.activate(a,url);assertTrue(received.await(3,TimeUnit.SECONDS));
        CaptionActions.deactivateFromCaptionButton();release.countDown();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));Thread.sleep(200);
        assertFalse(CaptionActions.isVisibleActive());assertFalse(translated());assertEquals(1,posts.get());
    }
    @Test public void settingsApiTestAndModelCatalogUseTheApplicationServices() throws Exception {
        assertEquals("你好，世界。",CaptionActions.testApi());assertEquals(1,posts.get());
        assertEquals(java.util.Collections.singletonList("fixture-model"),CaptionActions.models(server.url("/v1").toString()));
        Field usage=TokenCostAudit.class.getDeclaredField("memoryState");usage.setAccessible(true);
        JSONObject snapshot=new JSONObject(usage.get(null).toString());
        assertEquals(1,snapshot.getJSONObject("session").getJSONObject("buckets").getJSONObject("core_event_rebuild_r2").getInt("logical_requests"));
    }
    @Test public void nativeSourceSelectionDoesNotStartAnEngineOrDuplicateTheNativeRenderer() throws Exception {
        CaptionActions.activate(a,url);await(this::translated);
        CaptionChoice.select("en",false);CaptionActions.deactivateFromNativeCaptionState();
        CaptionActions.activateSource(a,url.replace("&tlang=zh-Hans",""));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertFalse(CaptionActions.isVisibleActive());assertFalse(CaptionActions.ownsNativeTrack());
        assertFalse(translated());assertEquals("",field("pendingText"));assertEquals(1,posts.get());
    }
    @Test public void configurationChangeUsesTheNewModelAndRetiresTheOutstandingRequest() throws Exception {
        delayFirst=true;CaptionActions.activate(a,url);assertTrue(received.await(3,TimeUnit.SECONDS));
        DeepSeekConfig.saveModel(a,"fixture-model-new");CaptionActions.refreshConfiguration(a);release.countDown();
        await(()->posts.get()==2&&translated());assertEquals("fixture-model-new",lastModel);
    }
}
