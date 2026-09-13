package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rhythm-aware centered semantic ledger runtime.
 *
 * <p>Semantic quality and on-screen cadence are deliberately separate layers. The translation
 * planner still produces complete source-language semantic units using the dev9 quality contract.
 * Those canonical translations are immutable. Long canonical units may later be presentation-sliced
 * by AI, but the slice texts must concatenate byte-for-byte to the canonical translation, so display
 * cadence cannot rewrite translation content.</p>
 *
 * <p>Priority and background retry state are also independent. Background misses can never suppress
 * an urgent play-head repair. A predictive gap-rescue request starts before playback reaches an
 * unresolved atom, while already-ready display entries remain non-destructively protected.</p>
 */
final class SemanticLedgerCaptionControllerV2 {
    private static final int UNRESOLVED = 0;
    private static final int READY = 1;

    private static final int PRIORITY_BASE_BEFORE = 30;
    private static final int PRIORITY_BASE_AFTER = 82;
    private static final int PRIORITY_MAX_BEFORE = 68;
    private static final int PRIORITY_MAX_AFTER = 172;
    private static final int PRIORITY_CORE_AFTER = 32;

    // Background inventory is built from stable, overlapping time blocks instead of a rolling
    // "first unresolved atom" window. Adjacent 30 s cores start every 15 s, so difficult boundaries
    // are naturally seen twice. Three consecutive blocks share one wider source page. dev10 sent one
    // API call per block and hoped a provider-side prefix cache would absorb the repeated page
    // payload; measured cache-hit rates on real traffic showed that hope did not pay off. dev15
    // instead merges every not-yet-done block of a page into a single request up front (see
    // buildBackgroundLocked/Request#backgroundPage), so the atoms/context page is transmitted once
    // per page instead of once per block. If a merged page request keeps failing, it permanently
    // falls back to the original one-request-per-block shape for that page (Request#backgroundBlock)
    // so a bad page can never make the whole video's background inventory stall.
    private static final long BACKGROUND_BLOCK_STRIDE_MS = 15_000L;
    private static final long BACKGROUND_BLOCK_CORE_MS = 30_000L;
    private static final int BACKGROUND_CACHE_PAGE_BLOCKS = 3;
    private static final int BACKGROUND_BLOCK_CONTEXT_BEFORE = 26;
    private static final int BACKGROUND_BLOCK_CONTEXT_AFTER = 44;
    private static final int BACKGROUND_MAX_EXTERNAL_FAILURES = 2;
    private static final long BACKGROUND_BLOCK_RETRY_MS = 4_500L;
    // dev9 gave every atom two looks with different core framing because adjacent 30 s cores start
    // every 15 s. dev15's page merge collapsed those two looks into one, and with temperature 0 a
    // boundary the model declines once is declined forever. dev16 restores the second look as one
    // bounded, differently-framed repair per cache page, centered on the atoms that stayed
    // unresolved instead of re-sending the identical page request.
    private static final int BACKGROUND_ALT_CORE_MARGIN_ATOMS = 8;
    private static final int BACKGROUND_ALT_MAX_CORE_ATOMS = 120;

    private static final int CONTEXT_OUTSIDE_WINDOW_ATOMS = 20;
    private static final long PREFETCH_AHEAD_MS = 60_000L;
    private static final long BACKGROUND_LOW_WATER_MS = 30_000L;
    private static final long BACKGROUND_CANCEL_AHEAD_MS = PREFETCH_AHEAD_MS + 12_000L;
    private static final long GAP_RESCUE_AHEAD_MS = 9_000L;
    private static final long LONG_DISPLAY_THRESHOLD_MS = 5_200L;
    private static final long SEEK_THRESHOLD_MS = 2_900L;
    private static final long STARTUP_SEEK_DEBOUNCE_MS = 2_200L;
    private static final int STARTUP_SEEK_CORE_MARGIN_ATOMS = 6;
    private static final long PRIORITY_CURRENT_REPAIR_GRACE_MS = 650L;
    private static final long PRIORITY_FOCUS_URGENT_LEAD_MS = 2_800L;
    private static final long PRIORITY_FOCUS_MAX_BACKOFF_MS = 6_000L;
    private static final int PRIORITY_FOCUS_BACKOFF_AFTER_MISSES = 2;
    private static final long PRIORITY_DEDUP_COOLDOWN_MS = 1_800L;
    private static final long FOREIGN_TRACK_GRACE_MS = 1_500L;
    private static final long PLAYER_RESTORE_GRACE_MS = 4_000L;
    private static final long DISPLAY_TICK_MS = 80L;
    private static final long MAX_CLOCK_EXTRAPOLATION_MS = 1_350L;
    private static final int CACHE_FORMAT = 8;
    private static final byte[] CACHE_MARKER =
            "\n#ai-source-atom-semantic-ledger-v2-rhythm".getBytes(StandardCharsets.UTF_8);

    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final ExecutorService SOURCES = Executors.newCachedThreadPool(
            daemonThreadFactory("AiCaptionLedger2Source-")
    );
    private static final ExecutorService PRIORITY = Executors.newSingleThreadExecutor(
            daemonThreadFactory("AiCaptionLedger2Current-")
    );
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor(
            daemonThreadFactory("AiCaptionLedger2Ahead-")
    );
    private static final ExecutorService REFINER = Executors.newSingleThreadExecutor(
            daemonThreadFactory("AiCaptionDisplayRefine-")
    );
    private static final Handler DISPLAY = new Handler(Looper.getMainLooper());
    private static final Runnable DISPLAY_TICK = SemanticLedgerCaptionControllerV2::displayTick;
    private static final Object ACTIVE_LOCK = new Object();

    private static volatile Session active;
    private static volatile String currentVideoId = "";
    private static volatile boolean compactPlayer;
    private static volatile long restoreGraceUntilMs;
    private static volatile long latestVideoTimeMs;
    private static volatile long latestVideoTimeRealtimeMs;
    private static volatile float latestPlaybackRate = 1f;

    private SemanticLedgerCaptionControllerV2() {}

    static boolean isVisibleActive() {
        Session session = active;
        return session != null && !session.cancelled && session.visible;
    }

    static void deactivateFromCaptionButton() {
        if (isVisibleActive()) deactivate("已通过字幕按钮关闭 AI 字幕");
    }

    static void deactivateFromNativeCaptionState() {
        if (isVisibleActive()) deactivate("原生 CC 字幕状态已关闭");
    }
    static void deactivateForCoreSwitch() {
        Session session = active;
        if (session != null && !session.cancelled) deactivate("切换字幕翻译核心");
    }
    static String activeTranslatedUrl() {
        Session session = active;
        return session == null || session.cancelled ? "" : session.translatedUrl;
    }

    static void setMainActivity(Activity activity) {
        CaptionOverlay.setActivity(activity);
        Session session = active;
        if (session != null) render(session, session.currentTimeMs);
    }

    static void onPlayerType(String rawType) {
        String type = rawType == null ? "" : rawType.toUpperCase(java.util.Locale.ROOT);
        boolean compact = type.contains("MINIMIZED") || type.contains("MINIMAL") ||
                type.contains("HIDDEN") || type.contains("DISMISSED") ||
                type.contains("PICTURE_IN_PICTURE");
        if (compactPlayer && !compact) {
            restoreGraceUntilMs = SystemClock.elapsedRealtime() + PLAYER_RESTORE_GRACE_MS;
        }
        compactPlayer = compact;
    }

    static String restoreTargetAfterMiniplayer(String url) {
        Session session = active;
        if (session == null || session.cancelled || !session.visible) return url;
        long now = SystemClock.elapsedRealtime();
        boolean protectedRequest = compactPlayer || now <= restoreGraceUntilMs ||
                now - session.activatedAtMs <= FOREIGN_TRACK_GRACE_MS;
        if (!protectedRequest || !DeepSeekCaptionHook.isYouTubeTimedTextUrl(url)) return url;
        String requestedVideo = videoIdFromUrl(url);
        if (!requestedVideo.isEmpty() && !session.videoId.isEmpty() &&
                !requestedVideo.equals(session.videoId)) return url;
        return TargetLanguage.withCode(url, session.targetLanguage.code);
    }

    static void onVideoId(String rawVideoId) {
        String videoId = rawVideoId == null ? "" : rawVideoId.trim();
        if (videoId.isEmpty()) return;
        String previous = currentVideoId;
        currentVideoId = videoId;
        SemanticCaptionTimeline.onVideoId(videoId);
        if (!previous.isEmpty() && !previous.equals(videoId)) {
            latestVideoTimeMs = 0L;
            latestVideoTimeRealtimeMs = SystemClock.elapsedRealtime();
            latestPlaybackRate = 1f;
        }
        Session session = active;
        if (session == null || session.cancelled) return;
        String owner = session.videoId;
        if ((!owner.isEmpty() && !owner.equals(videoId)) ||
                (owner.isEmpty() && !previous.isEmpty() && !previous.equals(videoId))) {
            deactivate("视频已切换：" + abbreviatedVideoId(videoId));
            return;
        }
        if (owner.isEmpty()) session.videoId = videoId;
    }

    static void refreshConfiguration(Context context) {
        Session session = active;
        if (session == null || session.cancelled || context == null) return;
        if (!DeepSeekConfig.load(context).enabled) {
            deactivate("已在设置中停用 AI 字幕");
            return;
        }
        activate(context, session.translatedUrl);
    }

    static void activate(Context context, String translatedUrl) {
        activateInternal(context, translatedUrl, true);
    }

    static void prewarm(Context context, String sourceUrl) {
        if (context == null || !DeepSeekCaptionHook.isYouTubeTimedTextUrl(sourceUrl) ||
                TargetLanguage.fromUrl(sourceUrl) != null || !DeepSeekConfig.isReady(context)) return;
        String code = DeepSeekConfig.defaultTargetLanguage(context);
        if (code.isEmpty()) return;
        Session current = active;
        String sourceVideo = videoIdFromUrl(sourceUrl);
        if (current != null && !current.cancelled && !current.visible &&
                current.targetLanguage.code.equalsIgnoreCase(code) &&
                (sourceVideo.isEmpty() || current.videoId.isEmpty() || sourceVideo.equals(current.videoId))) {
            return;
        }
        activateInternal(context, TargetLanguage.withCode(sourceUrl, code), false);
    }

