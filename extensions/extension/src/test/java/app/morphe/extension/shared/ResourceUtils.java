package app.morphe.extension.shared;
import android.content.Context;
public class ResourceUtils {
    public static Context activity;
    public static String getString(String key) {
        if (activity == null) return key;
        int id=activity.getResources().getIdentifier(key,"string",activity.getPackageName());
        return id==0?key:activity.getString(id);
    }
    public static String getStringByLocale(String key, java.util.Locale ignored) { return getString(key); }
}
