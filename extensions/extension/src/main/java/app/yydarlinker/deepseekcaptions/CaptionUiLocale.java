package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import java.util.Locale;

/** A settings-only snapshot. Never changes host resources, the global locale or menu sorting. */
public final class CaptionUiLocale {
    private CaptionUiLocale() {}
    private static final java.util.Map<Context,java.lang.ref.WeakReference<Snapshot>> contexts=new java.util.WeakHashMap<>();
    public static final class Snapshot {
        public final Context context;
        public final Locale locale;
        public final String source, identity;
        Snapshot(Context base, Locale selected, String source,Configuration configuration,String identity) {
            this.locale=selected; this.source=source;this.identity=identity;
            ContextThemeWrapper themed=new ContextThemeWrapper(base.createConfigurationContext(configuration),0);
            themed.getTheme().setTo(base.getTheme());
            context=themed;
        }
    }
    static Locale canonical(Locale value) {
        if("zh".equals(value.getLanguage())) {
            boolean traditional="Hant".equals(value.getScript()) ||
                    java.util.Arrays.asList("TW","HK","MO").contains(value.getCountry());
            return Locale.forLanguageTag(traditional?"zh-TW":"zh-CN");
        }
        return value;
    }
    public static Snapshot snapshot(Context supplied) {
        Context base=supplied!=null?supplied:CaptionAddonSupport.context();
        if(base==null)return null;
        Locale selected=base.getResources().getConfiguration().getLocales().get(0);
        String source="context_fallback";
        try {
            ClassLoader loader=base.getClassLoader();
            Class<?> settings=Class.forName("app.morphe.extension.shared.settings.BaseSettings",true,loader);
            Object setting=settings.getField("MORPHE_LANGUAGE").get(null);
            Object language=Class.forName("app.morphe.extension.shared.settings.EnumSetting",true,loader)
                    .getMethod("get").invoke(setting);
            if(language instanceof Enum<?>) {
                if(!"DEFAULT".equals(((Enum<?>)language).name())) {
                    Object locale=Class.forName("app.morphe.extension.shared.settings.AppLanguage",true,loader)
                            .getMethod("getLocale").invoke(language);
                    if(locale instanceof Locale) { selected=(Locale)locale; source="morphe_override"; }
                } else source="morphe_default_host_configuration";
            }
        } catch(ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            // The explicit host configuration remains authoritative until official settings are ready.
        }
        selected=canonical(selected);
        Configuration configuration=new Configuration(base.getResources().getConfiguration());configuration.setLocale(selected);
        android.util.DisplayMetrics metrics=base.getResources().getDisplayMetrics();
        String identity=selected.toLanguageTag()+"/"+source+"/"+configuration.toString()+"/"+
                System.identityHashCode(base.getResources())+"/"+System.identityHashCode(base.getTheme())+"/"+
                metrics.density+"/"+metrics.scaledDensity+"/"+metrics.densityDpi+"/"+metrics.widthPixels+"x"+metrics.heightPixels;
        synchronized(contexts){
            java.lang.ref.WeakReference<Snapshot> ref=contexts.get(base);Snapshot previous=ref==null?null:ref.get();
            if(previous!=null && identity.equals(previous.identity)){
                previous.context.getTheme().setTo(base.getTheme());return previous;
            }
            Snapshot current=new Snapshot(base,selected,source,configuration,identity);
            contexts.put(base,new java.lang.ref.WeakReference<>(current));return current;
        }
    }
    static Context context(Context base) { Snapshot s=snapshot(base); return s==null?base:s.context; }
    static String identity(Context base) { Snapshot s=snapshot(base); return s==null?"unavailable":s.identity; }
    static void direction(android.view.View view,Context base) {
        Snapshot s=snapshot(base);
        if(s!=null){view.setLayoutDirection(android.text.TextUtils.getLayoutDirectionFromLocale(s.locale));
            if(view instanceof android.widget.TextView)((android.widget.TextView)view).setTextLocale(s.locale);}
    }
}