    private static void activateInternal(Context context, String translatedUrl, boolean visible) {
        if (context == null || translatedUrl == null) return;
        Context app = context.getApplicationContext();
        DeepSeekConfig.Snapshot config = DeepSeekConfig.load(app);
        TargetLanguage target = TargetLanguage.fromUrl(translatedUrl);
        if (target == null) return;
        String requestKey = CaptionEngine.requestKey(app, translatedUrl) + "|semantic-ledger-v2-rhythm";
        String videoId = videoIdFromUrl(translatedUrl);
        if (videoId.isEmpty()) videoId = currentVideoId;

        final Session session;
        synchronized (ACTIVE_LOCK) {
            Session current = active;
            if (current != null && !current.cancelled && current.requestKey.equals(requestKey) &&
                    sameTranslationConfig(current.config, config) && !current.terminalError) {
                current.activatedAtMs = SystemClock.elapsedRealtime();
                boolean instant = visible && !current.visible && current.firstReady;
                if (visible) current.visible = true;
                if (current.visible) render(current, current.currentTimeMs);
                if (instant) {
                    CaptionDiagnostics.mark(app, "AI_SHOWN_FROM_PREWARM", "预热语义账本已就绪，选择后立即显示");
                }
                schedule(current);
                scheduleDisplayTick();
                return;
            }
            if (current != null) current.cancel();
            session = new Session(
                    SESSION_IDS.incrementAndGet(), app, translatedUrl, requestKey,
                    videoId, target, config
            );
            session.visible = visible;
            session.currentTimeMs = estimatedVideoTime(SystemClock.elapsedRealtime());
            active = session;
        }

        scheduleDisplayTick();
        if (!config.ready()) {
            session.error = "请先在 Morphe 设置中启用 AI 字幕并填写 API Key";
            session.terminalError = true;
            if (session.visible) CaptionOverlay.showStatus(session.error, overlayGuard(session, session.renderGeneration));
            CaptionDiagnostics.mark(app, "CONFIG_NOT_READY", session.error);
            return;
        }

        if (visible) CaptionOverlay.showStatus(
                target.displayName + " AI 字幕准备中…",
                overlayGuard(session, session.renderGeneration)
        );
        CaptionDiagnostics.mark(
                app,
                visible ? "DYNAMIC_STARTED" : "INSTANT_PREWARM_STARTED",
                "目标 " + target.promptLabel() +
                        (visible ? "，正在建立节奏感知 AI 语义账本" : "，后台预热节奏感知 AI 语义账本")
        );
        session.sourceTask = SOURCES.submit(() -> loadAndStart(session));
    }

    static void observeTimedTextUrl(String url) {
        if (!DeepSeekCaptionHook.isYouTubeTimedTextUrl(url)) return;
        Session session = active;
        if (session == null || session.cancelled) return;
        if (compactPlayer || SystemClock.elapsedRealtime() <= restoreGraceUntilMs) return;
        if (!session.visible && TargetLanguage.fromUrl(url) == null) return;
        String requestedVideo = videoIdFromUrl(url);
        if (!requestedVideo.isEmpty() && !session.videoId.isEmpty() &&
                !requestedVideo.equals(session.videoId)) {
            deactivateIfCurrent(session, "检测到新视频字幕轨");
            return;
        }
        String target = query(url, "tlang");
        long age = SystemClock.elapsedRealtime() - session.activatedAtMs;
        if ((target != null && !target.trim().isEmpty()) || age > FOREIGN_TRACK_GRACE_MS) {
            deactivateIfCurrent(session, "已切换到其他字幕轨");
        }
    }

    static void onVideoTime(long timeMs) {
        long clean = Math.max(0L, timeMs);
        long now = SystemClock.elapsedRealtime();
        long expected = estimatedVideoTime(now);
        boolean seek = latestVideoTimeRealtimeMs > 0L && Math.abs(clean - expected) > SEEK_THRESHOLD_MS;
        updatePlaybackClock(clean, now);

        Session session = active;
        if (session == null || session.cancelled) return;
        session.currentTimeMs = clean;

        boolean debounceStartupSeek = false;
        boolean logStartupDebounce = false;
        if (seek) {
            synchronized (session.lock) {
                debounceStartupSeek = shouldDebounceStartupSeekLocked(session, clean, now);
                if (debounceStartupSeek && !session.startupSeekDebounceLogged) {
                    session.startupSeekDebounceLogged = true;
                    logStartupDebounce = true;
                }
            }
        }
        if (logStartupDebounce) {
            CaptionDiagnostics.mark(
                    session.context,
                    "STARTUP_SEEK_DEBOUNCED",
                    "首屏优先请求仍覆盖新的播放锚点；忽略启动阶段瞬时 seek 重建，避免重复首句 API"
            );
        }
        if (seek && !debounceStartupSeek) reprioritizeAfterSeek(session, clean);
        render(session, clean);
        schedule(session);
        scheduleDisplayTick();
    }

    private static boolean shouldDebounceStartupSeekLocked(Session session, long timeMs, long now) {
        if (session.firstReady || !session.timelineReady || session.timelineStartedAtMs <= 0L) return false;
        if (now - session.timelineStartedAtMs > STARTUP_SEEK_DEBOUNCE_MS) return false;
        Request current = session.priorityRequest;
        if (current == null || session.atoms.isEmpty()) return false;
        int newAnchor = anchor(session.atoms, timeMs);
        if (newAnchor < 0) return false;
        int from = Math.max(0, current.coreFrom - STARTUP_SEEK_CORE_MARGIN_ATOMS);
        int to = Math.min(session.atoms.size(), current.coreTo + STARTUP_SEEK_CORE_MARGIN_ATOMS);
        return newAnchor >= from && newAnchor < to;
    }

    private static void displayTick() {
        Session session = active;
        if (session == null || session.cancelled) return;
        long time = estimatedVideoTime(SystemClock.elapsedRealtime());
        session.currentTimeMs = time;
        render(session, time);
        schedule(session);
        scheduleDisplayTick();
    }

    private static void scheduleDisplayTick() {
        DISPLAY.removeCallbacks(DISPLAY_TICK);
        Session session = active;
        if (session != null && !session.cancelled) DISPLAY.postDelayed(DISPLAY_TICK, DISPLAY_TICK_MS);
    }

    private static void loadAndStart(Session session) {
        try {
            RawCaptionSource.Source source = RawCaptionSource.load(session.context, session.translatedUrl);
            if (!isCurrent(session) || session.cancelled) return;
            SourceAtomTimeline.Result atomized = SourceAtomTimeline.build(source.body, source.document);
            if (atomized.atoms.isEmpty()) {
                failSession(session, "这个视频没有可用的原字幕时间原子");
                return;
            }

            SemanticCaptionTimeline.replace(session.videoId, SourceAtomTimeline.asCues(atomized.atoms));
            CaptionDiagnostics.mark(
                    session.context,
                    "SOURCE_ATOM_TIMELINE_READY",
                    "原生 " + atomized.rawCueCount + " 个 cue → " + atomized.atoms.size() +
                            " 个词级时间原子；原生细粒度 timing " +
                            Math.round(atomized.preciseRatio() * 100d) + "%"
            );

            session.cacheKey = DiskCaptionCache.key(
                    cacheIdentity(source.body), session.config, session.targetLanguage.code
            );
            synchronized (session.lock) {
                session.atoms = atomized.atoms;
                List<String> texts = new ArrayList<>(atomized.atoms.size());
                for (SourceAtomTimeline.Atom atom : atomized.atoms) {
                    texts.add(atom.text == null ? "" : atom.text);
                }
                session.sourceTexts = Collections.unmodifiableList(texts);
                int count = atomized.atoms.size();
                session.displayTexts = new String[count];
                session.displayFrom = new int[count];
                session.displayTo = new int[count];
                Arrays.fill(session.displayFrom, -1);
                Arrays.fill(session.displayTo, -1);
                session.states = new int[count];
                session.priorityAttempts = new int[count];
                session.priorityFocusMisses = new int[count];
                session.backgroundAttempts = new int[count];
                session.priorityRetryAfterMs = new long[count];
                session.backgroundRetryAfterMs = new long[count];
                session.refineQueued = new boolean[count];
                int blockCount = backgroundBlockCountLocked(session);
                session.backgroundBlockDone = new boolean[blockCount];
                session.backgroundBlockAttempts = new int[blockCount];
                session.backgroundBlockRetryAfterMs = new long[blockCount];
                int pageCount = backgroundPageCountForBlocks(blockCount);
                session.backgroundPageFallback = new boolean[pageCount];
                session.backgroundPageAttempts = new int[pageCount];
                session.backgroundPageRetryAfterMs = new long[pageCount];
                session.backgroundPageAltUsed = new boolean[pageCount];
                session.backgroundPageAltFrom = new int[pageCount];
                session.backgroundPageAltTo = new int[pageCount];
                Arrays.fill(session.backgroundPageAltFrom, -1);
                Arrays.fill(session.backgroundPageAltTo, -1);
                session.timelineReady = true;
                session.timelineStartedAtMs = SystemClock.elapsedRealtime();
            }

            int cached = restoreCache(session);
            int anchor;
            synchronized (session.lock) {
                anchor = anchor(session.atoms, session.currentTimeMs);
                session.firstReady = isReadyLocked(session, anchor);
            }
            if (cached > 0) {
                CaptionDiagnostics.mark(
                        session.context,
                        "CACHE_HIT",
                        "立即复用 " + cached + "/" + session.atoms.size() + " 个词级时间原子的节奏语义账本"
                );
                render(session, session.currentTimeMs);
                scheduleCachedRefinements(session);
            }

            CaptionDiagnostics.mark(
                    session.context,
                    "SEMANTIC_LEDGER_V2_STARTED",
                    "完整语义翻译与显示切片分层；首屏优先独占，后台固定 30 秒块/15 秒步长，3 块共享缓存源页；播放头前 9 秒主动修复缺口"
            );
            schedule(session);
        } catch (Throwable failure) {
            if (!session.cancelled && isCurrent(session)) {
                failSession(session, CaptionDiagnostics.errorDetail(failure));
            }
        }
    }

