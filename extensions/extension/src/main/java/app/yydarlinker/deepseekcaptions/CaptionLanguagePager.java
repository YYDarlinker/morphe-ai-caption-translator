package app.yydarlinker.deepseekcaptions;

import android.text.StaticLayout;
import java.util.*;

/** Non-Chinese presentation-only pagination, wholly inside one accepted event. */
final class CaptionLanguagePager {
  private CaptionLanguagePager() {}
  static List<RebuildPageLayout.Page> plan(String text,long start,long end,
      CaptionOverlay.LayoutBudget budget,CaptionRenderSpec spec) {
    if(text==null || text.isEmpty() || end<=start || !wellFormed(text,spec))
      return Collections.emptyList();
    int[] characters=CaptionUnicode.characterBoundaries(text,spec.locale);
    int[] lines=CaptionUnicode.lineBoundaries(text,spec.locale);
    List<RebuildPageLayout.Page> pages=choose(text,start,end,lines,budget,spec);
    // Only a geometrically insoluble locale-line plan may split an unbreakable word.
    // Every emergency seam still belongs to one complete ICU grapheme; never cut NBSP glue.
    if(!pages.isEmpty()) return pages;
    int[] emergency=Arrays.stream(characters).filter(n -> n==0 || n==text.length()
        || text.charAt(n-1)!='\u00a0' && text.charAt(n)!='\u00a0').toArray();
    return choose(text,start,end,emergency,budget,spec);
  }
  private static boolean wellFormed(String text,CaptionRenderSpec spec) {
    for(int n=0;n<text.length();n++) {
      char c=text.charAt(n);
      if(Character.isHighSurrogate(c)) {
        if(n+1>=text.length() || !Character.isLowSurrogate(text.charAt(++n))) return false;
      } else if(Character.isLowSurrogate(c)) return false;
    }
    int[] cuts=CaptionUnicode.characterBoundaries(text,spec.locale);
    for(int i=1;i<cuts.length;i++) {
      int cp=text.codePointAt(cuts[i-1]),type=Character.getType(cp);
      if(type==Character.NON_SPACING_MARK || type==Character.COMBINING_SPACING_MARK
          || type==Character.ENCLOSING_MARK) return false;
    }
    return true;
  }
  private static List<RebuildPageLayout.Page> choose(String text,long start,long end,int[] cuts,
      CaptionOverlay.LayoutBudget budget,CaptionRenderSpec spec) {
    int n=cuts.length;long duration=end-start;
    double[] cost=new double[n];Arrays.fill(cost,Double.POSITIVE_INFINITY);cost[0]=0;
    int[] previous=new int[n],pages=new int[n];Arrays.fill(previous,-1);
    int total=Math.max(1,spec.readingUnits(text));
    for(int from=0;from<n-1;from++) {
      if(!Double.isFinite(cost[from])) continue;
      for(int to=from+1;to<n;to++) {
        String part=text.substring(cuts[from],cuts[to]);
        StaticLayout layout=spec.layout(part,budget.preferredPx,budget.width);
        if(layout.getLineCount()>spec.maxLines) break;
        if(!spec.fits(part,layout,budget.width,spec.maxLines) || pages[from]+1>duration) continue;
        double ms=duration*Math.max(1,spec.readingUnits(part))/(double)total;
        double lineUnits=0;
        for(int line=0;line<layout.getLineCount();line++) lineUnits=Math.max(lineUnits,
            spec.lineUnits(part.substring(layout.getLineStart(line),layout.getLineEnd(line))));
        double cpl=Math.max(0,lineUnits-spec.profile.referenceCpl);
        double score=cost[from]+200+(layout.getLineCount()==1 ? 0 : 1800)
            +cpl*cpl+Math.max(0,ms-spec.profile.softPageDurationMs)/20;
        if(to<n-1) {
          int at=cuts[to];while(at>cuts[from] && Character.isWhitespace(text.codePointBefore(at)))
            at-=Character.charCount(text.codePointBefore(at));
          if(at>cuts[from] && ".!?;:，。！？；：".indexOf(text.codePointBefore(at))>=0) score-=80;
        }
        if(score<cost[to]) {cost[to]=score;previous[to]=from;pages[to]=pages[from]+1;}
      }
    }
    if(previous[n-1]<0) return Collections.emptyList();
    Deque<String> chosen=new ArrayDeque<>();
    for(int to=n-1;to>0;to=previous[to]) chosen.addFirst(text.substring(cuts[previous[to]],cuts[to]));
    List<String> parts=new ArrayList<>(chosen); // Backtrack edges into source order; text is unchanged.
    long[] weights=new long[parts.size()];long sum=0;
    for(int i=0;i<parts.size();i++) {weights[i]=Math.max(1,spec.readingUnits(parts.get(i)));sum+=weights[i];}
    List<RebuildPageLayout.Page> result=new ArrayList<>();long at=start,used=0,cumulative=0;
    long extra=duration-parts.size();
    for(int i=0;i<parts.size();i++) {
      cumulative+=weights[i];long distributed=(long)(extra*(cumulative/(double)sum));
      long next=i==parts.size()-1 ? end : at+1+distributed-used;
      result.add(new RebuildPageLayout.Page(parts.get(i),at,next));at=next;used=distributed;
    }
    return Collections.unmodifiableList(result);
  }
}
