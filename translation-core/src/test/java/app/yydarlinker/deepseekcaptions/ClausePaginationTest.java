package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Page turns are clause-aware: a long clause is split alone, never appended to the prior one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ClausePaginationTest {
  static final String ZH =
      "如果订购的产品没收到，那显然是诈骗；但即便真的收到了，是否就万事大吉呢？"
          + "无论结果如何，让我们一探这个地下网购网站的真实面目。";

  static void assertClausePages(String text, List<RebuildPageLayout.Page> pages) {
    assertFalse("a complete translation must always produce a page", pages.isEmpty());
    CaptionClauses clauses = CaptionClauses.of(text);
    int at = 0;
    for (RebuildPageLayout.Page page : pages) {
      assertTrue("pages must be a contiguous prefix partition", text.startsWith(page.text, at));
      int to = at + page.text.length();
      assertTrue(
          "page mixes a previous clause with a cut tail: " + page.text,
          clauses.allows(at, to));
      assertTrue(page.end > page.start);
      at = to;
    }
    assertEquals(text.length(), at);
  }

  @Test
  public void chineseLongClauseIsSplitWithoutMixingItWithThePriorClause() {
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    TranslationBudget budget = new TranslationBudget(190, 40f, 50f, spec);
    assertClausePages(ZH, RebuildPageLayout.plan(ZH, 0, 12000, budget, spec));
  }

  @Test
  public void spacedScriptsObeyTheSameClauseRule() {
    String text =
        "If the product did not arrive, that is clearly fraud; but even if it did, is everything"
            + " really fine? We will investigate this underground shop and its true nature.";
    CaptionRenderSpec spec =
        new CaptionRenderSpec("en", CaptionLanguageProfile.fromCode("en"), false);
    TranslationBudget budget = new TranslationBudget(220, 32f, 40f, spec);
    assertClausePages(text, CaptionLanguagePager.plan(text, 0, 12000, budget, spec));
  }

  @Test
  public void aShortWindowStillShowsTheWholeTranslation() {
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    TranslationBudget budget = new TranslationBudget(190, 40f, 50f, spec);
    List<RebuildPageLayout.Page> pages = RebuildPageLayout.plan(ZH, 0, 150, budget, spec);
    assertClausePages(ZH, pages);
  }

  @Test
  public void malformedProviderTextStillYieldsAVisiblePage() {
    CaptionPresentationBudget budget =
        new CaptionPresentationBudget(190, 40f, 50f, CaptionRenderSpec.LEGACY);
    List<CaptionDisplayPage> pages =
        TranslationPresentation.pages("\uD800bad", 0, 1000, budget, CaptionRenderSpec.LEGACY);
    assertFalse("a malformed but non-empty translation must not blank the caption", pages.isEmpty());
    assertEquals(0, pages.get(0).fromUtf16);
    assertEquals(4, pages.get(0).toUtf16);
  }

  /**
   * A page is a run of whole clauses that fits one line; the next clause starts the next page.
   * The line fits are predicates here, because Robolectric never wraps text: a width of 17 means
   * "seventeen characters per line".
   */
  @Test
  public void pagesAreRunsOfWholeClausesThatFitOneLine() {
    String text =
        "它之所以引发热议，原因非常简单：这个网站如其名，专门经营“官方未销售的苹果产品”，"
            + "这概念怎么看都透着深深的黑暗感。这到底是怎么回事呢？";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(
            text, 0, 14709, value -> len(value) <= 17, value -> len(value) <= 34, spec);
    assertTrue("a 17-character line cannot hold this block", pages.size() > 1);
    assertClausePages(text, pages);
    long total = 0;
    for (RebuildPageLayout.Page page : pages) {
      total += page.end - page.start;
      String visible = page.text.trim();
      assertTrue(
          "a page must end on a clause mark: |" + page.text + "|",
          "，。！？；：,!?;:".indexOf(visible.charAt(visible.length() - 1)) >= 0);
      assertTrue(
          "a two-line page must hold exactly one over-long clause: |" + page.text + "|",
          len(page.text) <= 17 || countClauseMarks(page.text) <= 1);
    }
    assertEquals("pages must cover the event exactly", 14709, total);
  }

  /** A model that omits the final full stop must not move any page boundary. */
  @Test
  public void aMissingFullStopDoesNotChangeThePageBoundaries() {
    String withStop = "这个网站如其名，专门经营官方未销售的苹果产品，这概念怎么看都透着黑暗。";
    String withoutStop = withStop.substring(0, withStop.length() - 1);
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> a =
        RebuildPageLayout.unitPages(withStop, 0, 9000, v -> len(v) <= 16, v -> len(v) <= 32, spec);
    List<RebuildPageLayout.Page> b =
        RebuildPageLayout.unitPages(withoutStop, 0, 9000, v -> len(v) <= 16, v -> len(v) <= 32, spec);
    assertEquals("the trailing full stop must not decide the page count", a.size(), b.size());
    for (int i = 0; i + 1 < a.size(); i++)
      assertEquals(
          "a page boundary moved because a full stop was missing", a.get(i).text, b.get(i).text);
  }

  /**
   * An unterminated tail is just the last clause: it is never dropped, and no page ends mid-clause
   * unless that page is the single clause the box cannot fit on one line.
   */
  @Test
  public void anUnterminatedTailIsShownAsItsOwnLastClause() {
    String text = "这个网站如其名，专门经营官方未销售的苹果产品";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(text, 0, 6000, v -> len(v) <= 12, v -> len(v) <= 24, spec);
    assertFalse(pages.isEmpty());
    assertTrue(
        "the unterminated clause must stay whole at the end: |"
            + pages.get(pages.size() - 1).text
            + "|",
        pages.get(pages.size() - 1).text.trim().endsWith("专门经营官方未销售的苹果产品"));
    int at = 0;
    for (RebuildPageLayout.Page page : pages) {
      assertTrue(text.startsWith(page.text, at));
      at += page.text.length();
      assertTrue(
          "page ends mid-clause: |" + page.text + "|",
          len(page.text) <= 12
              || at == text.length()
              || "，。！？；：,!?;:"
                      .indexOf(page.text.trim().charAt(page.text.trim().length() - 1))
                  >= 0);
    }
    assertEquals(text.length(), at);
  }

  private static int len(String text) {
    return text.codePointCount(0, text.length());
  }

  /**
   * A clause that introduces the next one ("理由非常简单：", "正如其名所示，") never ends a page: it
   * would leave the reader with a lead and no content.
   */
  @Test
  public void aLeadInClauseNeverEndsAPage() {
    String text =
        "它之所以引起关注，理由非常简单：正如其名所示，该网站专门经营“官方未销售的苹果产品”，"
            + "这概念怎么看都黑得离谱。";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(text, 0, 12000, v -> len(v) <= 17, v -> len(v) <= 34, spec);
    assertTrue(pages.size() > 1);
    for (int i = 0; i + 1 < pages.size(); i++) {
      String page = pages.get(i).text.trim();
      String clause = page.substring(Math.max(0, page.lastIndexOf('，') + 1));
      assertFalse(
          "page ends on a lead-in clause: |" + page + "|",
          page.endsWith("：") || (clause.endsWith("，") && len(clause) <= 8));
    }
    assertClausePages(text, pages);
  }

  /**
   * When the model emits fragments without punctuation, the invisible join between its events is
   * still a page boundary: a page may end there, so two fragments never merge into one unbroken
   * run and no fragment is shown half with the next one.
   */
  @Test
  public void anUnpunctuatedEventJoinIsAValidPageBoundary() {
    String a = "互联网上几乎充斥着各种文章和排行榜";
    String b = "它们都信誓旦旦地把俄罗斯排在第二";
    String c = "甚至还有少数把俄罗斯排在第一位";
    String text = a + "\u200b" + b + "\u200b" + c;
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(text, 0, 16840, v -> len(v) <= 17, v -> len(v) <= 34, spec);
    assertEquals("each unpunctuated fragment is its own page", 3, pages.size());
    assertEquals(a + "\u200b", pages.get(0).text);
    assertEquals(b + "\u200b", pages.get(1).text);
    assertEquals(c, pages.get(2).text);
  }
  /** A trailing fragment of a sentence the model cut stays with the page before it. */
  @Test
  public void aShortDanglingTailDoesNotGetItsOwnPage() {
    String text = "我之前曾提到过军事战略是建立在战略之上的，所以与其直接谈论中国拥有的技术，我想谈谈";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(text, 0, 10025, v -> len(v) <= 17, v -> len(v) <= 34, spec);
    for (RebuildPageLayout.Page page : pages)
      assertTrue(
          "a dangling tail became its own page: |" + page.text + "|",
          len(page.text.trim()) >= 10 || "，。！？；：,!?;:".indexOf(page.text.trim().charAt(page.text.trim().length() - 1)) >= 0);
  }

  private static int countClauseMarks(String text) {
    int n = 0;
    for (int i = 0; i < text.length(); i++)
      if ("，。！？；：,!?;:".indexOf(text.charAt(i)) >= 0) n++;
    return n;
  }

  /**
   * One long clause with no clause mark inside it has no place a reader could follow a page turn,
   * so it gets the two-line exception even when it is far past the recommended wall time. A
   * duration alone is never a reason to cut a clause open.
   */
  @Test
  public void aLongUnbrokenClauseStaysOneTwoLinePage() {
    String text = "这个网站专门经营官方尚未发售的苹果原装配件";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(text, 0, 9600, v -> len(v) <= 12, v -> len(v) <= 24, spec);
    assertEquals("no clause mark inside the clause: one page", 1, pages.size());
    assertEquals(text, pages.get(0).text);
    assertEquals(0, pages.get(0).start);
    assertEquals(9600, pages.get(0).end);
  }

  /** A two- or three-character marker clause never gets a page of its own. */
  @Test
  public void aBareDiscourseMarkerIsNeverAPage() {
    String text = "当然，现在有很多不同的声音存在，而且还会越来越多。";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    List<RebuildPageLayout.Page> pages =
        RebuildPageLayout.unitPages(text, 0, 7000, v -> len(v) <= 12, v -> len(v) <= 24, spec);
    for (RebuildPageLayout.Page page : pages)
      assertTrue("a bare marker got its own page: |" + page.text + "|", len(page.text.trim()) >= 4);
  }

  /** A quotation's own comma is never a licence to split the quoted words across two pages. */
  @Test
  public void aQuotedSpanIsNeverSplitBetweenTwoPages() {
    String text = "“官方未销售的苹果产品，需求非常高却并未销售”这句话很值得注意，值得反复推敲，再仔细看看。";
    CaptionRenderSpec spec = CaptionRenderSpec.LEGACY;
    TranslationBudget budget = new TranslationBudget(400, 40f, 48.7f, spec);
    List<RebuildPageLayout.Page> pages = RebuildPageLayout.plan(text, 0, 9000, budget, spec);
    assertClausePages(text, pages);
    CaptionClauses clauses = CaptionClauses.of(text);
    int at = 0;
    for (RebuildPageLayout.Page page : pages) {
      int to = at + page.text.length();
      assertFalse("page starts inside a quotation: " + page.text, clauses.insideQuoted(at));
      assertFalse("page ends inside a quotation: " + page.text, clauses.insideQuoted(to));
      at = to;
    }
  }
}
