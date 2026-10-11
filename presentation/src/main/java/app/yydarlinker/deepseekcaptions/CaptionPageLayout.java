package app.yydarlinker.deepseekcaptions;
import java.util.*;
import app.yydarlinker.caption.contracts.CaptionContracts.Window;
/** UI values copied from upstream pages; no translation or time allocation policy. */
final class CaptionPageLayout {
    static final class Page {
        final String text,canonicalText,timingBasis,cutReason;
        final long start,end;
        final int targetFrom,targetTo;
        final boolean displayable=true;
        final CaptionLinePlan linePlan;
        final List<Window> windows;
        Page(CaptionDisplayPage v){text=v.text;canonicalText=v.linePlan.canonicalText();timingBasis=v.timingBasis;cutReason="upstream_page";start=v.start;end=v.end;targetFrom=v.fromUtf16;targetTo=v.toUtf16;linePlan=v.linePlan;windows=Collections.singletonList(new Window(start,end));}
        boolean contains(long at){return start<=at&&at<end;}
    }
    static List<Page> adapt(List<CaptionDisplayPage> values){List<Page> result=new ArrayList<>();for(CaptionDisplayPage value:values)result.add(new Page(value));return Collections.unmodifiableList(result);}
    static int indexAt(List<Page> pages,long at){for(int i=0;i<pages.size();i++)if(pages.get(i).contains(at))return i;return -1;}
}
