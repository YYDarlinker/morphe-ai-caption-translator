package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** One event in one view. Layout never edits a translation or creates new timeline events. */
final class CaptionOverlay {
  interface RenderGuard {
    boolean isValid();
  }

  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final java.util.concurrent.atomic.AtomicLong COMMAND =
      new java.util.concurrent.atomic.AtomicLong();
  private static WeakReference<Activity> activityRef = new WeakReference<>(null);
  private static WeakReference<FrameLayout> hostRef = new WeakReference<>(null),
      anchorRef = new WeakReference<>(null);
  private static WeakReference<TextView> textRef = new WeakReference<>(null);

  /** Immutable geometry; background translation can measure without touching Views. */
  static final class LayoutBudget {
    final int width;
    final float minimumPx, preferredPx;

    LayoutBudget(int w, float px) {
      this(w,px,px);
    }

    LayoutBudget(int w,float px,float preferred) {
      width=w; minimumPx=px; preferredPx=preferred;
    }

    boolean fits(String value) {
      if (value.isEmpty()) return true;
      TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      paint.setTypeface(Typeface.DEFAULT);
      paint.setTextSize(minimumPx);
      return StaticLayout.Builder.obtain(value, 0, value.length(), paint, Math.max(1, width))
              .setIncludePad(false)
              .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
              .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
              .build()
              .getLineCount()
          <= 2;
    }

    boolean fitsPreferred(String value) {
      if (value.isEmpty()) return true;
      TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      paint.setTypeface(Typeface.DEFAULT);
      paint.setTextSize(preferredPx);
      return StaticLayout.Builder.obtain(value, 0, value.length(), paint, Math.max(1, width))
          .setIncludePad(false)
          .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
          .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
          .build().getLineCount() <= 2;
    }

    boolean canPresent(RebuildProtocol.Event event) {
      return !RebuildPageLayout.plan(event.text, event.start, event.end,
          this::fitsPreferred).isEmpty();
    }

    int preferredColumns() { return Math.max(1,(int)(width/Math.max(1,preferredPx))); }

    int approximateColumns() {
      return Math.max(1, (int) (width / Math.max(1, minimumPx)));
    }
  }

  private static volatile LayoutBudget layoutBudget;

  static LayoutBudget budget() {
    return layoutBudget;
  }

  private static String pendingText = "", pendingIdentity = "", lastNotice = "";
  private static long pendingStart = -1, pendingEnd = -1, pendingPosition = -1;
  private static List<RebuildPageLayout.Page> pendingPages = Collections.emptyList();
  private static int shownPage = -1;
  private static boolean previousShorts;
  private static boolean pendingStatus, suppressed, guardedExpansion;
  private static RenderGuard currentGuard;
  private static Supplier<String> fallback;
  private static Rect previous = new Rect();
  private static int normalVideoWidth;
  private static int previousScreenWidth;
  private static boolean referenceShorts, referenceLandscape;
  private static String playerType = "";
  private static long lastScan, lastLayout;
  private static boolean dirty = true;
  private static float downY, initial;
  private static long downAt;
  private static boolean dragging;
  private static final android.view.ViewTreeObserver.OnPreDrawListener WATCH =
      () -> {
        long now = SystemClock.uptimeMillis();
        if (now - lastLayout >= 100 && !guardedExpansion && !suppressed && !pendingText.isEmpty()) {
          lastLayout = now;
          render();
        }
        return true;
      };

  static void setActivity(Activity a) {
    main(
        () -> {
          if (activityRef.get() != a) {
            detach();
            normalVideoWidth = 0;
            playerType = "";
          }
          activityRef = new WeakReference<>(a);
          CaptionSurface.activity(a);
          dirty = true;
          render();
        });
  }

  static void showCaption(String s) {
    show(s, false, null, null);
  }

  static void showCaption(String s, RenderGuard g) {
    show(s, false, g, null);
  }

  static void showCaption(String s, RenderGuard g, Supplier<String> f) {
    show(s, false, g, f);
  }

  static void showStatus(String s) {
    show(s, true, null, null);
  }

  static void showStatus(String s, RenderGuard g) {
    show(s, true, g, null);
  }

