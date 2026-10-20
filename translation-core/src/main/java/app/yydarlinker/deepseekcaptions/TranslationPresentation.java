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
        return pages(text, start, end, geometry, spec, null);
    }

    static List<CaptionDisplayPage> pages(
            String text,
            long start,
            long end,
            CaptionPresentationBudget geometry,
            CaptionRenderSpec spec,
            long[] sourceAnchors) {
        if (text == null || text.isEmpty() || start < 0 || end <= start) {
            return Collections.emptyList();
        }
        TranslationBudget budget =
                new TranslationBudget(
                        geometry.width, geometry.minimumPx, geometry.preferredPx, spec);
        List<RebuildPageLayout.Page> planned;
        try {
            planned = RebuildPageLayout.plan(text, start, end, budget, spec, sourceAnchors);
        } catch (RuntimeException unplannable) {
            // A malformed or provider-supplied edge case must not turn into a blank caption.
            return Collections.singletonList(fullPage(text, start, end, budget, spec));
        }
        if (planned.isEmpty()) {
            return Collections.singletonList(fullPage(text, start, end, budget, spec));
        }
        List<CaptionDisplayPage> result = new ArrayList<>();
        int from = 0;
        for (RebuildPageLayout.Page page : planned) {
            if (!text.startsWith(page.text, from)) {
                // The planner must be a contiguous partition. If it is not, show the complete
                // translation rather than hiding the caption.
                return Collections.singletonList(fullPage(text, start, end, budget, spec));
            }
            int to = from + page.text.length();
            CaptionLinePlan line = linePlan(text, page.text, from, to, budget, spec);
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
        if (from != text.length())
            return Collections.singletonList(fullPage(text, start, end, budget, spec));
        return Collections.unmodifiableList(result);
    }

    /** Last-resort single page: full text, measured only to keep the box from failing. */
    private static CaptionDisplayPage fullPage(
            String text,
            long start,
            long end,
            TranslationBudget budget,
            CaptionRenderSpec spec) {
        return new CaptionDisplayPage(
                text,
                start,
                end,
                0,
                text.length(),
                "SOURCE_EVENT",
                linePlan(text, text, 0, text.length(), budget, spec));
    }

    private static CaptionLinePlan linePlan(
            String canonical,
            String pageText,
            int from,
            int to,
            TranslationBudget budget,
            CaptionRenderSpec spec) {
        try {
            StaticLayout layout = spec.layout(pageText, budget.preferredPx, budget.width);
            int cut = layout.getLineCount() > 1 ? from + layout.getLineEnd(0) : to;
            return new CaptionLinePlan(
                    canonical,
                    from,
                    to,
                    cut,
                    layout.getLineMax(0),
                    layout.getLineCount() > 1 ? layout.getLineMax(1) : 0,
                    CaptionLinePlan.CutClass.LANGUAGE_BOUNDARY);
        } catch (RuntimeException unmeasurable) {
            // No newline means the overlay still gets the complete text; its TextView will
            // perform the final visual clamp.
            return new CaptionLinePlan(
                    canonical,
                    from,
                    to,
                    to,
                    0,
                    0,
                    CaptionLinePlan.CutClass.EMERGENCY);
        }
    }
}
