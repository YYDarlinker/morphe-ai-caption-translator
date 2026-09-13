package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;

/** Stable compatibility facade plus the opt-in A/B core selector. */
final class PageCaptionController {
    private static final Object SELECT_LOCK = new Object();

    private static volatile Context appContext;
    private static volatile boolean contextual;
    private static volatile String currentVideoId = "";
    private static volatile String currentPlayerType = "";

    private PageCaptionController() {}

    static boolean isVisibleActive() {
        return contextual
                ? ContextualUnitCaptionController.isVisibleActive()
                : SemanticLedgerCaptionControllerV2.isVisibleActive();
    }

    static void deactivateFromCaptionButton() {
        synchronized (SELECT_LOCK) {
            if (contextual) ContextualUnitCaptionController.deactivateFromCaptionButton();
            else SemanticLedgerCaptionControllerV2.deactivateFromCaptionButton();
        }
    }

    static void deactivateFromNativeCaptionState() {
        synchronized (SELECT_LOCK) {
            if (contextual) ContextualUnitCaptionController.deactivateFromNativeCaptionState();
            else SemanticLedgerCaptionControllerV2.deactivateFromNativeCaptionState();
        }
    }

    static void setMainActivity(Activity activity) {
        synchronized (SELECT_LOCK) {
            if (activity != null) appContext = activity.getApplicationContext();
            selectLocked(appContext);
            // Both controllers currently only bind the same stable overlay activity reference.
            SemanticLedgerCaptionControllerV2.setMainActivity(activity);
            ContextualUnitCaptionController.setMainActivity(activity);
        }
    }

    static void onPlayerType(String rawType) {
        synchronized (SELECT_LOCK) {
            currentPlayerType = rawType == null ? "" : rawType;
            if (contextual) ContextualUnitCaptionController.onPlayerType(rawType);
            else SemanticLedgerCaptionControllerV2.onPlayerType(rawType);
        }
    }

    static String restoreTargetAfterMiniplayer(String url) {
        synchronized (SELECT_LOCK) {
            return contextual
                    ? ContextualUnitCaptionController.restoreTargetAfterMiniplayer(url)
                    : SemanticLedgerCaptionControllerV2.restoreTargetAfterMiniplayer(url);
        }
    }

    static void onVideoId(String videoId) {
        synchronized (SELECT_LOCK) {
            currentVideoId = videoId == null ? "" : videoId.trim();
            if (contextual) ContextualUnitCaptionController.onVideoId(videoId);
            else SemanticLedgerCaptionControllerV2.onVideoId(videoId);
            noteSelectedCoreLocked();
        }
    }

    static void refreshConfiguration(Context context) {
        synchronized (SELECT_LOCK) {
            selectLocked(context);
            if (contextual) ContextualUnitCaptionController.refreshConfiguration(context);
            else SemanticLedgerCaptionControllerV2.refreshConfiguration(context);
        }
    }

    static void activate(Context context, String translatedUrl) {
        synchronized (SELECT_LOCK) {
            selectLocked(context);
            if (contextual) ContextualUnitCaptionController.activate(context, translatedUrl);
            else SemanticLedgerCaptionControllerV2.activate(context, translatedUrl);
        }
    }

    static void prewarm(Context context, String sourceUrl) {
        synchronized (SELECT_LOCK) {
            selectLocked(context);
            if (contextual) ContextualUnitCaptionController.prewarm(context, sourceUrl);
            else SemanticLedgerCaptionControllerV2.prewarm(context, sourceUrl);
        }
    }

    static void observeTimedTextUrl(String url) {
        boolean useContextual;
        synchronized (SELECT_LOCK) {
            useContextual = contextual;
        }
        if (useContextual) ContextualUnitCaptionController.observeTimedTextUrl(url);
        else SemanticLedgerCaptionControllerV2.observeTimedTextUrl(url);
    }

    static void onVideoTime(long timeMs) {
        boolean useContextual;
        synchronized (SELECT_LOCK) {
            useContextual = contextual;
        }
        if (useContextual) ContextualUnitCaptionController.onVideoTime(timeMs);
        else SemanticLedgerCaptionControllerV2.onVideoTime(timeMs);
    }

    static String videoIdFromUrl(String url) {
        return SemanticLedgerCaptionControllerV2.videoIdFromUrl(url);
    }

    static String currentVideoIdSnapshot() {
        return currentVideoId;
    }

    private static void selectLocked(Context context) {
        if (context != null) appContext = context.getApplicationContext();
        Context app = appContext;
        boolean next = app != null && DeepSeekConfig.contextualUnitCoreEnabled(app);
        if (next == contextual) {
            noteSelectedCoreLocked();
            return;
        }

        boolean wasVisible = contextual
                ? ContextualUnitCaptionController.isVisibleActive()
                : SemanticLedgerCaptionControllerV2.isVisibleActive();
        String handoffUrl = contextual
                ? ContextualUnitCaptionController.activeTranslatedUrl()
                : SemanticLedgerCaptionControllerV2.activeTranslatedUrl();
        if (next) SemanticLedgerCaptionControllerV2.deactivateForCoreSwitch();
        else ContextualUnitCaptionController.deactivateForCoreSwitch();
        contextual = next;

        if (!currentVideoId.isEmpty()) {
            if (contextual) ContextualUnitCaptionController.onVideoId(currentVideoId);
            else SemanticLedgerCaptionControllerV2.onVideoId(currentVideoId);
        }
        if (!currentPlayerType.isEmpty()) {
            if (contextual) ContextualUnitCaptionController.onPlayerType(currentPlayerType);
            else SemanticLedgerCaptionControllerV2.onPlayerType(currentPlayerType);
        }
        noteSelectedCoreLocked();
        if (wasVisible && app != null && !handoffUrl.isEmpty()) {
            if (contextual) ContextualUnitCaptionController.activate(app, handoffUrl);
            else SemanticLedgerCaptionControllerV2.activate(app, handoffUrl);
        }
        if (app != null) {
            CaptionDiagnostics.mark(
                    app,
                    "TRANSLATION_CORE_SELECTED",
                    contextual
                            ? "实验 Contextual Unit Core"
                            : "v2.2.0 Semantic Ledger Core"
            );
        }
    }

    private static void noteSelectedCoreLocked() {
        Context app = appContext;
        if (app == null) return;
        TokenCostAudit.onCoreSelected(
                app,
                contextual ? "contextual_unit_v1" : "semantic_ledger_v2"
        );
    }
}