  static void showEvent(String s, RenderGuard g, Supplier<String> f, String id) {
    show(s, false, g, f, id);
  }

  static void showEvent(String s, RenderGuard g, Supplier<String> f, String id,
      long start, long end, long position) {
    show(s, false, g, f, id, start, end, position);
  }

  static void position(long position) {
    main(() -> {
      pendingPosition = position;
      int next = RebuildPageLayout.indexAt(pendingPages, position);
      if (next >= 0 && next != shownPage) {
        dirty = true;
        render();
      }
    });
  }

  private static void show(String s, boolean status, RenderGuard g, Supplier<String> f) {
    show(s, status, g, f, "");
  }

  private static void show(String s, boolean status, RenderGuard g, Supplier<String> f, String id) {
    show(s, status, g, f, id, -1, -1, -1);
  }

  private static void show(String s, boolean status, RenderGuard g, Supplier<String> f, String id,
      long start, long end, long position) {
    if (g != null && !g.isValid()) return;
    long command = COMMAND.incrementAndGet();
    main(
        () -> {
          if (command != COMMAND.get() || g != null && !g.isValid()) return;
          pendingText = s == null ? "" : s;
          pendingIdentity = id;
          pendingStart = start;
          pendingEnd = end;
          pendingPosition = position;
          pendingPages = Collections.emptyList();
          shownPage = -1;
          pendingStatus = status;
          currentGuard = g;
          fallback = f;
          dirty = true;
          render();
        });
  }

  static void hide() {
    hide(null);
  }

  static void hide(RenderGuard g) {
    if (g != null && !g.isValid()) return;
    long command = COMMAND.incrementAndGet();
    main(
        () -> {
          if (command != COMMAND.get() || g != null && !g.isValid()) return;
          pendingText = "";
          pendingPages = Collections.emptyList();
          shownPage = -1;
          fallback = null;
          currentGuard = g;
          hideView();
        });
  }

  static void clear() {
    long command = COMMAND.incrementAndGet();
    main(
        () -> {
          if (command != COMMAND.get()) return;
          pendingText = "";
          pendingPages = Collections.emptyList();
          shownPage = -1;
          pendingStatus = false;
          fallback = null;
          currentGuard = null;
          normalVideoWidth = 0;
          hideView();
        });
  }

  static void refreshStyle(Context c) {
    main(
        () -> {
          dirty = true;
          render();
        });
  }

  static void refreshSurface() {
    main(
        () -> {
          long now = SystemClock.uptimeMillis();
          if (now >= lastScan && now - lastScan < 500L) return;
          CaptionSurface.refresh();
          lastScan = SystemClock.uptimeMillis();
          if (CaptionSurface.isShorts()) {
            suppressed = false;
            guardedExpansion = false;
          }
          dirty = true;
          render();
        });
  }

  static void setPlayerType(String type) {
    main(
        () -> {
          String s = type == null ? "" : type.toUpperCase(java.util.Locale.ROOT);
          boolean changed = !s.equals(playerType);
          if (changed) {
            playerType = s;
            normalVideoWidth = 0;
          }
          suppressed =
              !CaptionSurface.isShorts()
                  && (s.contains("MINIM")
                      || s.contains("HIDDEN")
                      || s.contains("DISMISSED")
                      || s.contains("PICTURE_IN_PICTURE"));
          dirty = true;
          render();
          // This callback may arrive before YouTube updates the video bounds.
          if (changed) {
            normalVideoWidth = 0;
            dirty = true;
          }
        });
  }

  static void beginGuardedExpansion() {
    main(
        () -> {
          guardedExpansion = true;
          hideView();
        });
  }

  static void restoreAfterGuardedExpansion(String type) {
    main(
        () -> {
          guardedExpansion = false;
          suppressed = false;
          CaptionSurface.refresh();
          dirty = true;
          setPlayerType(type);
        });
  }

  private static void main(Runnable r) {
    if (Looper.myLooper() == Looper.getMainLooper()) r.run();
    else MAIN.post(r);
  }

  private static void hideView() {
    FrameLayout a = anchorRef.get();
    if (a != null) a.setVisibility(View.GONE);
  }

