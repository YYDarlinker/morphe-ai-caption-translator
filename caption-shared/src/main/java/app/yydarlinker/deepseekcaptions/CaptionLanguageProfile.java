package app.yydarlinker.deepseekcaptions;

import java.util.Collections;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable locale, direction and advisory reading references for complete-unit presentation. */
final class CaptionLanguageProfile {
    static final String POLICY_VERSION = "caption-reading-v4-r1";

    enum Direction {
        LTR,
        RTL,
        FIRST_STRONG
    }

    enum LineCounter {
        CJK,
        GRAPHEME,
        JAPANESE_WIDTH,
        KOREAN_WIDTH
    }

    final String id;
    final String policyVersion = POLICY_VERSION;
    final Locale locale;
    final Direction direction;
    final LineCounter lineCounter;
    final int referenceCps, referenceCpl;
    final long softPageDurationMs = 7000;
    final long minimumPageMs;

    private CaptionLanguageProfile(
            String id, Direction direction, LineCounter line, int cps, int cpl) {
        this.id = id;
        locale = Locale.forLanguageTag(id.equals("generic") ? "und" : id);
        this.direction = direction;
        lineCounter = line;
        referenceCps = cps;
        referenceCpl = cpl;
        minimumPageMs = id.equals("ja") ? 500 : 833;
    }

    private static final Map<String, CaptionLanguageProfile> PROFILES = new LinkedHashMap<>();
    static final CaptionLanguageProfile GENERIC =
            new CaptionLanguageProfile(
                    "generic", Direction.FIRST_STRONG, LineCounter.GRAPHEME, 17, 42);

    static {
        add("zh-Hans", Direction.LTR, LineCounter.CJK, 9, 16);
        add("zh-Hant", Direction.LTR, LineCounter.CJK, 9, 16);
        add("ja", Direction.LTR, LineCounter.JAPANESE_WIDTH, 4, 13);
        add("ko", Direction.LTR, LineCounter.KOREAN_WIDTH, 12, 16);
        add("en", Direction.LTR, LineCounter.GRAPHEME, 20, 42);
        add("th", Direction.LTR, LineCounter.GRAPHEME, 17, 35);
        for (String id : new String[] {"es", "fr", "de", "pt", "ru", "vi", "id"})
            add(id, Direction.LTR, LineCounter.GRAPHEME, 17, 42);
        add("ar", Direction.RTL, LineCounter.GRAPHEME, 20, 42);
        add("hi", Direction.LTR, LineCounter.GRAPHEME, 22, 42);
    }

    private static void add(String id, Direction direction, LineCounter line, int cps, int cpl) {
        PROFILES.put(id, new CaptionLanguageProfile(id, direction, line, cps, cpl));
    }

    static Map<String, CaptionLanguageProfile> profiles() {
        return Collections.unmodifiableMap(PROFILES);
    }

    /**
     * Normalizes metadata, never a Session target/URL/cache key. UNKNOWN is not a language guess.
     */
    static String normalizeCode(String code) {
        if (code == null) return "UNKNOWN";
        String tag = code.trim().replace('_', '-').replaceAll("\\s*-\\s*", "-");
        if (tag.isEmpty()
                || tag.length() > 128
                || !tag.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) return "UNKNOWN";
        try {
            Locale parsed = new Locale.Builder().setLanguageTag(tag).build();
            String primary = parsed.getLanguage();
            if (primary.isEmpty()
                    || primary.equals("und")
                    || primary.equals("unknown")
                    || primary.equals("zxx")
                    || primary.equals("mul")) return "UNKNOWN";
            return parsed.toLanguageTag();
        } catch (IllformedLocaleException e) {
            return "UNKNOWN";
        }
    }

    static CaptionLanguageProfile fromCode(String code) {
        String tag = normalizeCode(code);
        if (tag.equals("UNKNOWN")) return GENERIC;
        Locale parsed = Locale.forLanguageTag(tag);
        if (parsed.getLanguage().equals("zh")) {
            String script = parsed.getScript(), region = parsed.getCountry();
            if (script.equals("Hans")) return PROFILES.get("zh-Hans");
            if (script.equals("Hant")) return PROFILES.get("zh-Hant");
            if (!script.isEmpty()) return GENERIC;
            if (region.equals("CN") || region.equals("SG")) return PROFILES.get("zh-Hans");
            if (region.equals("TW") || region.equals("HK") || region.equals("MO"))
                return PROFILES.get("zh-Hant");
            return GENERIC;
        }
        CaptionLanguageProfile p = PROFILES.get(parsed.getLanguage());
        return p == null ? GENERIC : p;
    }

    String readingCounterId() {
        return "visible_grapheme";
    }

    String lineCounterId() {
        return lineCounter.name().toLowerCase(Locale.ROOT);
    }
}
