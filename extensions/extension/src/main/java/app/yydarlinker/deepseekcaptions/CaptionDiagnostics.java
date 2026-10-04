package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.SharedPreferences;

/** Small on-device trace so subtitle failures can be diagnosed without adb/logcat. */
final class CaptionDiagnostics {
    private static final String PREFS = "deepseek_caption_diagnostics";
    private static final String STAGE = "stage";
    private static final String DETAIL = "detail";
    private static final String TIME = "time";
    private static final String HISTORY = "history";
    private static final int MAX_HISTORY = 8000;
    private static final String DECISIONS = "timing_and_protocol_decisions";

    private CaptionDiagnostics() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void mark(Context context, String stage, String detail) {
        if (context == null) return;
        try {
            String cleanStage = sanitize(stage, 80);
            String cleanDetail = sanitize(CaptionQualityTrace.redact(detail, DeepSeekConfig.load(context).apiKey, 1600), 1700);
            long now = System.currentTimeMillis();
            SharedPreferences p = prefs(context);
            String old = p.getString(HISTORY, "");
            String line = now + " | " + cleanStage + (cleanDetail.isEmpty() ? "" : " | " + cleanDetail);
            CaptionDiagnosticArchive.append(context, "history", line);
            String next = old == null || old.isEmpty() ? line : line + "\n" + old;
            if (next.length() > MAX_HISTORY) next = next.substring(0, MAX_HISTORY);
            // Keep bounded clock/rejection evidence separate from noisy display selections.
            if(importantDecision(cleanStage)) {
                String decisions=p.getString(DECISIONS, "");
                decisions=line+(decisions.isEmpty()?"":"\n"+decisions);
                if(decisions.length()>3000)decisions=decisions.substring(0,3000);
                p.edit().putString(DECISIONS,decisions).apply();
            }
            p.edit()
                    .putString(STAGE, cleanStage)
                    .putString(DETAIL, cleanDetail)
                    .putLong(TIME, now)
                    .putString(HISTORY, next)
                    .apply();

        } catch (Throwable ignored) {
        }
    }

    static void clear(Context context) {
        try { prefs(context).edit().clear().apply(); } catch (Throwable ignored) {}
        try { CaptionDiagnosticArchive.clear(context); } catch (Throwable ignored) {}
        try { CaptionQualityTrace.clear(context); } catch (Throwable ignored) {}
        try { TokenCostAudit.clear(context); } catch (Throwable ignored) {}
    }

    /**
     * The three localized header lines. Each one is a single authored template: the engine label, the
     * mode sentence and the debug state are never assembled from translated fragments here, and the
     * "On"/"Off" wording comes from the catalog so it stays grammatical in every language.
     */
    private static String localizedHeader(Context context) {
        String separator = CaptionStrings.settings(context, "label_separator");
        String engine = format(separator, CaptionStrings.settings(context, "engine"))
                + CaptionStrings.settings(context, "engine_event_rebuild") + " / " + RebuildProtocol.VERSION;
        String mode = format(separator, CaptionStrings.settings(context, "mode"))
                + CaptionStrings.settings(context, CaptionChoice.translates()
                        ? "mode_auto_translate" : "mode_original");
        String debug = format(CaptionStrings.settings(context, "display_debug"),
                CaptionStrings.settings(context, DeepSeekConfig.displayTextDebugEnabled(context) ? "on" : "off"));
        return engine + "\n" + mode + "\n" + debug;
    }

    /** One positional substitution into an authored template. */
    private static String format(String template, Object... values) {
        return String.format(java.util.Locale.ROOT, template, values);
    }

    /**
     * The diagnostics panel body. UI text is resolved through the settings catalog so it follows the
     * interface language; every recorded source line, translation, provider reply, identifier, counter
     * and timestamp is appended verbatim and is never translated.
     */
    static String uiText(Context context) {
        return uiText(context, true);
    }

