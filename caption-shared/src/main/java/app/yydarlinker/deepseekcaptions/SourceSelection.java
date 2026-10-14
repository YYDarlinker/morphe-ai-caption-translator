package app.yydarlinker.deepseekcaptions;

import app.yydarlinker.caption.contracts.CaptionContracts;

import java.util.Locale;

/** Actual source identity. Signed URLs stay in memory; diagnostics use track IDs and reasons. */
final class SourceSelection {
    static final String POLICY = "manual-first-v1";

    enum Kind {
        MANUAL,
        ASR,
        UNKNOWN
    }

    final String videoId, trackId, language, url, reason;
    final Kind kind;
    final boolean catalogComplete;
    final long catalogRevision;

    SourceSelection(
            String video,
            String id,
            String language,
            String url,
            Kind kind,
            boolean complete,
            long revision,
            String reason) {
        videoId = video;
        trackId = id;
        this.language = CaptionLanguageProfile.normalizeCode(language);
        this.url = url;
        this.kind = kind;
        catalogComplete = complete;
        catalogRevision = revision;
        this.reason = reason;
    }

    String identity() {
        return POLICY + "|" + videoId + "|" + trackId + "|" + language + "|" + kind;
    }

    String diagnostic() {
        return "policy="
                + POLICY
                + ";video="
                + videoId
                + ";track_identity_hash="
                + CaptionContracts.hash(identity())
                + ";actual_source_code="
                + language
                + ";kind="
                + kind
                + ";catalog_complete="
                + catalogComplete
                + ";catalog_revision="
                + catalogRevision
                + ";reason="
                + reason;
    }

    static boolean sameWritingLanguage(String source, String target) {
        Locale a = Locale.forLanguageTag(source), b = Locale.forLanguageTag(target);
        if (a.getLanguage().isEmpty()
                || !a.getLanguage().equals(b.getLanguage())
                || "UNKNOWN".equals(source)
                || "UNKNOWN".equals(target)) return false;
        if (a.getLanguage().equals("zh")) {
            String x = chineseScript(a), y = chineseScript(b);
            return !x.isEmpty() && x.equals(y);
        }
        return a.getScript().isEmpty()
                || b.getScript().isEmpty()
                || a.getScript().equals(b.getScript());
    }

    private static String chineseScript(Locale locale) {
        if (!locale.getScript().isEmpty()) return locale.getScript();
        switch (locale.getCountry()) {
            case "CN":
            case "SG":
                return "Hans";
            case "TW":
            case "HK":
            case "MO":
                return "Hant";
            default:
                return "";
        }
    }
}
