package app.yydarlinker.deepseekcaptions;

/** Immutable measured capacity. UI supplies geometry; the upstream review keeps its own policy. */
final class TranslationBudget extends CaptionPresentationBudget {
    TranslationBudget(int width, float minimum, float preferred, CaptionRenderSpec spec) {
        super(width, minimum, preferred, spec);
    }

    TranslationBudget withSpec(CaptionRenderSpec spec) {
        return new TranslationBudget(width, minimumPx, preferredPx, spec);
    }

    boolean canPresent(RebuildProtocol.Event event) {
        return canPresent(event, renderSpec);
    }

    boolean canPresent(RebuildProtocol.Event event, CaptionRenderSpec spec) {
        return !RebuildPageLayout.plan(event.text, event.start, event.end, this, spec).isEmpty();
    }
}
