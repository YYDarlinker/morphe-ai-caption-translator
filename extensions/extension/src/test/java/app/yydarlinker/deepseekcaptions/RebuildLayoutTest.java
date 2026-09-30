package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Native Android text measurement with controlled player bounds; not a real YouTube screenshot. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 28,
    shadows = {RebuildLayoutTest.Geometry.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class RebuildLayoutTest {
  static Rect bounds;
  static boolean shorts;
  Activity a;

  @Implements(CaptionSurface.class)
  public static class Geometry {
    @Implementation
    public static boolean isShorts() {
      return shorts;
    }

    @Implementation
    public static View refresh() {
      return null;
    }

    @Implementation
    public static Rect videoBounds(View h) {
      return bounds == null ? null : new Rect(bounds);
    }
  }

  @Before
  public void setup() {
    shorts = false;
    bounds = new Rect(0, 0, 600, 340);
    a = Robolectric.buildActivity(Activity.class).setup().visible().get();
    a.getResources().getDisplayMetrics().widthPixels = 1264;
    a.getResources().getDisplayMetrics().heightPixels = 2736;
    DeepSeekConfig.saveCaptionSizeTier(a, 2);
    CaptionOverlay.clear();
    CaptionOverlay.setActivity(a);
  }

  @After
  public void done() {
    CaptionOverlay.clear();
    CaptionOverlay.setActivity(null);
    a.finish();
  }

  static Object field(String name) throws Exception {
    Field f = CaptionOverlay.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(null);
  }

  TextView text() throws Exception {
    return (TextView) ((WeakReference<?>) field("textRef")).get();
  }

  FrameLayout anchor() throws Exception {
    return (FrameLayout) ((WeakReference<?>) field("anchorRef")).get();
  }

  @Test
  public void shortEventIsOneReadableLine() throws Exception {
    CaptionOverlay.showCaption("这是完整的一句。", () -> true);
    assertEquals("这是完整的一句。", text().getText().toString());
    assertEquals(1, CaptionOverlay.lines(a, text().getText().toString(), 18, 550));
    assertEquals(2, text().getMaxLines());
  }

  @Test
  public void longEventFallsBackWithoutTailCropping() throws Exception {
    bounds = new Rect(0, 0, 240, 400);
    String longText = String.join("", java.util.Collections.nCopies(120, "字"));
    CaptionOverlay.showCaption(longText, () -> true, () -> "[Original] source");
    assertEquals("[Original] source", text().getText().toString());
    assertEquals(longText, field("pendingText"));
    float minimum = SubtitleStyleMetrics.textSizePxForGlyphHeight(text().getPaint(),
        SubtitleStyleMetrics.targetGlyphHeightPx(0,1264,false));
    assertTrue(text().getTextSize() >= minimum);
  }

  @Test
  public void oversizedOriginalAlsoUsesExplicitStatus() throws Exception {
    bounds = new Rect(0, 0, 200, 400);
    String text = String.join("", java.util.Collections.nCopies(200, "字"));
    CaptionOverlay.showCaption(text, () -> true, () -> text);
    assertNotEquals(text, text().getText().toString());
    assertTrue(
        text().getText().toString().contains("Caption") || text().getText().toString().equals("…"));
  }

  @Test
  public void layoutDoesNotInsertHardLineBreaksIntoTranslation() throws Exception {
    String caption =
        "A complete thought remains intact while the renderer selects its visual wrapping.";
    CaptionOverlay.showCaption(caption, () -> true);
    assertEquals(caption, field("pendingText"));
    assertFalse(field("pendingText").toString().contains("\n"));
  }

  @Test
  public void viewRemainsInsideActualVideoBounds() throws Exception {
    bounds = new Rect(20, 30, 380, 250);
    CaptionOverlay.showCaption("字幕", () -> true);
    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) anchor().getLayoutParams();
    assertTrue(p.leftMargin >= 20);
    assertTrue(p.leftMargin + p.width <= 380);
    assertTrue(p.topMargin >= 30);
    assertTrue(p.topMargin + p.height <= 250);
  }

  @Test
  public void missingGeometryHidesRatherThanCoveringComments() throws Exception {
    CaptionOverlay.showCaption("字幕", () -> true);
    assertEquals(View.VISIBLE, anchor().getVisibility());
    bounds = null;
    CaptionOverlay.refreshStyle(a);
    assertEquals(View.GONE, anchor().getVisibility());
  }

  @Test
  public void expiredSessionGuardHidesEvenWithoutNewCaption() throws Exception {
    boolean[] valid = {true};
    CaptionOverlay.showCaption("旧视频", () -> valid[0]);
    valid[0] = false;
    CaptionOverlay.refreshStyle(a);
    assertEquals(View.GONE, anchor().getVisibility());
  }

  @Test
  public void shortsCanRecoverAfterHiddenMiniplayerWithoutAnotherPlayerCallback() throws Exception {
    CaptionOverlay.showCaption("Shorts 字幕", () -> true);
    CaptionOverlay.beginGuardedExpansion();
    assertEquals(View.GONE, anchor().getVisibility());
    shorts = true;
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
    CaptionOverlay.refreshSurface();
    assertEquals(View.VISIBLE, anchor().getVisibility());
  }

  @Test
  public void identicalOverflowDoesNotFloodDiagnosticsOnSurfaceRefresh() throws Exception {
    CaptionDiagnostics.clear(a);
    bounds = new Rect(0, 0, 240, 400);
    String caption = String.join("", java.util.Collections.nCopies(120, "字"));
    CaptionOverlay.showEvent(caption, () -> true, () -> "Original", "r2:1:9");
    for (int n = 0; n < 8; n++) {
      Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
      CaptionOverlay.refreshSurface();
    }
    String history =
        a.getSharedPreferences("deepseek_caption_diagnostics", 0).getString("history", "");
    assertEquals(1, history.split("REBUILD_LAYOUT_FALLBACK", -1).length - 1);
    assertTrue(history.contains("width="));
    assertTrue(history.contains("lines="));
    assertEquals(caption, field("pendingText"));
  }

  @Test
  public void actualPresentationDistinguishesFallbackFromSelectedTranslation() throws Exception {
    CaptionDiagnostics.clear(a);
    DeepSeekConfig.saveDisplayTextDebugEnabled(a, true);
    bounds = new Rect(0, 0, 240, 400);
    String caption = String.join("", java.util.Collections.nCopies(120, "字"));
    CaptionOverlay.showEvent(caption, () -> true, () -> "source retained", "r2:2:10");
    String history =
        a.getSharedPreferences("deepseek_caption_diagnostics", 0).getString("history", "");
    assertTrue(history.contains("REBUILD_PRESENTED"));
    assertTrue(history.contains("mode=original_fallback"));
    assertTrue(history.contains("source retained"));
    DeepSeekConfig.saveDisplayTextDebugEnabled(a, false);
  }

  @Test
  public void capturedLongResponseIsRejectedByRealGeometryBeforeReady() throws Exception {
    bounds = new Rect(0, 0, 300, 500);
    CaptionOverlay.showCaption("Preparing", () -> true);
    CaptionOverlay.LayoutBudget budget = CaptionOverlay.budget();
    assertNotNull(budget);
    RebuildSource source =
        RebuildContractTest.source(
            String.join(" ", java.util.Collections.nCopies(34, "word")), 340);
    String captured = "大量国产设计的武器装备，其中大部分解放军使用的装备，在冷战大部分时期要么是经授权、要么是未经授权仿制或衍生于苏联设计";
    RebuildProtocol.Plan plan =
        RebuildProtocol.parse(
            RebuildContractTest.reply(
                RebuildContractTest.block(source),
                new org.json.JSONArray().put(RebuildContractTest.event(0, 33, captured))),
            source,
            RebuildContractTest.block(source));
    try {
      RebuildProtocol.validateLayout(plan, budget::fits);
      fail("accepted layout overflow");
    } catch (RebuildProtocol.Invalid expected) {
      assertEquals("layout_overflow", expected.code);
    }
    assertEquals(captured, plan.events.get(0).text); // No text shortening in order to pass.
  }

  @Test
  public void shortTranslationPassesGeometryAndKeepsSourceTime() throws Exception {
    bounds = new Rect(0, 0, 300, 500);
    CaptionOverlay.showCaption("Preparing", () -> true);
    RebuildSource source = RebuildContractTest.source("Hello world.", 700);
    RebuildProtocol.Plan plan =
        RebuildProtocol.parse(
            RebuildContractTest.reply(
                RebuildContractTest.block(source),
                new org.json.JSONArray().put(RebuildContractTest.event(0, 1, "你好，世界。"))),
            source,
            RebuildContractTest.block(source));
    RebuildProtocol.validateLayout(plan, CaptionOverlay.budget()::fits);
    assertEquals(1400, plan.events.get(0).end);
  }

  @Test public void twoLinesUseCompactBackgroundRatherThanMaximumVideoWidth() throws Exception {
    // Keep the same two explicit lines readable at the larger N19 default glyph size.
    bounds=new Rect(0,0,640,360);
    String caption="This is the first line\nAnd this is the second";
    CaptionOverlay.showCaption(caption,()->true);
    assertEquals(caption,text().getText().toString());
    assertTrue(text().getMeasuredWidth()<Math.round(bounds.width()*.92f)-20);
    assertEquals(2,text().getLayout().getLineCount());
  }
  @Test public void bilibiliScaleAppliesToDetailFullscreenAndShortsFont() throws Exception {
    bounds=new Rect(0,0,360,203);CaptionOverlay.showCaption("Short caption",()->true);
    float inline=text().getTextSize();
    float detailGlyph=SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint());
    assertEquals(44.5f,detailGlyph,.5f);
    CaptionOverlay.setPlayerType("FULLSCREEN");
    a.getResources().getDisplayMetrics().widthPixels=2736;
    a.getResources().getDisplayMetrics().heightPixels=1264;
    bounds=new Rect(0,0,640,360);CaptionOverlay.refreshStyle(a);
    assertEquals(55.5f,SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
    assertEquals(55.5f/44.5f,SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint())/detailGlyph,.03f);
    CaptionOverlay.setPlayerType("WATCH");
    a.getResources().getDisplayMetrics().widthPixels=1264;
    a.getResources().getDisplayMetrics().heightPixels=2736;
    bounds=new Rect(0,0,360,203);CaptionOverlay.refreshStyle(a);
    assertEquals(inline,text().getTextSize(),.1f);
    shorts=true;CaptionOverlay.refreshStyle(a);
    assertEquals(inline,text().getTextSize(),.1f);
  }
  @Test public void videoContractionWithinSamePlayerScalesAndRestores() throws Exception {
    bounds=new Rect(0,0,640,360);CaptionOverlay.showCaption("Short caption",()->true);
    float normal=text().getTextSize();
    bounds=new Rect(0,0,360,203);CaptionOverlay.refreshStyle(a);
    assertEquals(SubtitleStyleMetrics.targetGlyphHeightPx(2,1264,false)*360f/640f,
        SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),1f);
    bounds=new Rect(0,0,640,360);CaptionOverlay.refreshStyle(a);
    assertEquals(normal,text().getTextSize(),.1f);
  }
  @Test public void longWrappedTextKeepsAllCharactersAtCompactWidth() throws Exception {
    // The original 600px fixture was calibrated for the former 25.6px glyph default.
    bounds=new Rect(0,0,1040,585);
    String caption="The complete sentence should wrap naturally across two readable lines.";
    CaptionOverlay.showCaption(caption,()->true);
    assertEquals(caption,text().getText().toString());
    android.text.Layout layout=text().getLayout();
    assertEquals(2,layout.getLineCount());
    assertEquals(caption.length(),layout.getLineEnd(layout.getLineCount()-1));
  }
  @Test public void previewAndRendererShareFullscreenReferenceSizing() {
    float screenWidth=2736,previewWidth=600;
    float actual=SubtitleStyleMetrics.targetGlyphHeightPx(2,screenWidth,true);
    float preview=SubtitleStyleMetrics.previewGlyphHeightPx(2,previewWidth);
    assertEquals(actual*previewWidth/screenWidth,preview,.001f);
  }

  @Test public void earlyPlayerModeCallbackKeepsFontUntilScreenGeometryActuallyChanges() throws Exception {
    bounds=new Rect(0,0,640,360);
    CaptionOverlay.setPlayerType("WATCH");
    CaptionOverlay.showCaption("字幕",()->true);
    assertEquals(44.5f,SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
    CaptionOverlay.setPlayerType("WATCH_WHILE_FULLSCREEN");
    CaptionOverlay.refreshStyle(a);
    assertEquals("mode callback arrives before rotation",44.5f,
        SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
    a.getResources().getDisplayMetrics().widthPixels=2736;
    a.getResources().getDisplayMetrics().heightPixels=1264;
    CaptionOverlay.refreshStyle(a);
    assertEquals(55.5f,SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
    CaptionOverlay.setPlayerType("WATCH_WHILE_MAXIMIZED");
    CaptionOverlay.refreshStyle(a);
    assertEquals("exit callback also arrives before rotation",55.5f,
        SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
    a.getResources().getDisplayMetrics().widthPixels=1264;
    a.getResources().getDisplayMetrics().heightPixels=2736;
    CaptionOverlay.refreshStyle(a);
    assertEquals(44.5f,SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
  }

  @Test public void shortsAlwaysUseDetailRatioAheadOfFullscreenModeAndOrientation() throws Exception {
    a.getResources().getDisplayMetrics().widthPixels=2736;
    a.getResources().getDisplayMetrics().heightPixels=1264;
    bounds=new Rect(0,0,1000,563);
    CaptionOverlay.setPlayerType("WATCH_WHILE_FULLSCREEN");
    shorts=true;
    CaptionOverlay.showCaption("字幕",()->true);
    assertEquals(SubtitleStyleMetrics.targetGlyphHeightPx(2,2736,false),
        SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
    shorts=false;
    CaptionOverlay.refreshStyle(a);
    assertEquals(55.5f,SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
  }

  @Test public void largerDefaultKeepsOverflowSafetyAtFormerSmallWrappingFixture() throws Exception {
    bounds=new Rect(0,0,600,340);
    String caption="The complete sentence should wrap naturally across two readable lines.";
    CaptionOverlay.showCaption(caption,()->true,()->"Original");
    assertEquals("Original",text().getText().toString());
    assertEquals(caption,field("pendingText"));
    assertEquals(SubtitleStyleMetrics.textSizePxForGlyphHeight(text().getPaint(),44.5f),
        CaptionOverlay.budget().preferredPx,.001f);
    // Existing explicit-original fallback is allowed to use the smallest approved tier.
    assertEquals(34f,SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
    assertTrue(text().getLayout().getLineCount()<=2);
  }

  @Test public void videoAspectRatioDoesNotChangeScreenBasedFont() throws Exception {
    bounds=new Rect(0,0,1264,711);CaptionOverlay.showCaption("字幕",()->true);
    float before=text().getTextSize();
    bounds=new Rect(0,0,1264,948);CaptionOverlay.refreshStyle(a);
    assertEquals(before,text().getTextSize(),.001f);
    CaptionOverlay.setPlayerType("ANOTHER_VIDEO");
    bounds=new Rect(0,0,1000,948);CaptionOverlay.refreshStyle(a);
    assertEquals(before,text().getTextSize(),.001f);
  }

  @Test public void contractionThresholdComposesWithRatioAndRestores() throws Exception {
    bounds=new Rect(0,0,1264,711);CaptionOverlay.showCaption("字幕",()->true);
    float before=text().getTextSize();
    bounds=new Rect(0,0,1012,569);CaptionOverlay.refreshStyle(a); // under 20% contraction
    assertEquals(before,text().getTextSize(),.001f);
    bounds=new Rect(0,0,632,355);CaptionOverlay.refreshStyle(a);
    assertEquals(SubtitleStyleMetrics.targetGlyphHeightPx(2,632,false),
        SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
    assertEquals(SubtitleStyleMetrics.targetGlyphHeightPx(2,632,false),
        SubtitleStyleMetrics.renderedGlyphHeightPx(2,1264,false,632,1264),.001f);
    bounds=new Rect(0,0,1264,711);CaptionOverlay.refreshStyle(a);
    assertEquals(before,text().getTextSize(),.001f);
  }

  @Test public void densityAndFontScaleDoNotMultiplyGlyphTarget() throws Exception {
    CaptionOverlay.showCaption("字幕",()->true);
    float before=text().getTextSize();
    a.getResources().getDisplayMetrics().density=2.75f;
    a.getResources().getDisplayMetrics().scaledDensity=4.4f;
    a.getResources().getConfiguration().fontScale=1.6f;
    CaptionOverlay.refreshStyle(a);
    assertEquals(before,text().getTextSize(),.001f);
  }

  @Test public void unchangedVideoBoundsStillRefreshWhenOnlyScreenWidthChanges() throws Exception {
    CaptionOverlay.showCaption("字幕",()->true);
    float before=text().getTextSize();
    a.getResources().getDisplayMetrics().widthPixels=2736;
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150));
    FrameLayout host=(FrameLayout)((WeakReference<?>)field("hostRef")).get();
    host.getViewTreeObserver().dispatchOnPreDraw();
    assertEquals(44.5f*2736f/1264f,SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint()),.5f);
  }

  @Test public void presentedPixelMetricsMatchActualTextPaintAndGeometry() throws Exception {
    CaptionDiagnostics.clear(a);DeepSeekConfig.saveDisplayTextDebugEnabled(a,true);
    bounds=new Rect(0,0,1264,711);CaptionOverlay.showCaption("字幕",()->true);
    String history=a.getSharedPreferences("deepseek_caption_diagnostics",0).getString("history","");
    assertTrue(history.contains(";width="+CaptionOverlay.budget().width+";"));
    assertTrue(history.contains(";target_glyph_height_px="+SubtitleStyleMetrics.targetGlyphHeightPx(2,1264,false)));
    assertTrue(history.contains(";glyph_height_px="+SubtitleStyleMetrics.measuredGlyphHeightPx(text().getPaint())));
    assertTrue(history.contains(";font_metrics_height_px="+SubtitleStyleMetrics.fontMetricsHeightPx(text().getPaint())));
    assertTrue(history.contains(";screen_width_px=1264;video_width_px=1264;normal_video_width_px=1264;size_tier=2;size_mode=detail;"));
    assertTrue(history.contains(";density="));assertTrue(history.contains(";fontScale="));
    assertEquals(CaptionOverlay.budget().preferredPx,text().getTextSize(),.001f);
    DeepSeekConfig.saveDisplayTextDebugEnabled(a,false);
  }

  @Test public void a10NarrowEventShowsBothOwnedPagesInsteadOfOverflowStatus() throws Exception {
    bounds = new Rect(0, 0, 600, 340);
    assertTimedPages(
        "第一，中国的国防预算实际上比你以为的更大；这不是因为他们想隐瞒，而是因为会计标准不同，以及纳入和排除的项目不同。",
        165680, 173023, 4);
  }

  @Test public void a06LongEventKeepsPreferredFontAcrossPages() throws Exception {
    bounds = new Rect(0, 0, 1000, 560);
    assertTimedPages(
        "这就让人不禁要问：如果中国国防开支如此之少，那么这些隐形战斗机、航空母舰、高超音速导弹和反舰弹道导弹都从何而来？",
        65002, 76092, 3);
  }

  @Test public void eventBeyondReadablePageBudgetRecordsUnresolvedFallback() throws Exception {
    CaptionDiagnostics.clear(a);
    bounds = new Rect(0, 0, 240, 400);
    String caption = String.join("", java.util.Collections.nCopies(120, "字"));
    CaptionOverlay.showEvent(caption, () -> true, () -> "", "n3-unresolved", 0, 5000, 0);
    assertNotEquals(caption, text().getText().toString());
    assertEquals(caption, field("pendingText"));
    String history = a.getSharedPreferences("deepseek_caption_diagnostics", 0)
        .getString("history", "");
    assertTrue(history.contains("REBUILD_LAYOUT_FALLBACK"));
    assertTrue(history.contains("pagination_unresolved=true"));
  }

  @Test public void cpsFailureUsesOriginalEvenWhenTranslationFitsTwoLines() throws Exception {
    CaptionDiagnostics.clear(a);
    String caption = "这一条译文虽然很短，但时间窗口更短。";
    CaptionOverlay.showEvent(caption, () -> true, () -> "Original", "n15-cps", 0, 1200, 0);
    assertEquals("Original", text().getText().toString());
    String history = a.getSharedPreferences("deepseek_caption_diagnostics", 0)
        .getString("history", "");
    assertTrue(history.contains("REBUILD_LAYOUT_FALLBACK"));
    assertTrue(history.contains("pagination_unresolved=true"));
  }

  @Test public void ownedShortWindowIsLoggedWithoutExtendingItsTime() throws Exception {
    CaptionDiagnostics.clear(a);
    CaptionOverlay.showEvent("短句。", () -> true, () -> "Original", "n15-short", 500, 1386, 500);
    assertEquals("短句。", text().getText().toString());
    java.util.List<RebuildPageLayout.Page> pages =
        (java.util.List<RebuildPageLayout.Page>) field("pendingPages");
    assertEquals(1, pages.size());
    assertEquals(500, pages.get(0).start);
    assertEquals(1386, pages.get(0).end);
    String history = a.getSharedPreferences("deepseek_caption_diagnostics", 0)
        .getString("history", "");
    assertTrue(history.contains("duration_exception=owned_window_lt_1200"));
  }
  @SuppressWarnings("unchecked")
  @Test public void presentedLinesDescribeEachShownPage() throws Exception {
    CaptionDiagnostics.clear(a);
    DeepSeekConfig.saveDisplayTextDebugEnabled(a, true);
    bounds = new Rect(0, 0, 600, 340);
    String caption = "第一，中国的国防预算实际上比你以为的更大；这不是因为他们想隐瞒，而是因为会计标准不同，以及纳入和排除的项目不同。";
    CaptionOverlay.showEvent(caption, () -> true, () -> "", "n17a-lines", 165680, 173023, 165680);
    java.util.List<RebuildPageLayout.Page> pages =
        (java.util.List<RebuildPageLayout.Page>) field("pendingPages");
    assertTrue(pages.size() > 1);
    for (int i = 0; i < pages.size(); i++) {
      CaptionOverlay.position(pages.get(i).start);
      assertEquals(pages.get(i).text, text().getText().toString());
      int actualLines = text().getLayout().getLineCount();
      assertTrue(actualLines <= 2);
      String history = a.getSharedPreferences("deepseek_caption_diagnostics", 0)
          .getString("history", "");
      assertTrue("page " + (i + 1) + " should log its displayed line count",
          history.contains("id=n17a-lines;mode=caption_page;")
              && history.contains(";lines=" + actualLines + ";page=" + (i + 1) + "/" + pages.size()
                  + ";page_range=" + pages.get(i).start + "-" + pages.get(i).end));
    }
    DeepSeekConfig.saveDisplayTextDebugEnabled(a, false);
  }
  @SuppressWarnings("unchecked")
  private void assertTimedPages(String caption, long start, long end, int expectedPages) throws Exception {
    CaptionOverlay.showEvent(caption, () -> true, () -> "", "n3", start, end, start);
    java.util.List<RebuildPageLayout.Page> pages =
        (java.util.List<RebuildPageLayout.Page>) field("pendingPages");
    assertEquals(expectedPages, pages.size());
    float expected = SubtitleStyleMetrics.textSizePxForGlyphHeight(text().getPaint(),
        SubtitleStyleMetrics.targetGlyphHeightPx(DeepSeekConfig.displayStyle(a).captionSizeTier,
            a.getResources().getDisplayMetrics().widthPixels,false));
    StringBuilder joined = new StringBuilder();
    long cursor = start;
    for (RebuildPageLayout.Page page : pages) {
      CaptionOverlay.position(page.start);
      assertEquals(page.text, text().getText().toString());
      assertEquals("preferred font, no shrink", expected, text().getTextSize(), .1f);
      assertEquals(cursor, page.start);
      assertTrue(page.end - page.start >= 1200);
      assertTrue(page.text.codePointCount(0, page.text.length()) * 1000L
          <= 8 * (page.end - page.start));
      joined.append(page.text);
      cursor = page.end;
    }
    assertEquals(caption, joined.toString());
    assertEquals(end, cursor);
  }
}
