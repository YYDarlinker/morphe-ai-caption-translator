package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import app.yydarlinker.caption.contracts.CaptionContracts;

import java.lang.ref.WeakReference;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the patched YouTube code calls. Methods marked "bound at patch time" are empty stubs whose
 * bodies the patch replaces with accesses to the obfuscated caption model; every other method is
 * ordinary code that the patch only calls.
 */
public final class NativeCaptionBridge {
    private static final String DISABLE = "DISABLE_CAPTIONS_OPTION";
    private static final String AUTO_TRANSLATE = "AUTO_TRANSLATE_CAPTIONS_OPTION";
    private static final String PREFERRED_TRACK = "PREFERRED_TRACK";
    private static final int MAX_SELECTIONS = 6;

    private static final Object SELECTION_LOCK = new Object();
    private static final AtomicBoolean drawObserved = new AtomicBoolean();
    private static final AtomicBoolean captionsShownInFlight = new AtomicBoolean();

    private static volatile Context context;
    private static volatile Set<String> availableLanguages = Collections.emptySet();
    private static volatile Map<String, String> nativeLabels = Collections.emptyMap();
    private static volatile String labelLocale = "";

    /** The last selection per video, oldest first; guarded by {@link #SELECTION_LOCK}. */
    private static final LinkedHashMap<String, Selection> selections = new LinkedHashMap<>();

    private static String lastTrackListMark = "", visibleVideo = "";
    /** The last native auto-translate code set observed, so a diagnostic is not repeated. */
    private static String lastAvailableTracks = "";
    private static Object switchingManager;

    private NativeCaptionBridge() {}

    enum Refresh {
        APPLIED,
        AI_STARTED,
        CAPTIONS_OFF,
        DEFERRED
    }

    /** One selection of a video. The player objects are held weakly: a manager can be collected. */
    private static final class Selection {
        final String video, language, selectedUrl;
        final boolean off, translated, asr;
        final WeakReference<Object> manager, track;
        final Object origin;
        final int reason;

        Selection(String video, Object manager, Object track, Object origin, int reason) {
            this.video = video;
            this.manager = new WeakReference<>(manager);
            this.track = new WeakReference<>(track);
            this.origin = origin;
            this.reason = reason;
            off = isOff(track);
            selectedUrl = off ? "" : url(track);
            language = off ? "" : language(track);
            translated = !off && TargetLanguage.fromUrl(selectedUrl) != null;
            asr = !off && vss(track).startsWith("a.");
        }
    }

    static void initialize(Context value) {
        context = value.getApplicationContext();
    }

    static boolean enabled() {
        return CaptionAddonSupport.aiInstalled()
                && context != null
                && DeepSeekConfig.enabled(context);
    }

    private static void mark(String stage, String detail) {
        CaptionDiagnostics.mark(context, stage, detail);
    }

    private static boolean isOff(Object track) {
        return track == null || DISABLE.equals(language(track));
    }

    private static boolean isPreferred(Object origin) {
        return origin instanceof Enum<?> && PREFERRED_TRACK.equals(((Enum<?>) origin).name());
    }

    private static String videoOf(Object track) {
        return CaptionUrls.videoId(url(track));
    }

    private static boolean isSourceTrack(Object track) {
        return DeepSeekCaptionHook.isYouTubeTimedTextUrl(url(track));
    }

    // ---- Language menu ----

    static void observeLanguageMetadata(Map<String, String> labels) {
        nativeLabels = Collections.unmodifiableMap(new HashMap<>(labels));
        availableLanguages = Collections.unmodifiableSet(new HashSet<>(labels.keySet()));
        labelLocale = LanguageMenuOrder.locale().toLanguageTag();
    }

    private static boolean labelsCurrent() {
        return labelLocale.equals(LanguageMenuOrder.locale().toLanguageTag());
    }

    static String languageStatus(String code) {
        if (!labelsCurrent() || nativeLabels.isEmpty()) return "languages_unavailable";
        return availableLanguages.contains(code) ? "languages_existing" : "languages_new";
    }