    private static void schedule(Session session) {
        if (!isCurrent(session) || session.cancelled || !session.timelineReady) return;
        Request cancelPriority = null;
        Request cancelBackground = null;
        Request startPriority = null;
        Request startBackground = null;
        int rescueTarget = -1;
        int anchor;
        synchronized (session.lock) {
            anchor = anchor(session.atoms, session.currentTimeMs);
            if (anchor < 0 || anchor >= session.states.length) return;
            long now = SystemClock.elapsedRealtime();
            BackgroundPauseGovernor.Snapshot demand = BackgroundPauseGovernor.snapshot();

            if (session.priorityRequest != null && isReadyLocked(session, session.priorityRequest.focus)) {
                cancelPriority = session.priorityRequest;
                session.priorityRequest = null;
            } else if (session.priorityRequest != null &&
                    !session.priorityRequest.nearPlayback(session.currentTimeMs, session.atoms, GAP_RESCUE_AHEAD_MS)) {
                cancelPriority = session.priorityRequest;
                session.priorityRequest = null;
            }

            rescueTarget = findUrgentGapLocked(session, anchor, now, demand);
            if (rescueTarget >= 0 && demand.held &&
                    session.heldPriorityGeneration == demand.generation &&
                    session.heldPriorityFocus == rescueTarget) {
                rescueTarget = -1;
            }
            if ((session.visible || !session.firstReady) && rescueTarget >= 0 &&
                    session.priorityRequest == null) {
                Request candidate = buildPriorityLocked(
                        session, rescueTarget, demand.generation, rescueTarget != anchor
                );
                if (!samePriorityFingerprintLocked(session, candidate, now)) {
                    startPriority = candidate;
                    session.priorityRequest = startPriority;
                    if (demand.held) {
                        session.heldPriorityGeneration = demand.generation;
                        session.heldPriorityFocus = rescueTarget;
                    }
                    rememberPriorityFingerprintLocked(session, candidate, now);
                }
            }

            if (session.backgroundRequest != null &&
                    !session.backgroundRequest.nearPlayback(
                            session.currentTimeMs, session.atoms, BACKGROUND_CANCEL_AHEAD_MS
                    )) {
                cancelBackground = session.backgroundRequest;
                session.backgroundRequest = null;
            }

            long bufferAhead = bufferAheadMsLocked(session);
            if (!session.firstReady) {
                session.backgroundRefillActive = false;
            } else {
                if (bufferAhead <= BACKGROUND_LOW_WATER_MS) {
                    session.backgroundRefillActive = true;
                } else if (bufferAhead >= PREFETCH_AHEAD_MS) {
                    session.backgroundRefillActive = false;
                }
            }

            if (session.backgroundRequest == null && session.backgroundRefillActive &&
                    session.priorityRequest == null) {
                // dev17: temporarily disable alt-framing second looks. dev16 measured data showed that
                // the alt-framing repair added 4 API calls costing ¥0.0220 (20,940 tokens) to recover
                // only 192 atoms — approximately 109 tokens per atom, which is worse than the regular
                // page/block cost efficiency (88.5 tokens per atom). The second look was designed to
                // rescue difficult boundaries that page merge deterministically declined at temperature 0,
                // but in practice most of those holes are filled naturally by the overlapping stride or
                // by priority rescue when playback approaches. Until the per-atom cost of alt-framing
                // drops meaningfully below the base block cost, the feature stays off.
                int target = findBackgroundTargetLocked(session, anchor, now);
                if (target >= 0) {
                    startBackground = buildBackgroundLocked(session, target);
                    session.backgroundRequest = startBackground;
                }
            }
        }

        if (cancelPriority != null) cancelPriority.cancel();
        if (cancelBackground != null) cancelBackground.cancel();
        if (startPriority != null) {
            if (rescueTarget != anchor) {
                long lead = 0L;
                synchronized (session.lock) {
                    if (rescueTarget >= 0 && rescueTarget < session.atoms.size()) {
                        lead = Math.max(0L, session.atoms.get(rescueTarget).startMs - session.currentTimeMs);
                    }
                }
                CaptionDiagnostics.mark(
                        session.context,
                        "GAP_RESCUE_REQUEST",
                        "检测到播放头前方字幕缺口，提前 " + lead + " ms 启动完整语义修复"
                );
            }
            submit(session, startPriority, PRIORITY);
        }
        if (startBackground != null) {
            if (startBackground.pageMerge) {
                CaptionDiagnostics.mark(
                        session.context,
                        "BACKGROUND_PAGE_REQUEST",
                        "缓存页 #" + startBackground.pageIndex + " 合并块 #" + startBackground.blockFrom +
                                "-#" + startBackground.blockTo + "：核心 " +
                                (startBackground.coreTo - startBackground.coreFrom) + " 个原子，上下文共 " +
                                (startBackground.windowTo - startBackground.windowFrom) + " 个原子"
                );
            } else if (startBackground.altFraming) {
                CaptionDiagnostics.mark(
                        session.context,
                        "BACKGROUND_ALT_FRAMING_REQUEST",
                        "缓存页 #" + startBackground.pageIndex + " 残留边界改用不同框法二次观察：核心 " +
                                (startBackground.coreTo - startBackground.coreFrom) + " 个原子，上下文共 " +
                                (startBackground.windowTo - startBackground.windowFrom) + " 个原子"
                );
            }
            submit(session, startBackground, BACKGROUND);
        }
    }

    private static int findUrgentGapLocked(
            Session session,
            int anchor,
            long now,
            BackgroundPauseGovernor.Snapshot demand
    ) {
        long horizon = session.currentTimeMs + GAP_RESCUE_AHEAD_MS;
        for (int i = Math.max(0, anchor); i < session.states.length; i++) {
            SourceAtomTimeline.Atom atom = session.atoms.get(i);
            if (atom.startMs > horizon) break;
            if (session.states[i] == READY) continue;
            if (session.priorityRetryAfterMs[i] > now) return -1;

            // Keep the first-caption path and one bounded repair at the current play head alive.
            // Once playback demand is held, a future gap cannot create a new paid request until
            // player time advances and the governor generation changes.
            boolean futureGap = atom.startMs > session.currentTimeMs + PRIORITY_CURRENT_REPAIR_GRACE_MS;
            if (session.firstReady && futureGap && demand.held) return -1;
            return i;
        }
        return -1;
    }

    private static Request buildPriorityLocked(
            Session session, int focus, long demandGeneration, boolean gapRescue
    ) {
        int attempt = Math.max(0, session.priorityAttempts[focus]);
        int before = Math.min(PRIORITY_MAX_BEFORE, PRIORITY_BASE_BEFORE + attempt * 12);
        int after = Math.min(PRIORITY_MAX_AFTER, PRIORITY_BASE_AFTER + attempt * 26);
        int windowFrom = Math.max(0, focus - before);
        int windowTo = Math.min(session.atoms.size(), focus + after);
        int coreFrom = Math.max(windowFrom, focus - 6);
        int coreTo = Math.min(windowTo, focus + PRIORITY_CORE_AFTER + Math.min(28, attempt * 6));
        if (coreTo <= coreFrom) coreTo = Math.min(windowTo, coreFrom + 1);
        return Request.priority(
                windowFrom, windowTo, coreFrom, coreTo, focus,
                ++session.requestSequence, demandGeneration, gapRescue
        );
    }

    private static boolean samePriorityFingerprintLocked(
            Session session,
            Request candidate,
            long now
    ) {
        return session.lastPriorityFingerprint.equals(candidate.fingerprint()) &&
                now - session.lastPriorityFingerprintAtMs < PRIORITY_DEDUP_COOLDOWN_MS;
    }

    private static void rememberPriorityFingerprintLocked(
            Session session,
            Request request,
            long now
    ) {
        session.lastPriorityFingerprint = request.fingerprint();
        session.lastPriorityFingerprintAtMs = now;
    }

    private static Request buildBackgroundLocked(Session session, int first) {
        int block = backgroundBlockIndexForAtomLocked(session, first);
        int page = backgroundCachePageForBlock(block);
        int firstPageBlock = page * BACKGROUND_CACHE_PAGE_BLOCKS;
        int lastPageBlock = Math.min(
                Math.max(0, session.backgroundBlockDone.length - 1),
                firstPageBlock + BACKGROUND_CACHE_PAGE_BLOCKS - 1
        );

        // dev18: page merge is provider-independent. dev17 gated it to api.deepseek.com on the
        // hypothesis that page merge collapses cache hits on DashScope, but dev17's own report
        // measured 0.0% cache hit with page merge disabled — the cache never existed on DashScope,
        // and gating it off only regressed per-atom cost from 88.5 (page, dev16) to 138.8 (block,
        // dev17) tokens. Re-enable unconditionally; the fixed-block fallback still exists so a
        // failing page can never stall inventory.
        boolean fallback = page < session.backgroundPageFallback.length &&
                session.backgroundPageFallback[page];
        if (!fallback) {
            // Merge every not-yet-done block of this cache page into a single request instead of
            // sending each block separately: the large atoms/context source page is transmitted once
            // per page rather than once per block, removing the repeated full-price re-send that a
            // provider-side prefix cache was expected (but failed in practice) to absorb.
            //
            // The merge stops at the prefetch horizon. dev15 merged the whole page regardless, which
            // bought up to 45 s beyond the 60 s the scheduler is allowed to look ahead; blocks past
            // the horizon simply stay unresolved until playback brings them into range.
            long horizon = session.currentTimeMs + PREFETCH_AHEAD_MS;
            int mergedFrom = -1;
            int mergedTo = -1;
            int mergedFirstBlock = -1;
            int mergedLastBlock = -1;
            for (int b = firstPageBlock; b <= lastPageBlock; b++) {
                if (b >= session.backgroundBlockDone.length || session.backgroundBlockDone[b]) continue;
                int bFrom = backgroundBlockCoreFromLocked(session, b);
                int bTo = backgroundBlockCoreToLocked(session, b);
                if (bFrom < 0 || bFrom >= bTo) continue;
                // Always admit the block the scheduler already cleared, then stop at the horizon.
                if (mergedFrom >= 0 && session.atoms.get(bFrom).startMs > horizon) break;
                mergedFrom = mergedFrom < 0 ? bFrom : Math.min(mergedFrom, bFrom);
                mergedTo = Math.max(mergedTo, bTo);
                if (mergedFirstBlock < 0) mergedFirstBlock = b;
                mergedLastBlock = b;
            }
            if (mergedFrom >= 0 && mergedTo > mergedFrom) {
                // Context follows the merged core, not the whole page. Sizing the window to the full
                // page would keep paying full page input even when only one block is in range.
                int pageWindowFrom = Math.max(0, mergedFrom - BACKGROUND_BLOCK_CONTEXT_BEFORE);
                int pageWindowTo = Math.min(
                        session.atoms.size(), mergedTo + BACKGROUND_BLOCK_CONTEXT_AFTER
                );
                return Request.backgroundPage(
                        pageWindowFrom, pageWindowTo, mergedFrom, mergedTo,
                        ++session.requestSequence, page, mergedFirstBlock, mergedLastBlock
                );
            }
        }

        int coreFrom = backgroundBlockCoreFromLocked(session, block);
        int coreTo = backgroundBlockCoreToLocked(session, block);
        int windowFrom = Math.max(0, coreFrom - BACKGROUND_BLOCK_CONTEXT_BEFORE);
        int windowTo = Math.min(session.atoms.size(), coreTo + BACKGROUND_BLOCK_CONTEXT_AFTER);
        return Request.backgroundBlock(
                windowFrom, windowTo, coreFrom, coreTo,
                ++session.requestSequence, page, block
        );
    }

