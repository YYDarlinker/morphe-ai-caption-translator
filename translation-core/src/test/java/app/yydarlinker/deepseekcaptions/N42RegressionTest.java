package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * N42 regressions: numeric value identity, authored display groups, cue overflow projection,
 * time-balanced page turns and language-neutral quality gates.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class N42RegressionTest {
    private static final CaptionLanguageContext JA_ZH =
            CaptionLanguageContext.explicit("ja", "zh-Hans");

    private static RebuildSource source(String... cues) {
        List<RebuildSource.Word> words = new ArrayList<>();
        long at = 150_250;
        for (int cue = 0; cue < cues.length; cue++) {
            long end = at + Math.max(400, cues[cue].length() * 150L);
            for (String token : RebuildSource.tokens(cues[cue]))
                words.add(
                        new RebuildSource.Word(
                                token, at, end, cue, RebuildSource.Precision.NATIVE));
            at = end + 100;
        }
        return new RebuildSource(words);
    }

    private static RebuildSource auto(String text) {
        List<RebuildSource.Word> words = new ArrayList<>();
        long at = 0;
        for (String token : RebuildSource.tokens(text)) {
            words.add(new RebuildSource.Word(token, at, at + 300, 0, RebuildSource.Precision.NATIVE));
            at += 300;
        }
        return new RebuildSource(words);
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
    public void numericIdentityIsValueBasedAcrossNotation() {
        CaptionLanguageContext enZh = CaptionLanguageContext.explicit("en", "zh-Hans");
        assertEquals(
                RebuildNumbers.Result.EQUIVALENT,
                RebuildNumbers.compare("like 4 and a half times the price", "大约4.5倍的价格", enZh));
        assertEquals(
                RebuildNumbers.Result.EQUIVALENT,
                RebuildNumbers.compare("one and a half hours", "1.5小时", enZh));
        assertEquals(
                RebuildNumbers.Result.EQUIVALENT,
                RebuildNumbers.compare("10000 units", "1万件", enZh));
        assertEquals(
                RebuildNumbers.Result.CONTRADICTED,
                RebuildNumbers.compare("there are 24 ships", "有25艘船", enZh));
        assertTrue(RebuildProtocol.numbersSafe("10000 units", "1万件"));
    }

    @Test
    public void authoredFragmentCuesBecomeOneDisplayGroup() throws Exception {
        RebuildSource s =
                source(
                        "が",
                        "しかし",
                        "もう一点注目すべきはサイト内のこの表記",
                        "「AppleUnsoldはAppleと提携していない」");
        RebuildPlanner.Block b = RebuildPlanner.planAuthored(s, JA_ZH).get(0);
        List<RebuildPlanner.Block.CueRange> groups = RebuildCueContract.groups(s, b);
        assertEquals("が + しかし + the next cue are one page", 2, groups.size());
        assertEquals(0, groups.get(0).from);
        assertEquals(b.cues.get(2).to, groups.get(0).to);

        JSONArray events =
                new JSONArray()
                        .put(event(s, 0, b.cues.get(1).to, "但然而"))
                        .put(event(s, b.cues.get(2).from, b.cues.get(2).to, "另一点值得关注的是网站内的这个标注"))
                        .put(event(s, b.cues.get(3).from, b.cues.get(3).to, "“AppleUnsold没有与Apple合作”"));
        try {
            RebuildProtocol.Plan plan = RebuildProtocol.parseBound(reply(b, events), s, b, JA_ZH);
            assertEquals(2, RebuildCueContract.units(0, s, b, plan.events, JA_ZH).size());
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
    }

    @Test
    public void anEventThatCrossesADisplayGroupIsPublishedAsAnEventPage() throws Exception {
        RebuildSource s =
                source(
                        "最近のニュースを見ました",
                        "天気がいいので散歩します",
                        "始めよう");
        RebuildPlanner.Block b = RebuildPlanner.planAuthored(s, JA_ZH).get(0);
        JSONArray events =
                new JSONArray()
                        .put(
                                event(
                                        s,
                                        0,
                                        b.cues.get(1).to,
                                        "我把两个独立的显示组并成了一个事件"));
        RebuildProtocol.Plan plan =
                RebuildProtocol.parseBound(reply(b, events), s, b, JA_ZH);
        RebuildCueProjection.Result projection =
                RebuildCueProjection.project(0, s, b, plan.events, JA_ZH);
        assertEquals("no display group is exactly tiled", 0, projection.cuePages);
        assertEquals("the sentence becomes one event page", 1, projection.eventPages);
        assertEquals("我把两个独立的显示组并成了一个事件", projection.units.get(0).text);
    }

    @Test
    public void anOverflowingAuthoredCueBecomesSubPagesNotBlankOrTruncated() {
        String text = "这个网站专门经营官方未发售的苹果原装配件，而且价格非常便宜，但是货源并不稳定。";
        CaptionRenderSpec spec = JA_ZH.renderSpec.withAuthored();
        List<RebuildPageLayout.Page> pages =
                RebuildPageLayout.unitPages(
                        text,
                        0,
                        9000,
                        value -> value.codePointCount(0, value.length()) <= 8,
                        value -> value.codePointCount(0, value.length()) <= 16,
                        spec);
        assertTrue("a cue wider than two lines must project sub-pages", pages.size() > 1);
        assertEquals("AUTHORED_OVERFLOW", pages.get(0).timingBasis);
        int at = 0;
        long time = 0;
        for (RebuildPageLayout.Page page : pages) {
            assertTrue(text.startsWith(page.text, at));
            at += page.text.length();
            assertTrue(
                    "sub-page does not fit the two-line box: " + page.text,
                    page.text.codePointCount(0, page.text.length()) <= 16);
            assertEquals(time, page.start);
            time = page.end;
        }
        assertEquals(text.length(), at);
        assertEquals(9000, time);
    }

    @Test
    public void aLongFittingPageIsSplitOnWholeClauses() {
        String text = "这类文章获得了数百万的浏览量和关注，它们塑造了公众舆论。";
        CaptionLanguageContext zh = CaptionLanguageContext.explicit("en", "zh-Hans");
        TranslationBudget budget = new TranslationBudget(1600, 44.5f, 48.7f, zh.renderSpec);
        List<RebuildPageLayout.Page> pages =
                RebuildPageLayout.plan(text, 0, 7000, budget, zh.renderSpec);
        assertEquals("a 7 s, two-clause page should turn once", 2, pages.size());
        CaptionClauses clauses = CaptionClauses.of(text);
        int at = 0;
        for (RebuildPageLayout.Page page : pages) {
            assertTrue(text.startsWith(page.text, at));
            assertTrue(clauses.allows(at, at + page.text.length()));
            at += page.text.length();
        }
        assertEquals(text.length(), at);
        assertEquals(7000, pages.get(pages.size() - 1).end);
    }

    @Test
    public void everyTargetLanguageUsesTheSameMeasuredPageRules() {
        String[] codes = {"en", "ja", "ko", "ar", "th", "hi", "ru", "fr"};
        String[] texts = {
            "If the product did not arrive, that is clearly fraud; we will investigate the shop.",
            "製品が届かなければ、それは明らかに詐欺です。私たちは店を調査します。",
            "제품이 도착하지 않았다면 그것은 분명한 사기입니다. 우리는 가게를 조사할 것입니다.",
            "إذا لم يصل المنتج، فهذا احتيال واضح؛ سوف نحقق في المتجر.",
            "หากสินค้าไม่มาถึง นั่นคือการฉ้อโกงอย่างชัดเจน เราจะตรวจสอบร้านค้า",
            "अगर उत्पाद नहीं आया, तो यह स्पष्ट धोखाधड़ी है; हम दुकान की जांच करेंगे।",
            "Если товар не пришёл, это явное мошенничество; мы проверим магазин.",
            "Si le produit n'est pas arrivé, c'est clairement une fraude ; nous enquêterons."
        };
        for (int i = 0; i < codes.length; i++) {
            CaptionLanguageContext context = CaptionLanguageContext.explicit("en", codes[i]);
            CaptionRenderSpec spec = context.renderSpec;
            TranslationBudget budget = new TranslationBudget(300, 32f, 40f, spec);
            List<RebuildPageLayout.Page> pages =
                    RebuildPageLayout.plan(texts[i], 0, 9000, budget, spec);
            assertFalse(codes[i] + ": no page", pages.isEmpty());
            StringBuilder joined = new StringBuilder();
            for (RebuildPageLayout.Page page : pages) {
                assertTrue(codes[i] + ": page does not fit", spec.fits(page.text, 40f, 300, 2));
                joined.append(page.text);
            }
            assertEquals(codes[i] + ": pages must cover the text", texts[i], joined.toString());
            assertEquals(codes[i] + ": page windows must cover the event", 9000, pages.get(pages.size() - 1).end);
        }
        assertTrue(CaptionScript.compact(java.util.Locale.forLanguageTag("ja")));
        assertTrue(CaptionScript.compact(java.util.Locale.forLanguageTag("th")));
        assertFalse(CaptionScript.compact(java.util.Locale.forLanguageTag("en")));
        assertTrue(CaptionScript.requiresSpace('A', 'B'));
        assertFalse(CaptionScript.requiresSpace('中', '文'));
        assertTrue(CaptionScript.requiresSpace('中', 'A'));
    }

    @Test
    public void streamedAuthoredPrefixShowsCompletedGroupsImmediately() throws Exception {
        RebuildSource s =
                source(
                        "最近のニュースを見ました",
                        "天気がいいので散歩します",
                        "始めよう");
        RebuildPlanner.Block b = RebuildPlanner.planAuthored(s, JA_ZH).get(0);
        JSONArray prefix =
                new JSONArray()
                        .put(
                                event(
                                        s,
                                        b.cues.get(0).from,
                                        b.cues.get(0).to,
                                        "我最近看了新闻"));
        RebuildProtocol.Plan partial = RebuildProtocol.parsePrefix(reply(b, prefix), s, b, JA_ZH);
        List<RebuildDisplayGroups.Unit> units =
                RebuildCueContract.partialUnits(0, s, b, partial.events, JA_ZH);
        assertEquals("only the complete first group may show", 1, units.size());
        assertEquals(b.cues.get(0).start, units.get(0).start);
        assertEquals(b.cues.get(0).end, units.get(0).end);
    }

    @Test
    public void japaneseToChineseGetsTheSameQualitySignals() throws Exception {
        RebuildSource s =
                auto("これは重要な問題ですが、次の話に進みます");
        RebuildPlanner.Block b = new RebuildPlanner.Block(0, 0, s.words.size() - 1, s);
        int particle = -1;
        for (int i = 0; i + 1 < s.words.size(); i++)
            if (s.words.get(i).text.startsWith("が")) {
                particle = i;
                break;
            }
        assertTrue("fixture must contain a Japanese dependent particle", particle > 0);
        List<RebuildReview.Issue> issues =
                RebuildReview.inspect(
                        s,
                        b,
                        List.of(
                                new RebuildProtocol.Event(
                                        0, particle, s.words.get(0).start, s.words.get(particle).end, "这是一个重要问题。")),
                        JA_ZH);
        assertFalse("ending a source clause on 「が」 must be visible", issues.isEmpty());

        CaptionLanguageContext enAr = CaptionLanguageContext.explicit("en", "ar");
        try {
            RebuildSemantics.validateTarget("the product did not arrive", "the product did not arrive", enAr);
            fail("Latin text accepted for an Arabic target");
        } catch (RebuildProtocol.Invalid expected) {
            assertEquals("target_language_mismatch", expected.code);
        }
    }
}