    public static String translationLabel(String code) {
        // Chinese variants are product labels, not native YouTube labels: the native menu can say
        // "中文（繁体中文）" while the app and its settings say "中文（繁体）". Use the one localizer
        // for both the inserted entry and its metadata so the menu cannot show two names.
        String canonical = CaptionLanguageSelection.canonical(code);
        if ("zh-Hans".equals(canonical) || "zh-Hant".equals(canonical))
            return LanguageMenuOrder.label(canonical);
        String name = labelsCurrent() ? nativeLabels.get(code) : null;
        return name == null ? LanguageMenuOrder.label(code) : name;
    }

    /** The native translation list plus the languages the user chose, each sorted into place. */
    public static List<?> augmentTranslations(List<?> original) {
        if ((!CaptionAddonSupport.aiInstalled() && !CaptionAddonSupport.simplifiedInstalled())
                || original == null
                || original.isEmpty()) return original;
        long started = System.nanoTime();
        try {
            Set<String> chosen = CaptionLanguageSelection.menuCodes();
            if (chosen.isEmpty()) return original;
            Set<String> present = new HashSet<>();
            Object prototype = null;
            for (Object track : original) {
                String code = CaptionLanguageSelection.canonical(language(track));
                if (!code.isEmpty()) present.add(code);
                if (prototype == null && isSourceTrack(track)) prototype = track;
            }
            if (prototype == null) return original;
            List<Object> copy = new ArrayList<>(original);
            Collator collator = Collator.getInstance(LanguageMenuOrder.locale());
            boolean changed = false;
            for (String code : chosen) {
                if (!present.add(code)) {
                    // The language is already native. A selected Chinese variant still needs the
                    // product label; replace only the label on a copy that keeps the native URL,
                    // vss and position.
                    int at = indexOfCode(copy, code);
                    if (at < 0 || !isChineseVariant(code)) continue;
                    Object existing = copy.get(at);
                    String before = displayName(existing).toString();
                    if (!redundantChineseLabel(code, before)) continue;
                    String wanted = translationLabel(code);
                    if (before.equals(wanted)) continue;
                    Object relabeled = relabelTranslation(existing, code);
                    if (relabeled == null) continue;
                    String after = displayName(relabeled).toString();
                    if (before.equals(after)) continue;
                    copy.set(at, relabeled);
                    changed = true;
                    mark(
                            "LANGUAGE_MENU_RELABELED",
                            "code="
                                    + code
                                    + ";from="
                                    + before
                                    + ";to="
                                    + after
                                    + ";position="
                                    + at
                                    + ";native_url_preserved=true");
                    continue;
                }
                Object added = cloneTranslation(prototype, code);
                if (added == null) continue;
                String label = displayName(added).toString();
                int at = insertionIndex(copy, label, collator);
                copy.add(at, added);
                changed = true;
                mark(
                        "LANGUAGE_MENU_INSERTED",
                        "code="
                                + code
                                + ";display_name="
                                + label
                                + ";position="
                                + at
                                + ";native_preserved=true");
            }
            String tracks = availableTracks(copy);
            if (!tracks.equals(lastAvailableTracks)) {
                lastAvailableTracks = tracks;
                mark(
                        "LANGUAGE_TRACKS_AVAILABLE",
                        "count=" + copy.size() + ";codes=" + tracks);
            }
            mark(
                    "LANGUAGE_MENU_READY",
                    "selected="
                            + chosen.size()
                            + ";items="
                            + copy.size()
                            + ";deduplicated=true;elapsed_us="
                            + (System.nanoTime() - started) / 1000);
            return changed ? copy : original;
        } catch (Exception failed) {
            mark("AI_MENU_INSERT_FAILED", failed.getClass().getSimpleName());
            return original;
        }
    }

    /** The runtime auto-translate code set, bounded and deduplicated for diagnostics. */
    private static String availableTracks(List<Object> tracks) {
        List<String> codes = new ArrayList<>();
        for (Object track : tracks) {
            if (codes.size() >= 160) break;
            String code = language(track);
            if (code == null || code.isEmpty() || codes.contains(code)) continue;
            codes.add(code);
        }
        StringBuilder out = new StringBuilder();
        for (String code : codes) {
            if (out.length() > 0) out.append(',');
            out.append(code);
        }
        return out.toString();
    }

