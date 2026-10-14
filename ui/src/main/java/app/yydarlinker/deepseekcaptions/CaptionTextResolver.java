package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/** UI text only. A configuration context never leaves string(), and is never retained. */
final class CaptionTextResolver {
    private static final String SETTINGS = "app.morphe.extension.shared.settings.";
    private static final String YOUTUBE = "com.google.android.youtube";

    private CaptionTextResolver() {}

    /** The language Morphe is set to, else the language of the app. */
    static Locale locale(Context caller) {
        Locale chosen = morpheLocale();
        if (chosen != null) return chosen;
        try {
            return caller.getResources().getConfiguration().getLocales().get(0);
        } catch (RuntimeException unavailable) {
            return Locale.ENGLISH;
        }
    }

    /** Morphe's own language setting, or null when it follows the system or is not installed. */
    private static Locale morpheLocale() {
        try {
            Object setting =
                    Class.forName(SETTINGS + "BaseSettings").getField("MORPHE_LANGUAGE").get(null);
            Class<?> enumSetting = Class.forName(SETTINGS + "EnumSetting");
            if (!enumSetting.isInstance(setting)) return null;
            java.lang.reflect.Method get = enumSetting.getMethod("get");
            if (!Enum.class.isAssignableFrom(get.getReturnType())) return null;
            Object value = get.invoke(setting);
            Class<?> language = Class.forName(SETTINGS + "AppLanguage");
            if (!language.isInstance(value) || "DEFAULT".equals(((Enum<?>) value).name()))
                return null;
            java.lang.reflect.Method getLocale = language.getMethod("getLocale");
            if (getLocale.getReturnType() != Locale.class) return null;
            Object selected = getLocale.invoke(value);
            return selected instanceof Locale ? (Locale) selected : null;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError unavailable) {
            return null;
        }
    }

    /** The text of [key] in the language of the app. */
    static String string(Context caller, String key) {
        return string(caller, locale(caller), key);
    }

    /**
     * The text of [key] in [locale], read through a configuration context that exists only for this
     * call. A key without a resource is shown as it is.
     */
    static String string(Context caller, Locale locale, String key) {
        if (caller == null) return key;
        int id = identifier(caller, key);
        if (id == 0) return key;
        Configuration configuration = new Configuration(caller.getResources().getConfiguration());
        configuration.setLocale(locale);
        return caller.createConfigurationContext(configuration).getString(id);
    }

    private static int identifier(Context caller, String key) {
        String name = "cap_" + key;
        try {
            Object id =
                    Class.forName("app.morphe.extension.shared.ResourceUtils")
                            .getMethod("getStringIdentifier", String.class)
                            .invoke(null, name);
            // Confirm that the id belongs to this resource table, including renamed APKs.
            if (id instanceof Integer
                    && (Integer) id != 0
                    && name.equals(caller.getResources().getResourceEntryName((Integer) id)))
                return (Integer) id;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // A standalone host: look the name up directly.
        }
        int id = caller.getResources().getIdentifier(name, "string", caller.getPackageName());
        return id != 0 ? id : caller.getResources().getIdentifier(name, "string", YOUTUBE);
    }

    /**
     * Gives [view] the layout and text direction of the app language. Data (an editor holding a url
     * or a key) stays left to right.
     */
    static void direction(View view, boolean data) {
        Locale selected = locale(view.getContext());
        boolean rtl =
                !data
                        && TextUtils.getLayoutDirectionFromLocale(selected)
                                == View.LAYOUT_DIRECTION_RTL;
        int layout = rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        int text =
                data
                        ? View.TEXT_DIRECTION_LTR
                        : rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LOCALE;
        if (view.getLayoutDirection() != layout) view.setLayoutDirection(layout);
        if (view.getTextDirection() != text) view.setTextDirection(text);
        if (view instanceof TextView) {
            LocaleList locales = new LocaleList(selected);
            if (!locales.equals(((TextView) view).getTextLocales()))
                ((TextView) view).setTextLocales(locales);
        }
    }
}