    /**
     * A3: one differently-framed repair per cache page. The core is centered on the atoms that the
     * page request left unresolved and the context window is rebuilt around that core, so the model
     * sees a genuinely different framing rather than the byte-identical page request it already
     * answered deterministically.
     */
    private static Request buildAltFramingLocked(Session session, int page) {
        int holeFrom = session.backgroundPageAltFrom[page];
        int holeTo = session.backgroundPageAltTo[page];
        session.backgroundPageAltUsed[page] = true;
        session.backgroundPageAltFrom[page] = -1;
        session.backgroundPageAltTo[page] = -1;

        int coreFrom = Math.max(0, holeFrom - BACKGROUND_ALT_CORE_MARGIN_ATOMS);
        int coreTo = Math.min(
                session.atoms.size(), holeTo + 1 + BACKGROUND_ALT_CORE_MARGIN_ATOMS
        );
        if (coreTo <= coreFrom) coreTo = Math.min(session.atoms.size(), coreFrom + 1);
        int windowFrom = Math.max(0, coreFrom - BACKGROUND_BLOCK_CONTEXT_BEFORE);
        int windowTo = Math.min(session.atoms.size(), coreTo + BACKGROUND_BLOCK_CONTEXT_AFTER);
        return Request.backgroundAlt(
                windowFrom, windowTo, coreFrom, coreTo, ++session.requestSequence, page
        );
    }

    /** Records the unresolved span a merged page left behind, so it can get one re-framed look. */
    private static boolean armAltFramingLocked(Session session, Request request) {
        int page = request.pageIndex;
        if (page < 0 || page >= session.backgroundPageAltUsed.length) return false;
        if (session.backgroundPageAltUsed[page]) return false;
        if (session.backgroundPageAltFrom[page] >= 0) return false;

        int from = -1;
        int to = -1;
        int end = Math.min(session.states.length, request.coreTo);
        for (int i = Math.max(0, request.coreFrom); i < end; i++) {
            if (session.states[i] == READY) continue;
            if (from < 0) from = i;
            to = i;
        }
        if (from < 0 || to < from) return false;
        if (to - from + 1 > BACKGROUND_ALT_MAX_CORE_ATOMS) {
            to = from + BACKGROUND_ALT_MAX_CORE_ATOMS - 1;
        }
        session.backgroundPageAltFrom[page] = from;
        session.backgroundPageAltTo[page] = to;
        return true;
    }

    private static int findPendingAltPageLocked(Session session) {
        long horizon = session.currentTimeMs + PREFETCH_AHEAD_MS;
        for (int page = 0; page < session.backgroundPageAltFrom.length; page++) {
            int from = session.backgroundPageAltFrom[page];
            if (from < 0 || from >= session.states.length) continue;
            int to = Math.min(session.states.length - 1, session.backgroundPageAltTo[page]);
            if (to < from || blockAllReadyLocked(session, from, to + 1) ||
                    session.atoms.get(to).endMs < session.currentTimeMs) {
                session.backgroundPageAltFrom[page] = -1;
                session.backgroundPageAltTo[page] = -1;
                continue;
            }
            if (session.atoms.get(from).startMs > horizon) continue;
            return page;
        }
        return -1;
    }

    private static int backgroundCachePageForBlock(int block) {
        return Math.max(0, block) / BACKGROUND_CACHE_PAGE_BLOCKS;
    }

    private static int backgroundPageCountForBlocks(int blockCount) {
        if (blockCount <= 0) return 0;
        return (blockCount + BACKGROUND_CACHE_PAGE_BLOCKS - 1) / BACKGROUND_CACHE_PAGE_BLOCKS;
    }

    private static int findBackgroundTargetLocked(Session session, int anchor, long now) {
        if (session.backgroundBlockDone.length == 0 || session.atoms.isEmpty()) return -1;
        long horizon = session.currentTimeMs + PREFETCH_AHEAD_MS;
        int startBlock = backgroundBlockIndexForAtomLocked(session, Math.max(0, anchor));
        int lastBlock = backgroundBlockIndexForTimeLocked(session, horizon);
        for (int block = startBlock; block <= lastBlock && block < session.backgroundBlockDone.length; block++) {
            if (session.backgroundBlockDone[block]) continue;
            if (session.backgroundBlockRetryAfterMs[block] > now) continue;
            int page = backgroundCachePageForBlock(block);
            boolean pageFallback = page < session.backgroundPageFallback.length &&
                    session.backgroundPageFallback[page];
            if (!pageFallback && page < session.backgroundPageRetryAfterMs.length &&
                    session.backgroundPageRetryAfterMs[page] > now) continue;
            int coreFrom = backgroundBlockCoreFromLocked(session, block);
            int coreTo = backgroundBlockCoreToLocked(session, block);
            if (coreFrom < 0 || coreFrom >= coreTo || coreFrom >= session.states.length) {
                session.backgroundBlockDone[block] = true;
                continue;
            }
            if (blockAllReadyLocked(session, coreFrom, coreTo)) {
                session.backgroundBlockDone[block] = true;
                continue;
            }
            if (session.atoms.get(coreFrom).startMs > horizon) break;
            return coreFrom;
        }
        return -1;
    }

    private static boolean blockAllReadyLocked(Session session, int from, int toExclusive) {
        int end = Math.min(session.states.length, Math.max(from, toExclusive));
        for (int i = Math.max(0, from); i < end; i++) {
            if (session.states[i] != READY) return false;
        }
        return end > from;
    }