    private static boolean isChineseVariant(String code) {
        String canonical = CaptionLanguageSelection.canonical(code);
        return "zh-Hans".equals(canonical) || "zh-Hant".equals(canonical);
    }

    /**
     * Is this native label redundantly naming the script inside the language name?
     * "中文（繁体中文）" is the observed case; "中文（台湾）" and "中文（繁体）" are not.
     */
    public static boolean redundantChineseLabel(String code, String label) {
        String canonical = CaptionLanguageSelection.canonical(code);
        if (label == null) return false;
        String text = label.replaceAll("\\s+", "");
        if ("zh-Hant".equals(canonical))
            return text.contains("繁体中文") || text.contains("繁體中文");
        if ("zh-Hans".equals(canonical))
            return text.contains("简体中文") || text.contains("簡體中文");
        return false;
    }

    private static int indexOfCode(List<Object> list, String code) {
        for (int i = 0; i < list.size(); i++)
            if (code.equals(CaptionLanguageSelection.canonical(language(list.get(i))))) return i;
        return -1;
    }

    private static int insertionIndex(List<Object> list, String label, Collator collator) {
        String key = LanguageMenuOrder.sortLabel(label);
        for (int i = 0; i < list.size(); i++)
            if (collator.compare(
                            key, LanguageMenuOrder.sortLabel(displayName(list.get(i)).toString()))
                    < 0) return i;
        return list.size();
    }

    // ---- Native draw ----

    public static boolean suppressNativeDraw() {
        if (CaptionAddonSupport.aiInstalled()
                && context != null
                && drawObserved.compareAndSet(false, true))
            mark(
                    "NATIVE_DRAW_HOOK_CONNECTED",
                    "build=n30;official=1.45.0;presentation=n29-presentation-v3;"
                            + "hook=SubtitleWindowView.draw");
        // Holding an accepted AI track survives waiting, safe blanks and rotation; source-only
        // keeps its previously recognized visible-overlay behavior.
        return enabled() && (CaptionActions.ownsNativeTrack() || CaptionActions.isVisibleActive());
    }

    // ---- The source tracks of a video ----

    /** Called when the native model first exposes its signed source tracks. */
    public static void onOriginalTrackList(List<?> tracks) {
        collectSourceCatalog(tracks);
        if (!CaptionAddonSupport.aiInstalled()
                || context == null
                || tracks == null
                || tracks.isEmpty()
                || CaptionActions.isVisibleActive()) return;
        try {
            String owner = "", sourceUrl = "";
            Set<String> sources = new HashSet<>();
            for (Object track : tracks) {
                String candidate = url(track);
                if (!DeepSeekCaptionHook.isYouTubeTimedTextUrl(candidate)) continue;
                String candidateOwner = CaptionUrls.videoId(candidate);
                if (candidateOwner.isEmpty()) continue;
                if (!owner.isEmpty() && !owner.equals(candidateOwner)) return;
                owner = candidateOwner;
                sourceUrl = CaptionUrls.sourceCaptionUrl(candidate);
                sources.add(sourceUrl);
            }
            if (owner.isEmpty()) return;
            boolean foreground = owner.equals(CaptionActions.currentVideoIdSnapshot());
            boolean sourceReady = false;
            synchronized (SELECTION_LOCK) {
                String markKey = owner + ":" + foreground;
                if (!markKey.equals(lastTrackListMark)) {
                    lastTrackListMark = markKey;
                    mark(
                            "NATIVE_TRACK_LIST_READY",
                            "video="
                                    + owner
                                    + ";foreground="
                                    + foreground
                                    + ";tracks="
                                    + tracks.size()
                                    + ";sources="
                                    + sources.size()
                                    + ";elapsed_realtime_ms="
                                    + SystemClock.elapsedRealtime());
                }
                if (foreground && sources.size() == 1) sourceReady = true;
            }
            if (sourceReady
                    && CaptionButtonController.captionsKnownOn()
                    && !CaptionActions.isVisibleActive())
                CaptionButtonController.scheduleCaptionsShown();
        } catch (Exception failure) {
            mark("NATIVE_TRACK_LIST_FAILED", failure.getClass().getSimpleName());
        }
    }

