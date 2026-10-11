package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/**
 * Through the real engine: a page stays up across the 700 ms pause before the next sentence (a
 * hard source break, so the two are never joined), instead of blanking the box between them.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ContinuousDisplayTest {
  final Context context=RuntimeEnvironment.getApplication();
  final String url="https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&tlang=zh-Hans";
  final AtomicLong position=new AtomicLong(500);
  final List<String> shown=new CopyOnWriteArrayList<>();
  final List<String> hidden=new CopyOnWriteArrayList<>();
  final List<String> blank=new CopyOnWriteArrayList<>();
  volatile boolean truncated,inspected;
  MockWebServer server;

  @Before public void setup() throws Exception {
    RebuildController.stop();RebuildApi.reset();RebuildCache.clear(context);
    server=new MockWebServer();
    server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){
      try {
        if(truncated)return new MockResponse().setBody(new JSONObject().put("choices",new JSONArray().put(new JSONObject()
          .put("message",new JSONObject().put("content","{\"block\":\"b0_0_9\",\"events\":[{\"from\":0,\"to\":3,\"source\":\"sentence 0")))).toString());
        JSONObject input=new JSONObject(new JSONObject(request.getBody().readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content"));
        JSONArray tokens=input.getJSONArray("owned_tokens"),events=new JSONArray();
        String block=input.getString("block");
        if(inspected&&block.startsWith("b0_"))return new MockResponse().setResponseCode(400)
            .setBody("{\"error\":{\"code\":\"data_inspection_failed\",\"message\":\"Input data may contain inappropriate content.\"}}");
        // One event per fixture sentence ("sentence N says ... over time").
        for(int start=0;start<tokens.length();){
          int end=start;StringBuilder source=new StringBuilder(tokens.getJSONArray(start).getString(1));
          while(end+1<tokens.length()&&!tokens.getJSONArray(end+1).getString(1).equals("sentence"))source.append(' ').append(tokens.getJSONArray(++end).getString(1));
          String number=tokens.getJSONArray(start).getString(1).equals("sentence")&&start+1<tokens.length()?tokens.getJSONArray(start+1).getString(1):"x";
          events.put(new JSONObject().put("from",tokens.getJSONArray(start).getInt(0)).put("to",tokens.getJSONArray(end).getInt(0))
              .put("source",source.toString()).put("text","第"+number+"句说预算每年都在增长而军队变化缓慢。"));
          start=end+1;
        }
        String reply=new JSONObject().put("block",block).put("events",events).toString();
        return new MockResponse().setBody(new JSONObject().put("choices",new JSONArray().put(new JSONObject()
            .put("message",new JSONObject().put("content",reply)))).toString());
      }catch(Exception e){return new MockResponse().setResponseCode(500).setBody("fixture error");}
    }});
    server.start();
    TranslationRuntime.install(new TranslationRuntime.Host(){
      TranslationSettings settings(Context c){return new TranslationSettings(true,server.url("/v1").toString(),"fixture-model","faithful","fixture-key","stored_custom");}
      TranslationSourceAccess.Source load(Context c,String u,boolean translate,DeepSeekApiClient.RequestControl control) throws Exception {
        byte[] body=StartupSchedulingTest.source();
        return new TranslationSourceAccess.Source(body,CaptionDocument.parse(body,"application/json"),"",0,null);
      }
      String target(){return "zh-Hans";}
      TranslationRuntime.ClockSample clock(String owner){
        return new TranslationRuntime.ClockSample(true,"abcdefghijk",position.get(),SystemClock.elapsedRealtime(),3,1f);
      }
    });
    TranslationOutput.install(new TranslationOutput.Sink(){
      public void showEvent(String text,TranslationOutput.RenderGuard g,Supplier<String> f,String id,long start,long end,long at,CaptionRenderSpec spec,CaptionDisplayCandidate candidate){
        if(g.isValid()){if(text.trim().isEmpty())blank.add(id);shown.add(text);g.onApplied();}
      }
      public void hide(TranslationOutput.RenderGuard g){if(g.isValid()){hidden.add(g.blankReason());g.onApplied();}}
    });
    TranslationAudit.install(new TranslationAudit.Sink(){});
    RebuildController.activity(context);RebuildController.video("abcdefghijk");RebuildController.time(0);
  }

  @After public void teardown() throws Exception {
    RebuildController.stop();server.shutdown();
    TranslationOutput.install(new TranslationOutput.Sink(){});
  }

  void await(BooleanSupplier done) throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    while(!done.getAsBoolean()&&System.nanoTime()<deadline){
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));Thread.sleep(10);
    }
    assertTrue("engine did not reach the expected observable state",done.getAsBoolean());
  }

  void play(long to) throws Exception {
    position.set(to);RebuildController.time(to);
    for(int i=0;i<6;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));Thread.sleep(5);}
  }

  @Test public void aPageStaysUpThroughTheShortPauseBeforeTheNextSentence() throws Exception {
    RebuildController.activate(context,url,false,true);
    await(()->shown.contains("第0句说预算每年都在增长而军队变化缓慢。"));
    // Sentence 0 ends at 6750 ms; sentence 1 starts at 7450 ms.
    play(6000);hidden.clear();play(7000);play(7200);
    assertTrue("box blanked in a 700 ms pause: "+hidden,hidden.isEmpty());
    assertEquals("第0句说预算每年都在增长而军队变化缓慢。",shown.get(shown.size()-1));
    await(()->{try{play(7600);}catch(Exception e){throw new AssertionError(e);}return shown.contains("第1句说预算每年都在增长而军队变化缓慢。");});
    assertTrue(hidden.isEmpty());
  }

  @Test public void aBlockThatFailedToTranslateClearsTheBoxInsteadOfShowingAnEmptyEvent() throws Exception {
    truncated=true;
    RebuildController.activate(context,url,false,true);
    await(()->hidden.contains("translation_failed"));
    assertTrue("empty text reached the overlay as an event: "+blank,blank.isEmpty());
  }

  /** Bailian refuses one block's text with HTTP 400; later blocks must still be translated. */
  @Test public void aProviderInspectionRefusalOfOneBlockDoesNotStopLaterBlocks() throws Exception {
    inspected=true;
    RebuildController.activate(context,url,false,true);
    await(()->hidden.contains("translation_failed"));
    assertTrue("nothing from the refused block is shown: "+shown,shown.isEmpty());
    position.set(60000);RebuildController.time(60000);
    await(()->{try{play(60100);}catch(Exception e){throw new AssertionError(e);}return !shown.isEmpty();});
    assertTrue(shown.get(0).startsWith("第"));
  }
}