  private static void detach() {
    FrameLayout h = hostRef.get(), a = anchorRef.get();
    if (h != null && h.getViewTreeObserver().isAlive())
      h.getViewTreeObserver().removeOnPreDrawListener(WATCH);
    if (a != null && a.getParent() instanceof ViewGroup) ((ViewGroup) a.getParent()).removeView(a);
    hostRef = new WeakReference<>(null);
    anchorRef = new WeakReference<>(null);
    textRef = new WeakReference<>(null);
    previous.setEmpty();
    previousScreenWidth = 0;
    lastScan = -500;
    lastLayout = 0;
    lastNotice = "";
    layoutBudget = null;
    pendingPages = Collections.emptyList();
    shownPage = -1;
  }

  private static boolean attach(Activity a) {
    FrameLayout h = hostRef.get();
    if (h != null && h.isAttachedToWindow() && anchorRef.get() != null) return true;
    detach();
    View content = a.findViewById(android.R.id.content);
    if (!(content instanceof FrameLayout)) return false;
    h = (FrameLayout) content;
    FrameLayout anchor = new FrameLayout(a);
    anchor.setTag("yydarlinker.deepseek.caption.anchor");
    anchor.setClipChildren(false);
    anchor.setClipToPadding(false);
    anchor.setElevation(dp(a, 12));
    TextView text = new TextView(a);
    text.setTag("yydarlinker.deepseek.caption.overlay");
    text.setTextColor(Color.WHITE);
    text.setGravity(Gravity.CENTER);
    text.setIncludeFontPadding(false);
    text.setPadding(dp(a, 6), dp(a, 4), dp(a, 6), dp(a, 4));
    text.setShadowLayer(dp(a, 1), 0, dp(a, 1), 0xD0000000);
    text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
    text.setSingleLine(false);
    text.setMaxLines(2);
    text.setEllipsize(null);
    text.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    text.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
    text.setOnTouchListener((v, e) -> drag(v, e));
    anchor.addView(
        text,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    h.addView(anchor, new FrameLayout.LayoutParams(1, 1));
    hostRef = new WeakReference<>(h);
    anchorRef = new WeakReference<>(anchor);
    textRef = new WeakReference<>(text);
    h.getViewTreeObserver().addOnPreDrawListener(WATCH);
    dirty = true;
    return true;
  }

  private static void render() {
    Activity a = activityRef.get();
    if (a == null
        || a.isFinishing()
        || a.isDestroyed()
        || pendingText.isEmpty()
        || suppressed
        || guardedExpansion
        || currentGuard != null && !currentGuard.isValid()) {
      hideView();
      return;
    }
    if (!attach(a)) return;
    FrameLayout host = hostRef.get(), anchor = anchorRef.get();
    TextView text = textRef.get();
    long now = SystemClock.uptimeMillis();
    if (now - lastScan >= 500) {
      CaptionSurface.refresh();
      lastScan = now;
    }
    Rect b = CaptionSurface.videoBounds(host);
    if (b == null || b.width() < 50 || b.height() < 50) {
      layoutBudget = null;
      hideView();
      return;
    }
    boolean shorts = CaptionSurface.isShorts();
    android.util.DisplayMetrics metrics = a.getResources().getDisplayMetrics();
    int screenWidth = metrics.widthPixels;
    if (!dirty
        && b.equals(previous)
        && screenWidth == previousScreenWidth
        && shorts == previousShorts
        && anchor.getVisibility() == View.VISIBLE) return;
    previousShorts = shorts;
    dirty = false;
    previous.set(b);
    previousScreenWidth = screenWidth;
    DeepSeekConfig.Snapshot cfg = DeepSeekConfig.displayStyle(a);
    boolean landscapeHost = host.getWidth() > host.getHeight();
    if (normalVideoWidth == 0 || shorts != referenceShorts
        || landscapeHost != referenceLandscape) {
      normalVideoWidth = b.width();
      referenceShorts = shorts;
      referenceLandscape = landscapeHost;
    } else {
      normalVideoWidth = Math.max(normalVideoWidth, b.width());
    }
    float targetGlyphHeight = SubtitleStyleMetrics.renderedGlyphHeightPx(
        cfg.captionGlyphHeightRatioBps,screenWidth,b.width(),normalVideoWidth);
    float preferred = SubtitleStyleMetrics.textSizePxForGlyphHeight(text.getPaint(),targetGlyphHeight);
    float minimum = SubtitleStyleMetrics.textSizePxForGlyphHeight(text.getPaint(),
        SubtitleStyleMetrics.renderedGlyphHeightPx(
            DeepSeekConfig.MIN_CAPTION_GLYPH_HEIGHT_RATIO_BPS,screenWidth,b.width(),normalVideoWidth));
    int width = Math.max(1, Math.round(b.width() * (CaptionSurface.isShorts() ? .78f : .92f)));
    int inner = Math.max(1, width - text.getPaddingLeft() - text.getPaddingRight());
    layoutBudget =
        new LayoutBudget(inner,minimum,preferred);
    float size = preferred;
    String shown = pendingText;
    String mode = pendingStatus ? "status" : "caption";
    boolean ownedCaption = !pendingStatus && pendingStart >= 0 && pendingEnd > pendingStart;
    pendingPages = ownedCaption
        ? RebuildPageLayout.plan(pendingText, pendingStart, pendingEnd,
            value -> linesPx(value, preferred, inner) <= 2,
            value -> linesPx(value, preferred, inner) <= 1)
        : Collections.emptyList();
    shownPage = RebuildPageLayout.indexAt(pendingPages, pendingPosition);
    if (shownPage >= 0) {
      shown = pendingPages.get(shownPage).text;
      if (pendingPages.size() > 1) mode = "caption_page";
    } else if (!ownedCaption) {
      while (size > minimum && linesPx(shown, size, inner) > 2)
        size = Math.max(minimum, size - .5f);
    }
    // A failed time/CPS/seam gate must not show the invalid translation as a single page.
    if (shownPage < 0 && (ownedCaption || linesPx(shown, size, inner) > 2)) {
      mode = "original_fallback";
      shown = fallback == null ? "" : fallback.get();
      if (shown == null || shown.isEmpty() || linesPx(shown, size, inner) > 2) {
        mode = "overflow_status";
        shown = CaptionStrings.get(a, "caption_overflow");
      }
      if (linesPx(shown, size, inner) > 2) shown = "…";
    }
    text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    String notice = pendingIdentity + "|" + pendingText + "|" + mode + "|" + inner + "|" + size
        + "|" + shownPage + "|" + screenWidth + "|" + b.width()
        + "|" + metrics.density + "|" + a.getResources().getConfiguration().fontScale;
    if (!notice.equals(lastNotice)) {
      lastNotice = notice;
      String detail =
          "id="
              + pendingIdentity
              + ";mode="
              + mode
              + ";width="
              + inner
              + ";sp=" + size / metrics.scaledDensity
              + ";text_size_px="
              + size
              + ";target_glyph_height_px=" + targetGlyphHeight
              + ";rendered_glyph_target_px=" + targetGlyphHeight * size / preferred
              + ";glyph_height_px=" + SubtitleStyleMetrics.measuredGlyphHeightPx(text.getPaint())
              + ";font_metrics_height_px=" + SubtitleStyleMetrics.fontMetricsHeightPx(text.getPaint())
              + ";screen_width_px=" + screenWidth
              + ";video_width_px=" + b.width()
              + ";normal_video_width_px=" + normalVideoWidth
              + ";ratio_bps=" + cfg.captionGlyphHeightRatioBps
              + ";density=" + metrics.density
              + ";fontScale=" + a.getResources().getConfiguration().fontScale
              + ";lines="
              + linesPx(shown, size, inner)
              + (shownPage >= 0 ? ";page=" + (shownPage + 1) + "/" + pendingPages.size()
                  + ";page_range=" + pendingPages.get(shownPage).start + "-"
                  + pendingPages.get(shownPage).end
                  + (pendingEnd - pendingStart < RebuildPageLayout.MIN_PAGE_MS
                      ? ";duration_exception=owned_window_lt_1200" : "")
                  : ";pagination_unresolved=true");
      if (shownPage >= 0 && pendingEnd - pendingStart < RebuildPageLayout.MIN_PAGE_MS)
        CaptionDiagnostics.mark(a, "REBUILD_LAYOUT_TIME_EXCEPTION", detail);
      if (mode.equals("original_fallback") || mode.equals("overflow_status"))
        CaptionDiagnostics.mark(a, "REBUILD_LAYOUT_FALLBACK", detail);
      if (DeepSeekConfig.displayTextDebugEnabled(a))
        CaptionDiagnostics.mark(
            a,
            "REBUILD_PRESENTED",
            detail
                + ";text="
                + CaptionQualityTrace.redact(shown, DeepSeekConfig.load(a).apiKey, 400));
    }
    text.setText(shown);
    text.setSingleLine(false);
    text.setMaxLines(2);
    text.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
    text.setTextColor(pendingStatus ? 0xE6FFFFFF : Color.WHITE);
    int compact = compactWidthPx(shown, size, inner) + text.getPaddingLeft() + text.getPaddingRight();
    text.setMaxWidth(compact);
    text.getLayoutParams().width = compact;
    GradientDrawable bg = new GradientDrawable();
    bg.setColor((SubtitleStyleMetrics.alpha(cfg.backgroundOpacity) << 24));
    bg.setCornerRadius(dp(a, 4));
    text.setBackground(bg);
    text.measure(
        View.MeasureSpec.makeMeasureSpec(compact, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int height = text.getMeasuredHeight();
    boolean landscape = b.width() > b.height();
    float y =
        CaptionSurface.isShorts()
            ? DeepSeekConfig.shortsPosition(a)
            : DeepSeekConfig.captionPositionY(a, landscape);
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) anchor.getLayoutParams();
    params.width = width;
    params.height = height;
    params.gravity = Gravity.TOP | Gravity.START;
    params.leftMargin = b.left + (b.width() - width) / 2;
    params.topMargin =
        Math.max(
            b.top, Math.min(b.bottom - height, b.top + Math.round(b.height() * y) - height / 2));
    anchor.setLayoutParams(params);
    anchor.setVisibility(View.VISIBLE);
    anchor.bringToFront();
  }

  static int compactWidth(Context a, String value, float sp, int maximum) {
    return compactWidthPx(value,TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,sp,a.getResources().getDisplayMetrics()),maximum);
  }

  static int compactWidthPx(String value, float sizePx, int maximum) {
    int target = linesPx(value,sizePx,maximum), low=1, high=maximum;
    while(low<high){int mid=(low+high)/2;if(linesPx(value,sizePx,mid)<=target)high=mid;else low=mid+1;}
    return Math.min(maximum,low+1); // one pixel rounding guard; never omit text
  }

  static int lines(Context a, String s, float sp, int width) {
    return linesPx(s,TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,sp,a.getResources().getDisplayMetrics()),width);
  }

