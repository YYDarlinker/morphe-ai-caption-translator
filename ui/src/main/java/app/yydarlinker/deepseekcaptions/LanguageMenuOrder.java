package app.yydarlinker.deepseekcaptions;

import android.content.Context;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Insert only the added language using the app locale and native labels. Never force Chinese to the
 * end.
 */
public final class LanguageMenuOrder {
    static Locale locale() {
        Context c = CaptionAddonSupport.context();
        return c == null
                ? Locale.getDefault()
                : c.getResources().getConfiguration().getLocales().get(0);
    }

    private static String simplifiedLabel() {
        String app = appLabel("language_zh_hans");
        if (app != null) return app;
        Locale display = locale();
        if ("zh".equals(display.getLanguage())) {
            boolean traditional =
                    "Hant".equals(display.getScript())
                            || Arrays.asList("TW", "HK", "MO").contains(display.getCountry());
            return traditional ? "中文（簡體）" : "中文（简体）";
        }
        return Locale.forLanguageTag("zh-Hans").getDisplayName(display);
    }

    /**
     * The Traditional Chinese label follows the same app resource as the settings picker. ICU's
     * display name can be "中文 (繁体)" (ASCII space) or, on some platform data, redundantly
     * "中文（繁体中文）"; neither is the product label.
     */
    private static String traditionalLabel() {
        String app = appLabel("language_zh_hant");
        if (app != null) return app;
        Locale display = locale();
        if ("zh".equals(display.getLanguage())) {
            boolean simplified =
                    "Hans".equals(display.getScript())
                            || Arrays.asList("CN", "SG").contains(display.getCountry());
            return simplified ? "中文（繁体）" : "中文（繁體）";
        }
        return Locale.forLanguageTag("zh-Hant").getDisplayName(display);
    }

    /** The localized app resource for [key], or null when this host has no caption resources. */
    private static String appLabel(String key) {
        Context c = CaptionAddonSupport.context();
        if (c == null) return null;
        try {
            String value = CaptionStrings.get(c, key);
            if (value == null || value.trim().isEmpty()) return null;
            // A missing resource returns the key verbatim; never show that as a language name.
            return value.equals(key) || value.startsWith("language_") ? null : value;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static int rank(String code) {
        String s = code == null ? "" : code.toLowerCase(Locale.ROOT);
        return s.equals("zh-hans") || s.equals("zh-cn")
                ? 1
                : s.equals("zh-hant") || s.equals("zh-tw") || s.equals("zh-hk") ? 2 : 0;
    }

    static String label(String code) {
        if (rank(code) == 1) return simplifiedLabel();
        if ("zh-hant".equalsIgnoreCase(code == null ? "" : code.trim())) return traditionalLabel();
        return Locale.forLanguageTag(code == null ? "" : code).getDisplayName(locale());
    }

    static String sortLabel(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "");
    }

    static int compare(String a, String b) {
        return Collator.getInstance(locale()).compare(sortLabel(label(a)), sortLabel(label(b)));
    }

    /** [values] with only the Simplified Chinese entry moved to where its label sorts. */
    static <T> List<T> sorted(List<T> values, Function<T, String> code) {
        List<T> out = new ArrayList<>();
        T simplified = null;
        for (T value : values) {
            if (rank(code.apply(value)) == 1) {
                if (simplified == null) simplified = value;
            } else out.add(value);
        }
        if (simplified == null) return new ArrayList<>(values);
        Collator collator = Collator.getInstance(locale());
        String name = label(code.apply(simplified));
        int at = out.size();
        for (int i = 0; i < out.size(); i++)
            if (collator.compare(sortLabel(name), sortLabel(label(code.apply(out.get(i))))) < 0) {
                at = i;
                break;
            }
        out.add(at, simplified);
        return out;
    }
}
