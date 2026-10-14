package app.yydarlinker.deepseekcaptions;

/**
 * The caption choice of this app launch: whether captions are on and in which language. It keeps no
 * default language and no url or track object of any video.
 */
final class CaptionChoice {
    private static String language = "";
    private static boolean translation, on, chosen;

    private CaptionChoice() {}

    static synchronized void select(String code, boolean translate) {
        if (code == null || code.trim().isEmpty() || code.endsWith("_OPTION")) return;
        language = code;
        translation = translate;
        on = true;
        chosen = true;
    }

    static synchronized void toggle(boolean enabled) {
        on = enabled;
        if (!enabled) chosen = true;
    }

    static synchronized boolean isOn() {
        return chosen && on && !language.isEmpty();
    }

    /** Has the user (or the remembered selection) decided anything yet, even "off"? */
    static synchronized boolean known() {
        return chosen;
    }

    static synchronized boolean translates() {
        return translation;
    }

    /** Only an actual Auto-translate selection may use the paid API. */
    static synchronized boolean allowsTranslation() {
        return isOn() && translation;
    }

    static synchronized String language() {
        return language;
    }

    static synchronized void reset() {
        language = "";
        translation = on = chosen = false;
    }
}