  static int linesPx(String s, float sizePx, int width) {
    android.text.TextPaint paint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    paint.setTypeface(Typeface.DEFAULT);
    paint.setTextSize(sizePx);
    return StaticLayout.Builder.obtain(s, 0, s.length(), paint, Math.max(1, width))
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
        .getLineCount();
  }

  private static boolean drag(View v, MotionEvent e) {
    Activity a = activityRef.get();
    if (a == null || previous.height() <= 0) return false;
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downY = e.getRawY();
        downAt = SystemClock.uptimeMillis();
        dragging = false;
        initial =
            CaptionSurface.isShorts()
                ? DeepSeekConfig.shortsPosition(a)
                : DeepSeekConfig.captionPositionY(a, previous.width() > previous.height());
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!dragging && SystemClock.uptimeMillis() - downAt >= 350) {
          dragging = true;
          v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        if (dragging) {
          float y =
              Math.max(.08f, Math.min(.92f, initial + (e.getRawY() - downY) / previous.height()));
          if (CaptionSurface.isShorts()) DeepSeekConfig.saveShortsPosition(a, y);
          else DeepSeekConfig.saveCaptionPosition(a, previous.width() > previous.height(), y);
          dirty = true;
          render();
        }
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        dragging = false;
        return true;
      default:
        return false;
    }
  }

  private static int dp(Context c, float x) {
    return Math.round(x * c.getResources().getDisplayMetrics().density);
  }
}
