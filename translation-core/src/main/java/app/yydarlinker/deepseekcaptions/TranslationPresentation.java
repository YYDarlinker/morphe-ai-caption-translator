package app.yydarlinker.deepseekcaptions;

import android.text.StaticLayout;

import java.util.*;

/** Projects accepted upstream events into immutable pages without application/UI dependencies. */
final class TranslationPresentation {
    static List<CaptionDisplayPage> pages(
            String text,
            long start,
            long end,
            CaptionPresentationBudget geometry,
            CaptionRenderSpec spec) {
        TranslationBudget budget =
                new TranslationBudget(
                        geometry.width, geometry.minimumPx, geometry.preferredPx, spec);
        List<RebuildPageLayout.Page> planned =
                RebuildPageLayout.plan(text, start, end, budget, spec);
        List<CaptionDisplayPage> result = new ArrayList<>();
        int from = 0;
        for (RebuildPageLayout.Page page : planned) {
            if (!text.startsWith(page.text, from))
                throw new IllegalStateException("upstream_page_canonical_mismatch");
            int to = from + page.text.length();
            StaticLayout layout = spec.layout(page.text, budget.preferredPx, budget.width);
            int cut = layout.getLineCount() > 1 ? from + layout.getLineEnd(0) : to;
            CaptionLinePlan line =
                    new CaptionLinePlan(
                            text,
                            from,
                            to,
                            cut,
                            layout.getLineMax(0),
                            layout.getLineCount() > 1 ? layout.getLineMax(1) : 0,
                            CaptionLinePlan.CutClass.LANGUAGE_BOUNDARY);
            result.add(
                    new CaptionDisplayPage(
                            line.displayText,
                            page.start,
                            page.end,
                            from,
                            to,
                            page.timingBasis,
                            line));
            from = to;
        }
        if (!result.isEmpty() && from != text.length())
            throw new IllegalStateException("upstream_page_coverage");
        return Collections.unmodifiableList(result);
    }
}
