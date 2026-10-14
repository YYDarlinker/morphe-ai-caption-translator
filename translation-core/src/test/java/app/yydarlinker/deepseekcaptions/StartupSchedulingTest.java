package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Real engine, planner and HTTP stack; a playing native clock and a multi-block source. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class StartupSchedulingTest {
  final Context context=RuntimeEnvironment.getApplication();
  final String url="https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&tlang=zh-Hans";
  MockWebServer server;
  final List<String> requested=new CopyOnWriteArrayList<>();
  final CountDownLatch nextRequested=new CountDownLatch(1);
  volatile long playingSince;
  volatile boolean nextBeforeLandingReply;
  final List<String> shown=new CopyOnWriteArrayList<>();

  /** Twelve seven-second sentences separated by 700 ms pauses, so blocks follow the sentences. */
  static byte[] source() throws Exception {
    JSONArray events=new JSONArray();long at=0;
    for(int sentence=0;sentence<12;sentence++) {
      JSONArray segs=new JSONArray();
      String[] words=("sentence "+sentence+" says the budget grows every year while the army changes slowly over time").split(" ");
      for(int w=0;w<words.length;w++)segs.put(new JSONObject().put("utf8",(w==0?"":" ")+words[w]).put("tOffsetMs",w*450));
      events.put(new JSONObject().put("tStartMs",at).put("dDurationMs",words.length*450).put("segs",segs));
      at+=words.length*450+700;
    }
    return new JSONObject().put("events",events).toString().getBytes(StandardCharsets.UTF_8);
  }

  @Before public void setup() throws Exception {
    RebuildController.stop();RebuildApi.reset();RebuildCache.clear(context);
    server=new MockWebServer();
    server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){
      try {
        JSONObject input=new JSONObject(new JSONObject(request.getBody().readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content"));
        String block=input.getString("block");requested.add(block);
        if(block.startsWith("b0_"))nextBeforeLandingReply=nextRequested.await(3,TimeUnit.SECONDS); // hold the landing block
        else nextRequested.countDown();
        JSONArray tokens=input.getJSONArray("owned_tokens");
        JSONObject content=new JSONObject().put("block",block).put("events",new JSONArray().put(new JSONObject()
            .put("from",tokens.getJSONArray(0).getInt(0)).put("to",tokens.getJSONArray(tokens.length()-1).getInt(0))
            .put("source",input.getString("source_text")).put("text","预算每年都在增长，而军队变化缓慢。")));
        return new MockResponse().setBody(new JSONObject().put("choices",new JSONArray().put(new JSONObject()
            .put("message",new JSONObject().put("content",content.toString())))).toString());
      }catch(Exception e){return new MockResponse().setResponseCode(500).setBody("fixture error");}
    }});
    server.start();
    TranslationRuntime.install(new TranslationRuntime.Host(){
      TranslationSettings settings(Context c){return new TranslationSettings(true,server.url("/v1").toString(),"fixture-model","faithful","fixture-key","stored_custom");}
      TranslationSourceAccess.Source load(Context c,String u,boolean translate,DeepSeekApiClient.RequestControl control) throws Exception {
        byte[] body=source();
        return new TranslationSourceAccess.Source(body,CaptionDocument.parse(body,"application/json"),"",0,null);
      }
      String target(){return "zh-Hans";}
      TranslationRuntime.ClockSample clock(String owner){
        // The fake clock outruns the held HTTP reply; playing stays inside block 0, so only the
        // bootstrap rule, never a new landing, can request block 1 early.
        long now=SystemClock.elapsedRealtime();
        return new TranslationRuntime.ClockSample(true,"abcdefghijk",Math.min(500,Math.max(0,now-playingSince)),now,3,1f);
      }
    });
    TranslationOutput.install(new TranslationOutput.Sink(){
      public void showEvent(String text,TranslationOutput.RenderGuard g,String id,long start,long end,long at,CaptionRenderSpec spec){
        if(g.isValid()){shown.add(text);g.onApplied();}
      }
    });
    TranslationAudit.install(new TranslationAudit.Sink(){});
    playingSince=SystemClock.elapsedRealtime();
    RebuildController.activity(context);RebuildController.video("abcdefghijk");RebuildController.time(0);
  }

  @After public void teardown() throws Exception {
    nextRequested.countDown();RebuildController.stop();server.shutdown();
    TranslationOutput.install(new TranslationOutput.Sink(){});
  }

  void await(BooleanSupplier done) throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
    while(!done.getAsBoolean()&&System.nanoTime()<deadline){
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));Thread.sleep(10);
    }
    assertTrue("engine did not reach the expected observable state",done.getAsBoolean());
  }

  @Test public void nextBlockIsRequestedWhileTheLandingBlockIsStillInFlight() throws Exception {
    RebuildController.activate(context,url,false,true);
    await(()->!shown.isEmpty()&&requested.size()>=2);
    assertTrue(requested.get(0).startsWith("b0_"));
    assertTrue(requested.get(1).startsWith("b1_"));
    assertTrue("block 1 must be requested before block 0's reply",nextBeforeLandingReply);
  }
}
