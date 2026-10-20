package app.yydarlinker.deepseekcaptions;

/**
 * The caption choice of this app launch: whether captions are on and in which language. It keeps no
 * default language and no url or track object of any video.
 */
final class CaptionChoice {
    private static String language = "";
    private static boolean translation, on, chosen, userOff, explicitOff;

    private CaptionChoice() {}

    static synchronized void select(String code, boolean translate) {
        if (code == null || code.trim().isEmpty() || code.endsWith("_OPTION")) return;
        language = code;
        translation = translate;
        on = true;
        chosen = true;
        userOff = false;
        explicitOff = false;
    }

    /** A user decision: off stays off across automatic track rebuilds and video re-entry. */
    static synchronized void toggle(boolean enabled) {
        on = enabled;
        if (!enabled) chosen = true;
        userOff = !enabled;
        if (enabled) explicitOff = false;
    }

    /** The Auto-translate menu was explicitly set to Off; an engine toggle must not clear it. */
    static synchronized void markExplicitOff() {
        on = false;
        chosen = true;
        userOff = true;
        explicitOff = true;
    }

    static synchronized boolean explicitOff() {
        return explicitOff;
    }

    /** The player reset its own caption model; this is not a user decision and never becomes one. */
    static synchronized void automaticOff() {
        on = false;
    }

    static synchronized boolean userOff() {
        return userOff;
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

    /** The explicit target of this launch, also across an automatic caption-model reset. */
    static synchronized String targetLanguage() {
        return chosen && translation && !userOff && !language.isEmpty() ? language : "";
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
        translation = on = chosen = userOff = explicitOff = false;
    }
}
