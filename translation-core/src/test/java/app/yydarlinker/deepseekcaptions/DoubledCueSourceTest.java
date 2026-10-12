package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

/** A track that shows every cue twice is read once; ordinary repeated speech is untouched. */
public class DoubledCueSourceTest {
  static final String[] LINES = {
    "我刚刚把这家超市买了下来", "你们四位要尝试一项前所未有的挑战", "吃光足以喂饱一座城市的食物",
    "选个货架开始大吃特吃吧", "我从没吃过但就算不好吃", "胡安加油", "说一说你们一天能吃多少卡路里",
    "那个大块头说他能吃下两万卡路里", "距离每增加十码", "接住橄榄球的奖励也会随之增加"
  };

  static JSONObject cue(long start, long end, String text) throws Exception {
    return new JSONObject().put("tStartMs", start).put("dDurationMs", end - start)
        .put("segs", new JSONArray().put(new JSONObject().put("utf8", text)));
  }

  static RebuildSource source(JSONArray events) throws Exception {
    return RebuildContractTest.json(new JSONObject().put("events", events).toString());
  }

  @Test public void everyCueTwiceIsReadOnceAndKeepsTheDisplayedTime() throws Exception {
    JSONArray doubled = new JSONArray(), single = new JSONArray();
    long at = 0;
    for (String line : LINES) {
      doubled.put(cue(at, at + 1200, line));
      doubled.put(cue(at + 1300, at + 2400, line));
      single.put(cue(at, at + 2400, line));
      at += 2600;
    }
    RebuildSource twice = source(doubled), once = source(single);
    assertEquals(once.words.size(), twice.words.size());
    for (int i = 0; i < once.words.size(); i++) {
      assertEquals(once.words.get(i).text, twice.words.get(i).text);
      assertEquals(once.words.get(i).start, twice.words.get(i).start);
      assertEquals(once.words.get(i).end, twice.words.get(i).end);
    }
  }

  @Test public void twoLineCuesShownAgainAreAlsoCollapsed() throws Exception {
    JSONArray events = new JSONArray();
    long at = 0;
    for (int i = 0; i + 1 < LINES.length; i += 2) {
      for (int copy = 0; copy < 2; copy++) {
        events.put(cue(at, at + 900, LINES[i]));
        events.put(cue(at + 900, at + 1800, LINES[i + 1]));
        at += 1900;
      }
    }
    RebuildSource s = source(events);
    String text = s.text(0, s.words.size() - 1).replace(" ", "");
    for (String line : LINES) assertEquals(line, 1, count(text, line));
  }

  @Test public void aRepeatedSentenceInOrdinarySpeechIsKept() throws Exception {
    JSONArray events = new JSONArray();
    long at = 0;
    for (String line : LINES) {
      events.put(cue(at, at + 1800, line));
      at += 2000;
    }
    events.put(cue(at, at + 1800, "胡安加油"));
    events.put(cue(at + 1900, at + 3600, "胡安加油"));
    RebuildSource s = source(events);
    String text = s.text(0, s.words.size() - 1).replace(" ", "");
    assertEquals(3, count(text, "胡安加油"));
  }

  @Test public void sameStartEventsWithZeroWidthPaddingAreOneCue() throws Exception {
    JSONArray padded = new JSONArray(), single = new JSONArray();
    long at = 0;
    for (String line : LINES) {
      padded.put(cue(at, at + 2400, "​​​​" + line + "​​​"));
      padded.put(cue(at, at + 2400, line + "​​"));
      single.put(cue(at, at + 2400, line));
      at += 2600;
    }
    RebuildSource twice = source(padded), once = source(single);
    assertEquals(once.words.size(), twice.words.size());
    for (int i = 0; i < once.words.size(); i++) {
      assertEquals(once.words.get(i).text, twice.words.get(i).text);
      assertFalse(twice.words.get(i).text.contains("​"));
    }
  }

  /** First 160 events of the real tracks of plN7JMbadRg: every event twice, zero-width padded. */
  @Test public void theRealDoubledTracksReadOnceWithoutInvisibleTokens() throws Exception {
    for (String lang : new String[] {"zh-Hans", "en"}) {
      byte[] body = PlannerGoldenTest.resource("/r151/plN7JMbadRg." + lang + ".json3.gz");
      JSONArray events = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("events");
      assertEquals(160, events.length());
      RebuildSource s = RebuildSource.read(body, CaptionDocument.parse(body, "json3"));
      for (RebuildSource.Word w : s.words) assertFalse(lang, w.text.matches(".*[\u200b\u2060\ufeff].*"));
      // Each cue is spoken once: eighty distinct cues, each starting where its own display starts.
      Set<Long> starts = new HashSet<>();
      for (int i = 0; i < events.length(); i++) starts.add(events.getJSONObject(i).getLong("tStartMs"));
      assertEquals(80, starts.size());
      String text = s.text(0, s.words.size() - 1);
      String first = lang.equals("en") ? "I just bought this entire" : "我刚刚把这家超市买了下来";
      assertEquals(lang, 1, count(text.replace(" ", ""), first.replace(" ", "")));
      for (int i = 1; i < s.words.size(); i++) assertTrue(s.words.get(i).start >= s.words.get(i - 1).end);
    }
  }

  /** Ruby written as base(reading) is translated from its base text; a stray bracket stays. */
  @Test public void rubyReadingsAreDroppedForTranslationOnlyWhenTheTrackIsRuby() throws Exception {
    String[] lines = {"君(きみ)といた時(とき)は見(み)えた", "今(いま)は見(み)えなくなった", "透明(とうめい)な彗星(すいせい)を", "探(さが)している"};
    JSONArray events = new JSONArray();
    long at = 0;
    for (String line : lines) { events.put(cue(at, at + 1800, line)); at += 2000; }
    byte[] body = new JSONObject().put("events", events).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    RebuildSource shown = RebuildSource.read(body, CaptionDocument.parse(body, "json3"), false);
    RebuildSource translated = RebuildSource.read(body, CaptionDocument.parse(body, "json3"), true);
    assertTrue(shown.text(0, shown.words.size() - 1).contains("(きみ)"));
    String text = translated.text(0, translated.words.size() - 1).replace(" ", "");
    assertEquals("君といた時は見えた今は見えなくなった透明な彗星を探している", text);
    JSONArray one = new JSONArray().put(cue(0, 1800, "面白い(笑)")).put(cue(2000, 3800, "彼(かれ)は来た"));
    byte[] few = new JSONObject().put("events", one).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    RebuildSource kept = RebuildSource.read(few, CaptionDocument.parse(few, "json3"), true);
    assertTrue(kept.text(0, kept.words.size() - 1).contains("(かれ)"));
  }

  static int count(String text, String part) {
    int n = 0;
    for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
    return n;
  }
}
