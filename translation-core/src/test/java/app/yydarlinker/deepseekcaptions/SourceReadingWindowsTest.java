package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

/** Own captions shown as they are are re-paged by sentence and pause, never reworded or lost. */
public class SourceReadingWindowsTest {
  static final CaptionLanguageContext CONTEXT = CaptionLanguageContext.explicit("en", "zh-Hans");

  static JSONObject cue(long start, long end, String text) throws Exception {
    return new JSONObject().put("tStartMs", start).put("dDurationMs", end - start)
        .put("segs", new JSONArray().put(new JSONObject().put("utf8", text)));
  }

  static List<RebuildSource.TimedWindow> pages(RebuildSource s) {
    return SourceReadingWindows.build(s, CONTEXT.renderSpec, CONTEXT);
  }

  static RebuildSource source(JSONArray events) throws Exception {
    return RebuildContractTest.json(new JSONObject().put("events", events).toString());
  }

  static void assertPartition(RebuildSource s, List<RebuildSource.TimedWindow> pages) {
    int next = 0;
    for (RebuildSource.TimedWindow page : pages) {
      assertEquals(next, page.from);
      assertTrue(page.to >= page.from && page.end > page.start);
      next = page.to + 1;
    }
    assertEquals(s.words.size(), next);
  }

  @Test public void fragmentsOfOneSentenceShareAPageAndSentencesKeepTheirOwn() throws Exception {
    String[][] sentences = {
        {"我刚刚把这家超市", "买了下来。"}, {"你们四位要尝试", "一项前所未有的挑战。"},
        {"吃光足以喂饱", "一座城市的食物。"}, {"选个货架", "开始大吃特吃吧。"}};
    JSONArray events = new JSONArray();
    long at = 0;
    for (String[] pair : sentences) {
      events.put(cue(at, at + 900, pair[0]));
      events.put(cue(at + 900, at + 1800, pair[1]));
      at += 1900;
    }
    RebuildSource s = source(events);
    List<RebuildSource.TimedWindow> pages = pages(s);
    assertPartition(s, pages);
    assertEquals(sentences.length, pages.size());
    for (int i = 0; i < pages.size(); i++)
      assertEquals(sentences[i][0] + sentences[i][1],
          s.text(pages.get(i).from, pages.get(i).to).replace(" ", ""));
  }

  @Test public void aRunTooLongForTheBoxIsCutAtItsClauses() throws Exception {
    String text = "我们今天来到了一个非常大的仓库，里面堆满了各种各样的食物，"
        + "每个人都可以随便挑选自己喜欢的东西，然后在规定的时间里全部吃完，"
        + "只要成功就能拿走一大笔奖金";
    JSONArray events = new JSONArray().put(cue(0, 4800, text));
    RebuildSource s = source(events);
    List<RebuildSource.TimedWindow> pages = pages(s);
    assertPartition(s, pages);
    assertTrue(pages.size() >= 2);
    for (int i = 0; i < pages.size() - 1; i++) {
      String page = s.text(pages.get(i).from, pages.get(i).to).trim();
      assertTrue(page, page.endsWith("，"));
      assertTrue(page, SourceReadingWindows.units(page) <= 50);
    }
    assertEquals(text, s.text(0, s.words.size() - 1).replace(" ", ""));
  }

  @Test public void aPauseNeverFallsInsideAPage() throws Exception {
    JSONArray events = new JSONArray()
        .put(cue(0, 800, "好的")).put(cue(800, 1600, "我们开始")).put(cue(3000, 3800, "第二轮")).put(cue(3800, 4600, "马上开始"));
    RebuildSource s = source(events);
    List<RebuildSource.TimedWindow> pages = pages(s);
    assertPartition(s, pages);
    assertEquals(2, pages.size());
    assertTrue(pages.get(0).end <= 1600 && pages.get(1).start >= 3000);
  }

  @Test public void aSentenceSplitOverManyFragmentsInEnglishReadsAsWholePages() throws Exception {
    CaptionLanguageContext english = CaptionLanguageContext.explicit("en", "en");
    JSONArray events = new JSONArray();
    String[] parts = {"so what we are going", "to do today is take", "this entire grocery store", "and eat everything in it.",
        "Who is ready", "to win a million dollars?"};
    long at = 0;
    for (String part : parts) { events.put(cue(at, at + 1000, part)); at += 1000; }
    RebuildSource s = source(events);
    List<RebuildSource.TimedWindow> pages = SourceReadingWindows.build(s, english.renderSpec, english);
    assertPartition(s, pages);
    assertTrue(pages.size() < parts.length);
    for (RebuildSource.TimedWindow page : pages)
      assertTrue(s.text(page.from, page.to), SourceReadingWindows.units(s.text(page.from, page.to)) <= 105);
    String last = s.text(pages.get(pages.size() - 1).from, pages.get(pages.size() - 1).to);
    assertTrue(last, last.endsWith("dollars?"));
    String first = s.text(pages.get(0).from, pages.get(0).to);
    assertFalse(first, first.endsWith(" to") || first.endsWith(" the"));
  }

  @Test public void lookupFindsThePageAtATimeAndTheNeighboursAroundAGap() throws Exception {
    JSONArray events = new JSONArray().put(cue(0, 1000, "第一句话。")).put(cue(1700, 2700, "第二句话。"));
    RebuildSource s = source(events);
    List<RebuildSource.TimedWindow> pages = pages(s);
    assertEquals(2, pages.size());
    assertSame(pages.get(0), SourceReadingWindows.at(pages, 500));
    assertNull(SourceReadingWindows.at(pages, 1200));
    assertSame(pages.get(0), SourceReadingWindows.before(pages, 1200));
    assertSame(pages.get(1), SourceReadingWindows.after(pages, 1200));
    assertSame(pages.get(1), SourceReadingWindows.at(pages, 1700));
  }

  /** The real own-language track of plN7JMbadRg: every word kept once, in order, in readable pages. */
  @Test public void theRealTrackIsRepagedWithoutLosingAWord() throws Exception {
    byte[] body = PlannerGoldenTest.resource("/r151/plN7JMbadRg.zh-Hans.json3.gz");
    RebuildSource s = RebuildSource.read(body, CaptionDocument.parse(body, "json3"));
    List<RebuildSource.TimedWindow> pages = pages(s);
    assertPartition(s, pages);
    assertTrue(pages.size() <= s.windows().size());
    for (RebuildSource.TimedWindow page : pages) {
      assertTrue(SourceReadingWindows.units(s.text(page.from, page.to)) <= 50);
      assertTrue(page.end - page.start <= SourceReadingWindows.MAX_PAGE_MS * 3 / 2);
    }
  }
}
