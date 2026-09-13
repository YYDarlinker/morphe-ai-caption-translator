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
 * Centered-window semantic ledger runtime.
 *
 * <p>Atoms remain immutable timing anchors, but they no longer form a prefix-commit state machine.
 * AI returns complete semantic units from overlapping centered windows. Valid later units may be
 * committed even if an earlier boundary is unresolved, and already-visible units are never cleared
 * for repair. This removes the legacy pending-tail/seam-repair livelock without weakening the
 * language-quality contract.</p>
 */
final class SemanticLedgerCaptionController {
    private static final int UNRESOLVED = 0;
    private static final int READY = 1;

    private static final int PRIORITY_BASE_BEFORE = 28;
    private static final int PRIORITY_BASE_AFTER = 74;
    private static final int PRIORITY_MAX_BEFORE = 58;
    private static final int PRIORITY_MAX_AFTER = 146;
    private static final int PRIORITY_CORE_AFTER = 28;

    private static final int BACKGROUND_CORE_ATOMS = 56;
    private static final int BACKGROUND_BASE_BEFORE = 24;
    private static final int BACKGROUND_BASE_AFTER = 40;
    private static final int BACKGROUND_MAX_BEFORE = 52;
    private static final int BACKGROUND_MAX_AFTER = 84;

    private static final int CONTEXT_OUTSIDE_WINDOW_ATOMS = 18;
    private static final long PREFETCH_AHEAD_MS = 60_000L;
    private static final long SEEK_THRESHOLD_MS = 2_900L;
    private static final long FOREIGN_TRACK_GRACE_MS = 1_500L;
    private static final long PLAYER_RESTORE_GRACE_MS = 4_000L;
    private static final long DISPLAY_TICK_MS = 80L;
    private static final long MAX_CLOCK_EXTRAPOLATION_MS = 1_350L;
    private static final int CACHE_FORMAT = 7;
    private static final byte[] CACHE_MARKER =
            "\n#ai-source-atom-semantic-ledger-v1".getBytes(StandardCharsets.UTF_8);

    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final ExecutorService SOURCES = Executors.newCachedThreadPool(
            daemonThreadFactory("AiCaptionLedgerSource-")
    );
    private static final ExecutorService PRIORITY = Executors.newSingleThreadExecutor(
            daemonThreadFactory("AiCaptionLedgerCurrent-")
    );
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor(
            daemonThreadFactory("AiCaptionLedgerAhead-")
    );
    private static final Handler DISPLAY = new Handler(Looper.getMainLooper());
    private static final Runnable DISPLAY_TICK = SemanticLedgerCaptionController::displayTick;
    private static final Object ACTIVE_LOCK = new Object();

    private static volatile Session active;
    private static volatile String currentVideoId = "";
    private static volatile boolean compactPlayer;
    private static volatile long restoreGraceUntilMs;
    private static volatile long latestVideoTimeMs;
    private static volatile long latestVideoTimeRealtimeMs;
    private static volatile float latestPlaybackRate = 1f;

