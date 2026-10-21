package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * N51, through the real engine: a reply whose sentences cross the author's cue boundaries must
 * still reach the caption box. Before N51 the whole block was rejected and the box showed only the
 * waiting placeholder for its whole duration.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class N51AuthoredDisplayTest {
    private static final String[] TEXTS = {"第一句译文", "第二句译文", "第三句译文"};

    final Context context = RuntimeEnvironment.getApplication();
    final String url =
            "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=ja&tlang=zh-Hans";
    final AtomicLong position = new AtomicLong(500);
    final List<String> shown = new CopyOnWriteArrayList<>();
    final List<String> waiting = new CopyOnWriteArrayList<>();
    final List<String> hidden = new CopyOnWriteArrayList<>();
    MockWebServer server;

    /** Four contiguous authored cues, six kana tokens each, 300 ms per token. */
    static byte[] source() throws Exception {
        JSONArray events = new JSONArray();
        long at = 0;
        for (int cue = 0; cue < 4; cue++) {
            JSONArray segs = new JSONArray();
            for (int word = 0; word < 6; word++) {
                char kana = (char) ('\u3042' + ((cue * 6 + word) % 40));
                segs.put(new JSONObject().put("utf8", String.valueOf(kana)).put("tOffsetMs", word * 300));
            }
            events.put(
                    new JSONObject()
                            .put("tStartMs", at)
                            .put("dDurationMs", 1800)
                            .put("segs", segs));
            at += 1800;
        }
        return new JSONObject()
                .put("events", events)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    static String join(JSONArray tokens, int from, int to) throws Exception {
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(tokens.getJSONArray(i).getString(1));
        }
        return out.toString();
    }

    static JSONObject event(JSONArray tokens, int from, int to, String text) throws Exception {
        return new JSONObject()
                .put("from", from)
                .put("to", to)
                .put("source", join(tokens, from, to))
                .put("text", text);
    }

    @Before
    public void setup() throws Exception {
        RebuildController.stop();
        RebuildApi.reset();
        RebuildCache.clear(context);
        server = new MockWebServer();
        server.setDispatcher(
                new Dispatcher() {
                    public MockResponse dispatch(RecordedRequest request) {
                        try {
                            JSONObject root = new JSONObject(request.getBody().readUtf8());
                            JSONObject input =
                                    new JSONObject(
                                            root.getJSONArray("messages")
                                                    .getJSONObject(1)
                                                    .getString("content"));
                            String block = input.getString("block");
                            JSONArray tokens = input.getJSONArray("owned_tokens");
                            JSONArray cues = input.getJSONArray("display_cues");
                            int cue1From = cues.getJSONObject(1).getInt("from");
                            int cue1To = cues.getJSONObject(1).getInt("to");
                            int cue2From = cues.getJSONObject(2).getInt("from");
                            int cue3To = cues.getJSONObject(3).getInt("to");
                            // The model merges cue 0 with the head of cue 1, splits cue 1, then
                            // merges cue 2 and cue 3: the exact N51 shape.
                            int firstTo = Math.min(cue1To, cue1From + 2);
                            JSONArray events =
                                    new JSONArray()
                                            .put(event(tokens, 0, firstTo, TEXTS[0]))
                                            .put(event(tokens, firstTo + 1, cue1To, TEXTS[1]))
                                            .put(event(tokens, cue2From, cue3To, TEXTS[2]));
                            String content =
                                    new JSONObject()
                                            .put("block", block)
                                            .put("events", events)
                                            .toString();
                            return new MockResponse()
                                    .setBody(
                                            new JSONObject()
                                                    .put(
                                                            "choices",
                                                            new JSONArray()
                                                                    .put(
                                                                            new JSONObject()
                                                                                    .put(
                                                                                            "message",
                                                                                            new JSONObject()
                                                                                                    .put(
                                                                                                            "content",
                                                                                                            content))))
                                                    .toString());
                        } catch (Exception error) {
                            return new MockResponse().setResponseCode(500).setBody("fixture error");
                        }
                    }
                });
        server.start();
        TranslationRuntime.install(
                new TranslationRuntime.Host() {
                    TranslationSettings settings(Context c) {
                        return new TranslationSettings(
                                true,
                                server.url("/v1").toString(),
                                "fixture-model",
                                "faithful",
                                "fixture-key",
                                "stored_custom");
                    }

                    TranslationSourceAccess.Source load(
                            Context c, String u, boolean translate, DeepSeekApiClient.RequestControl control)
                            throws Exception {
                        byte[] body = source();
                        return new TranslationSourceAccess.Source(
                                body, CaptionDocument.parse(body, "application/json"), "", 0, null);
                    }

                    String target() {
                        return "zh-Hans";
                    }

                    boolean authoredTrack(String u) {
                        return true;
                    }

                    TranslationRuntime.ClockSample clock(String owner) {
                        return new TranslationRuntime.ClockSample(
                                true, "abcdefghijk", position.get(), SystemClock.elapsedRealtime(), 3, 1f);
                    }
                });
        TranslationOutput.install(
                new TranslationOutput.Sink() {
                    public void showEvent(
                            String text,
                            TranslationOutput.RenderGuard g,
                            String id,
                            long start,
                            long end,
                            long at,
                            CaptionRenderSpec spec) {
                        if (g.isValid() && !text.trim().isEmpty()) {
                            shown.add(text);
                            g.onApplied();
                        }
                    }

                    public void showWaitingEvent(
                            String text,
                            TranslationOutput.RenderGuard g,
                            String id,
                            long start,
                            long end,
                            long at) {
                        if (g.isValid()) {
                            waiting.add(text);
                            g.onApplied();
                        }
                    }

                    public void hide(TranslationOutput.RenderGuard g) {
                        if (g.isValid()) {
                            hidden.add(g.blankReason());
                            g.onApplied();
                        }
                    }
                });
        TranslationAudit.install(new TranslationAudit.Sink() {});
        RebuildController.activity(context);
        RebuildController.video("abcdefghijk");
        RebuildController.time(0);
    }

    @After
    public void teardown() throws Exception {
        RebuildController.stop();
        server.shutdown();
        TranslationOutput.install(new TranslationOutput.Sink() {});
    }

    void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
            Thread.sleep(10);
        }
        assertTrue("engine did not reach the expected observable state", done.getAsBoolean());
    }

    void play(long to) throws Exception {
        position.set(to);
        RebuildController.time(to);
        for (int i = 0; i < 6; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
            Thread.sleep(5);
        }
    }

    @Test
    public void aSentenceCrossingCueBoundariesReachesTheCaptionBox() throws Exception {
        RebuildController.activate(context, url, false, true);
        await(() -> shown.contains(TEXTS[0]));
        hidden.clear();
        int waitingBefore = waiting.size();

        // Inside cue 0 the first merged sentence is on screen; at 2 s it still is, because the
        // event page owns the source span it translates.
        play(500);
        assertEquals(TEXTS[0], shown.get(shown.size() - 1));
        play(2000);
        assertEquals(TEXTS[0], shown.get(shown.size() - 1));

        // Cue 1's tail has its own sentence.
        play(3000);
        await(() -> shown.contains(TEXTS[1]));
        // Cue 2 and cue 3 were merged by the model; the text must be displayed all the same.
        play(4500);
        await(() -> shown.contains(TEXTS[2]));
        play(6500);
        assertEquals(TEXTS[2], shown.get(shown.size() - 1));

        assertEquals(
                "the box blanked between projected pages: " + hidden,
                0,
                hidden.size());
        assertEquals(
                "a translated block must not fall back to the waiting placeholder",
                waitingBefore,
                waiting.size());
    }
}
