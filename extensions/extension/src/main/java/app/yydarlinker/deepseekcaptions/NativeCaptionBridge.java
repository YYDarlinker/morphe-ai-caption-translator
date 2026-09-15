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
    private static final Object SELECTION_LOCK=new Object();
    private static final java.util.LinkedHashMap<String,Selection> selections=new java.util.LinkedHashMap<>();
    private static String visibleVideo="";
    private static Object switchingManager;
    enum Refresh { APPLIED, CAPTIONS_OFF, DEFERRED }
    /** Per-video weak references, not one process-wide "last callback" slot. */
    private static final class Selection {
        final String video,language;final boolean off,translated,asr;
        final java.lang.ref.WeakReference<Object> manager,track;
        final Object origin;final int reason;
        Selection(String video,Object manager,Object track,Object origin,int reason){
            this.video=video;this.manager=new java.lang.ref.WeakReference<>(manager);this.track=new java.lang.ref.WeakReference<>(track);
            this.origin=origin;this.reason=reason;off=track==null||"DISABLE_CAPTIONS_OPTION".equals(language(track));
            language=off?"":language(track);translated=!off&&TargetLanguage.fromUrl(url(track))!=null;asr=!off&&vss(track).startsWith("a.");
        }
    }
    public static void onNativeSelectionWithReason(Object manager,Object track,Object origin,int reason){
        captureSelection(manager,track,origin,reason);
    }
    public static void onNativeSelection(Object manager,Object track,Object origin){captureSelection(manager,track,origin,0);}
    private static void captureSelection(Object manager,Object track,Object origin,int reason){
        synchronized(SELECTION_LOCK){
            // Internal null/reselect callbacks must not erase the snapshot or change memory.
            if(manager!=null&&manager==switchingManager)return;
            String code=track==null?"DISABLE_CAPTIONS_OPTION":language(track);
            if("AUTO_TRANSLATE_CAPTIONS_OPTION".equals(code))return;
            boolean off=track==null||"DISABLE_CAPTIONS_OPTION".equals(code);
            String video=off?modelVideo(manager):PageCaptionController.videoIdFromUrl(url(track));
            String current=PageCaptionController.currentVideoIdSnapshot();
            if(video.isEmpty()&&off){Selection owner=latestForManager(manager);if(owner!=null)video=owner.video;}
            // An unidentified background null callback is not evidence that the visible CC is off.
            if(video.isEmpty()&&!current.isEmpty())return;
            if(!off&&!DeepSeekCaptionHook.isYouTubeTimedTextUrl(url(track)))return;
            Selection value=new Selection(video,manager,track,origin,reason);
            selections.remove(video);selections.put(video,value);
            while(selections.size()>6)selections.remove(selections.keySet().iterator().next());
            if(!current.isEmpty()&&!current.equals(video)){
                CaptionDiagnostics.mark(context,"NATIVE_SELECTION_BACKGROUND","visible_state_preserved");return;
            }
            rememberAsrTracks(manager);
            boolean explicit=origin instanceof Enum<?> && "PREFERRED_TRACK".equals(((Enum<?>)origin).name());
            if(explicit&&CaptionAddonSupport.memoryInstalled()){
                if(off)RememberedCaptionSelection.off();
                else if(!code.endsWith("_OPTION"))RememberedCaptionSelection.select(code,value.translated,value.asr);
            }
            applySelection(track,off||explicit||!CaptionChoice.known()||!CaptionChoice.isOn());
        }
    }
    static void onVideoId(String video){
        video=video==null?"":video.trim();
        synchronized(SELECTION_LOCK){
            if(video.equals(visibleVideo))return;
            visibleVideo=video;CaptionChoice.reset();
            if(video.isEmpty())return;
            Selection value=selections.get(video);
            if(value==null||!ownsManager(value))return;
            if(value.off){CaptionChoice.toggle(false);return;}
            Object track=currentTrack(value);
            if(track!=null)applySelection(track,true);
        }
    }
    private static Selection currentSelection(){
        String video=PageCaptionController.currentVideoIdSnapshot();return video.isEmpty()?null:selections.get(video);
    }
    private static Selection latestForManager(Object manager){
        if(manager==null)return null;Selection found=null;
        for(Selection value:selections.values())if(value.manager.get()==manager)found=value;
        return found;
    }
    private static boolean ownsManager(Selection value){
        Object manager=value.manager.get();if(manager==null||value.origin==null)return false;
        Selection owner=latestForManager(manager);if(owner!=null&&!owner.video.equals(value.video))return false;
        String model=modelVideo(manager);return model.isEmpty()||model.equals(value.video);
    }
    private static String modelVideo(Object manager){
        if(manager==null)return "";
        try{List<?> tracks=nativeTracks(manager);if(tracks==null)return "";String owner="";
            for(Object track:tracks){String video=PageCaptionController.videoIdFromUrl(url(track));if(video.isEmpty())continue;
                if(!owner.isEmpty()&&!owner.equals(video))return "mixed_model";owner=video;}
            return owner;
        }catch(Exception unavailable){return "";}
    }
    private static Object currentTrack(Selection value){
        if(value.off||!ownsManager(value))return null;
        Object manager=value.manager.get();if(manager==null)return null;
        try{
            List<?> tracks=value.translated?translatedTracks(manager):nativeTracks(manager);
            if(tracks!=null&&!tracks.isEmpty()){
                for(Object track:tracks)if(value.video.equals(PageCaptionController.videoIdFromUrl(url(track)))&&value.language.equals(language(track))
                        &&value.translated==(TargetLanguage.fromUrl(url(track))!=null)&&value.asr==vss(track).startsWith("a."))return track;
                return null; // A changed native model is authoritative; never reuse its old object.
            }
        }catch(Exception unavailable){return null;}
        Object track=value.track.get();return track!=null&&value.video.equals(PageCaptionController.videoIdFromUrl(url(track)))?track:null;
    }
    static Refresh refreshNativeTrack(){
        synchronized(SELECTION_LOCK){
            if(CaptionChoice.known()&&!CaptionChoice.isOn())return Refresh.CAPTIONS_OFF;
            Selection value=currentSelection();
            if(value==null||!ownsManager(value))return Refresh.DEFERRED;
            if(value.off)return Refresh.CAPTIONS_OFF;
            Object track=currentTrack(value),manager=value.manager.get();
            if(track==null||manager==null)return Refresh.DEFERRED;
            String current=PageCaptionController.currentVideoIdSnapshot();
            if(!current.isEmpty()&&!current.equals(value.video))return Refresh.DEFERRED;
            rememberAsrTracks(manager);
            try{
                switchingManager=manager;
                selectNative(manager,null,value.origin,value.reason);
                // A video transition while the selector runs must not resurrect the old video.
                current=PageCaptionController.currentVideoIdSnapshot();
                if(!current.isEmpty()&&!current.equals(value.video))return Refresh.DEFERRED;
                selectNative(manager,track,value.origin,value.reason);
            }finally{switchingManager=null;}
            current=PageCaptionController.currentVideoIdSnapshot();
            if(!current.isEmpty()&&!current.equals(value.video))return Refresh.DEFERRED;
            if(enabled())applySelection(track,false);
            return Refresh.APPLIED;
        }
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
