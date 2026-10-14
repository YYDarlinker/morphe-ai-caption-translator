package app.yydarlinker.deepseekcaptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The pages that the translation core planned, as the caption box needs them. */
final class CaptionPageLayout {
    private CaptionPageLayout() {}

    static final class Page {
        final String text, timingBasis;
        final long start, end;
        final int targetFrom, targetTo;
        final CaptionLinePlan linePlan;

        Page(CaptionDisplayPage page) {
            text = page.text;
            timingBasis = page.timingBasis;
            start = page.start;
            end = page.end;
            targetFrom = page.fromUtf16;
            targetTo = page.toUtf16;
            linePlan = page.linePlan;
        }

        boolean contains(long at) {
            return start <= at && at < end;
        }
    }

    static List<Page> adapt(List<CaptionDisplayPage> values) {
        List<Page> pages = new ArrayList<>();
        for (CaptionDisplayPage value : values) pages.add(new Page(value));
        return Collections.unmodifiableList(pages);
    }

    /** The page that is shown at [at], or -1. */
    static int indexAt(List<Page> pages, long at) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).contains(at)) return i;
        return -1;
    }
}