    private static int backgroundBlockCountLocked(Session session) {
        if (session.atoms.isEmpty()) return 0;
        long base = session.atoms.get(0).startMs;
        long lastStart = session.atoms.get(session.atoms.size() - 1).startMs;
        long span = Math.max(0L, lastStart - base);
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, span / BACKGROUND_BLOCK_STRIDE_MS + 1L));
    }

    private static int backgroundBlockIndexForAtomLocked(Session session, int atomIndex) {
        if (session.atoms.isEmpty()) return 0;
        int clean = Math.max(0, Math.min(session.atoms.size() - 1, atomIndex));
        return backgroundBlockIndexForTimeLocked(session, session.atoms.get(clean).startMs);
    }

    private static int backgroundBlockIndexForTimeLocked(Session session, long timeMs) {
        if (session.atoms.isEmpty()) return 0;
        long base = session.atoms.get(0).startMs;
        long relative = Math.max(0L, timeMs - base);
        int block = (int) Math.min(Integer.MAX_VALUE, relative / BACKGROUND_BLOCK_STRIDE_MS);
        int count = session.backgroundBlockDone.length > 0
                ? session.backgroundBlockDone.length
                : backgroundBlockCountLocked(session);
        return Math.max(0, Math.min(Math.max(0, count - 1), block));
    }

    private static int backgroundBlockCoreFromLocked(Session session, int block) {
        if (session.atoms.isEmpty()) return -1;
        long base = session.atoms.get(0).startMs;
        long start = base + Math.max(0L, (long) block) * BACKGROUND_BLOCK_STRIDE_MS;
        int index = firstAtomAtOrAfterLocked(session.atoms, start);
        return Math.min(session.atoms.size() - 1, Math.max(0, index));
    }

    private static int backgroundBlockCoreToLocked(Session session, int block) {
        int from = backgroundBlockCoreFromLocked(session, block);
        if (from < 0) return -1;
        long base = session.atoms.get(0).startMs;
        long end = base + Math.max(0L, (long) block) * BACKGROUND_BLOCK_STRIDE_MS + BACKGROUND_BLOCK_CORE_MS;
        int to = firstAtomAtOrAfterLocked(session.atoms, end);
        return Math.max(from + 1, Math.min(session.atoms.size(), to));
    }

    private static int firstAtomAtOrAfterLocked(List<SourceAtomTimeline.Atom> atoms, long timeMs) {
        int low = 0;
        int high = atoms.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (atoms.get(middle).startMs < timeMs) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static void submit(Session session, Request request, ExecutorService executor) {
        request.future = executor.submit(() -> planWindow(session, request));
    }

    private static void planWindow(Session session, Request request) {
        long started = SystemClock.elapsedRealtime();
        if (request.priority) {
            CaptionDiagnostics.mark(
                    session.context,
                    "FIRST_CUE_REQUEST",
                    "优先语义窗口：核心 " + (request.coreTo - request.coreFrom) +
                            " 个原子，上下文共 " + (request.windowTo - request.windowFrom) + " 个原子"
            );
        }
        try {
            List<SourceAtomTimeline.Atom> window;
            List<String> before;
            List<String> after;
            synchronized (session.lock) {
                window = new ArrayList<>(session.atoms.subList(request.windowFrom, request.windowTo));
                before = context(
                        session.sourceTexts,
                        Math.max(0, request.windowFrom - CONTEXT_OUTSIDE_WINDOW_ATOMS),
                        request.windowFrom
                );
                after = context(
                        session.sourceTexts,
                        request.windowTo,
                        Math.min(session.sourceTexts.size(), request.windowTo + CONTEXT_OUTSIDE_WINDOW_ATOMS)
                );
            }

            SemanticUnitApiClient.Result result = SemanticUnitApiClient.plan(
                    window,
                    request.coreFrom - request.windowFrom,
                    request.coreTo - request.windowFrom,
                    request.focus - request.windowFrom,
                    before,
                    after,
                    session.config,
                    session.targetLanguage,
                    new DeepSeekApiClient.RequestControl() {
                        @Override public boolean isCancelled() {
                            return request.cancelled || session.cancelled || !isCurrent(session);
                        }

                        @Override public void onConnection(HttpURLConnection connection) {
                            request.connection = connection;
                            if (connection != null &&
                                    (request.cancelled || session.cancelled || !isCurrent(session))) {
                                connection.disconnect();
                            }
                        }

                        @Override public void onRequestBodySent() {
                            request.bodySent = true;
                        }
                    },
                    request.priority,
                    request.priority
                            ? (request.gapRescue
                                    ? SemanticUnitApiClient.LANE_PRIORITY_GAP_RESCUE
                                    : SemanticUnitApiClient.LANE_PRIORITY_CURRENT)
                            : request.pageMerge
                                    ? SemanticUnitApiClient.LANE_BACKGROUND_PAGE
                                    : request.altFraming
                                            ? SemanticUnitApiClient.LANE_BACKGROUND_ALT
                                            : SemanticUnitApiClient.LANE_BACKGROUND_BLOCK
            );
            finishPlan(session, request, result, started);
        } catch (Throwable failure) {
            failPlan(session, request, failure, started);
        }
    }

    private static List<String> context(List<String> source, int from, int to) {
        if (from >= to) return Collections.emptyList();
        return new ArrayList<>(source.subList(from, to));
    }

    private static void finishPlan(
            Session session,
            Request request,
            SemanticUnitApiClient.Result result,
            long started
    ) {
        int committed = 0;
        int replacements = 0;
        int conflicts = 0;
        boolean firstReadyNow = false;
        int focusMisses = 0;
        long focusBackoffMs = 0L;
        long now = SystemClock.elapsedRealtime();
        boolean priorityFocusReturned = !request.priority || resultCoversLocalFocus(
                result, request.focus - request.windowFrom
        );
        List<RefineCandidate> refinements = new ArrayList<>();

        synchronized (session.lock) {
            detachRequestLocked(session, request);
            if (request.cancelled || session.cancelled || !isCurrent(session)) return;

            for (SemanticUnitApiClient.Unit unit : result.units) {
                int globalFrom = request.windowFrom + unit.from;
                int globalTo = request.windowFrom + unit.to;
                if ((!request.priority || !priorityFocusReturned) &&
                        rangeAllReadyLocked(session, globalFrom, globalTo)) continue;
                MergeResult merged = mergeCanonicalUnitLocked(session, globalFrom, globalTo, unit.text);
                committed += merged.committedAtoms;
                replacements += merged.replacedUnits;
                conflicts += merged.conflicts;
                if (merged.changed && shouldRefineLocked(session, globalFrom, globalTo, unit.text)) {
                    refinements.add(new RefineCandidate(globalFrom, globalTo, unit.text.trim()));
                }
            }

            int anchor = anchor(session.atoms, session.currentTimeMs);
            if (request.priority) {
                for (int i = request.coreFrom; i < request.coreTo && i < session.states.length; i++) {
                    if (session.states[i] == READY) {
                        resetRetriesLocked(session, i);
                        continue;
                    }
                    int attempt = ++session.priorityAttempts[i];
                    long delay = Math.min(2_200L, 250L + attempt * 380L);
                    session.priorityRetryAfterMs[i] = now + delay;
                }

                if (request.focus >= 0 && request.focus < session.states.length) {
                    if (session.states[request.focus] == READY) {
                        session.priorityFocusMisses[request.focus] = 0;
                    } else {
                        focusMisses = ++session.priorityFocusMisses[request.focus];
                        long lead = Math.max(
                                0L, session.atoms.get(request.focus).startMs - session.currentTimeMs
                        );
                        if (focusMisses >= PRIORITY_FOCUS_BACKOFF_AFTER_MISSES &&
                                lead > PRIORITY_FOCUS_URGENT_LEAD_MS) {
                            float rate = Math.max(0.5f, Math.min(3f, latestPlaybackRate));
                            long untilUrgent = Math.round(
                                    (lead - PRIORITY_FOCUS_URGENT_LEAD_MS) / rate
                            );
                            focusBackoffMs = Math.min(
                                    PRIORITY_FOCUS_MAX_BACKOFF_MS,
                                    Math.max(1_200L, untilUrgent)
                            );
                            session.priorityRetryAfterMs[request.focus] = Math.max(
                                    session.priorityRetryAfterMs[request.focus],
                                    now + focusBackoffMs
                            );
                        }
                    }
                }
            } else if (request.pageMerge) {
                // Forward progress: a successful page response always advances the block frontier.
                // dev15 only completed blocks that happened to be fully READY here, so with a
                // temperature-0 page request that returned the same units repeatedly, the page was
                // re-sent identically forever and produced a pool of (identical) 0-atom results.
                // Any atoms the model did not cover this round are repaired at most once later with
                // a different framing (see altFraming below); never re-ask the same page.
                int pageBlocksCompleted = 0;
                for (int b = request.blockFrom; b <= request.blockTo &&
                        b < session.backgroundBlockDone.length; b++) {
                    if (session.backgroundBlockDone[b]) continue;
                    session.backgroundBlockDone[b] = true;
                    session.backgroundBlockAttempts[b] = 0;
                    session.backgroundBlockRetryAfterMs[b] = 0L;
                    pageBlocksCompleted++;
                }
                TokenCostAudit.recordBlocksCompleted("background_page", pageBlocksCompleted);
                if (request.pageIndex >= 0 && request.pageIndex < session.backgroundPageAttempts.length) {
                    session.backgroundPageAttempts[request.pageIndex] = 0;
                    session.backgroundPageRetryAfterMs[request.pageIndex] = 0L;
                }
            } else if (request.altFraming) {
                // The single re-framed repair ran; its hole budget is consumed by
                // buildAltFramingLocked. Record what it recovered.
                TokenCostAudit.recordCommittedAtoms("background_alt", committed);
            } else {
                int block = backgroundBlockIndexForAtomLocked(session, request.coreFrom);
                if (block >= 0 && block < session.backgroundBlockDone.length) {
                    session.backgroundBlockDone[block] = true;
                    session.backgroundBlockAttempts[block] = 0;
                    session.backgroundBlockRetryAfterMs[block] = 0L;
                }
            }

            // A merged page response may have left atoms uncovered. Give those a single second look
            // with a genuinely different framing (armAltFramingLocked), so the dev9 two-look
            // guarantee survives page merging without re-sending the identical page ever again.
            if (!request.priority && request.pageMerge && armAltFramingLocked(session, request)) {
                TokenCostAudit.recordAltFramingArmed();
            }

            session.completedRequests++;
            if (isReadyLocked(session, anchor)) {
                if (!session.firstReady) {
                    session.firstReady = true;
                    firstReadyNow = true;
                }
            }
        }

        if (request.priority && focusBackoffMs > 0L) {
            CaptionDiagnostics.mark(
                    session.context,
                    "PRIORITY_FOCUS_BACKOFF",
                    "同一播放缺口连续 " + focusMisses +
                            " 次未覆盖焦点；距离播放头仍有余量，延后约 " + focusBackoffMs +
                            " ms 再尝试，逼近时仍恢复紧急修复"
            );
        }

        request.connection = null;
        session.error = "";
        long took = Math.max(0L, SystemClock.elapsedRealtime() - started);
        long buffer = bufferAheadMs(session);

        if (firstReadyNow) {
            CaptionDiagnostics.mark(
                    session.context,
                    session.visible ? "FIRST_AI_READY" : "PREWARM_FIRST_READY",
                    (session.visible ? "首个 AI 完整语义单元已显示" : "首个 AI 完整语义单元已静默预热") +
                            "，用时 " + took + " ms；前方库存 " + buffer + " ms"
            );
        } else if (request.priority) {
            CaptionDiagnostics.mark(
                    session.context,
                    "CURRENT_UNIT_READY",
                    "当前中心窗口完成：接受 " + result.units.size() + " 个完整单元，新增 " + committed +
                            " 个原子，用时 " + took + " ms；前方库存 " + buffer + " ms"
            );
        } else if (request.pageMerge) {
            CaptionDiagnostics.mark(
                    session.context,
                    committed == 0 ? "BACKGROUND_PAGE_NO_NEW_ATOMS" : "BACKGROUND_PAGE_READY",
                    "缓存页 #" + request.pageIndex + "（合并块 #" + request.blockFrom + "-#" + request.blockTo +
                            "）：接受 " + result.units.size() + " 个完整单元，新增 " + committed +
                            " 个原子，用时 " + took + " ms；前方库存 " + buffer + " ms"
            );
        } else if (session.completedRequests <= 4 || session.completedRequests % 4 == 0 ||
                committed == 0 || took > 2_400L) {
            int block;
            synchronized (session.lock) {
                block = backgroundBlockIndexForAtomLocked(session, request.coreFrom);
            }
            CaptionDiagnostics.mark(
                    session.context,
                    committed == 0 ? "FIXED_BLOCK_NO_NEW_ATOMS" : "FIXED_BLOCK_READY",
                    "固定背景块 #" + block + "（缓存页 #" + backgroundCachePageForBlock(block) +
                            "，fallback）：接受 " + result.units.size() + " 个完整单元，新增 " + committed +
                            " 个原子，用时 " + took + " ms；前方库存 " + buffer + " ms"
            );
        }
        if (!request.priority) {
            TokenCostAudit.recordCommittedAtoms(
                    request.pageMerge ? "background_page" : "background_block", committed
            );
        }

        if (replacements > 0 || conflicts > 0) {
            CaptionDiagnostics.mark(
                    session.context,
                    "LEDGER_SHADOW_MERGE",
                    "非破坏式合并：未来显示单元替换 " + replacements + " 个；当前可见冲突保留 " + conflicts + " 个"
            );
        }

        render(session, session.currentTimeMs);
        if (committed > 0 || replacements > 0) persistCacheAsync(session);
        for (RefineCandidate candidate : refinements) scheduleRefinement(session, candidate);
        markCompleteIfNeeded(session);
        schedule(session);
    }

    private static boolean resultCoversLocalFocus(
            SemanticUnitApiClient.Result result,
            int localFocus
    ) {
        if (result == null || result.units == null || localFocus < 0) return false;
        for (SemanticUnitApiClient.Unit unit : result.units) {
            if (unit != null && unit.from <= localFocus && localFocus <= unit.to) return true;
        }
        return false;
    }

    private static MergeResult mergeCanonicalUnitLocked(Session session, int from, int to, String text) {
        if (from < 0 || to < from || to >= session.states.length || text == null || text.trim().isEmpty()) {
            return MergeResult.NONE;
        }
        String canonical = text.trim();
        if (rangeMatchesCanonicalLocked(session, from, to, canonical)) return MergeResult.NONE;

        List<int[]> overlaps = collectDisplayRangesLocked(session, from, to);
        for (int[] range : overlaps) {
            if (rangesOverlap(range[0], range[1], session.visibleDisplayFrom, session.visibleDisplayTo)) {
                return new MergeResult(0, 0, 1, false);
            }
        }

        int replaced = 0;
        for (int[] range : overlaps) {
            clearDisplayRangeLocked(session, range[0], range[1]);
            replaced++;
        }

        int committed = 0;
        for (int i = from; i <= to; i++) {
            if (session.states[i] != READY) committed++;
            session.states[i] = READY;
            session.displayTexts[i] = canonical;
            session.displayFrom[i] = from;
            session.displayTo[i] = to;
            session.refineQueued[i] = false;
            resetRetriesLocked(session, i);
        }
        return new MergeResult(committed, replaced, 0, true);
    }

    private static boolean rangeAllReadyLocked(Session session, int from, int to) {
        if (from < 0 || to < from || to >= session.states.length) return false;
        for (int i = from; i <= to; i++) {
            if (session.states[i] != READY) return false;
        }
        return true;
    }

    private static boolean rangeMatchesCanonicalLocked(Session session, int from, int to, String canonical) {
        if (from < 0 || to < from || to >= session.states.length) return false;
        StringBuilder joined = new StringBuilder(canonical.length() + 16);
        int i = from;
        while (i <= to) {
            if (session.states[i] != READY) return false;
            int df = session.displayFrom[i];
            int dt = session.displayTo[i];
            if (df != i || dt < df || dt > to) return false;
            String part = session.displayTexts[i];
            if (part == null) return false;
            joined.append(part);
            i = dt + 1;
        }
        return i == to + 1 && canonical.equals(joined.toString());
    }

    private static List<int[]> collectDisplayRangesLocked(Session session, int from, int to) {
        List<int[]> ranges = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            if (session.states[i] != READY) continue;
            int df = session.displayFrom[i];
            int dt = session.displayTo[i];
            if (df >= 0 && dt >= df && !containsRange(ranges, df, dt)) {
                ranges.add(new int[]{df, dt});
            }
        }
        return ranges;
    }

    private static boolean shouldRefineLocked(Session session, int from, int to, String text) {
        if (from < 0 || to < from || to >= session.atoms.size()) return false;
        if (text == null || text.trim().length() < 8) return false;
        long duration = session.atoms.get(to).endMs - session.atoms.get(from).startMs;
        if (duration <= LONG_DISPLAY_THRESHOLD_MS) return false;
        for (int i = from; i <= to; i++) if (session.refineQueued[i]) return false;
        return true;
    }

    private static void scheduleCachedRefinements(Session session) {
        List<RefineCandidate> candidates = new ArrayList<>();
        synchronized (session.lock) {
            int i = 0;
            while (i < session.states.length) {
                if (session.states[i] != READY || session.displayFrom[i] != i) {
                    i++;
                    continue;
                }
                int to = session.displayTo[i];
                String text = session.displayTexts[i];
                if (to < i || to >= session.states.length || text == null) {
                    i++;
                    continue;
                }
                if (shouldRefineLocked(session, i, to, text)) {
                    candidates.add(new RefineCandidate(i, to, text));
                }
                i = to + 1;
            }
        }
        for (RefineCandidate candidate : candidates) scheduleRefinement(session, candidate);
    }

    private static void scheduleRefinement(Session session, RefineCandidate candidate) {
        synchronized (session.lock) {
            if (!isCurrent(session) || session.cancelled || !session.timelineReady) return;
            if (!rangeIsWholeCanonicalLocked(session, candidate)) return;
            for (int i = candidate.from; i <= candidate.to; i++) {
                if (session.refineQueued[i]) return;
            }
            for (int i = candidate.from; i <= candidate.to; i++) session.refineQueued[i] = true;
        }

        REFINER.execute(() -> {
            List<SourceAtomTimeline.Atom> atoms = null;
            try {
                synchronized (session.lock) {
                    if (!rangeIsWholeCanonicalLocked(session, candidate)) {
                        clearRefineQueuedLocked(session, candidate.from, candidate.to);
                        return;
                    }
                    atoms = new ArrayList<>(session.atoms.subList(candidate.from, candidate.to + 1));
                }

                // Local-first: the dev7 deterministic slicer is zero-token and already good enough for
                // most long units. AI display slicing is now strictly a fallback for the minority of
                // units the local planner cannot safely split, instead of a default that is discarded
                // whenever the local result is also usable.
                SemanticDisplaySliceApiClient.Result local =
                        LocalDisplaySliceFallback.slice(atoms, candidate.text);
                if (local.slices.size() > 1) {
                    TokenCostAudit.recordDisplayLocalOutcome(true);
                    applyRefinement(session, candidate, local, 0);
                    return;
                }
                TokenCostAudit.recordDisplayLocalOutcome(false);

                SemanticDisplaySliceApiClient.Result result = SemanticDisplaySliceApiClient.slice(
                        atoms,
                        candidate.text,
                        session.config,
                        session.targetLanguage,
                        new DeepSeekApiClient.RequestControl() {
                            @Override public boolean isCancelled() {
                                return session.cancelled || !isCurrent(session);
                            }

                            @Override public void onConnection(HttpURLConnection connection) {
                                if (connection != null && (session.cancelled || !isCurrent(session))) {
                                    connection.disconnect();
                                }
                            }
                        }
                );
                if (result.slices.size() <= 1) {
                    synchronized (session.lock) {
                        clearRefineQueuedLocked(session, candidate.from, candidate.to);
                    }
                    return;
                }
                CaptionDiagnostics.mark(
                        session.context,
                        "DISPLAY_SLICE_AI_FALLBACK_READY",
                        "本地零 Token 节奏切分未能安全拆分，AI 显示切片补充产出 " + result.slices.size() + " 段"
                );
                applyRefinement(session, candidate, result, 0);
            } catch (Throwable failure) {
                synchronized (session.lock) {
                    clearRefineQueuedLocked(session, candidate.from, candidate.to);
                }
                if (!session.cancelled && isCurrent(session)) {
                    CaptionDiagnostics.mark(
                            session.context,
                            "DISPLAY_SLICE_SKIPPED",
                            "本地切分未达标、AI 显示切片也失败，长语义单元保持完整译文显示：" +
                                    CaptionDiagnostics.errorDetail(failure)
                    );
                }
            }
        });
    }

    private static void applyRefinement(
            Session session,
            RefineCandidate candidate,
            SemanticDisplaySliceApiClient.Result result,
            int deferrals
    ) {
        if (session.cancelled || !isCurrent(session)) return;
        boolean defer = false;
        int maxDuration = 0;
        synchronized (session.lock) {
            if (!rangeIsWholeCanonicalLocked(session, candidate)) {
                clearRefineQueuedLocked(session, candidate.from, candidate.to);
                return;
            }
            if (rangesOverlap(candidate.from, candidate.to,
                    session.visibleDisplayFrom, session.visibleDisplayTo)) {
                defer = true;
            } else {
                clearDisplayRangeLocked(session, candidate.from, candidate.to);
                int expected = candidate.from;
                for (SemanticDisplaySliceApiClient.Slice local : result.slices) {
                    int from = candidate.from + local.from;
                    int to = candidate.from + local.to;
                    if (from != expected || to < from || to > candidate.to) {
                        clearRefineQueuedLocked(session, candidate.from, candidate.to);
                        return;
                    }
                    for (int i = from; i <= to; i++) {
                        session.states[i] = READY;
                        session.displayTexts[i] = local.text;
                        session.displayFrom[i] = from;
                        session.displayTo[i] = to;
                        resetRetriesLocked(session, i);
                    }
                    long duration = session.atoms.get(to).endMs - session.atoms.get(from).startMs;
                    maxDuration = (int) Math.max(maxDuration, Math.min(Integer.MAX_VALUE, duration));
                    expected = to + 1;
                }
                clearRefineQueuedLocked(session, candidate.from, candidate.to);
            }
        }

        if (defer) {
            if (deferrals < 40) {
                DISPLAY.postDelayed(() -> applyRefinement(session, candidate, result, deferrals + 1), 350L);
            } else {
                synchronized (session.lock) {
                    clearRefineQueuedLocked(session, candidate.from, candidate.to);
                }
            }
            return;
        }

        CaptionDiagnostics.mark(
                session.context,
                "DISPLAY_SLICE_READY",
                "完整译文保持逐字不变，拆为 " + result.slices.size() +
                        " 个显示片段；最长片段约 " + maxDuration + " ms"
        );
        persistCacheAsync(session);
        render(session, session.currentTimeMs);
    }

    private static boolean rangeIsWholeCanonicalLocked(Session session, RefineCandidate candidate) {
        if (candidate.from < 0 || candidate.to < candidate.from || candidate.to >= session.states.length) {
            return false;
        }
        for (int i = candidate.from; i <= candidate.to; i++) {
            if (session.states[i] != READY ||
                    session.displayFrom[i] != candidate.from ||
                    session.displayTo[i] != candidate.to ||
                    session.displayTexts[i] == null ||
                    !candidate.text.equals(session.displayTexts[i])) {
                return false;
            }
        }
        return true;
    }

    private static void clearRefineQueuedLocked(Session session, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(session.refineQueued.length - 1, to);
        for (int i = start; i <= end; i++) session.refineQueued[i] = false;
    }

    private static boolean containsRange(List<int[]> ranges, int from, int to) {
        for (int[] range : ranges) if (range[0] == from && range[1] == to) return true;
        return false;
    }

    private static boolean rangesOverlap(int aFrom, int aTo, int bFrom, int bTo) {
        if (bFrom < 0 || bTo < bFrom) return false;
        return aFrom <= bTo && bFrom <= aTo;
    }

    private static void clearDisplayRangeLocked(Session session, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(session.states.length - 1, to);
        for (int i = start; i <= end; i++) {
            int df = session.displayFrom[i];
            int dt = session.displayTo[i];
            if (df < start || dt > end) continue;
            session.states[i] = UNRESOLVED;
            session.displayTexts[i] = null;
            session.displayFrom[i] = -1;
            session.displayTo[i] = -1;
        }
    }

    private static void resetRetriesLocked(Session session, int index) {
        if (index < 0 || index >= session.states.length) return;
        session.priorityAttempts[index] = 0;
        if (index < session.priorityFocusMisses.length) session.priorityFocusMisses[index] = 0;
        session.backgroundAttempts[index] = 0;
        session.priorityRetryAfterMs[index] = 0L;
        session.backgroundRetryAfterMs[index] = 0L;
    }

    private static void failPlan(Session session, Request request, Throwable failure, long started) {
        boolean blockAbandoned = false;
        boolean pageFallenBack = false;
        int block = -1;
        int page = -1;
        synchronized (session.lock) {
            detachRequestLocked(session, request);
            if (request.cancelled || session.cancelled || !isCurrent(session)) return;
            long now = SystemClock.elapsedRealtime();
            if (request.priority) {
                for (int i = request.coreFrom; i < request.coreTo && i < session.states.length; i++) {
                    if (session.states[i] == READY) continue;
                    int attempt = ++session.priorityAttempts[i];
                    session.priorityRetryAfterMs[i] = now + Math.min(2_500L, 450L + attempt * 450L);
                }
            } else if (request.pageMerge) {
                page = request.pageIndex;
                if (page >= 0 && page < session.backgroundPageAttempts.length) {
                    int attempt = ++session.backgroundPageAttempts[page];
                    if (attempt >= BACKGROUND_MAX_EXTERNAL_FAILURES) {
                        session.backgroundPageFallback[page] = true;
                        session.backgroundPageAttempts[page] = 0;
                        session.backgroundPageRetryAfterMs[page] = 0L;
                        pageFallenBack = true;
                    } else {
                        session.backgroundPageRetryAfterMs[page] = now + BACKGROUND_BLOCK_RETRY_MS;
                    }
                }
            } else if (request.altFraming) {
                // The one re-framed repair failed. Its single budget is already consumed by
                // buildAltFramingLocked and it has no block frontier of its own, so there is nothing
                // left to retry or mark done: the hole simply falls through to the priority play-head
                // path if playback ever reaches it.
                page = request.pageIndex;
            } else {
                block = backgroundBlockIndexForAtomLocked(session, request.coreFrom);
                if (block >= 0 && block < session.backgroundBlockDone.length) {
                    int attempt = ++session.backgroundBlockAttempts[block];
                    if (attempt >= BACKGROUND_MAX_EXTERNAL_FAILURES) {
                        session.backgroundBlockDone[block] = true;
                        session.backgroundBlockRetryAfterMs[block] = 0L;
                        blockAbandoned = true;
                    } else {
                        session.backgroundBlockRetryAfterMs[block] = now + BACKGROUND_BLOCK_RETRY_MS;
                    }
                }
            }
        }
        request.connection = null;
        long took = Math.max(0L, SystemClock.elapsedRealtime() - started);
        String detail = CaptionDiagnostics.errorDetail(failure);
        String event;
        String prefix;
        if (request.priority) {
            event = "CURRENT_UNIT_RETRY";
            prefix = "中心语义窗口失败，将保留已有账本并独立退避：";
        } else if (request.pageMerge) {
            event = pageFallenBack ? "BACKGROUND_PAGE_FALLBACK" : "BACKGROUND_PAGE_RETRY";
            prefix = pageFallenBack
                    ? "缓存页 #" + page + " 合并请求连续失败，降级为逐块 fallback 请求（不再尝试页级合并）："
                    : "缓存页 #" + page + " 合并请求失败，将保持相同合并范围后重试一次：";
        } else if (request.altFraming) {
            event = "FIXED_BLOCK_RETRY";
            prefix = "边界二次观察请求失败，单次预算已用尽，残留空洞交由播放头优先修复：";
        } else {
            event = blockAbandoned ? "FIXED_BLOCK_ABANDONED" : "FIXED_BLOCK_RETRY";
            prefix = blockAbandoned
                    ? "固定背景块 #" + block + " 连续失败，停止后台重试并交由重叠块/播放头修复："
                    : "固定背景块 #" + block + " 失败，将保持相同请求形状后重试一次：";
        }
        if (pageFallenBack) TokenCostAudit.recordPageFallback();
        CaptionDiagnostics.mark(
                session.context,
                event,
                prefix + detail + "；用时 " + took + " ms"
        );
        render(session, session.currentTimeMs);
        schedule(session);
    }

    private static void reprioritizeAfterSeek(Session session, long timeMs) {
        Request cancelPriority;
        Request cancelBackground;
        synchronized (session.lock) {
            if (session.atoms.isEmpty()) return;
            session.renderGeneration++;
            session.lastRenderSignature = "";
            int anchor = anchor(session.atoms, timeMs);
            for (int i = Math.max(0, anchor - 8); i < Math.min(session.states.length, anchor + 80); i++) {
                if (session.states[i] != READY) {
                    session.priorityAttempts[i] = 0;
                    session.priorityFocusMisses[i] = 0;
                    session.backgroundAttempts[i] = 0;
                    session.priorityRetryAfterMs[i] = 0L;
                    session.backgroundRetryAfterMs[i] = 0L;
                }
            }
            cancelPriority = session.priorityRequest;
            cancelBackground = session.backgroundRequest;
            session.priorityRequest = null;
            session.backgroundRequest = null;
            session.backgroundRefillActive = false;
            session.lastPriorityFingerprint = "";
            session.lastPriorityFingerprintAtMs = 0L;
            session.heldPriorityGeneration = -1L;
            session.heldPriorityFocus = -1;
        }
        if (cancelPriority != null) cancelPriority.cancel();
        if (cancelBackground != null) cancelBackground.cancel();
        CaptionDiagnostics.mark(
                session.context,
                "SEEK_REPRIORITIZED",
                "已围绕跳转位置重建中心语义窗口，不清除任何已完成字幕"
        );
        schedule(session);
    }

    private static void render(Session session, long timeMs) {
        if (!isCurrent(session) || session.cancelled || !session.visible) return;
        String text = "";
        boolean status = false;
        int visibleFrom = -1;
        int visibleTo = -1;
        long renderGeneration;
        synchronized (session.lock) {
            renderGeneration = session.renderGeneration;
            if (!session.timelineReady || session.atoms.isEmpty()) {
                text = session.error.isEmpty()
                        ? session.targetLanguage.displayName + " AI 字幕准备中…"
                        : session.error;
                status = true;
            } else {
                int index = indexAtOrBefore(session.atoms, timeMs);
                if (isReadyLocked(session, index)) {
                    int from = session.displayFrom[index];
                    int to = session.displayTo[index];
                    if (from >= 0 && to >= from && to < session.atoms.size()) {
                        long start = session.atoms.get(from).startMs;
                        long end = session.atoms.get(to).endMs;
                        if (timeMs >= start && timeMs < end) {
                            text = session.displayTexts[index] == null
                                    ? "" : session.displayTexts[index].trim();
                            if (!text.isEmpty()) {
                                visibleFrom = from;
                                visibleTo = to;
                            }
                        }
                    }
                }
            }
            session.visibleDisplayFrom = visibleFrom;
            session.visibleDisplayTo = visibleTo;
        }

        String signature = (status ? "S|" : text.isEmpty() ? "H|" : "C|") + text;
        synchronized (session.lock) {
            if (signature.equals(session.lastRenderSignature)) return;
            session.lastRenderSignature = signature;
        }
        CaptionOverlay.RenderGuard guard = overlayGuard(session, renderGeneration);
        if (text.isEmpty()) CaptionOverlay.hide(guard);
        else if (status) CaptionOverlay.showStatus(text, guard);
        else CaptionOverlay.showCaption(text, guard);
    }

    private static long bufferAheadMs(Session session) {
        synchronized (session.lock) {
            return bufferAheadMsLocked(session);
        }
    }

    private static long bufferAheadMsLocked(Session session) {
        if (!session.timelineReady || session.atoms.isEmpty()) return 0L;
        int anchor = anchor(session.atoms, session.currentTimeMs);
        if (!isReadyLocked(session, anchor)) return 0L;
        int anchorTo = session.displayTo[anchor];
        if (anchorTo < anchor || anchorTo >= session.atoms.size()) return 0L;
        long end = session.atoms.get(anchorTo).endMs;
        int i = anchorTo + 1;
        while (i < session.states.length && session.states[i] == READY) {
            int to = session.displayTo[i];
            if (to < i || to >= session.states.length) break;
            end = Math.max(end, session.atoms.get(to).endMs);
            i = to + 1;
        }
        return Math.max(0L, end - session.currentTimeMs);
    }

    private static boolean isReadyLocked(Session session, int index) {
        return index >= 0 && index < session.states.length && session.states[index] == READY;
    }

    private static int restoreCache(Session session) {
        byte[] data = DiskCaptionCache.get(session.context, session.cacheKey);
        if (data == null) return 0;
        try {
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (root.optInt("format", -1) != CACHE_FORMAT ||
                    root.optInt("atom_count", -1) != session.atoms.size()) return 0;
            JSONArray entries = root.optJSONArray("display_entries");
            if (entries == null) return 0;
            int restored = 0;
            synchronized (session.lock) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject value = entries.optJSONObject(i);
                    if (value == null) continue;
                    String text = value.has("text") ? value.optString("text", null) : null;
                    int from = value.optInt("from", -1);
                    int to = value.optInt("to", -1);
                    if (text == null || text.trim().isEmpty() || from < 0 || to < from ||
                            to >= session.atoms.size()) continue;
                    boolean overlap = false;
                    for (int atom = from; atom <= to; atom++) {
                        if (session.states[atom] == READY) {
                            overlap = true;
                            break;
                        }
                    }
                    if (overlap) continue;
                    for (int atom = from; atom <= to; atom++) {
                        session.displayTexts[atom] = text;
                        session.displayFrom[atom] = from;
                        session.displayTo[atom] = to;
                        session.states[atom] = READY;
                        restored++;
                    }
                }
            }
            return restored;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void persistCacheAsync(Session session) {
        SOURCES.execute(() -> persistCache(session));
    }

    private static void persistCache(Session session) {
        if (session.cacheKey == null || session.cacheKey.isEmpty()) return;
        synchronized (session.cacheWriteLock) {
            try {
                JSONArray entries = new JSONArray();
                synchronized (session.lock) {
                    for (int i = 0; i < session.states.length; i++) {
                        if (session.states[i] != READY || session.displayFrom[i] != i ||
                                session.displayTexts[i] == null) continue;
                        int to = session.displayTo[i];
                        if (to < i || to >= session.states.length) continue;
                        entries.put(new JSONObject()
                                .put("from", i)
                                .put("to", to)
                                .put("text", session.displayTexts[i]));
                    }
                }
                JSONObject root = new JSONObject()
                        .put("format", CACHE_FORMAT)
                        .put("atom_count", session.atoms.size())
                        .put("display_entries", entries);
                DiskCaptionCache.put(
                        session.context,
                        session.cacheKey,
                        root.toString().getBytes(StandardCharsets.UTF_8)
                );
            } catch (Throwable ignored) {
            }
        }
    }

    private static void markCompleteIfNeeded(Session session) {
        synchronized (session.lock) {
            if (session.completeLogged) return;
            for (int state : session.states) if (state != READY) return;
            session.completeLogged = true;
        }
        CaptionDiagnostics.mark(session.context, "TRANSLATION_COMPLETE", "整条视频 AI 语义账本已覆盖全部时间原子");
    }

    private static void detachRequestLocked(Session session, Request request) {
        if (session.priorityRequest == request) session.priorityRequest = null;
        if (session.backgroundRequest == request) session.backgroundRequest = null;
    }

    private static void updatePlaybackClock(long videoTimeMs, long realtimeMs) {
        long previousRealtime = latestVideoTimeRealtimeMs;
        long previousVideo = latestVideoTimeMs;
        if (previousRealtime > 0L) {
            long realDelta = realtimeMs - previousRealtime;
            long videoDelta = videoTimeMs - previousVideo;
            if (realDelta >= 120L && realDelta <= 5_000L && videoDelta >= 0L &&
                    videoDelta <= Math.round(realDelta * 3.25f) + 500L) {
                latestPlaybackRate = videoDelta <= 40L
                        ? 0f
                        : Math.max(0.25f, Math.min(3f, videoDelta / (float) realDelta));
            }
        }
        latestVideoTimeMs = videoTimeMs;
        latestVideoTimeRealtimeMs = realtimeMs;
    }

    private static long estimatedVideoTime(long realtimeMs) {
        if (latestVideoTimeRealtimeMs <= 0L || latestPlaybackRate <= 0f) return latestVideoTimeMs;
        long elapsed = Math.max(0L, realtimeMs - latestVideoTimeRealtimeMs);
        return latestVideoTimeMs + Math.round(
                Math.min(elapsed, MAX_CLOCK_EXTRAPOLATION_MS) * latestPlaybackRate
        );
    }

    private static int anchor(List<SourceAtomTimeline.Atom> atoms, long timeMs) {
        if (atoms.isEmpty()) return -1;
        int index = indexAtOrBefore(atoms, timeMs);
        if (index < 0) return 0;
        if (timeMs >= atoms.get(index).endMs && index + 1 < atoms.size()) return index + 1;
        return index;
    }

    private static int indexAtOrBefore(List<SourceAtomTimeline.Atom> atoms, long timeMs) {
        if (atoms.isEmpty()) return -1;
        int low = 0;
        int high = atoms.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (atoms.get(middle).startMs <= timeMs) low = middle + 1;
            else high = middle;
        }
        return low - 1;
    }

    private static byte[] cacheIdentity(byte[] body) {
        byte[] source = body == null ? new byte[0] : body;
        byte[] result = Arrays.copyOf(source, source.length + CACHE_MARKER.length);
        System.arraycopy(CACHE_MARKER, 0, result, source.length, CACHE_MARKER.length);
        return result;
    }

    private static boolean sameTranslationConfig(
            DeepSeekConfig.Snapshot first,
            DeepSeekConfig.Snapshot second
    ) {
        return first.fingerprint().equals(second.fingerprint()) &&
                first.apiKey.equals(second.apiKey) && first.enabled == second.enabled;
    }

    private static boolean isCurrent(Session session) {
        return active == session;
    }

    private static CaptionOverlay.RenderGuard overlayGuard(Session session, long generation) {
        return () -> active == session && !session.cancelled && session.visible &&
                session.renderGeneration == generation;
    }

    private static void failSession(Session session, String detail) {
        if (!isCurrent(session) || session.cancelled) return;
        session.error = detail == null || detail.trim().isEmpty() ? "AI 字幕不可用" : detail.trim();
        session.terminalError = true;
        if (session.visible) CaptionOverlay.showStatus(session.error, overlayGuard(session, session.renderGeneration));
        CaptionDiagnostics.mark(session.context, "DYNAMIC_ERROR", session.error);
    }

    private static void deactivateIfCurrent(Session expected, String reason) {
        if (expected == null) return;
        Session previous;
        synchronized (ACTIVE_LOCK) {
            if (active != expected || expected.cancelled) return;
            previous = active;
            active = null;
        }
        previous.cancel();
        CaptionDiagnostics.mark(previous.context,
                "DYNAMIC_STOPPED", reason);
        CaptionOverlay.clear();
        DISPLAY.removeCallbacks(DISPLAY_TICK);
    }
    private static void deactivate(String reason) {
        Session previous;
        synchronized (ACTIVE_LOCK) {
            previous = active;
            active = null;
        }
        if (previous != null) {
            previous.cancel();
            CaptionDiagnostics.mark(previous.context, "DYNAMIC_STOPPED", reason);
        }
        CaptionOverlay.clear();
        DISPLAY.removeCallbacks(DISPLAY_TICK);
    }

    static String videoIdFromUrl(String url) {
        String value = query(url, "v");
        if (value == null || value.isEmpty()) value = query(url, "video_id");
        return value == null ? "" : value;
    }

    private static String abbreviatedVideoId(String videoId) {
        if (videoId == null || videoId.length() <= 16) return videoId == null ? "" : videoId;
        return videoId.substring(0, 16);
    }

    private static String query(String url, String name) {
        try {
            return Uri.parse(url).getQueryParameter(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class MergeResult {
        static final MergeResult NONE = new MergeResult(0, 0, 0, false);
        final int committedAtoms;
        final int replacedUnits;
        final int conflicts;
        final boolean changed;

        MergeResult(int committedAtoms, int replacedUnits, int conflicts, boolean changed) {
            this.committedAtoms = committedAtoms;
            this.replacedUnits = replacedUnits;
            this.conflicts = conflicts;
            this.changed = changed;
        }
    }

    private static final class RefineCandidate {
        final int from;
        final int to;
        final String text;

        RefineCandidate(int from, int to, String text) {
            this.from = from;
            this.to = to;
            this.text = text == null ? "" : text;
        }
    }

    private static final class Request {
        final int windowFrom;
        final int windowTo;
        final int coreFrom;
        final int coreTo;
        final int focus;
        final boolean priority;
        final boolean gapRescue;
        final boolean pageMerge;
        final boolean altFraming;
        final int pageIndex;
        final int blockFrom;
        final int blockTo;
        final long sequence;
        final long demandGeneration;
        volatile boolean cancelled;
        /**
         * Set once the prompt has reached the provider. From that moment cancelling this request
         * can only throw away a result that has already been paid for, so preemption must stop.
         */
        volatile boolean bodySent;
        volatile HttpURLConnection connection;
        volatile Future<?> future;

        private Request(
                int windowFrom,
                int windowTo,
                int coreFrom,
                int coreTo,
                int focus,
                boolean priority,
                boolean gapRescue,
                boolean pageMerge,
                boolean altFraming,
                int pageIndex,
                int blockFrom,
                int blockTo,
                long sequence,
                long demandGeneration
        ) {
            this.windowFrom = windowFrom;
            this.windowTo = windowTo;
            this.coreFrom = coreFrom;
            this.coreTo = coreTo;
            this.focus = focus;
            this.priority = priority;
            this.gapRescue = gapRescue;
            this.pageMerge = pageMerge;
            this.altFraming = altFraming;
            this.pageIndex = pageIndex;
            this.blockFrom = blockFrom;
            this.blockTo = blockTo;
            this.sequence = sequence;
            this.demandGeneration = demandGeneration;
        }

        static Request priority(
                int windowFrom, int windowTo, int coreFrom, int coreTo, int focus,
                long sequence, long demandGeneration, boolean gapRescue
        ) {
            return new Request(
                    windowFrom, windowTo, coreFrom, coreTo, focus,
                    true, gapRescue, false, false, -1, -1, -1, sequence, demandGeneration
            );
        }

        static Request backgroundBlock(
                int windowFrom, int windowTo, int coreFrom, int coreTo,
                long sequence, int pageIndex, int block
        ) {
            return new Request(
                    windowFrom, windowTo, coreFrom, coreTo, coreFrom,
                    false, false, false, false, pageIndex, block, block, sequence, 0L
            );
        }

        static Request backgroundPage(
                int windowFrom, int windowTo, int coreFrom, int coreTo,
                long sequence, int pageIndex, int blockFrom, int blockTo
        ) {
            return new Request(
                    windowFrom, windowTo, coreFrom, coreTo, coreFrom,
                    false, false, true, false, pageIndex, blockFrom, blockTo, sequence, 0L
            );
        }

        static Request backgroundAlt(
                int windowFrom, int windowTo, int coreFrom, int coreTo,
                long sequence, int pageIndex
        ) {
            return new Request(
                    windowFrom, windowTo, coreFrom, coreTo, coreFrom,
                    false, false, false, true, pageIndex, -1, -1, sequence, 0L
            );
        }

        String lane() {
            if (priority) {
                return gapRescue
                        ? SemanticUnitApiClient.LANE_PRIORITY_GAP_RESCUE
                        : SemanticUnitApiClient.LANE_PRIORITY_CURRENT;
            }
            if (pageMerge) return SemanticUnitApiClient.LANE_BACKGROUND_PAGE;
            if (altFraming) return SemanticUnitApiClient.LANE_BACKGROUND_ALT;
            return SemanticUnitApiClient.LANE_BACKGROUND_BLOCK;
        }

        String fingerprint() {
            return demandGeneration + ":" + focus + ":" + windowFrom + ":" + windowTo +
                    ":" + coreFrom + ":" + coreTo;
        }

        boolean coreContains(int index) {
            return index >= coreFrom && index < coreTo;
        }

        boolean nearPlayback(long timeMs, List<SourceAtomTimeline.Atom> atoms, long aheadMs) {
            if (atoms.isEmpty() || coreFrom < 0 || coreFrom >= atoms.size()) return false;
            long start = atoms.get(coreFrom).startMs;
            long end = atoms.get(Math.min(atoms.size() - 1, Math.max(coreFrom, coreTo - 1))).endMs;
            return end >= timeMs - 4_000L && start <= timeMs + aheadMs;
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) current.disconnect();
            Future<?> task = future;
            if (task != null) task.cancel(true);
        }
    }

    private static final class Session {
        final long id;
        final Context context;
        final String translatedUrl;
        final String requestKey;
        volatile String videoId;
        final TargetLanguage targetLanguage;
        final DeepSeekConfig.Snapshot config;
        final Object lock = new Object();
        final Object cacheWriteLock = new Object();

        volatile boolean cancelled;
        volatile boolean visible;
        volatile boolean timelineReady;
        volatile boolean terminalError;
        volatile boolean firstReady;
        volatile boolean completeLogged;
        volatile boolean backgroundRefillActive;
        volatile boolean startupSeekDebounceLogged;
        volatile long renderGeneration = 1L;
        volatile long lastPriorityFingerprintAtMs;
        volatile long heldPriorityGeneration = -1L;
        volatile long currentTimeMs;
        volatile long activatedAtMs = SystemClock.elapsedRealtime();
        volatile long timelineStartedAtMs;
        volatile long requestSequence;
        volatile long completedRequests;
        volatile String error = "";
        volatile String cacheKey = "";
        volatile String lastRenderSignature = "";
        volatile String lastPriorityFingerprint = "";
        volatile Future<?> sourceTask;
        volatile Request priorityRequest;
        volatile Request backgroundRequest;

        int visibleDisplayFrom = -1;
        int visibleDisplayTo = -1;
        int heldPriorityFocus = -1;
        List<SourceAtomTimeline.Atom> atoms = Collections.emptyList();
        List<String> sourceTexts = Collections.emptyList();
        String[] displayTexts = new String[0];
        int[] displayFrom = new int[0];
        int[] displayTo = new int[0];
        int[] states = new int[0];
        int[] priorityAttempts = new int[0];
        int[] priorityFocusMisses = new int[0];
        int[] backgroundAttempts = new int[0];
        long[] priorityRetryAfterMs = new long[0];
        long[] backgroundRetryAfterMs = new long[0];
        boolean[] refineQueued = new boolean[0];
        boolean[] backgroundBlockDone = new boolean[0];
        int[] backgroundBlockAttempts = new int[0];
        long[] backgroundBlockRetryAfterMs = new long[0];
        boolean[] backgroundPageFallback = new boolean[0];
        int[] backgroundPageAttempts = new int[0];
        long[] backgroundPageRetryAfterMs = new long[0];
        boolean[] backgroundPageAltUsed = new boolean[0];
        int[] backgroundPageAltFrom = new int[0];
        int[] backgroundPageAltTo = new int[0];

        Session(
                long id,
                Context context,
                String translatedUrl,
                String requestKey,
                String videoId,
                TargetLanguage targetLanguage,
                DeepSeekConfig.Snapshot config
        ) {
            this.id = id;
            this.context = context;
            this.translatedUrl = translatedUrl;
            this.requestKey = requestKey;
            this.videoId = videoId == null ? "" : videoId;
            this.targetLanguage = targetLanguage;
            this.config = config;
        }

        void cancel() {
            cancelled = true;
            Future<?> source = sourceTask;
            if (source != null) source.cancel(true);
            Request current = priorityRequest;
            if (current != null) current.cancel();
            Request ahead = backgroundRequest;
            if (ahead != null) ahead.cancel();
        }
    }
}