    /**
     * The raw diagnostic report. {@code localized=false} is the export form used by
     * {@link #fullText(Context)}: its headings stay exactly as they were, because the saved report has to
     * remain byte-comparable with earlier exports and must not absorb the reader's interface language.
     */
    static String uiText(Context context, boolean localized) {
        try {
            SharedPreferences p = prefs(context);
            String stage = p.getString(STAGE, "");
            String detail = p.getString(DETAIL, "");
            long time = p.getLong(TIME, 0L);
            String audit = TokenCostAudit.uiText(context, localized);
            String header = localized
                    ? localizedHeader(context)
                    : "Engine: Event rebuild / " + RebuildProtocol.VERSION + "\nMode: "
                            + (CaptionChoice.translates() ? "automatic translation" : "original captions (no translation API)")
                            + "\nDisplay text debug: " + (DeepSeekConfig.displayTextDebugEnabled(context) ? "on" : "off");
            if (stage == null || stage.isEmpty()) {
                String base = localized
                        ? CaptionStrings.settings(context, "no_request_yet")
                        : "No automatic translation request captured yet. Enable translation, set an API key, play a video, select a target language, then refresh diagnostics.";
                return audit == null || audit.isEmpty()
                        ? header + "\n" + base
                        : header + "\n" + base + "\n\n" + audit;
            }
            long seconds = time <= 0 ? -1 : Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
            String age = seconds < 0 ? "" : localized
                    ? " " + format(CaptionStrings.settings(context, "age_open"), seconds)
                            + CaptionStrings.settings(context, "age_close")
                    : " (about " + seconds + " seconds ago)";
            StringBuilder text = new StringBuilder();
            text.append(header).append("\n");
            text.append(localized
                            ? format(CaptionStrings.settings(context, "label_separator"),
                                    CaptionStrings.settings(context, "message_44feb4d98d48"))
                            : "Latest stage: ")
                    .append(stage).append(age);
            if (detail != null && !detail.isEmpty()) text.append("\n").append(detail);
            if (audit != null && !audit.isEmpty()) {
                text.append("\n\n").append(audit);
            }
            String decisions=p.getString(DECISIONS, "");
            if(!decisions.isEmpty())text.append("\n\n")
                    .append(localized ? CaptionStrings.settings(context, "timing_decisions") : "Timing decisions and errors (preserved with timestamps):\n")
                    .append(decisions);
            String history = p.getString(HISTORY, "");
            if (history != null && !history.isEmpty()) {
                text.append("\n\n")
                        .append(localized ? CaptionStrings.settings(context, "recent_trace") : "Recent trace:\n")
                        .append(history);
            }
            text.append(CaptionQualityTrace.text(context));
            return text.toString(); // Recorded source/translation/provider evidence must remain verbatim.
        } catch (Throwable error) {
            return (localized ? CaptionStrings.settings(context, "message_75a885d3b526") : "Failed to read diagnostics: ")
                    + error.getClass().getSimpleName();
        }
    }

    /**
     * The saved export. Its header is the raw, unlocalized report so an exported file keeps the format
     * earlier exports used, and the manifest and archive channels are appended unchanged.
     */
    static String fullText(Context c) {
        String history=CaptionDiagnosticArchive.read(c,"history"),quality=CaptionDiagnosticArchive.read(c,"quality");
        return uiText(c,false) + "\n\n[Export manifest; ui="+app.yydarlinker.extension.BuildConfig.CAPTION_PATCH_VERSION+"; engine="+RebuildProtocol.VERSION+"; build=n33; official=1.45.0; presentation=n29-presentation-v3"+"; exported_at="+System.currentTimeMillis()
            +"; completeness=bounded_not_guaranteed; history_records="+records(history,false)+"; quality_records="+records(quality,true)
            +"; truncation_markers="+(occurrences(history,"record truncated")+occurrences(quality,"record truncated"))
            +"; debug="+DeepSeekConfig.displayTextDebugEnabled(c)+"]\n"
            + "\n[Extended history: chronological; last 24h; up to 8 MiB per channel]\n"
            + history + "\n[Extended quality evidence; captured only while debug enabled]\n"+quality;
    }

    private static int records(String text,boolean json){int n=0;for(String line:text.split("\n"))if(json?line.startsWith("{"):line.matches("^[0-9]+ \\|.*"))n++;return n;}
    private static int occurrences(String text,String needle){int n=0,p=0;while((p=text.indexOf(needle,p))>=0){n++;p+=needle.length();}return n;}

    private static boolean importantDecision(String stage) {
        return (stage.startsWith("REBUILD_") && !stage.equals("REBUILD_SELECTED") && !stage.equals("REBUILD_PRESENTED") && !stage.equals("REBUILD_DISPLAY")) || stage.equals("SOURCE_RETRY_SCHEDULED") || stage.equals("SOURCE_LOAD_FAILED")
                || stage.equals("CONTEXTUAL_CORE_ERROR") || stage.equals("CONTEXTUAL_SEEK_REPRIORITIZED")
                || stage.equals("ASR_CUE_TIMING_BASE") || stage.equals("ASR_CUE_TIMING_APPLIED")
                || stage.equals("ASR_REFERENCE_FETCH_FAILED") || stage.equals("SOURCE_TIMING_FALLBACK")
                || stage.equals("SOURCE_TIMING_CALIBRATED") || stage.equals("SOURCE_TIMING_CONFIRMED")
                || stage.equals("ANCHOR_RESPONSE_REJECTED") || stage.equals("CONTEXTUAL_BATCH_FAILED")
                || stage.equals("FIRST_AI_READY") || stage.equals("SOURCE_TIMING_BASE")
                || stage.equals("ASR_LOCAL_TIMING_APPLIED") || stage.equals("ASR_LOCAL_TIMING_REJECTED")
                || stage.equals("ASR_NATIVE_WORD_TIMING_SELECTED") || stage.equals("ASR_NATIVE_WORD_TIMING_ALIGNED")
                || stage.equals("ASR_WORD_TIMING_UNAVAILABLE") || stage.equals("ENGINE_MODE_SAVED")
                || stage.equals("NATIVE_APPLIED_CAPTURE_FAILED") || stage.equals("NATIVE_APPLIED_OWNER_REJECTED")
                || stage.equals("OVERLAY_READABILITY_DEGRADED") || stage.equals("ENGINE_SNAPSHOT_ACTIVATED") || stage.equals("BACKGROUND_ACTIVATION_IGNORED");
    }

    private static String sanitize(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    static String errorDetail(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return sanitize(message, 220);
    }
}
