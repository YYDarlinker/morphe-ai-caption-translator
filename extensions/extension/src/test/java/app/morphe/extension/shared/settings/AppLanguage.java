package app.morphe.extension.shared.settings;
import java.util.Locale;
/** Test host has the exact public official 1.45 API; the original Activity stays Chinese. */
public enum AppLanguage {
    DEFAULT, OVERRIDE;
    public static Locale selected = Locale.JAPANESE;
    public Locale getLocale() { return selected; }
}
