package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class RebuildStreamTest {
  static String sse(String content, int piece, String finish, JSONObject usage) throws Exception {
    StringBuilder out = new StringBuilder(": keep-alive\n\n");
    for (int i = 0; i < content.length(); i += piece) {
      JSONObject delta = new JSONObject().put("content", content.substring(i, Math.min(content.length(), i + piece)));
      out.append("data: ").append(new JSONObject().put("model", "fixture").put("choices",
          new JSONArray().put(new JSONObject().put("index", 0).put("delta", delta).put("finish_reason", JSONObject.NULL)))).append("\n\n");
    }
    out.append("data: ").append(new JSONObject().put("choices", new JSONArray().put(new JSONObject()
        .put("index", 0).put("delta", new JSONObject()).put("finish_reason", finish)))).append("\n\n");
    out.append("data: ").append(new JSONObject().put("choices", new JSONArray()).put("usage", usage)).append("\n\n");
    return out.append("data: [DONE]\n\n").toString();
  }

  static final String REPLY = "{\n  \"block\": \"b0_0_5\",\n  \"events\": [\n"
      + "    {\"from\": 0, \"to\": 1, \"source\": \"he said \\\"no}\\\"\", \"text\": \"他说“不}”\"},\n"
      + "    {\"from\": 2, \"to\": 3, \"source\": \"a [list]\", \"text\": \"一个[列表]\"},\n"
      + "    {\"from\": 4, \"to\": 5, \"source\": \"the end\", \"text\": \"结束。\"}\n  ]\n}";

  @Test public void completedEventsIgnoresPartialObjectsAndBracesInsideStrings() {
    assertEquals(0, RebuildStream.completedEvents("{\"block\":\"b0\",\"events\":[{\"from\":0,\"text\":\"a}").size());
    for (int cut = 0; cut <= REPLY.length(); cut++) {
      List<String> found = RebuildStream.completedEvents(REPLY.substring(0, cut));
      int expected = cut >= REPLY.indexOf("},\n    {\"from\": 2") + 1 ? 1 : 0;
      if (cut >= REPLY.indexOf("},\n    {\"from\": 4") + 1) expected = 2;
      if (cut >= REPLY.lastIndexOf("\"}") + 2) expected = 3;
      assertEquals("prefix length " + cut, expected, found.size());
    }
    // A fenced reply and an "events" string value elsewhere do not confuse the scanner.
    assertEquals(1, RebuildStream.completedEvents("```json\n{\"note\":\"events\",\"events\":[{\"a\":1}]}```").size());
  }

  @Test public void readerRebuildsTheNonStreamedResponseAcrossArbitraryByteSplits() throws Exception {
    JSONObject usage = new JSONObject().put("prompt_tokens", 100).put("completion_tokens", 40).put("prompt_cache_hit_tokens", 64);
    byte[] body = sse(REPLY, 7, "stop", usage).getBytes(StandardCharsets.UTF_8);
    for (int split : new int[] {1, 2, 3, 5, 64, body.length}) {
      List<Integer> offered = new ArrayList<>();
      RebuildStream.Reader reader = new RebuildStream.Reader(events -> offered.add(events.length()));
      for (int at = 0; at < body.length; at += split) reader.feed(body, at, Math.min(split, body.length - at));
      JSONObject root = reader.response();
      assertTrue(reader.streamed());
      assertEquals(REPLY, root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
      assertEquals("stop", root.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
      assertEquals(64, root.getJSONObject("usage").getInt("prompt_cache_hit_tokens"));
      assertEquals("fixture", root.getString("model"));
      assertEquals(Arrays.asList(1, 2, 3), offered);
    }
  }

  @Test public void lengthFinishIsPreservedForTheTruncationCheck() throws Exception {
    RebuildStream.Reader reader = new RebuildStream.Reader(null);
    byte[] body = sse("{\"block\":\"b0\",\"events\":[", 4, "length", new JSONObject()).getBytes(StandardCharsets.UTF_8);
    reader.feed(body, 0, body.length);
    assertEquals("length", reader.response().getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
  }

  @Test public void aPlainJsonReplyIsNotMistakenForAStream() {
    RebuildStream.Reader reader = new RebuildStream.Reader(null);
    byte[] body = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}".getBytes(StandardCharsets.UTF_8);
    reader.feed(body, 0, body.length);
    assertFalse(reader.streamed());
  }

  @Test public void anErrorEventMarksTheStreamAsCutAndIsNotTakenForAReply() {
    RebuildStream.Reader reader = new RebuildStream.Reader(null);
    byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"block\\\":\\\"b0\"},\"finish_reason\":null}]}\n\n"
        + "data: {\"error\":{\"code\":\"data_inspection_failed\",\"message\":\"Output data may contain inappropriate content.\"}}\n\n")
        .getBytes(StandardCharsets.UTF_8);
    reader.feed(body, 0, body.length);
    assertEquals("data_inspection_failed", reader.error());
    assertTrue(reader.streamed());
    // A complete stream reports no error.
    RebuildStream.Reader whole = new RebuildStream.Reader(null);
    byte[] ok = sse_ok().getBytes(StandardCharsets.UTF_8);
    whole.feed(ok, 0, ok.length);
    assertEquals("", whole.error());
  }

  private static String sse_ok() {
    try { return sse(REPLY, 9, "stop", new JSONObject()); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
  }
}