    private SemanticLedgerCaptionController() {}

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
        String requestKey = CaptionEngine.requestKey(app, translatedUrl) + "|semantic-ledger-v1";
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
                    CaptionDiagnostics.mark(app, "AI_SHOWN_FROM_PREWARM", "预热完整语义单元已就绪，选择后立即显示");
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
            if (session.visible) CaptionOverlay.showStatus(session.error);
            CaptionDiagnostics.mark(app, "CONFIG_NOT_READY", session.error);
            return;
        }

        if (visible) CaptionOverlay.showStatus(target.displayName + " AI 字幕准备中…");
        CaptionDiagnostics.mark(
                app,
                visible ? "DYNAMIC_STARTED" : "INSTANT_PREWARM_STARTED",
                "目标 " + target.promptLabel() +
                        (visible ? "，正在建立 AI 完整语义单元账本" : "，后台预热 AI 完整语义单元账本")
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
            deactivate("检测到新视频字幕轨");
            return;
        }
        String target = query(url, "tlang");
        long age = SystemClock.elapsedRealtime() - session.activatedAtMs;
        if ((target != null && !target.trim().isEmpty()) || age > FOREIGN_TRACK_GRACE_MS) {
            deactivate("已切换到其他字幕轨");
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
        if (seek) reprioritizeAfterSeek(session, clean);
        render(session, clean);
        schedule(session);
        scheduleDisplayTick();
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
                session.unitFrom = new int[count];
                session.unitTo = new int[count];
                Arrays.fill(session.unitFrom, -1);
                Arrays.fill(session.unitTo, -1);
                session.states = new int[count];
                session.attempts = new int[count];
                session.retryAfterMs = new long[count];
                session.timelineReady = true;
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
                        "立即复用 " + cached + "/" + session.atoms.size() + " 个词级时间原子的 AI 语义账本"
                );
                render(session, session.currentTimeMs);
            }

            CaptionDiagnostics.mark(
                    session.context,
                    "SEMANTIC_LEDGER_STARTED",
                    "中心上下文窗口 → AI 完整语义单元；无 commit_limit、无破坏式 seam repair，滚动预取 60 秒"
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
        synchronized (session.lock) {
            int anchor = anchor(session.atoms, session.currentTimeMs);
            if (anchor < 0 || anchor >= session.states.length) return;
            long now = SystemClock.elapsedRealtime();

            if (session.priorityRequest != null && isReadyLocked(session, anchor)) {
                cancelPriority = session.priorityRequest;
                session.priorityRequest = null;
            } else if (session.priorityRequest != null &&
                    !session.priorityRequest.windowContains(anchor)) {
                cancelPriority = session.priorityRequest;
                session.priorityRequest = null;
            }

            boolean needsCurrent = (session.visible || !session.firstReady) && !isReadyLocked(session, anchor);
            if (needsCurrent && session.priorityRequest == null && session.retryAfterMs[anchor] <= now) {
                startPriority = buildPriorityLocked(session, anchor);
                session.priorityRequest = startPriority;
            }

            if (session.backgroundRequest != null &&
                    !session.backgroundRequest.nearPlayback(session.currentTimeMs, session.atoms)) {
                cancelBackground = session.backgroundRequest;
                session.backgroundRequest = null;
            }

            if (session.backgroundRequest == null) {
                int target = findBackgroundTargetLocked(session, anchor, now);
                if (target >= 0) {
                    startBackground = buildBackgroundLocked(session, target);
                    session.backgroundRequest = startBackground;
                }
            }
        }

        if (cancelPriority != null) cancelPriority.cancel();
        if (cancelBackground != null) cancelBackground.cancel();
        if (startPriority != null) submit(session, startPriority, PRIORITY);
        if (startBackground != null) submit(session, startBackground, BACKGROUND);
    }

    private static Request buildPriorityLocked(Session session, int anchor) {
        int attempt = Math.max(0, session.attempts[anchor]);
        int before = Math.min(PRIORITY_MAX_BEFORE, PRIORITY_BASE_BEFORE + attempt * 10);
        int after = Math.min(PRIORITY_MAX_AFTER, PRIORITY_BASE_AFTER + attempt * 22);
        int windowFrom = Math.max(0, anchor - before);
        int windowTo = Math.min(session.atoms.size(), anchor + after);
        int coreFrom = Math.max(windowFrom, anchor - 4);
        int coreTo = Math.min(windowTo, anchor + PRIORITY_CORE_AFTER + Math.min(20, attempt * 4));
        if (coreTo <= coreFrom) coreTo = Math.min(windowTo, coreFrom + 1);
        return new Request(
                windowFrom, windowTo, coreFrom, coreTo, anchor,
                true, ++session.requestSequence
        );
    }

    private static Request buildBackgroundLocked(Session session, int first) {
        int attempt = Math.max(0, session.attempts[first]);
        int coreSize = BACKGROUND_CORE_ATOMS + Math.min(24, attempt * 8);
        long horizon = session.currentTimeMs + PREFETCH_AHEAD_MS;
        int coreTo = first;
        while (coreTo < session.atoms.size() && coreTo - first < coreSize) {
            if (coreTo > first && session.atoms.get(coreTo).startMs > horizon) break;
            coreTo++;
        }
        coreTo = Math.max(first + 1, coreTo);
        int before = Math.min(BACKGROUND_MAX_BEFORE, BACKGROUND_BASE_BEFORE + attempt * 8);
        int after = Math.min(BACKGROUND_MAX_AFTER, BACKGROUND_BASE_AFTER + attempt * 12);
        int windowFrom = Math.max(0, first - before);
        int windowTo = Math.min(session.atoms.size(), coreTo + after);
        return new Request(
                windowFrom, windowTo, first, coreTo, first,
                false, ++session.requestSequence
        );
    }

    private static int findBackgroundTargetLocked(Session session, int anchor, long now) {
        long horizon = session.currentTimeMs + PREFETCH_AHEAD_MS;
        for (int i = Math.max(0, anchor); i < session.states.length; i++) {
            if (session.atoms.get(i).startMs > horizon) break;
            if (session.states[i] == READY) continue;
            if (session.retryAfterMs[i] > now) continue;
            Request current = session.priorityRequest;
            if (current != null && current.coreContains(i)) continue;
            return i;
        }
        return -1;
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
                    },
                    request.priority,
                    request.priority
                            ? SemanticUnitApiClient.LANE_PRIORITY_CURRENT
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
        long now = SystemClock.elapsedRealtime();

        synchronized (session.lock) {
            detachRequestLocked(session, request);
            if (request.cancelled || session.cancelled || !isCurrent(session)) return;

            for (SemanticUnitApiClient.Unit unit : result.units) {
                int globalFrom = request.windowFrom + unit.from;
                int globalTo = request.windowFrom + unit.to;
                MergeResult merged = mergeUnitLocked(session, globalFrom, globalTo, unit.text);
                committed += merged.committedAtoms;
                replacements += merged.replacedUnits;
                conflicts += merged.conflicts;
            }

            int anchor = anchor(session.atoms, session.currentTimeMs);
            for (int i = request.coreFrom; i < request.coreTo && i < session.states.length; i++) {
                if (session.states[i] == READY) {
                    session.attempts[i] = 0;
                    session.retryAfterMs[i] = 0L;
                    continue;
                }
                int attempt = ++session.attempts[i];
                long delay = request.priority
                        ? Math.min(4_000L, 350L + attempt * 500L)
                        : Math.min(15_000L, 1_200L + attempt * 1_800L);
                session.retryAfterMs[i] = now + delay;
            }

            session.completedRequests++;
            if (isReadyLocked(session, anchor)) {
                if (!session.firstReady) {
                    session.firstReady = true;
                    firstReadyNow = true;
                }
            }
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
        } else if (session.completedRequests <= 4 || session.completedRequests % 4 == 0 ||
                committed == 0 || took > 2_400L) {
            CaptionDiagnostics.mark(
                    session.context,
                    committed == 0 ? "ZERO_UNIT_WINDOW" : "UNIT_BATCH_READY",
                    "第 " + session.completedRequests + " 个中心窗口：接受 " + result.units.size() +
                            " 个完整单元，新增 " + committed + " 个原子，用时 " + took +
                            " ms；前方库存 " + buffer + " ms"
            );
        }

        if (replacements > 0 || conflicts > 0) {
            CaptionDiagnostics.mark(
                    session.context,
                    "LEDGER_SHADOW_MERGE",
                    "非破坏式合并：未来单元替换 " + replacements + " 个；当前可见冲突保留 " + conflicts + " 个"
            );
        }

        render(session, session.currentTimeMs);
        if (committed > 0 || replacements > 0) persistCacheAsync(session);
        markCompleteIfNeeded(session);
        schedule(session);
    }

    private static MergeResult mergeUnitLocked(Session session, int from, int to, String text) {
        if (from < 0 || to < from || to >= session.states.length || text == null || text.trim().isEmpty()) {
            return MergeResult.NONE;
        }
        String clean = text.trim();
        List<int[]> overlaps = new ArrayList<>();
        boolean exact = true;
        for (int i = from; i <= to; i++) {
            if (session.states[i] != READY) {
                exact = false;
                continue;
            }
            int pf = session.unitFrom[i];
            int pt = session.unitTo[i];
            String existing = session.displayTexts[i] == null ? "" : session.displayTexts[i];
            if (pf != from || pt != to || !clean.equals(existing)) exact = false;
            if (pf >= 0 && pt >= pf && !containsRange(overlaps, pf, pt)) {
                overlaps.add(new int[]{pf, pt});
            }
        }
        if (exact && !overlaps.isEmpty()) return MergeResult.NONE;

        for (int[] range : overlaps) {
            if (rangesOverlap(range[0], range[1], session.visibleUnitFrom, session.visibleUnitTo)) {
                return new MergeResult(0, 0, 1);
            }
        }

        int replaced = 0;
        for (int[] range : overlaps) {
            clearUnitLocked(session, range[0], range[1]);
            replaced++;
        }

        int committed = 0;
        for (int i = from; i <= to; i++) {
            if (session.states[i] != READY) committed++;
            session.states[i] = READY;
            session.displayTexts[i] = clean;
            session.unitFrom[i] = from;
            session.unitTo[i] = to;
            session.attempts[i] = 0;
            session.retryAfterMs[i] = 0L;
        }
        return new MergeResult(committed, replaced, 0);
    }

    private static boolean containsRange(List<int[]> ranges, int from, int to) {
        for (int[] range : ranges) if (range[0] == from && range[1] == to) return true;
        return false;
    }

    private static boolean rangesOverlap(int aFrom, int aTo, int bFrom, int bTo) {
        if (bFrom < 0 || bTo < bFrom) return false;
        return aFrom <= bTo && bFrom <= aTo;
    }

    private static void clearUnitLocked(Session session, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(session.states.length - 1, to);
        for (int i = start; i <= end; i++) {
            if (session.unitFrom[i] != from || session.unitTo[i] != to) continue;
            session.states[i] = UNRESOLVED;
            session.displayTexts[i] = null;
            session.unitFrom[i] = -1;
            session.unitTo[i] = -1;
            session.retryAfterMs[i] = 0L;
        }
    }

    private static void failPlan(Session session, Request request, Throwable failure, long started) {
        synchronized (session.lock) {
            detachRequestLocked(session, request);
            if (request.cancelled || session.cancelled || !isCurrent(session)) return;
            long now = SystemClock.elapsedRealtime();
            for (int i = request.coreFrom; i < request.coreTo && i < session.states.length; i++) {
                if (session.states[i] == READY) continue;
                int attempt = ++session.attempts[i];
                long delay = request.priority
                        ? Math.min(5_000L, 700L + attempt * 700L)
                        : Math.min(20_000L, 2_000L + attempt * 2_500L);
                session.retryAfterMs[i] = now + delay;
            }
        }
        request.connection = null;
        long took = Math.max(0L, SystemClock.elapsedRealtime() - started);
        String detail = CaptionDiagnostics.errorDetail(failure);
        CaptionDiagnostics.mark(
                session.context,
                request.priority ? "CURRENT_UNIT_RETRY" : "UNIT_BATCH_RETRY",
                "中心语义窗口失败，将保留已有账本并扩窗重试：" + detail + "；用时 " + took + " ms"
        );
        render(session, session.currentTimeMs);
        schedule(session);
    }

    private static void reprioritizeAfterSeek(Session session, long timeMs) {
        Request cancelPriority;
        Request cancelBackground;
        synchronized (session.lock) {
            if (session.atoms.isEmpty()) return;
            int anchor = anchor(session.atoms, timeMs);
            for (int i = Math.max(0, anchor - 8); i < Math.min(session.states.length, anchor + 72); i++) {
                if (session.states[i] != READY) {
                    session.attempts[i] = 0;
                    session.retryAfterMs[i] = 0L;
                }
            }
            cancelPriority = session.priorityRequest;
            cancelBackground = session.backgroundRequest;
            session.priorityRequest = null;
            session.backgroundRequest = null;
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
        synchronized (session.lock) {
            if (!session.timelineReady || session.atoms.isEmpty()) {
                text = session.error.isEmpty()
                        ? session.targetLanguage.displayName + " AI 字幕准备中…"
                        : session.error;
                status = true;
            } else {
                int index = indexAtOrBefore(session.atoms, timeMs);
                if (isReadyLocked(session, index)) {
                    int from = session.unitFrom[index];
                    int to = session.unitTo[index];
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
            session.visibleUnitFrom = visibleFrom;
            session.visibleUnitTo = visibleTo;
        }

        String signature = (status ? "S|" : text.isEmpty() ? "H|" : "C|") + text;
        synchronized (session.lock) {
            if (signature.equals(session.lastRenderSignature)) return;
            session.lastRenderSignature = signature;
        }
        if (text.isEmpty()) CaptionOverlay.hide();
        else if (status) CaptionOverlay.showStatus(text);
        else CaptionOverlay.showCaption(text);
    }

    private static long bufferAheadMs(Session session) {
        synchronized (session.lock) {
            if (!session.timelineReady || session.atoms.isEmpty()) return 0L;
            int anchor = anchor(session.atoms, session.currentTimeMs);
            if (!isReadyLocked(session, anchor)) return 0L;
            long end = session.atoms.get(session.unitTo[anchor]).endMs;
            int i = session.unitTo[anchor] + 1;
            while (i < session.states.length && session.states[i] == READY) {
                int to = session.unitTo[i];
                if (to < i || to >= session.states.length) break;
                end = Math.max(end, session.atoms.get(to).endMs);
                i = to + 1;
            }
            return Math.max(0L, end - session.currentTimeMs);
        }
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
            JSONArray units = root.optJSONArray("units");
            if (units == null) return 0;
            int restored = 0;
            synchronized (session.lock) {
                for (int i = 0; i < units.length(); i++) {
                    JSONObject value = units.optJSONObject(i);
                    if (value == null) continue;
                    String text = value.optString("text", "").trim();
                    int from = value.optInt("from", -1);
                    int to = value.optInt("to", -1);
                    if (text.isEmpty() || from < 0 || to < from || to >= session.atoms.size()) continue;
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
                        session.unitFrom[atom] = from;
                        session.unitTo[atom] = to;
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
                JSONArray units = new JSONArray();
                synchronized (session.lock) {
                    for (int i = 0; i < session.states.length; i++) {
                        if (session.states[i] != READY || session.unitFrom[i] != i ||
                                session.displayTexts[i] == null) continue;
                        int to = session.unitTo[i];
                        if (to < i || to >= session.states.length) continue;
                        units.put(new JSONObject()
                                .put("from", i)
                                .put("to", to)
                                .put("text", session.displayTexts[i]));
                    }
                }
                JSONObject root = new JSONObject()
                        .put("format", CACHE_FORMAT)
                        .put("atom_count", session.atoms.size())
                        .put("units", units);
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
        CaptionDiagnostics.mark(session.context, "TRANSLATION_COMPLETE", "整条视频完整 AI 语义单元账本已建立");
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

    private static void failSession(Session session, String detail) {
        if (!isCurrent(session) || session.cancelled) return;
        session.error = detail == null || detail.trim().isEmpty() ? "AI 字幕不可用" : detail.trim();
        session.terminalError = true;
        if (session.visible) CaptionOverlay.showStatus(session.error);
        CaptionDiagnostics.mark(session.context, "DYNAMIC_ERROR", session.error);
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
        static final MergeResult NONE = new MergeResult(0, 0, 0);
        final int committedAtoms;
        final int replacedUnits;
        final int conflicts;

        MergeResult(int committedAtoms, int replacedUnits, int conflicts) {
            this.committedAtoms = committedAtoms;
            this.replacedUnits = replacedUnits;
            this.conflicts = conflicts;
        }
    }

    private static final class Request {
        final int windowFrom;
        final int windowTo;
        final int coreFrom;
        final int coreTo;
        final int focus;
        final boolean priority;
        final long sequence;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile Future<?> future;

        Request(
                int windowFrom,
                int windowTo,
                int coreFrom,
                int coreTo,
                int focus,
                boolean priority,
                long sequence
        ) {
            this.windowFrom = windowFrom;
            this.windowTo = windowTo;
            this.coreFrom = coreFrom;
            this.coreTo = coreTo;
            this.focus = focus;
            this.priority = priority;
            this.sequence = sequence;
        }

        boolean windowContains(int index) {
            return index >= windowFrom && index < windowTo;
        }

        boolean coreContains(int index) {
            return index >= coreFrom && index < coreTo;
        }

        boolean nearPlayback(long timeMs, List<SourceAtomTimeline.Atom> atoms) {
            if (atoms.isEmpty() || coreFrom < 0 || coreFrom >= atoms.size()) return false;
            long start = atoms.get(coreFrom).startMs;
            long end = atoms.get(Math.min(atoms.size() - 1, Math.max(coreFrom, coreTo - 1))).endMs;
            return end >= timeMs - 4_000L && start <= timeMs + PREFETCH_AHEAD_MS + 12_000L;
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
        volatile long currentTimeMs;
        volatile long activatedAtMs = SystemClock.elapsedRealtime();
        volatile long requestSequence;
        volatile long completedRequests;
        volatile String error = "";
        volatile String cacheKey = "";
        volatile String lastRenderSignature = "";
        volatile Future<?> sourceTask;
        volatile Request priorityRequest;
        volatile Request backgroundRequest;

        int visibleUnitFrom = -1;
        int visibleUnitTo = -1;
        List<SourceAtomTimeline.Atom> atoms = Collections.emptyList();
        List<String> sourceTexts = Collections.emptyList();
        String[] displayTexts = new String[0];
        int[] unitFrom = new int[0];
        int[] unitTo = new int[0];
        int[] states = new int[0];
        int[] attempts = new int[0];
        long[] retryAfterMs = new long[0];

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
