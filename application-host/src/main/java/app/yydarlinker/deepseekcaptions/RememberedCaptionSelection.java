package app.yydarlinker.deepseekcaptions;

/**
 * The caption choice the user last made in this process. It knows nothing of the current video, the
 * API configuration or the AI gate.
 */
final class RememberedCaptionSelection {
    private static final int UNKNOWN = -1, OFF = 0, ON = 1;

    private static int decision = UNKNOWN;
    private static String language = "";
    private static boolean translated, asr;

    private RememberedCaptionSelection() {}

    static synchronized void select(String code, boolean translation, boolean automatic) {
        if (code == null || code.isEmpty() || code.endsWith("_OPTION")) return;
        language = code;
        translated = translation;
        asr = automatic;
        decision = ON;
    }

    static synchronized void off() {
        decision = OFF;
    }

    /** -1 when the user has not chosen, 0 for off, 1 for on. */
    static synchronized int decision() {
        return decision;
    }

    static synchronized String language() {
        return language;
    }

    static synchronized boolean translated() {
        return translated;
    }

    static synchronized boolean asr() {
        return asr;
    }

    static synchronized void reset() {
        decision = UNKNOWN;
        language = "";
        translated = asr = false;
    }
}
