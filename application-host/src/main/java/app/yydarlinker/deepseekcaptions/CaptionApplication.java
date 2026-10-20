package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;

import app.yydarlinker.caption.contracts.CaptionContracts.*;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;

/** Composition root: local settings/host/UI adapters around the pinned upstream engine. */
final class CaptionApplication extends TranslationRuntime.Host
        implements CaptionPort,
                CaptionActions.SettingsServices,
                TranslationOutput.Sink,
                TranslationAudit.Sink {
    private static final CaptionApplication INSTANCE = new CaptionApplication();
    private static volatile Context context;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static volatile String video = "";

    static void setMainActivity(Activity value) {
        if (value == null) return;
        context = value.getApplicationContext();
        activity = new WeakReference<>(value);
        TranslationRuntime.install(INSTANCE);
        TranslationOutput.install(INSTANCE);
        TranslationAudit.install(INSTANCE);
        CaptionActions.install(INSTANCE, INSTANCE);
        CaptionPlayerAuthority.setOwner(value);
        CaptionOverlay.setActivity(value);
        TokenCostAudit.onCoreSelected(context, "event_rebuild_r2");
        RebuildController.activity(context);
    }

    @Override
    public void command(Action action, String value) {
        Context c = context;
        if (c == null) return;
        switch (action) {
            case ACTIVATE:
                remember(value, false);
                RebuildController.activate(c, value, false, true);
                break;
            case PREWARM:
                RebuildController.activate(c, value, false, false);
                break;
            case DEACTIVATE:
                // Only a reset YouTube made on its own may come back; every other off is the
                // user's and must also close the caption choice, or a late tlang request could
                // revive the overlay while CC shows off.
                boolean automatic =
                        "native_selection".equals(value) && EngineResume.takeAutomatic();
                if (!automatic) {
                    EngineResume.declined();
                    CaptionChoice.toggle(false);
                }
                RebuildController.stop();
                break;
            case VIDEO:
                video = value == null ? "" : value.trim();
                if (video.isEmpty()) {
                    EngineResume.departed();
                    RebuildController.stop();
                } else {
                    EngineResume.video(video);
                    RebuildController.video(video);
                    resume(c, "video");
                }
                break;
            case SOURCE:
                if (CaptionUrls.isTimedText(value))
                    NativeAsrTrackReference.remember(
                            RawCaptionSource.sourceLanguage(value),
                            NativeCaptionBridge.simplifiedVss(""),
                            value);
                // The hook already gave YouTube an empty track for this selection, so the chosen
                // original or auto-generated captions are shown by the AI overlay, untranslated.
                remember(value, true);
                RebuildController.activate(c, value, true, true);
                break;
            case PLAYER_TYPE:
                CaptionOverlay.setPlayerType(value);
                RebuildController.player(value);
                break;
            case CONFIGURATION:
                RebuildController.refresh(c);
                CaptionOverlay.refreshStyle(c);
                break;
            case APPEARANCE:
            case POSITION_RESET:
                CaptionOverlay.refreshStyle(c);
                break;
            case CLEAR_CACHE:
                RebuildController.clearCache(c);
                break;
            case RECOVER:
                RebuildController.refresh(c);
                break;
            default: // UI_LOCALE and SOURCE_CATALOG need no work here.
                break;
        }
    }

    @Override
    public boolean active() {
        return RebuildController.visible();
    }

    // A visible source-only session also owns the screen: the native renderer stays masked.
    @Override
    public boolean ownsNativeTrack() {
        return RebuildController.ownsNativeTrack() || RebuildController.visible();
    }

    @Override
    public String videoId() {
        return video;
    }

    @Override
    public String restoreTarget(String url) {
        return CaptionLifecycleRestore.restoreAfterLifecycle(RebuildController.restore(url));
    }

    @Override
    public void playback(long at) {
        if (EngineResume.reappeared(SystemClock.elapsedRealtime())) {
            Context c = context;
            // The player ticks again after a silence: the video was shown anew. Bring back the
            // session lost with the departure, else lay the box out on the new surface.
            if (c != null && !resume(c, "playback")) CaptionOverlay.refreshSurface();
        }
        RebuildController.time(at);
    }

    private static void remember(String url, boolean original) {
        if (CaptionUrls.isTimedText(url))
            EngineResume.started(CaptionUrls.videoId(url), url, original);
    }

    /**
     * A Short that was left and entered again is not announced to the caption track selector, so
     * the session that stopped with the departure is restarted from what it was showing.
     */
    private static boolean resume(Context c, String via) {
        if (!DeepSeekConfig.enabled(c) || !RebuildController.activeUrl().isEmpty()) return false;
        // A user-off choice, an explicit CC-off or the AI switch beats a remembered session; an
        // automatic YouTube reset is recoverable.
        if (CaptionChoice.userOff()) return false;
        EngineResume.Session again = EngineResume.take(video);
        if (again == null) return false;
        if (video.isEmpty()) {
            video = again.video;
            RebuildController.video(video);
        }
        CaptionChoice.toggle(true);
        CaptionDiagnostics.mark(
                c,
                "ENGINE_REENTRY_RESUMED",
                "video="
                        + video
                        + ";source_only="
                        + (again.sourceOnly ? "1" : "0")
                        + ";via="
                        + via);
        RebuildController.activate(c, again.url, again.sourceOnly, true);
        return true;
    }

    @Override
    TranslationSettings settings(Context c) {
        synchronized (ApiProfiles.LOCK) {
            DeepSeekConfig.ProviderSettings p = DeepSeekConfig.load(c);
            return new TranslationSettings(
                    p.enabled,
                    p.baseUrl,
                    p.model,
                    p.prompt,
                    SecureApiKey.load(c),
                    p.preferenceProvenance);
        }
    }

    private static TranslationSourceAccess.Source adapt(RawCaptionSource.Source source) {
        return source == null
                ? null
                : new TranslationSourceAccess.Source(
                        source.body,
                        source.document,
                        source.timingProfile,
                        source.timingQuality,
                        source.timingEvidence);
    }

    @Override
    TranslationSourceAccess.Source load(
            Context c, String url, boolean translate, DeepSeekApiClient.RequestControl control)
            throws Exception {
        return adapt(RawCaptionSource.load(c, url, translate, control));
    }

    @Override
    TranslationSourceAccess.Source reference(
            Context c, String url, RebuildSource primary, DeepSeekApiClient.RequestControl control)
            throws Exception {
        return adapt(RawCaptionSource.reference(c, url, primary, control));
    }

    @Override
    boolean selectionOn() {
        return CaptionChoice.isOn();
    }

    @Override
    boolean translates() {
        return CaptionChoice.translates();
    }

    @Override
    String target() {
        return CaptionChoice.language();
    }

    @Override
    boolean authoredTrack(String url) {
        return SourceTrackPolicy.authored(url);
    }

    @Override
    boolean isShorts() {
        return CaptionSurface.isShorts();
    }

    @Override
    boolean isCompactType(String type) {
        return CaptionPlayerAuthority.isCompactType(type);
    }

    @Override
    boolean authoritativeType() {
        return CaptionPlayerAuthority.hasAuthoritativeType();
    }

    @Override
    boolean transitioning() {
        if (CaptionPlayerAuthority.state() == CaptionPlayerAuthority.COMPACT)
            CaptionPlayerAuthority.recoverShorts();
        return CaptionPlayerAuthority.state() == CaptionPlayerAuthority.COMPACT
                || CaptionPlayerAuthority.state()
                        == CaptionPlayerAuthority.TRANSITIONING_TO_REGULAR;
    }

    @Override
    long ownerEpoch() {
        return CaptionPlayerAuthority.ownerEpoch();
    }

    @Override
    void sessionOpened() {
        CaptionPlayerAuthority.setOwner(activity.get());
    }

    @Override
    void stopped() {
        CaptionPlayerAuthority.close();
    }

    @Override
    void suppressNative(boolean force) {
        if (force) CaptionMusicSuppressor.forceNativeRendererScan();
        else CaptionMusicSuppressor.kick();
    }

    @Override
    PlaybackState playbackState() {
        Activity a = activity.get();
        try {
            MediaController c = a == null ? null : a.getMediaController();
            return c == null || !a.getPackageName().equals(c.getPackageName())
                    ? null
                    : c.getPlaybackState();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    TranslationRuntime.ClockSample clock(String owner) {
        return OfficialPlayerClockAdapter.read(owner);
    }

    @Override
    String sourceKey(String url) {
        try {
            return SourceCaptionCache.key(url);
        } catch (Exception e) {
            return super.sourceKey(url);
        }
    }

    @Override
    boolean debugEnabled(Context c) {
        return DeepSeekConfig.displayTextDebugEnabled(c);
    }

    @Override
    String message(Context c, String key) {
        return CaptionStrings.get(c, key);
    }

    @Override
    void mark(Context c, String stage, String detail, CaptionCredentialRef ref) {
        CaptionDiagnostics.mark(c, stage, detail, ref);
    }

    @Override
    void record(
            Context c, String key, long id, JSONObject source, String response, String metadata) {
        CaptionQualityTrace.record(c, key, id, source, response, metadata);
    }

    @Override
    void clearSourceCache(Context c) {
        SourceCaptionCache.clear(c);
    }

    @Override
    String redact(String text, String key, int limit) {
        return CaptionQualityTrace.redact(text, key, limit);
    }

    @Override
    String errorDetail(Throwable error) {
        return CaptionDiagnostics.errorDetail(error);
    }

    @Override
    public TranslationBudget budget() {
        CaptionPresentationBudget b = CaptionOverlay.budget();
        return b == null
                ? null
                : new TranslationBudget(b.width, b.minimumPx, b.preferredPx, b.renderSpec);
    }

    /** The overlay's view of a render guard; it also advances the prepared page before a draw. */
    private static CaptionOverlay.RenderGuard guard(TranslationOutput.RenderGuard value) {
        return new CaptionOverlay.RenderGuard() {
            public boolean isValid() {
                return value.isValid();
            }

            public void onApplied() {
                value.onApplied();
            }

            public void beforeDraw() {
                CaptionOverlay.advancePreparedPage();
            }

            public long displayPosition(long supplied) {
                return value.displayPosition(supplied);
            }

            public String identity() {
                return value.identity();
            }

            public String session() {
                return value.session();
            }

            public long windowStart() {
                return value.windowStart();
            }

            public long windowEnd() {
                return value.windowEnd();
            }

            public String blankReason() {
                return value.blankReason();
            }

            public CaptionCredentialRef credential() {
                return value.credential();
            }
        };
    }

    @Override
    public void clear(TranslationOutput.RenderGuard g) {
        CaptionOverlay.clear(guard(g));
    }

    @Override
    public void hide(TranslationOutput.RenderGuard g) {
        display(() -> CaptionOverlay.hide(guard(g)));
    }

    @Override
    public void refreshSurface() {
        CaptionOverlay.refreshSurface();
    }

    @Override
    public void position(long at, TranslationOutput.RenderGuard g) {
        CaptionOverlay.position(at, guard(g));
    }

    @Override
    public void showStatus(String text, TranslationOutput.RenderGuard g) {
        display(() -> CaptionOverlay.showStatus(text, guard(g)));
    }

    @Override
    public void showWaitingEvent(
            String text,
            TranslationOutput.RenderGuard g,
            String id,
            long start,
            long end,
            long at) {
        display(() -> CaptionOverlay.showWaitingEvent(text, guard(g), id, start, end, at));
    }

    @Override
    public void showEvent(
            String text,
            TranslationOutput.RenderGuard g,
            String id,
            long start,
            long end,
            long at,
            CaptionRenderSpec spec) {
        display(() -> CaptionOverlay.showEvent(text, guard(g), id, start, end, at, spec));
    }

    @Override
    public void showEvent(
            String text,
            TranslationOutput.RenderGuard g,
            String id,
            long start,
            long end,
            long at,
            CaptionRenderSpec spec,
            long[] sourceAnchors) {
        display(
                () ->
                        CaptionOverlay.showEvent(
                                text, guard(g), id, start, end, at, spec, sourceAnchors));
    }

    /** A display fault must never take the host app down: the next tick draws the caption again. */
    private static void display(Runnable draw) {
        try {
            draw.run();
        } catch (RuntimeException fault) {
            Log.w("MorpheCaption", "caption display fault: " + fault);
        }
    }

    @Override
    public List<CaptionDisplayPage> displayPages(
            String text,
            long start,
            long end,
            CaptionPresentationBudget geometry,
            CaptionRenderSpec spec) {
        return TranslationPresentation.pages(text, start, end, geometry, spec);
    }

    @Override
    public List<CaptionDisplayPage> displayPages(
            String text,
            long start,
            long end,
            CaptionPresentationBudget geometry,
            CaptionRenderSpec spec,
            long[] sourceAnchors) {
        return TranslationPresentation.pages(text, start, end, geometry, spec, sourceAnchors);
    }

    @Override
    public List<String> models(String endpoint) throws Exception {
        DeepSeekConfig.ProviderSettings cfg = DeepSeekConfig.load(context);
        String profile = ApiProfiles.active(context);
        if (!endpoint.equals(cfg.baseUrl)) throw new IllegalStateException("profile_changed");
        return ProviderModelCatalog.fetch(
                endpoint,
                () -> {
                    synchronized (ApiProfiles.LOCK) {
                        DeepSeekConfig.ProviderSettings now = DeepSeekConfig.load(context);
                        if (!profile.equals(ApiProfiles.active(context))
                                || !cfg.baseUrl.equals(now.baseUrl)
                                || !cfg.credentialId.equals(now.credentialId))
                            throw new IllegalStateException("profile_changed");
                        return SecureApiKey.load(context);
                    }
                });
    }

    @Override
    public String testApi() throws Exception {
        return RebuildApi.test(settings(context));
    }

    private static TokenCostAudit.Request audit(TranslationAudit.Request r) {
        return (TokenCostAudit.Request) r.handle;
    }

    @Override
    public TranslationAudit.Request begin(
            TranslationSettings p,
            boolean priority,
            int targets,
            int contexts,
            int chars,
            int contextChars,
            String bucket) {
        DeepSeekConfig.ProviderSettings cfg =
                new DeepSeekConfig.ProviderSettings(
                        p.enabled,
                        p.baseUrl,
                        p.model,
                        p.prompt,
                        CaptionCredentialRef.of(p.apiKey),
                        p.preferenceProvenance);
        return new TranslationAudit.Request(
                TokenCostAudit.beginUnitBatch(
                        cfg,
                        priority,
                        targets,
                        contexts,
                        chars,
                        contextChars,
                        "event-rebuild-" + bucket));
    }

    @Override
    public int attempt(TranslationAudit.Request r, int bytes) {
        return TokenCostAudit.beginAttempt(audit(r), bytes);
    }

    @Override
    public void response(TranslationAudit.Request r, int attempt, JSONObject root) {
        TokenCostAudit.recordResponse(audit(r), attempt, root);
    }

    @Override
    public void failure(
            TranslationAudit.Request r,
            int attempt,
            TranslationAudit.FailureCategory category,
            String kind) {
        TokenCostAudit.recordFailure(
                audit(r), attempt, TokenCostAudit.FailureCategory.valueOf(category.name()), kind);
    }

    @Override
    public void quality(
            TranslationAudit.Request r, int accepted, int rejected, int qualityRejected) {
        TokenCostAudit.recordUnitQualityOutcome(audit(r), accepted, rejected, qualityRejected);
    }

    @Override
    public void batch(TranslationAudit.Request r, int count) {
        TokenCostAudit.recordUnitBatchOutcome(audit(r), count);
    }

    @Override
    public void cache(int total, int restored) {
        TokenCostAudit.recordUnitCacheOutcome(total, restored);
    }
}
