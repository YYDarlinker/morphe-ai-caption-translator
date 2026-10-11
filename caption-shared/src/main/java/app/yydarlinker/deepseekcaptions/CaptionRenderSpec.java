package app.yydarlinker.deepseekcaptions;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.view.View;
import android.widget.TextView;
import java.util.Arrays;
import java.util.Locale;

/** Immutable target presentation contract; never inferred from UI locale or text script. */
final class CaptionRenderSpec {
  static final String POLICY_VERSION = "upstream-presentation-ac65fe8";
  static final CaptionRenderSpec LEGACY = new CaptionRenderSpec("zh-Hans",
      CaptionLanguageProfile.fromCode("zh-Hans"), true);
  final String targetCode, presentationPolicy;
  final CaptionLanguageProfile profile;
  final Locale locale;
  final CaptionLanguageProfile.Direction direction;
  final boolean compactScript,legacy;
  final int maxLines = 2;
  private final TextPaint measurementPaint;
  private final TextDirectionHeuristic measurementDirection;
  static long layoutCalls; // Diagnostic counters, never layout or scheduling authority.

  CaptionRenderSpec(String target, CaptionLanguageProfile profile, boolean chinese) {
    this(target,profile,chinese,null,null);
  }
  private CaptionRenderSpec(String target,CaptionLanguageProfile profile,boolean chinese,
      TextPaint paint,TextDirectionHeuristic heuristic) {
    measurementPaint=paint;measurementDirection=heuristic;
    targetCode=target; this.profile=profile; compactScript=chinese;legacy=chinese;
    locale=Locale.forLanguageTag(target.equals("UNKNOWN") ? "und" : target);
    direction=profile.direction;
    presentationPolicy=POLICY_VERSION;
  }
  CaptionRenderSpec withPaint(TextView view) {
    TextPaint paint=new TextPaint(view.getPaint());
    paint.setTextSize(1); // Size is an explicit plan key and is set separately by layout().
    int direction=view.getTextDirection();TextDirectionHeuristic actual;
    switch(direction) {
      case View.TEXT_DIRECTION_LTR:actual=TextDirectionHeuristics.LTR;break;
      case View.TEXT_DIRECTION_RTL:actual=TextDirectionHeuristics.RTL;break;
      case View.TEXT_DIRECTION_ANY_RTL:actual=TextDirectionHeuristics.ANYRTL_LTR;break;
      case View.TEXT_DIRECTION_LOCALE:actual=TextDirectionHeuristics.LOCALE;break;
      case View.TEXT_DIRECTION_FIRST_STRONG_RTL:actual=TextDirectionHeuristics.FIRSTSTRONG_RTL;break;
      case View.TEXT_DIRECTION_FIRST_STRONG_LTR:actual=TextDirectionHeuristics.FIRSTSTRONG_LTR;break;
      default:actual=view.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL
          ? TextDirectionHeuristics.FIRSTSTRONG_RTL : TextDirectionHeuristics.FIRSTSTRONG_LTR;
    }
    return new CaptionRenderSpec(targetCode,profile,compactScript,paint,actual);
  }
  CaptionRenderSpec withPaint(TextPaint value) {
    return new CaptionRenderSpec(targetCode,profile,compactScript,new TextPaint(value),heuristic());
  }
  boolean sameMeasurement(CaptionRenderSpec other) {
    return other!=null && targetCode.equals(other.targetCode) && compactScript==other.compactScript
        && effectiveDirection()==other.effectiveDirection()&&paint(1).equalsForTextMeasurement(other.paint(1));
  }
  private TextDirectionHeuristic effectiveDirection(){return measurementDirection==null?heuristic():measurementDirection;}
  private static final java.util.LinkedHashMap<String,CaptionRenderSpec> MEASUREMENTS=new java.util.LinkedHashMap<>(64,.75f,true);
  private static long nextMeasurement;
  private volatile String cachedMeasurementKey;
  String measurementKey(){
    String cached=cachedMeasurementKey;if(cached!=null)return cached;
    synchronized(MEASUREMENTS){
      String found=null;for(java.util.Map.Entry<String,CaptionRenderSpec> row:MEASUREMENTS.entrySet())if(sameMeasurement(row.getValue())){found=row.getKey();break;}
      if(found!=null){MEASUREMENTS.get(found);cachedMeasurementKey=found;return found;}
      String key="font"+(++nextMeasurement);MEASUREMENTS.put(key,this);
      while(MEASUREMENTS.size()>64)MEASUREMENTS.remove(MEASUREMENTS.keySet().iterator().next());cachedMeasurementKey=key;return key;
    }
  }
  TextDirectionHeuristic heuristic() {
    return direction==CaptionLanguageProfile.Direction.RTL ? TextDirectionHeuristics.RTL
        : direction==CaptionLanguageProfile.Direction.LTR ? TextDirectionHeuristics.LTR
        : TextDirectionHeuristics.FIRSTSTRONG_LTR;
  }
  void apply(TextView view) {
    view.setTextDirection(direction==CaptionLanguageProfile.Direction.RTL ? View.TEXT_DIRECTION_RTL
        : direction==CaptionLanguageProfile.Direction.LTR ? View.TEXT_DIRECTION_LTR
        : View.TEXT_DIRECTION_FIRST_STRONG_LTR);
    view.setLayoutDirection(direction==CaptionLanguageProfile.Direction.RTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    if(!locale.equals(view.getTextLocale()))view.setTextLocale(locale);
  }
  TextPaint paint(float sizePx) {
    TextPaint p=measurementPaint==null?new TextPaint(Paint.ANTI_ALIAS_FLAG):new TextPaint(measurementPaint);
    if(measurementPaint==null){p.setTypeface(Typeface.DEFAULT);p.setTextLocale(locale);}p.setTextSize(sizePx);return p;
  }
  StaticLayout layout(String value, float sizePx, int width) {
    layoutCalls++;
    TextPaint paint=measurementPaint==null ? new TextPaint(Paint.ANTI_ALIAS_FLAG) : new TextPaint(measurementPaint);
    if(measurementPaint==null) {paint.setTypeface(Typeface.DEFAULT);paint.setTextLocale(locale);}
    paint.setTextSize(sizePx);
    StaticLayout.Builder builder=StaticLayout.Builder.obtain(value,0,value.length(),paint,Math.max(1,width))
        .setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    builder.setTextDirection(
        measurementDirection==null ? heuristic() : measurementDirection).setAlignment(Layout.Alignment.ALIGN_CENTER);
    return builder.build(); // No maxLines/ellipsis: measure the complete text before approving it.
  }
  boolean fits(String value, float sizePx, int width, int lines) {
    StaticLayout layout=layout(value,sizePx,width);
    return fits(value,layout,width,lines);
  }
  boolean fits(String value, Layout layout, int width, int lines) {
    if(layout==null || layout.getLineCount()>lines || layout.getLineCount()<1) return false;
    if(layout.getLineEnd(layout.getLineCount()-1)!=value.length()) return false;
    int[] boundaries=CaptionUnicode.characterBoundaries(value,locale);
    for(int i=0;i<layout.getLineCount();i++) {
      if(layout.getEllipsisCount(i)>0 || layout.getLineMax(i)>width+.01f
          || Arrays.binarySearch(boundaries,layout.getLineStart(i))<0
          || Arrays.binarySearch(boundaries,layout.getLineEnd(i))<0) return false;
    }
    return true;
  }
  float actualWidth(Layout layout) {
    float width=0;
    for(int i=0;i<layout.getLineCount();i++) width=Math.max(width,layout.getLineMax(i));
    return width;
  }
  int readingUnits(String text) { return CaptionUnicode.readingUnits(text,profile,locale); }
  double lineUnits(String text) { return CaptionUnicode.lineHalfUnits(text,profile,locale)/2.0; }
  double lineUnits(String text,Layout layout) {
    double units=0;
    for(int i=0;i<layout.getLineCount();i++) units=Math.max(units,
        lineUnits(text.substring(layout.getLineStart(i),layout.getLineEnd(i))));
    return units;
  }
}
