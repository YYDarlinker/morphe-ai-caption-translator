package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import app.yydarlinker.caption.contracts.CaptionContracts.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Composition root: local settings/host/UI adapters around the pinned upstream engine. */
final class CaptionApplication extends TranslationRuntime.Host implements CaptionPort,CaptionActions.SettingsServices,TranslationOutput.Sink,TranslationAudit.Sink {
    private static final CaptionApplication INSTANCE=new CaptionApplication();
    private static volatile Context context;
    private static WeakReference<Activity> activity=new WeakReference<>(null);
    private static volatile String video="";
    static void setMainActivity(Activity value){
        if(value==null)return;
        context=value.getApplicationContext();activity=new WeakReference<>(value);
        TranslationRuntime.install(INSTANCE);TranslationOutput.install(INSTANCE);TranslationAudit.install(INSTANCE);
        CaptionActions.install(INSTANCE,INSTANCE);
        CaptionPlayerAuthority.setOwner(value);CaptionOverlay.setActivity(value);
        TokenCostAudit.onCoreSelected(context,"event_rebuild_r2");
        RebuildController.activity(context);
    }
    @Override public void command(Action action,String value){
        Context c=context;if(c==null)return;
        switch(action){
            case ACTIVATE:RebuildController.activate(c,value,false,true);break;
            case PREWARM:RebuildController.activate(c,value,false,false);break;
            case DEACTIVATE:RebuildController.stop();break;
            case VIDEO:
                video=value==null?"":value.trim();
                if(video.isEmpty())RebuildController.stop();else RebuildController.video(video);
                break;
            case SOURCE:
                if(CaptionUrls.isTimedText(value))NativeAsrTrackReference.remember(RawCaptionSource.sourceLanguage(value,null),NativeCaptionBridge.simplifiedVss(""),value);
                // Native source selection keeps the local host renderer; it must not create an AI overlay.
                RebuildController.observe(value);
                break;
            case PLAYER_TYPE:CaptionOverlay.setPlayerType(value);RebuildController.player(value);break;
            case CONFIGURATION:RebuildController.refresh(c);CaptionOverlay.refreshStyle(c);break;
            case APPEARANCE:case POSITION_RESET:CaptionOverlay.refreshStyle(c);break;
            case CLEAR_CACHE:
                RebuildController.clearCache(c);break;
            case RECOVER:RebuildController.refresh(c);break;
            case UI_LOCALE:case SOURCE_CATALOG:break;
            default:break;
        }
    }
    @Override public boolean active(){return RebuildController.visible();}
    @Override public boolean ownsNativeTrack(){return RebuildController.ownsNativeTrack();}
    @Override public String videoId(){return video;}
    @Override public String restoreTarget(String url){return CaptionLifecycleRestore.restoreAfterLifecycle(RebuildController.restore(url));}
    @Override public void playback(long at){RebuildController.time(at);}
    @Override TranslationSettings settings(Context c){
        synchronized(ApiProfiles.LOCK){DeepSeekConfig.ProviderSettings p=DeepSeekConfig.load(c);
            return new TranslationSettings(p.enabled,p.baseUrl,p.model,p.prompt,SecureApiKey.load(c),p.preferenceProvenance);}
    }
    private static TranslationSourceAccess.Source adapt(RawCaptionSource.Source source){
        return source==null?null:new TranslationSourceAccess.Source(source.body,source.document,source.timingProfile,source.timingQuality,source.timingEvidence);
    }
    @Override TranslationSourceAccess.Source load(Context c,String url,boolean translate,DeepSeekApiClient.RequestControl control) throws Exception {return adapt(RawCaptionSource.load(c,url,false,translate,control));}
    @Override TranslationSourceAccess.Source reference(Context c,String url,RebuildSource primary,DeepSeekApiClient.RequestControl control) throws Exception {return adapt(RawCaptionSource.reference(c,url,primary,control));}
    @Override boolean selectionOn(){return CaptionChoice.isOn();}
    @Override boolean translates(){return CaptionChoice.translates();}
    @Override String target(){return CaptionChoice.language();}
    @Override boolean isShorts(){return CaptionSurface.isShorts();}
    @Override boolean isCompactType(String type){return CaptionPlayerAuthority.isCompactType(type);}
    @Override boolean authoritativeType(){return CaptionPlayerAuthority.hasAuthoritativeType();}
    @Override boolean transitioning(){return CaptionPlayerAuthority.state()==CaptionPlayerAuthority.COMPACT||CaptionPlayerAuthority.state()==CaptionPlayerAuthority.TRANSITIONING_TO_REGULAR;}
    @Override long ownerEpoch(){return CaptionPlayerAuthority.ownerEpoch();}
    @Override void sessionOpened(){CaptionPlayerAuthority.setOwner(activity.get());}
    @Override void stopped(){CaptionPlayerAuthority.close();}
    @Override void suppressNative(boolean force){if(force)CaptionMusicSuppressor.forceNativeRendererScan();else CaptionMusicSuppressor.kick();}
    @Override PlaybackState playbackState(){
        Activity a=activity.get();try{MediaController c=a==null?null:a.getMediaController();return c==null||!a.getPackageName().equals(c.getPackageName())?null:c.getPlaybackState();}catch(Exception ignored){return null;}
    }
    @Override TranslationRuntime.ClockSample clock(String owner){OfficialPlayerClockAdapter.Sample s=OfficialPlayerClockAdapter.read(owner);return new TranslationRuntime.ClockSample(s.available,s.videoId,s.position,s.readElapsed,s.state,s.speed);}
    @Override String sourceKey(String url){try{return SourceCaptionCache.key(url);}catch(Exception e){return super.sourceKey(url);}}
    @Override boolean debugEnabled(Context c){return DeepSeekConfig.displayTextDebugEnabled(c);}
    @Override String message(Context c,String key){return CaptionStrings.settings(c,key);}
    @Override void mark(Context c,String stage,String detail,CaptionCredentialRef ref){CaptionDiagnostics.mark(c,stage,detail,ref);}
    @Override void record(Context c,String key,long id,JSONObject source,String response,String metadata){CaptionQualityTrace.record(c,key,id,source,response,metadata);}
    @Override void noteReuseRepeat(){CaptionDiagnosticsWriter.noteReuseRepeat();}
    @Override void clearSourceCache(Context c){SourceCaptionCache.clear(c);}
    @Override String redact(String text,String key,int limit){return CaptionQualityTrace.redact(text,key,limit);}
    @Override String errorDetail(Throwable error){return CaptionDiagnostics.errorDetail(error);}
    @Override public TranslationBudget budget(){CaptionOverlay.LayoutBudget b=CaptionOverlay.budget();return b==null?null:new TranslationBudget(b.width,b.minimumPx,b.preferredPx,b.renderSpec);}
    private static CaptionOverlay.RenderGuard guard(TranslationOutput.RenderGuard value){
        return new CaptionOverlay.RenderGuard(){
            public boolean isValid(){return value.isValid();}
            public void onApplied(){value.onApplied();}
            public void beforeDraw(){CaptionOverlay.advancePreparedPage();}
            public long displayPosition(long supplied){return value.displayPosition(supplied);}
            public String identity(){return value.identity();}
            public String session(){return value.session();}
            public long windowStart(){return value.windowStart();}
            public long windowEnd(){return value.windowEnd();}
            public String blankReason(){return value.blankReason();}
            public CaptionCredentialRef credential(){return value.credential();}
        };
    }
    @Override public void clear(TranslationOutput.RenderGuard g){CaptionOverlay.clear(guard(g));}
    @Override public void hide(TranslationOutput.RenderGuard g){CaptionOverlay.hide(guard(g));}
    @Override public void refreshSurface(){CaptionOverlay.refreshSurface();}
    @Override public void position(long at,TranslationOutput.RenderGuard g){CaptionOverlay.position(at,guard(g));}
    @Override public void showStatus(String text,TranslationOutput.RenderGuard g){CaptionOverlay.showStatus(text,guard(g));}
    @Override public void showWaitingEvent(String text,TranslationOutput.RenderGuard g,String id,long start,long end,long at){CaptionOverlay.showWaitingEvent(text,guard(g),id,start,end,at);}
    @Override public void showEvent(String text,TranslationOutput.RenderGuard g,Supplier<String> fallback,String id,long start,long end,long at,CaptionRenderSpec spec,CaptionDisplayCandidate candidate){
        CaptionOverlay.showEvent(text,guard(g),fallback,id,start,end,at,spec,candidate);

    }
    @Override public List<CaptionDisplayPage> displayPages(String text,long start,long end,CaptionPresentationBudget geometry,CaptionRenderSpec spec){return TranslationPresentation.pages(text,start,end,geometry,spec);}
    @Override public List<String> models(String endpoint) throws Exception {
        DeepSeekConfig.ProviderSettings cfg=DeepSeekConfig.load(context);String profile=ApiProfiles.active(context);
        if(!endpoint.equals(cfg.baseUrl))throw new IllegalStateException("profile_changed");
        return ProviderModelCatalog.fetch(endpoint,()->{synchronized(ApiProfiles.LOCK){DeepSeekConfig.ProviderSettings now=DeepSeekConfig.load(context);if(!profile.equals(ApiProfiles.active(context))||!cfg.baseUrl.equals(now.baseUrl)||!cfg.credentialId.equals(now.credentialId))throw new IllegalStateException("profile_changed");return SecureApiKey.load(context);}});
    }
    @Override public String testApi() throws Exception {return RebuildApi.test(settings(context));}
    private static TokenCostAudit.Request audit(TranslationAudit.Request r){return (TokenCostAudit.Request)r.handle;}
    @Override public TranslationAudit.Request begin(TranslationSettings p,boolean priority,int targets,int contexts,int chars,int contextChars,String bucket){
        DeepSeekConfig.ProviderSettings cfg=new DeepSeekConfig.ProviderSettings(p.enabled,p.baseUrl,p.model,p.prompt,CaptionCredentialRef.of(p.apiKey),p.preferenceProvenance);
        return new TranslationAudit.Request(TokenCostAudit.beginUnitBatch(cfg,priority,targets,contexts,chars,contextChars,"event-rebuild-"+bucket));
    }
    @Override public int attempt(TranslationAudit.Request r,int bytes){return TokenCostAudit.beginAttempt(audit(r),bytes);}
    @Override public void response(TranslationAudit.Request r,int attempt,JSONObject root){TokenCostAudit.recordResponse(audit(r),attempt,root);}
    @Override public void failure(TranslationAudit.Request r,int attempt,TranslationAudit.FailureCategory category,String kind){TokenCostAudit.recordFailure(audit(r),attempt,TokenCostAudit.FailureCategory.valueOf(category.name()),kind);}
    @Override public void quality(TranslationAudit.Request r,int accepted,int rejected,int qualityRejected){TokenCostAudit.recordUnitQualityOutcome(audit(r),accepted,rejected,qualityRejected);}
    @Override public void batch(TranslationAudit.Request r,int count){TokenCostAudit.recordUnitBatchOutcome(audit(r),count);}
    @Override public void cache(int total,int restored){TokenCostAudit.recordUnitCacheOutcome(total,restored);}
}
