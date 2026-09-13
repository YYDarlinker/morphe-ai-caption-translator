package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Typed host accessors are bound and structurally validated by the patch, not runtime reflection. */
public final class NativeCaptionBridge {
    private static volatile Context context;
    private NativeCaptionBridge() {}
    static void initialize(Context value) { context=value.getApplicationContext(); }
    private static boolean enabled() { return context!=null && DeepSeekConfig.load(context).enabled; }

    public static boolean suppressNativeDraw() {
        return enabled() && DynamicCaptionController.isVisibleActive();
    }
    public static List<?> augmentTranslations(List<?> original) {
        if(!enabled() || original==null || original.isEmpty()) return original;
        try {
            Object prototype=null;
            for(Object track:original) {
                String code=language(track);
                if("zh-Hans".equals(TargetLanguage.fromCode(code).code)) return original;
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
    public static void onSelection(Object track) {
        if(!enabled()) return;
        try {
            String code=track==null ? "DISABLE_CAPTIONS_OPTION" : language(track);
            if("AUTO_TRANSLATE_CAPTIONS_OPTION".equals(code)) return;
            if(track==null || "DISABLE_CAPTIONS_OPTION".equals(code)) {
                DynamicCaptionController.deactivateFromNativeCaptionState(); return;
            }
            String selected=url(track);
            if(!DeepSeekCaptionHook.isYouTubeTimedTextUrl(selected)) return;
            // Existing translated tracks preserve their target; source-track choices also use our
            // overlay (translated to that selected language rather than allowing native drawing).
            if(TargetLanguage.fromUrl(selected)==null) selected=TargetLanguage.withCode(selected,code);
            CaptionButtonController.noteAiTrackSelected();
            CaptionLifecycleRestore.noteAiTarget(selected);
            DynamicCaptionController.activate(context,selected);
            CaptionMusicSuppressor.kick();
        } catch(Exception failed) {
            CaptionDiagnostics.mark(context,"AI_SELECTION_FAILED",failed.getClass().getSimpleName());
        }
    }
    public static String simplifiedUrl(String value) { return TargetLanguage.withCode(value,"zh-Hans"); }
    public static String simplifiedVss(String value) {
        if(value==null) return "tzh-Hans";
        int separator=value.indexOf('.');
        return "tzh-Hans"+(separator<0 ? "" : value.substring(separator));
    }
    public static String language(Object track) { return ""; } // replaced at patch time
    public static String url(Object track) { return ""; }
    public static Object cloneSimplified(Object track) { return null; }
}