    private static void publishSourceCatalog(Object manager) {
        if (manager == null) return;
        try {
            List<?> tracks = nativeTracks(manager);
            if (tracks != null && tracks.isEmpty()) {
                String owner = nativeModelVideo(manager);
                if (owner != null && !owner.isEmpty())
                    SourceCatalog.register(owner, Collections.emptyList(), true);
            } else collectSourceCatalog(tracks);
        } catch (Exception ignored) {
            // The model is gone or not ready; the catalog keeps what it has.
        }
    }

    /** Registers the untranslated tracks of each video and tells the engine its catalog changed. */
    private static void collectSourceCatalog(List<?> tracks) {
        if (tracks == null) return;
        Map<String, List<SourceSelection>> byVideo = new LinkedHashMap<>();
        for (Object track : tracks) {
            String selected = url(track), code = language(track), id = vss(track);
            NativeAsrTrackReference.remember(code, id, selected);
            if (code == null || code.endsWith("_OPTION") || !CaptionUrls.isTimedText(selected))
                continue;
            Uri descriptor = Uri.parse(selected);
            if (!descriptor.getQueryParameters("tlang").isEmpty()) continue;
            String owner = CaptionUrls.videoId(selected);
            String declared = CaptionLanguageContext.observe(selected, "").sourceCode;
            if (owner.isEmpty() || declared.isEmpty() || declared.equals("UNKNOWN")) continue;
            SourceSelection.Kind kind =
                    id == null
                            ? SourceSelection.Kind.UNKNOWN
                            : id.startsWith("a.")
                                    ? SourceSelection.Kind.ASR
                                    : id.startsWith(".")
                                            ? SourceSelection.Kind.MANUAL
                                            : SourceSelection.Kind.UNKNOWN;
            String stableId =
                    (id == null || id.isEmpty() ? "unknown/" + declared : id)
                            + "/"
                            + nullToEmpty(descriptor.getQueryParameter("name"))
                            + "/"
                            + nullToEmpty(descriptor.getQueryParameter("id"));
            byVideo.computeIfAbsent(owner, key -> new ArrayList<>())
                    .add(
                            new SourceSelection(
                                    owner, stableId, declared, selected, kind, true, 0, ""));
        }
        for (Map.Entry<String, List<SourceSelection>> entry : byVideo.entrySet()) {
            SourceCatalog.register(entry.getKey(), entry.getValue(), true);
            CaptionActions.command(CaptionContracts.Action.SOURCE_CATALOG, entry.getKey());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ---- Selections made by the native player ----

    public static void onNativeSelectionWithReason(
            Object manager, Object track, Object origin, int reason) {
        captureSelection(manager, track, origin, reason, null);
    }

    public static void onNativeSelection(Object manager, Object track, Object origin) {
        captureSelection(manager, track, origin, 0, null);
    }

    /**
     * Bound to the shared native dispatcher, after native track filtering, before loading text.
     * Both the manual menu and automatic model initialization flow through this point.
     */
    public static void onNativeTrackApplied(Object manager, Object event) {} // bound at patch time

    /** modelOwner is the caption model's videoId, NOT the event's playback nonce (CPN). */
    public static void onNativeAppliedEvent(
            Object manager,
            Object committed,
            Object requested,
            Object origin,
            int reason,
            String modelOwner) {
        // Forced-caption fallback can make the committed track non-null even after explicit Off.
        // Preserve that user intent; automatic null/default events still use the actual track.
        boolean explicitOff = isPreferred(origin) && isOff(requested);
        onNativeSelectionApplied(
                manager, explicitOff ? null : committed, origin, reason, modelOwner);
    }

    public static void onNativeSelectionApplied(
            Object manager, Object track, Object origin, int reason, String modelOwner) {
        try {
            captureSelection(
                    manager, track, origin, reason, modelOwner == null ? "" : modelOwner.trim());
        } catch (Exception failed) {
            mark("NATIVE_APPLIED_CAPTURE_FAILED", failed.getClass().getSimpleName());
        }
    }

    /**
     * Records what the native player selected for a video and, when that video is on screen, makes
     * the engine follow it. [modelOwner] is null for plain selections and the model's video for
     * applied events.
     */
    private static void captureSelection(
            Object manager, Object track, Object origin, int reason, String modelOwner) {
        synchronized (SELECTION_LOCK) {
            // Internal null/reselect callbacks must not erase the snapshot or change memory.
            if (manager != null && manager == switchingManager) return;
            String code = track == null ? DISABLE : language(track);
            if (AUTO_TRANSLATE.equals(code)) return;
            boolean off = isOff(track);
            String video =
                    off ? (modelOwner == null ? modelVideo(manager) : modelOwner) : videoOf(track);
            String current = CaptionActions.currentVideoIdSnapshot();
            boolean applied = modelOwner != null;
            // A reset with no model emits a null selection, not an explicit Off on the departed
            // video. Never infer its owner from a reused manager's previous selection.
            if (applied && off && video.isEmpty()) return;
            if (applied && !modelOwner.isEmpty() && !off && !modelOwner.equals(video)) {
                mark(
                        "NATIVE_APPLIED_OWNER_REJECTED",
                        "model_track_mismatch;model_foreground="
                                + modelOwner.equals(current)
                                + ";track_foreground="
                                + video.equals(current));
                return;
            }
            if (video.isEmpty() && off) {
                Selection owner = latestForManager(manager);
                if (owner != null) video = owner.video;
            }
            // An unidentified background null callback is not evidence that the visible CC is off.
            if (video.isEmpty() && !current.isEmpty()) return;
            if (!off && !isSourceTrack(track)) return;

            Selection value = new Selection(video, manager, track, origin, reason);
            if (applied) markApplied(value, code, current);
            selections.remove(video);
            selections.put(video, value);
            while (selections.size() > MAX_SELECTIONS)
                selections.remove(selections.keySet().iterator().next());
            if (!current.isEmpty() && !current.equals(video)) {
                mark("NATIVE_SELECTION_BACKGROUND", "visible_state_preserved");
                return;
            }
            publishSourceCatalog(manager);
            followNativeSelection(value, track, code, isPreferred(origin));
        }
    }

    private static void markApplied(Selection value, String code, String current) {
        boolean foreground = value.video.equals(current);
        mark(
                "NATIVE_TRACK_APPLIED",
                "owner=caption_model;foreground="
                        + foreground
                        + ";off="
                        + value.off
                        + ";translated="
                        + value.translated
                        + ";reason="
                        + value.reason
                        + ";origin="
                        + (value.origin instanceof Enum<?> ? ((Enum<?>) value.origin).name() : "")
                        + ";track="
                        + (value.off ? "" : code + "/" + (value.asr ? "asr" : "manual"))
                        + ";elapsed_realtime_ms="
                        + SystemClock.elapsedRealtime());
        if (foreground && !value.off)
            mark(
                    "NATIVE_OWNER_ESTABLISHED",
                    "video="
                            + value.video
                            + ";translated="
                            + value.translated
                            + ";elapsed_realtime_ms="
                            + SystemClock.elapsedRealtime());
    }

    /** Follows the native selection: it is the only language authority. */
    private static void followNativeSelection(
            Selection value, Object track, String code, boolean explicit) {
        boolean off = value.off;
        if (!explicit && !off)
            SourceCatalog.defaultLanguage(
                    value.video, CaptionLanguageContext.observe(value.selectedUrl, "").sourceCode);
        if (off) {
            if (explicit) EngineResume.declined();
            else EngineResume.automaticReset();
            applySelection(null, explicit);
            return;
        }
        applySelection(track, explicit);
    }

    /** Starts the engine for a native track (or stops it for null/Off). */
    static void applySelection(Object track, boolean explicit) {
        if (!CaptionAddonSupport.aiInstalled()) return;
        try {
            String code = track == null ? DISABLE : language(track);
            if (AUTO_TRANSLATE.equals(code)) return;
            if (DISABLE.equals(code)) {
                if (explicit) {
                    CaptionChoice.toggle(false);
                    CaptionChoice.markExplicitOff();
                } else CaptionChoice.automaticOff();
                if (enabled()) CaptionActions.deactivateFromNativeCaptionState();
                return;
            }
            String selected = url(track);
            if (!DeepSeekCaptionHook.isYouTubeTimedTextUrl(selected)) return;
            // A manual Off survives every automatic track rebuild. An explicit selection is
            // allowed to replace it.
            if (!explicit && (CaptionChoice.userOff() || CaptionChoice.explicitOff())) return;
            boolean translate = TargetLanguage.fromUrl(selected) != null;
            // The native selection is the session state; no separate language memory exists.
            CaptionChoice.select(code, translate);
            if (!enabled()) return;
            startEngine(selected, translate);
            CaptionMusicSuppressor.kick();
        } catch (Exception failed) {
            mark("AI_SELECTION_FAILED", failed.getClass().getSimpleName());
        }
    }

    /** Activates the engine on [selected]: our own target track, a translation, or source only. */
    private static void startEngine(String selected, boolean translate) {
        CaptionButtonController.noteAiTrackSelected();
        SourceSelection own =
                translate ? SourceTrackPolicy.ownTargetTrack(context, selected) : null;
        if (own != null) CaptionActions.activateSource(context, own.url);
        else if (translate) {
            String source = SourceTrackPolicy.apply(context, selected);
            CaptionLifecycleRestore.noteAiTarget(source);
            CaptionActions.activate(context, source);
        } else CaptionActions.activateSource(context, selected);
    }

    // ---- Switching videos ----

    static void onVideoId(String video) {
        video = video == null ? "" : video.trim();
        synchronized (SELECTION_LOCK) {
            if (video.equals(visibleVideo)) return;
            visibleVideo = video;
            mark("VIDEO_SWITCHED", "video=" + video);
        }
    }

    private static Selection currentSelection() {
        String video = CaptionActions.currentVideoIdSnapshot();
        return video.isEmpty() ? null : selections.get(video);
    }

    private static Selection latestForManager(Object manager) {
        Selection found = null;
        if (manager != null)
            for (Selection value : selections.values())
                if (value.manager.get() == manager) found = value;
        return found;
    }

    /** Is the manager of [value] still showing that video's captions? */
    private static boolean ownsManager(Selection value) {
        Object manager = value.manager.get();
        if (manager == null) return false;
        Selection owner = latestForManager(manager);
        if (owner != null && !owner.video.equals(value.video)) return false;
        String model = modelVideo(manager);
        return model.isEmpty() || model.equals(value.video);
    }

    private static String modelVideo(Object manager) {
        if (manager == null) return "";
        try {
            String bound = nativeModelVideo(manager);
            if (bound != null && !bound.isEmpty()) return bound;
            List<?> tracks = nativeTracks(manager);
            if (tracks == null) return "";
            String owner = "";
            for (Object track : tracks) {
                String video = videoOf(track);
                if (video.isEmpty()) continue;
                if (!owner.isEmpty() && !owner.equals(video)) return "mixed_model";
                owner = video;
            }
            return owner;
        } catch (Exception unavailable) {
            return "";
        }
    }

    private static List<?> tracksFor(Selection value, Object manager) {
        return value.translated ? translatedTracks(manager) : nativeTracks(manager);
    }

    /** The live track object of a selection, or null when it no longer exists. */
    private static Object currentTrack(Selection value) {
        if (value.off || !ownsManager(value)) return null;
        Object manager = value.manager.get();
        if (manager == null) return null;
        try {
            List<?> tracks = tracksFor(value, manager);
            if (tracks != null && !tracks.isEmpty()) {
                for (Object track : tracks)
                    if (value.video.equals(videoOf(track))
                            && value.language.equals(language(track))
                            && value.translated == (TargetLanguage.fromUrl(url(track)) != null)
                            && value.asr == vss(track).startsWith("a.")) return track;
                return null; // A changed native model is authoritative; never reuse its old object.
            }
        } catch (Exception unavailable) {
            return null;
        }
        Object track = value.track.get();
        return track != null && value.video.equals(videoOf(track)) ? track : null;
    }

    /**
     * Native managers may be collected after Shorts prefetch. Keep only the selected descriptor,
     * not strong player objects; never use a descriptor contradicted by a surviving native model.
     */
    private static boolean canActivateSnapshot(Selection value) {
        if (value.off
                || !value.video.equals(CaptionActions.currentVideoIdSnapshot())
                || !value.video.equals(CaptionUrls.videoId(value.selectedUrl))) return false;
        Object manager = value.manager.get();
        if (manager == null) return true;
        if (!ownsManager(value)) return false;
        try {
            List<?> tracks = tracksFor(value, manager);
            return tracks == null || tracks.isEmpty() || currentTrack(value) != null;
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** Starts the engine for the current native selection, without changing that selection. */
    static Refresh refreshNativeTrack() {
        synchronized (SELECTION_LOCK) {
            if (CaptionChoice.userOff()) return Refresh.CAPTIONS_OFF;
            Selection value = currentSelection();
            if (value == null) return Refresh.DEFERRED;
            if (value.off) return Refresh.CAPTIONS_OFF;
            if (!enabled()) return Refresh.CAPTIONS_OFF;
            if (!canActivateSnapshot(value)) return Refresh.DEFERRED;
            CaptionChoice.select(value.language, value.translated);
            startEngine(value.selectedUrl, value.translated);
            CaptionMusicSuppressor.forceNativeRendererScan();
            CaptionMusicSuppressor.kick();
            mark(
                    "ENGINE_NATIVE_ACTIVATED",
                    "target="
                            + value.language
                            + ";translated="
                            + value.translated);
            return value.translated ? Refresh.AI_STARTED : Refresh.APPLIED;
        }
    }

    /**
     * The native CC control turned on. The official Auto captions setting owns whether this
     * happens; here the remembered language becomes the active one. If the player committed no
     * track (the "never auto show" policy leaves captions off until a manual tap), the last signed
     * source track of the foreground video is used to start the engine directly.
     */
    static void onCaptionsShown() {
        if (!enabled()) return;
        if (!captionsShownInFlight.compareAndSet(false, true)) return;
        try {
            Selection value = currentSelection();
            if (value == null || value.off) return;
            CaptionChoice.select(value.language, value.translated);
            Refresh refreshed = refreshNativeTrack();
            mark(
                    "CAPTIONS_SHOWN",
                    "target="
                            + value.language
                            + ";translated="
                            + value.translated
                            + ";native="
                            + refreshed.name());
        } catch (Exception failed) {
            mark("CAPTIONS_SHOWN_FAILED", failed.getClass().getSimpleName());
        } finally {
            captionsShownInFlight.set(false);
        }
    }

    // ---- Translation tracks ----

    public static String translationUrl(String url, String code) {
        return TargetLanguage.withCode(url, code);
    }

    public static String translationVss(String value, String code) {
        int at = value == null ? -1 : value.indexOf('.');
        return "t" + code + (at < 0 ? "" : value.substring(at));
    }

    public static String simplifiedVss(String value) {
        if (value == null) return "tzh-Hans";
        int separator = value.indexOf('.');
        return "tzh-Hans" + (separator < 0 ? "" : value.substring(separator));
    }

    // ---- Bound at patch time ----

    /** The caption model's videoId, never the unrelated caption event playback identifier. */
    public static String nativeModelVideo(Object manager) {
        return "";
    }

    public static List<?> nativeTracks(Object manager) {
        return null;
    }

    public static List<?> translatedTracks(Object manager) {
        return null;
    }

    public static Object augmentMetadata(Object metadata) {
        return metadata;
    }

    public static CharSequence displayName(Object track) {
        return "";
    }

    public static String language(Object track) {
        return "";
    }

    public static String vss(Object track) {
        return "";
    }

    public static String url(Object track) {
        return "";
    }

    public static Object cloneTranslation(Object track, String code) {
        return null;
    }

    /** A copy of an existing native translation with only its display label replaced. */
    public static Object relabelTranslation(Object track, String code) {
        return track;
    }
}
