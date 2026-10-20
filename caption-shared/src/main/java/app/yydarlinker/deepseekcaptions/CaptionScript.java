package app.yydarlinker.deepseekcaptions;

import android.icu.lang.UScript;
import java.util.Locale;

/**
 * Script-level typography facts, not language policy. It answers two questions that used to be
 * hard-coded as "is the target Chinese?":
 *
 * <ul>
 *   <li>does this script set words together without an inter-word space (Han, Kana, Thai, Lao,
 *       Khmer, Myanmar)? Korean is written WITH spaces between eojeol and is deliberately not in
 *       this set;
 *   <li>must a visible space separate two adjacent runs of this script?
 * </ul>
 *
 * <p>The answer is derived from ICU script properties so a target language that was never listed
 * in a profile still gets correct spacing and line-break behaviour.
 */
final class CaptionScript {
    private CaptionScript() {}

    /** True when the locale's dominant script is written without spaces between words. */
    static boolean compact(Locale locale) {
        int[] codes = UScript.getCode(locale);
        if (codes == null || codes.length == 0) return false;
        return compact(codes[0]);
    }

    static boolean compact(int script) {
        switch (script) {
            case UScript.HAN:
            case UScript.HIRAGANA:
            case UScript.KATAKANA:
            case UScript.THAI:
            case UScript.LAO:
            case UScript.KHMER:
            case UScript.MYANMAR:
                return true;
            default:
                return false;
        }
    }

    /**
     * A joining space is needed only between two word characters whose scripts both use spaces.
     * Punctuation already carries the separation; a compact-script side sets tight.
     */
    static boolean requiresSpace(int left, int right) {
        if (left < 0 || right < 0) return false;
        if (Character.isWhitespace(left) || Character.isWhitespace(right)) return false;
        if (!Character.isLetterOrDigit(left) || !Character.isLetterOrDigit(right)) return false;
        int leftScript = scriptOf(left);
        int rightScript = scriptOf(right);
        // Chinese + Apple, Japanese + YouTube: a compact script next to a spaced one keeps the
        // visible word space. Only two compact-script sides set tight.
        return !(compact(leftScript) && compact(rightScript));
    }

    private static int scriptOf(int cp) {
        int script = UScript.getScript(cp);
        return script < 0 ? UScript.COMMON : script;
    }

    /** The primary ICU script of a language tag, or -1 when it cannot be determined. */
    static int primary(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return -1;
        try {
            int[] codes = UScript.getCode(new Locale.Builder().setLanguageTag(languageTag).build());
            return codes == null || codes.length == 0 ? -1 : codes[0];
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }
}
