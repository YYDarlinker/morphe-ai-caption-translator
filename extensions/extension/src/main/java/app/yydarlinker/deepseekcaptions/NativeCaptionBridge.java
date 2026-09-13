package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Typed host accessors are bound and structurally validated by the patch, not runtime reflection. */
public final class NativeCaptionBridge {
    private static volatile Context context;
    private NativeCaptionBridge() {}
    static void initialize(Context value) { context=value.getApplicationContext(); }
    static boolean enabled() { return context!=null && DeepSeekConfig.enabled(context); }

    public static boolean suppressNativeDraw() {
        return enabled() && DynamicCaptionController.isVisibleActive();
    }
    public static List<?> augmentTranslations(List<?> original) {
        if(original==null || original.isEmpty()) return original;
        try {
            Object prototype=null;
            for(Object track:original) {
                String code=language(track);
                if(code!=null && !code.isEmpty() && "zh-Hans".equals(TargetLanguage.fromCode(code).code)) return original;
                if(prototype==null && DeepSeekCaptionHook.isYouTubeTimedTextUrl(url(track))) prototype=track;
            }
            if(prototype==null) return original;
            Object simplified=cloneSimplified(prototype);
            if(simplified==null) return original;
            List<Object> copy=new ArrayList<>(original.size()+1);
            copy.add(simplified); copy.addAll(original); return copy;
        } catch(Exception failed) {
            CaptionDiagnostics.mark(context,"AI_MENU_INSERT_FAILED",failed.getClass().getSimpleName());
            return original;
        }
    }
    public static void onSelection(Object track) { applySelection(track,true); }
    static void applySelection(Object track,boolean remember) {
        if(!enabled()) return;
        try {
            String code=track==null ? "DISABLE_CAPTIONS_OPTION" : language(track);
            if("AUTO_TRANSLATE_CAPTIONS_OPTION".equals(code)) return;
            if(track==null || "DISABLE_CAPTIONS_OPTION".equals(code)) {
                if(remember) CaptionChoice.toggle(false);
                DynamicCaptionController.deactivateFromNativeCaptionState(); return;
            }
            String selected=url(track);
            if(!DeepSeekCaptionHook.isYouTubeTimedTextUrl(selected)) return;
            boolean translate=TargetLanguage.fromUrl(selected)!=null;
            if(remember) CaptionChoice.select(code,translate,vss(track).startsWith("a."));
            CaptionButtonController.noteAiTrackSelected();
            if(translate) {
                CaptionLifecycleRestore.noteAiTarget(selected);
                DynamicCaptionController.activate(context,selected);
            } else ContextualUnitCaptionController.activateSource(context,selected);
            CaptionMusicSuppressor.kick();
        } catch(Exception failed) {
            CaptionDiagnostics.mark(context,"AI_SELECTION_FAILED",failed.getClass().getSimpleName());
        }
    }
    public static void onNativeSelection(Object manager,Object track,Object origin) {
        if(origin instanceof Enum<?> && "PREFERRED_TRACK".equals(((Enum<?>)origin).name())) applySelection(track,true);
        else if(CaptionChoice.isOn()) applySelection(track,false);
    }
    public static Object resolveRemembered(Object manager) {
        if(!enabled() || !CaptionChoice.isOn()) return null;
        List<?> list=CaptionChoice.translates() ? translatedTracks(manager) : nativeTracks(manager);
        Object fallback=null;
        if(list!=null) for(Object track:list) {
            String code=language(track);
            if(CaptionChoice.language().equals(code)) {
                if(CaptionChoice.translates() || vss(track).startsWith("a.")==CaptionChoice.asr()) return track;
                fallback=track;
            }
        }
        return fallback;
    }
    public static int restoreDecision() { return !enabled() ? -1 : CaptionChoice.isOn() ? 1 : 0; }
    public static List<?> nativeTracks(Object manager) { return null; }
    public static List<?> translatedTracks(Object manager) { return null; }
    public static String simplifiedUrl(String value) { return TargetLanguage.withCode(value,"zh-Hans"); }
    public static String simplifiedVss(String value) {
        if(value==null) return "tzh-Hans";
        int separator=value.indexOf('.');
        return "tzh-Hans"+(separator<0 ? "" : value.substring(separator));
    }
    public static Object augmentMetadata(Object metadata) { return metadata; }
    public static String language(Object track) { return ""; } // replaced at patch time
    public static String vss(Object track) { return ""; }
    public static String url(Object track) { return ""; }
    public static Object cloneSimplified(Object track) { return null; }
}
