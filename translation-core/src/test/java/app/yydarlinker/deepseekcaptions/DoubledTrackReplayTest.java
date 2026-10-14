package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** The real doubled, zero-width-padded tracks of one video (each event present twice). */
public class DoubledTrackReplayTest {
  static RebuildSource read(String lang) throws Exception {
    return PlannerGoldenTest.read("/r3/plN7JMbadRg." + lang + ".json3.gz", "application/json");
  }

  @Test public void chineseTrackIsReadOnceWithoutInvisibleCharacters() throws Exception {
    RebuildSource s = read("zh-Hans");
    for (RebuildSource.Word w : s.words) assertFalse(w.text.contains("\u200b"));
    String text = s.text(0, s.words.size() - 1).replace(" ", "");
    for (String sentence : new String[] {"你们四位要尝试一项前所未有的挑战", "选个货架开始大吃特吃吧", "我从没吃过但就算不好吃"}) {
      int at = text.indexOf(sentence);
      assertTrue(sentence, at >= 0);
      assertEquals(sentence, -1, text.indexOf(sentence + sentence));
    }
    List<RebuildPlanner.Block> blocks = RebuildPlanner.plan(s, CaptionLanguageContext.explicit("zh-Hans", "zh-Hans"));
    assertFalse(blocks.isEmpty());
  }

  @Test public void englishTrackIsReadOnce() throws Exception {
    RebuildSource s = read("en");
    for (RebuildSource.Word w : s.words) assertFalse(w.text.contains("\u200b"));
    String text = s.text(0, s.words.size() - 1);
    assertFalse(text.contains("I just I just"));
  }
}
