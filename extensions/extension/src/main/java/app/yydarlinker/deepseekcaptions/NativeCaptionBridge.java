package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Typed host accessors are bound and structurally validated by the patch, not runtime reflection. */
public final class NativeCaptionBridge {
    private static volatile Context context;
    private NativeCaptionBridge() {}
    static void initialize(Context value) { context=value.getApplicationContext(); }
    static boolean enabled() { return CaptionAddonSupport.aiInstalled() && context!=null && DeepSeekConfig.enabled(context); }

    public static boolean suppressNativeDraw() {
        return enabled() && DynamicCaptionController.isVisibleActive();
    }
    public static List<?> augmentTranslations(List<?> original) {
        if(!CaptionAddonSupport.simplifiedInstalled() || original==null || original.isEmpty()) return original;
        try {
            Object prototype=null;
            for(Object track:original) {
                String code=language(track);
                if(LanguageMenuOrder.rank(code)==1){
                    Object corrected=cloneSimplified(track);if(corrected==null)return original;
                    List<Object> copy=new ArrayList<>(original);copy.set(copy.indexOf(track),corrected);
                    return LanguageMenuOrder.insertSimplified(copy,NativeCaptionBridge::language,t->displayName(t).toString());
                }
                if(prototype==null && DeepSeekCaptionHook.isYouTubeTimedTextUrl(url(track))) prototype=track;
            }
            if(prototype==null) return original;
            Object simplified=cloneSimplified(prototype);
            if(simplified==null) return original;
            List<Object> copy=new ArrayList<>(original.size()+1);
            copy.add(simplified); copy.addAll(original); return LanguageMenuOrder.insertSimplified(copy,NativeCaptionBridge::language,t->displayName(t).toString());
        } catch(Exception failed) {
            CaptionDiagnostics.mark(context,"AI_MENU_INSERT_FAILED",failed.getClass().getSimpleName());
            return original;
        }
    }
    public static void onSelection(Object track) { applySelection(track,true); }
    static void applySelection(Object track,boolean remember) {
        if(!CaptionAddonSupport.aiInstalled()) return;
        try {
            String code=track==null ? "DISABLE_CAPTIONS_OPTION" : language(track);
            if("AUTO_TRANSLATE_CAPTIONS_OPTION".equals(code)) return;
            if(track==null || "DISABLE_CAPTIONS_OPTION".equals(code)) {
                if(remember) CaptionChoice.toggle(false);
                if(enabled())DynamicCaptionController.deactivateFromNativeCaptionState(); return;
            }
            String selected=url(track);
            if(!DeepSeekCaptionHook.isYouTubeTimedTextUrl(selected)) return;
            boolean translate=TargetLanguage.fromUrl(selected)!=null;
            if(remember) CaptionChoice.select(code,translate,vss(track).startsWith("a."));
            if(!enabled())return;
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
    private static java.lang.ref.WeakReference<Object> selectedManager=new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<Object> selectedTrack=new java.lang.ref.WeakReference<>(null);
    private static Object selectedOrigin;
    private static boolean switching;
    private static int selectedReason;
    public static void onNativeSelectionWithReason(Object manager,Object track,Object origin,int reason){
        selectedReason=reason;onNativeSelection(manager,track,origin);
    }
    private static String selectedVideo="";
    public static void onNativeSelection(Object manager,Object track,Object origin) {
        String incomingVideo=track==null?"":PageCaptionController.videoIdFromUrl(url(track));
        if(!incomingVideo.isEmpty()&&!incomingVideo.equals(selectedVideo)){
            if(CaptionAddonSupport.aiInstalled())CaptionChoice.reset();selectedVideo=incomingVideo;
        }
        selectedManager=new java.lang.ref.WeakReference<>(manager);selectedTrack=new java.lang.ref.WeakReference<>(track);selectedOrigin=origin;
        if(switching)return;
        rememberAsrTracks(manager);
        boolean explicit=origin instanceof Enum<?> && "PREFERRED_TRACK".equals(((Enum<?>)origin).name());
        if(explicit && CaptionAddonSupport.memoryInstalled()) {
            String code=track==null?"DISABLE_CAPTIONS_OPTION":language(track);
            if(track==null || "DISABLE_CAPTIONS_OPTION".equals(code))RememberedCaptionSelection.off();
            else if(!code.endsWith("_OPTION"))RememberedCaptionSelection.select(code,TargetLanguage.fromUrl(url(track))!=null,vss(track).startsWith("a."));
        }
        // Current-video state is required by AI independently of optional cross-video memory.
        applySelection(track,explicit || !CaptionChoice.known());
    }
    static boolean canReselect(){return selectedManager.get()!=null && selectedOrigin!=null;}
    static void refreshNativeTrack(){
        Object manager=selectedManager.get(),track=selectedTrack.get(),origin=selectedOrigin;
        if(manager==null||origin==null||track==null)return;
        String video=PageCaptionController.videoIdFromUrl(url(track));
        String current=PageCaptionController.currentVideoIdSnapshot();
        if(!current.isEmpty()&&!current.equals(video))throw new IllegalStateException("Stale caption manager");
        try{switching=true;selectNative(manager,null,origin,selectedReason);selectNative(manager,track,origin,selectedReason);}
        finally{switching=false;}
    }
    public static void selectNative(Object manager,Object track,Object origin,int reason) {} // bound at patch time
    private static void rememberAsrTracks(Object manager) {
        if(!enabled() || manager==null)return;
        try{List<?> tracks=nativeTracks(manager);if(tracks!=null)for(Object track:tracks)
            NativeAsrTrackReference.remember(language(track),vss(track),url(track));
        }catch(Exception ignored){}
    }
    public static Object resolveRemembered(Object manager) {
        rememberAsrTracks(manager);
        if(!CaptionAddonSupport.memoryInstalled() || RememberedCaptionSelection.decision()!=1)return null;
        List<?> list=RememberedCaptionSelection.translated()?translatedTracks(manager):nativeTracks(manager);
        Object fallback=null;
        if(list!=null)for(Object track:list)if(RememberedCaptionSelection.language().equals(language(track))) {
            if(RememberedCaptionSelection.translated() || vss(track).startsWith("a.")==RememberedCaptionSelection.asr())return track;
            fallback=track;
        }
        return fallback;
    }
    public static int restoreDecision() { return !CaptionAddonSupport.memoryInstalled()?-1:RememberedCaptionSelection.decision(); }
    public static List<?> nativeTracks(Object manager) { return null; }
    public static List<?> translatedTracks(Object manager) { return null; }
    public static String simplifiedUrl(String value) { return TargetLanguage.withCode(value,"zh-Hans"); }
    public static String simplifiedVss(String value) {
        if(value==null) return "tzh-Hans";
        int separator=value.indexOf('.');
        return "tzh-Hans"+(separator<0 ? "" : value.substring(separator));
    }
    public static Object augmentMetadata(Object metadata) { return metadata; }
    public static CharSequence displayName(Object track) { return ""; }
    public static String language(Object track) { return ""; } // replaced at patch time
    public static String vss(Object track) { return ""; }
    public static String url(Object track) { return ""; }
    public static Object cloneSimplified(Object track) { return null; }
}
