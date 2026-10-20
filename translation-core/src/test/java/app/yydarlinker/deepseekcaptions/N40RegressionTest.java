package app.yydarlinker.deepseekcaptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** N40 regressions: native XML timing, authored grouping, target language and page anchors. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class N40RegressionTest {
  @Test
  public void xmlSegmentOnsetsAreNotDiscarded() throws Exception {
    String xml =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><timedtext format=\"3\"><body>"
            + "<p t=\"80\" d=\"3520\"><s t=\"0\">before</s><s t=\"319\"> february</s>"
            + "<s t=\"719\"> 24th</s><s t=\"1280\"> you</s><s t=\"1359\"> didn't</s>"
            + "<s t=\"1519\"> have</s><s t=\"1680\"> to</s></p>"
            + "<p t=\"1839\" d=\"3041\"><s t=\"0\">go</s><s t=\"241\"> far</s>"
            + "<s t=\"481\"> on</s><s t=\"560\"> the</s><s t=\"641\"> internet</s>"
            + "<s t=\"1040\"> to</s><s t=\"1201\"> find</s><s t=\"1441\"> people</s></p>"
            + "</body></timedtext>";
    byte[] body = xml.getBytes(StandardCharsets.UTF_8);
    CaptionDocument.Parsed parsed = CaptionDocument.parse(body, "application/xml");
    RebuildSource source = RebuildSource.read(body, parsed, true);
    assertEquals(15, source.words.size());
    assertEquals(15, source.nativeWordCount());
    assertEquals(80, source.words.get(0).start);
    assertEquals(399, source.words.get(1).start);
    assertEquals(1839, source.words.get(6).end);
    assertEquals(1839, source.words.get(7).start);
    assertEquals(RebuildSource.Precision.NATIVE, source.words.get(7).precision);
    assertTrue(source.formatEvidence, source.formatEvidence.contains("detected_fmt=xml"));
    assertTrue(source.formatEvidence, source.formatEvidence.contains("offset_segments=15"));
  }

  @Test
  public void authoredBlocksGroupCuesWithoutCrossingASentence() {
    List<RebuildSource.Word> words = new ArrayList<>();
    add(words, "Hello", 0, 400, 0);
    add(words, "world", 400, 900, 0);
    add(words, "this", 1000, 1400, 1);
    add(words, "is", 1400, 1700, 1);
    add(words, "a", 1800, 1900, 2);
    add(words, "test.", 1900, 2400, 2);
    add(words, "Next.", 3000, 3500, 3);
    RebuildSource source = new RebuildSource(words);
    List<RebuildPlanner.Block> blocks =
        RebuildPlanner.planAuthored(source, CaptionLanguageContext.LEGACY);
    assertEquals(2, blocks.size());
    assertEquals(0, blocks.get(0).from);
    assertEquals(5, blocks.get(0).to);
    assertEquals("Hello world this is a test.", source.text(0, 5));
    assertEquals(6, blocks.get(1).from);
    assertEquals(6, blocks.get(1).to);
  }

  @Test
  public void targetLanguageRejectsJapaneseEcho() throws Exception {
    RebuildSource source = RebuildContractTest.source("配送方法は通常とそれより少し速い", 300);
    RebuildPlanner.Block block = RebuildContractTest.block(source);
    String reply =
        RebuildContractTest.reply(
            block,
            new JSONArray()
                .put(
                    RebuildContractTest.event(0, source.words.size() - 1, "配送方法には、通常とやや高速です。")
                        .put("source", source.text(0, source.words.size() - 1))));
    try {
      RebuildProtocol.parseBound(
          reply,
          source,
          block,
          CaptionLanguageContext.explicit("ja", "zh-Hans"));
      fail("source echo accepted");
    } catch (RebuildProtocol.Invalid expected) {
      assertEquals("target_language_mismatch", expected.code);
    }
  }

  @Test
  public void targetLanguageRejectsEnglishEchoButAcceptsChinese() throws Exception {
    RebuildSource source = RebuildContractTest.source("hello world", 300);
    RebuildPlanner.Block block = RebuildContractTest.block(source);
    String sourceText = source.text(0, source.words.size() - 1);
    try {
      RebuildProtocol.parseBound(
          RebuildContractTest.reply(
              block,
              new JSONArray().put(RebuildContractTest.event(0, 1, sourceText).put("source", sourceText))),
          source,
          block,
          CaptionLanguageContext.explicit("en", "zh-Hans"));
      fail("English echo accepted for a Chinese target");
    } catch (RebuildProtocol.Invalid expected) {
      assertEquals("target_language_mismatch", expected.code);
    }
    RebuildProtocol.Plan accepted =
        RebuildProtocol.parseBound(
            RebuildContractTest.reply(
                block,
                new JSONArray().put(RebuildContractTest.event(0, 1, "你好世界").put("source", sourceText))),
            source,
            block,
            CaptionLanguageContext.explicit("en", "zh-Hans"));
    assertEquals("你好世界", accepted.events.get(0).text);
  }

  @Test
  public void sourceAnchorsPlacePageTurnsOnRealPauses() {
    String text = "虽然很容易嘲笑这类叙事，但事实是此类文章获得了关注。";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(
            text,
            0,
            8000,
            value -> value.codePointCount(0, value.length()) <= 12,
            value -> value.codePointCount(0, value.length()) <= 40,
            spec,
            new long[] {4000});
    assertEquals(2, pages.size());
    assertEquals(0, pages.get(0).start);
    assertEquals(4000, pages.get(0).end);
    assertEquals(4000, pages.get(1).start);
    assertEquals(8000, pages.get(1).end);
    assertEquals("SOURCE_PAUSE_ANCHOR", pages.get(0).timingBasis);
    assertEquals(text, pages.get(0).text + pages.get(1).text);
  }

  @Test
  public void sourceAnchorsCoverEveryIntermediatePageInOrder() {
    String text = "甲乙丙，丁戊己，庚辛壬。";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(
            text,
            0,
            6000,
            value -> value.codePointCount(0, value.length()) <= 4,
            value -> value.codePointCount(0, value.length()) <= 4,
            spec,
            new long[] {2000, 4000});
    assertEquals(3, pages.size());
    assertEquals(2000, pages.get(0).end);
    assertEquals(2000, pages.get(1).start);
    assertEquals(4000, pages.get(1).end);
    assertEquals(4000, pages.get(2).start);
    assertEquals(6000, pages.get(2).end);
  }

  private static void add(
      List<RebuildSource.Word> words, String text, long start, long end, int cue) {
    words.add(new RebuildSource.Word(text, start, end, cue, RebuildSource.Precision.NATIVE));
  }
}
