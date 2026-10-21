package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * N51: a translated reply is displayed even when its event boundaries disagree with the author's
 * cue boundaries. The cue is the preferred page; the event is the guarantee.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class N51AuthoredProjectionTest {
    private static final CaptionLanguageContext JA_ZH =
            CaptionLanguageContext.explicit("ja", "zh-Hans");
    private static final TranslationSettings CFG =
            new TranslationSettings(
                    true,
                    "https://example.invalid/v1",
                    "fixture-model",
                    "faithful",
                    "fixture-key",
                    "stored_custom");

    /** The four cues of block b4_268_374 in caption-diagnostics-1.5.4-20261020-173353. */
    private static final String[] N51_CUES = {
        "国内・海外ともにまだほぼ上がっていない",
        "というわけで、これはもう一番乗りで自爆しに行くほか無いですよね♡",
        "これまで様々な通販サイトにはびこる数多の闇を暴き出してきたうちの投稿者が",
        "この圧倒的な闇に飛び込まずにいられるはずがない",
    };

    /** The three events of request 89: token ranges shifted, exact translation text. */
    private static final int[][] N51_EVENTS = {{0, 23}, {24, 47}, {48, 106}};
    private static final String[] N51_TEXTS = {
        "无论是国内还是海外，热度都几乎还没起来，所以，",
        "除了抢先去“自爆”之外别无选择了吧♡",
        "此前揭露过各种电商平台上滋生的无数黑暗面，我们这位博主怎么可能不投身于这压倒性的黑暗之中呢。",
    };

    private static RebuildSource source(long cueGapMs, String... cues) {
        List<RebuildSource.Word> words = new ArrayList<>();
        long at = 1_000;
        for (int cue = 0; cue < cues.length; cue++) {
            if (cue > 0) at += cueGapMs;
            for (String token : RebuildSource.tokens(cues[cue])) {
                words.add(
                        new RebuildSource.Word(
                                token, at, at + 300, cue, RebuildSource.Precision.NATIVE));
                at += 300;
            }
        }
        return new RebuildSource(words);
    }

    private static RebuildPlanner.Block block(RebuildSource s) {
        // The projection is per request block; build the block the planner produces for these
        // consecutive cues directly so the fixture does not depend on block-packing thresholds.
        int last = s.words.size() - 1;
        return new RebuildPlanner.Block(0, 0, last, s, RebuildPlanner.cueRanges(s, 0, last));
    }

    private static JSONObject event(RebuildSource s, int from, int to, String text)
            throws Exception {
        return new JSONObject()
                .put("from", from)
                .put("to", to)
                .put("source", s.text(from, to))
                .put("text", text);
    }

    private static String reply(RebuildPlanner.Block b, JSONArray events) throws Exception {
        return new JSONObject().put("block", b.id()).put("events", events).toString();
    }

    @Test
    public void theCapturedN51ReplyIsDisplayedInsteadOfWithheld() throws Exception {
        RebuildSource s = source(0, N51_CUES);
        RebuildPlanner.Block b = block(s);
        assertEquals(4, b.cues.size());
        // The diagnostic's cue 0 boundary: tokens 0-17.
        assertEquals(0, b.cues.get(0).from);
        assertEquals(17, b.cues.get(0).to);

        JSONArray events = new JSONArray();
        for (int i = 0; i < N51_EVENTS.length; i++)
            events.put(
                    event(
                            s,
                            N51_EVENTS[i][0],
                            N51_EVENTS[i][1],
                            N51_TEXTS[i]));

        // Before N51 this threw authored_cue_boundary and the whole 14.5 s block stayed blank.
        RebuildProtocol.Plan plan = RebuildProtocol.parseBestEffort(reply(b, events), s, b, JA_ZH);
        assertEquals(3, plan.events.size());

        RebuildCueProjection.Result projection =
                RebuildCueProjection.project(0, s, b, plan.events, JA_ZH);
        assertEquals(4, projection.groups);
        assertEquals("no cue is exactly tiled", 0, projection.cuePages);
        assertEquals("the three sentences become three pages", 3, projection.eventPages);
        assertEquals(3, projection.units.size());
        assertEquals(N51_TEXTS[0], projection.units.get(0).text);
        assertEquals(N51_TEXTS[1], projection.units.get(1).text);
        assertEquals(N51_TEXTS[2], projection.units.get(2).text);

        // Every token of every cue is on screen, exactly once.
        int[] seen = new int[s.words.size()];
        for (RebuildDisplayGroups.Unit unit : projection.units)
            for (int token = unit.from; token <= unit.to; token++) seen[token]++;
        for (int i = 0; i < seen.length; i++) {
            assertEquals("token " + i + " must be displayed exactly once", 1, seen[i]);
        }
        for (RebuildPlanner.Block.CueRange cue : b.cues) {
            boolean covered = false;
            for (RebuildDisplayGroups.Unit unit : projection.units)
                if (unit.from <= cue.from && unit.to >= cue.to) covered = true;
            if (!covered) {
                // A cue split between two event pages is still covered token by token.
                for (int token = cue.from; token <= cue.to; token++)
                    assertTrue("cue token " + token + " uncovered", seen[token] == 1);
            }
        }
        // Pages never overlap and never go backwards.
        for (int i = 0; i + 1 < projection.units.size(); i++)
            assertTrue(
                    "pages must be ordered and disjoint",
                    projection.units.get(i).end <= projection.units.get(i + 1).start);
    }

    @Test
    public void anExactlyAlignedReplyKeepsTheAuthorsCuePages() throws Exception {
        RebuildSource s = source(0, N51_CUES);
        RebuildPlanner.Block b = block(s);
        JSONArray events =
                new JSONArray()
                        .put(event(s, b.cues.get(0).from, b.cues.get(0).to, "第一页"))
                        .put(event(s, b.cues.get(1).from, b.cues.get(1).to, "第二页"))
                        .put(event(s, b.cues.get(2).from, b.cues.get(2).to, "第三页"))
                        .put(event(s, b.cues.get(3).from, b.cues.get(3).to, "第四页"));
        RebuildProtocol.Plan plan = RebuildProtocol.parseBestEffort(reply(b, events), s, b, JA_ZH);
        RebuildCueProjection.Result projection =
                RebuildCueProjection.project(0, s, b, plan.events, JA_ZH);
        assertEquals(4, projection.cuePages);
        assertEquals(0, projection.eventPages);
        assertEquals(4, projection.units.size());
        for (int i = 0; i < 4; i++) {
            RebuildPlanner.Block.CueRange cue = b.cues.get(i);
            assertEquals(cue.start, projection.units.get(i).start);
            assertEquals(cue.end, projection.units.get(i).end);
        }
    }

    @Test
    public void aCueGapShorterThanTheHoldIsHeldInsteadOfBlanked() throws Exception {
        RebuildSource s = source(500, "最初の一言", "次の一言");
        RebuildPlanner.Block b = block(s);
        JSONArray events =
                new JSONArray()
                        .put(event(s, b.cues.get(0).from, b.cues.get(0).to, "第一页"))
                        .put(event(s, b.cues.get(1).from, b.cues.get(1).to, "第二页"));
        RebuildProtocol.Plan plan = RebuildProtocol.parseBestEffort(reply(b, events), s, b, JA_ZH);
        RebuildCueProjection.Result projection =
                RebuildCueProjection.project(0, s, b, plan.events, JA_ZH);
        assertEquals(2, projection.units.size());
        assertEquals(
                "a 500 ms cue gap must not clear the box",
                projection.units.get(1).start,
                projection.units.get(0).end);
    }

    @Test
    public void theAuthoredPromptPrefersCuesButAllowsASentenceToSpanOne() {
        RebuildSource s = source(0, N51_CUES);
        RebuildPlanner.Block b = block(s);
        String prompt = RebuildApi.prompt(CFG, "zh-Hans", JA_ZH, b);
        assertTrue(prompt.contains("preferred page boundaries"));
        assertTrue(prompt.contains("an event may span the boundary"));
        assertTrue(prompt.contains(RebuildGuidance.SENTENCE));
        assertFalse(prompt.contains("Every event must lie inside exactly one group"));
    }

    /**
     * The guarantee across models: whatever segmentation a reply uses, every translated token is
     * published exactly once, pages stay ordered and disjoint, and no cue's text is withheld.
     */
    @Test
    public void anySegmentationPublishesEveryTranslatedTokenExactlyOnce() {
        Random random = new Random(51);
        String[] kana = {
            "あ", "い", "う", "え", "お", "か", "き", "く", "け", "こ", "さ", "し", "す", "せ",
            "そ", "た", "ち", "つ", "て", "と", "な", "に", "ぬ", "ね", "の",
        };
        for (int round = 0; round < 300; round++) {
            int cueCount = 1 + random.nextInt(4);
            List<RebuildSource.Word> words = new ArrayList<>();
            List<Integer> cueEnds = new ArrayList<>();
            int token = 0;
            long at = 1_000;
            for (int cue = 0; cue < cueCount; cue++) {
                int length = 3 + random.nextInt(10);
                if (cue > 0) at += random.nextInt(400);
                for (int k = 0; k < length; k++) {
                    String text = kana[(token * 7 + cue) % kana.length];
                    words.add(
                            new RebuildSource.Word(
                                    text, at, at + 300, cue, RebuildSource.Precision.NATIVE));
                    at += 300;
                    token++;
                }
                cueEnds.add(token - 1);
            }
            RebuildSource s = new RebuildSource(words);
            RebuildPlanner.Block b =
                    new RebuildPlanner.Block(
                            0, 0, token - 1, s, RebuildPlanner.cueRanges(s, 0, token - 1));

            int cuts = 1 + random.nextInt(6);
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 1; i < cuts; i++) {
                int cut = 1 + random.nextInt(token - 1);
                if (!starts.contains(cut)) starts.add(cut);
            }
            Collections.sort(starts);
            List<RebuildProtocol.Event> events = new ArrayList<>();
            for (int i = 0; i < starts.size(); i++) {
                int from = starts.get(i);
                int to = i + 1 < starts.size() ? starts.get(i + 1) - 1 : token - 1;
                events.add(
                        new RebuildProtocol.Event(
                                from,
                                to,
                                s.words.get(from).start,
                                s.words.get(to).end,
                                "译文" + i));
            }

            RebuildCueProjection.Result projection =
                    RebuildCueProjection.project(0, s, b, events, JA_ZH);
            assertEquals(
                    "cue pages plus event pages are all pages",
                    projection.units.size(),
                    projection.cuePages + projection.eventPages);
            int[] seen = new int[token];
            for (RebuildDisplayGroups.Unit unit : projection.units) {
                assertFalse("a page must carry text", unit.text.isEmpty());
                for (int t = unit.from; t <= unit.to; t++) seen[t]++;
            }
            for (int t = 0; t < token; t++)
                assertEquals("round " + round + " token " + t, 1, seen[t]);
            for (int i = 0; i + 1 < projection.units.size(); i++)
                assertTrue(
                        "round " + round + " pages must be ordered",
                        projection.units.get(i).end <= projection.units.get(i + 1).start);
            assertTrue(
                    "the last cue must end inside the last page",
                    projection.units.get(projection.units.size() - 1).end >= b.cues.get(cueCount - 1).end
                            || projection.units.get(projection.units.size() - 1).to
                                    == token - 1);
        }
    }
}
