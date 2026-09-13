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
 * Play-head driven source-atom subtitle runtime.
 *
 * <p>The runtime never treats a YouTube cue as a sentence. Fine lexical atoms are immutable timing
 * anchors; the model groups them into closed source-language units and translates those units as a
 * whole. A missing atom is never skipped permanently: when playback reaches a seam between already
 * committed pages, the adjacent page is temporarily reopened and the local region is replanned as a
 * contiguous unit so a tiny READY island cannot trap an untranslatable one-atom hole.</p>
 */
final class AtomPageCaptionController {
    private static final int PENDING = 0;
    private static final int IN_FLIGHT = 1;
    private static final int READY = 2;
    private static final int FAILED = 3;

    private static final int MAX_PRIORITY_ATTEMPTS = 3;
    private static final int MAX_BACKGROUND_ATTEMPTS = 3;
    private static final int BASE_PRIORITY_ATOMS = 52;
    private static final int MAX_PRIORITY_ATOMS = 78;
    private static final int BASE_BACKGROUND_ATOMS = 74;
    private static final int MAX_BACKGROUND_ATOMS = 110;
    private static final int PRIORITY_CHARS = 900;
    private static final int BACKGROUND_CHARS = 1_600;
    private static final int PRIORITY_HISTORY_ATOMS = 12;
    private static final int MAX_REPAIR_EXTRA_ATOMS = 36;
    private static final int CONTEXT_BEFORE_ATOMS = 12;
    private static final int CONTEXT_AFTER_ATOMS = 18;
    private static final long PREFETCH_AHEAD_MS = 36_000L;
    private static final long SEEK_THRESHOLD_MS = 2_900L;
    private static final long FOREIGN_TRACK_GRACE_MS = 1_500L;
    private static final long PLAYER_RESTORE_GRACE_MS = 4_000L;
    private static final long DISPLAY_TICK_MS = 80L;
    private static final long MAX_CLOCK_EXTRAPOLATION_MS = 1_350L;
    private static final int CACHE_FORMAT = 6;
    private static final byte[] CACHE_MARKER = "\n#ai-source-atom-v3-salvage".getBytes(StandardCharsets.UTF_8);

    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final ExecutorService SOURCES = Executors.newCachedThreadPool(
            daemonThreadFactory("AiCaptionSource-")
    );
    private static final ExecutorService PRIORITY = Executors.newSingleThreadExecutor(
            daemonThreadFactory("AiCaptionCurrent-")
    );
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor(
            daemonThreadFactory("AiCaptionAhead-")
    );
    private static final Handler DISPLAY = new Handler(Looper.getMainLooper());
    private static final Runnable DISPLAY_TICK = AtomPageCaptionController::displayTick;
    private static final Object ACTIVE_LOCK = new Object();

    private static volatile Session active;
    private static volatile String currentVideoId = "";
    private static volatile boolean compactPlayer;
    private static volatile long restoreGraceUntilMs;
    private static volatile long latestVideoTimeMs;
    private static volatile long latestVideoTimeRealtimeMs;
    private static volatile float latestPlaybackRate = 1f;

    private AtomPageCaptionController() {}

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
        String requestKey = CaptionEngine.requestKey(app, translatedUrl) + "|source-atom-v3";
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
                    CaptionDiagnostics.mark(app, "AI_SHOWN_FROM_PREWARM", "预热语义页已就绪，选择后立即显示");
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
                        (visible ? "，正在准备首个源语言语义页" : "，后台预热首个源语言语义页")
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

            session.cacheKey = DiskCaptionCache.key(cacheIdentity(source.body), session.config, session.targetLanguage.code);
            synchronized (session.lock) {
                session.atoms = atomized.atoms;
                List<String> texts = new ArrayList<>(atomized.atoms.size());
                for (SourceAtomTimeline.Atom atom : atomized.atoms) texts.add(atom.text == null ? "" : atom.text);
                session.sourceTexts = Collections.unmodifiableList(texts);
                int count = atomized.atoms.size();
                session.displayTexts = new String[count];
                session.pageFrom = new int[count];
                session.pageTo = new int[count];
                Arrays.fill(session.pageFrom, -1);
                Arrays.fill(session.pageTo, -1);
                session.states = new int[count];
                session.priorityAttempts = new int[count];
                session.backgroundAttempts = new int[count];
                session.retryAfterMs = new long[count];
                session.timelineReady = true;
            }

            int cached = restoreCache(session);
            int anchor;
            synchronized (session.lock) {
                anchor = anchor(session.atoms, session.currentTimeMs);
                session.firstReady = anchor >= 0 && anchor < session.states.length && session.states[anchor] == READY;
            }
            if (cached > 0) {
                CaptionDiagnostics.mark(
                        session.context,
                        "CACHE_HIT",
                        "立即复用 " + cached + "/" + session.atoms.size() + " 个词级时间原子的 AI 语义页"
                );
                render(session, session.currentTimeMs);
            }

            CaptionDiagnostics.mark(
                    session.context,
                    "REALTIME_QUEUE_STARTED",
                    "source-atom → AI closed-unit；安全前缀提交 + seam repair，滚动预取 36 秒"
            );
            schedule(session);
        } catch (Throwable failure) {
            if (!session.cancelled && isCurrent(session)) failSession(session, CaptionDiagnostics.errorDetail(failure));
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

            if (session.visible && anchor != session.lastVisibleAnchor) {
                session.lastVisibleAnchor = anchor;
                if (session.states[anchor] == FAILED) {
                    session.states[anchor] = PENDING;
                    session.priorityAttempts[anchor] = 0;
                    session.retryAfterMs[anchor] = 0L;
                }
            }
            if (session.states[anchor] == READY) session.firstReady = true;

            boolean needsCurrent = (session.visible || !session.firstReady) && session.states[anchor] != READY;
            if (needsCurrent) {
                if (session.priorityRequest != null && !session.priorityRequest.contains(anchor)) {
                    cancelPriority = session.priorityRequest;
                    session.priorityRequest = null;
                    resetOwnedLocked(session, cancelPriority);
                }
                if (session.backgroundRequest != null) {
                    cancelBackground = session.backgroundRequest;
                    session.backgroundRequest = null;
                    resetOwnedLocked(session, cancelBackground);
                }
                if (session.priorityRequest == null && session.retryAfterMs[anchor] <= SystemClock.elapsedRealtime()) {
                    startPriority = buildPriorityLocked(session, anchor);
                    if (startPriority != null) session.priorityRequest = startPriority;
                }
            } else if (session.backgroundRequest == null && session.priorityRequest == null) {
                startBackground = buildBackgroundLocked(session, anchor);
                if (startBackground != null) session.backgroundRequest = startBackground;
            }
        }

        if (cancelPriority != null) cancelPriority.cancel();
        if (cancelBackground != null) cancelBackground.cancel();
        if (startPriority != null) submit(session, startPriority, PRIORITY);
        if (startBackground != null) submit(session, startBackground, BACKGROUND);
    }

    private static Request buildPriorityLocked(Session session, int anchor) {
        int attempt = Math.max(0, session.priorityAttempts[anchor]);
        int forwardBudget = Math.min(MAX_PRIORITY_ATOMS, BASE_PRIORITY_ATOMS + attempt * 12);
        int from = anchor;

        // Include the preceding uncommitted history, or reopen exactly one adjacent committed page.
        // This is the key seam repair: a lone atom such as "and" can now be regrouped with the page
        // before/after it instead of being trapped forever between immutable READY islands.
        if (from > 0 && session.states[from - 1] == READY) {
            int pageStart = session.pageFrom[from - 1];
            int pageEnd = session.pageTo[from - 1];
            if (pageStart >= 0 && pageEnd >= from - 1 && pageStart <= from - 1 &&
                    from - pageStart <= PRIORITY_HISTORY_ATOMS * 2) {
                from = pageStart;
            }
        } else {
            int history = 0;
            while (from > 0 && history < PRIORITY_HISTORY_ATOMS && session.states[from - 1] != READY) {
                from--;
                history++;
            }
        }

        int totalBudget = Math.min(
                MAX_PRIORITY_ATOMS + PRIORITY_HISTORY_ATOMS,
                forwardBudget + Math.max(0, anchor - from)
        );
        int to = growWindow(session, from, totalBudget, PRIORITY_CHARS, Long.MAX_VALUE, false);
        if (to <= anchor) to = Math.min(session.atoms.size(), anchor + 1);

        int[] repaired = expandRepairToAdjacentPageLocked(session, from, to, anchor,
                totalBudget + MAX_REPAIR_EXTRA_ATOMS);
        from = repaired[0];
        to = repaired[1];

        boolean reopened = invalidateReadyPagesLocked(session, from, to);
        if (reopened) {
            CaptionDiagnostics.mark(
                    session.context,
                    "PAGE_SEAM_REPAIR",
                    "当前字幕缺口与相邻已完成页存在语义接缝，已局部重开并联合规划"
            );
        }
        return claimLocked(session, from, to, anchor, true);
    }

    private static Request buildBackgroundLocked(Session session, int anchor) {
        long horizon = session.currentTimeMs + PREFETCH_AHEAD_MS;
        int first = -1;
        for (int i = Math.max(0, anchor); i < session.states.length; i++) {
            if (session.atoms.get(i).startMs > horizon) break;
            int state = session.states[i];
            boolean eligible = state == PENDING ||
                    (state == FAILED && session.backgroundAttempts[i] < MAX_BACKGROUND_ATTEMPTS);
            // PENDING means the model deliberately deferred an unfinished tail. It must never be
            // skipped merely because it already needed several larger windows; otherwise later READY
            // pages form an island and playback reaches a permanent blank segment.
            if (eligible && session.retryAfterMs[i] <= SystemClock.elapsedRealtime()) {
                first = i;
                break;
            }
        }
        if (first < 0) return null;
        int attempt = Math.max(0, session.backgroundAttempts[first]);
        int maxAtoms = Math.min(MAX_BACKGROUND_ATOMS, BASE_BACKGROUND_ATOMS + Math.min(4, attempt) * 18);
        int to = growWindow(session, first, maxAtoms, BACKGROUND_CHARS, horizon, true);
        return claimLocked(session, first, to, first, false);
    }

    private static int growWindow(
            Session session,
            int from,
            int maxAtoms,
            int maxChars,
            long horizon,
            boolean stopAtReady
    ) {
        int chars = 0;
        int to = from;
        while (to < session.atoms.size() && to - from < maxAtoms) {
            SourceAtomTimeline.Atom atom = session.atoms.get(to);
            if (to > from && atom.startMs > horizon) break;
            if (stopAtReady && to > from && session.states[to] == READY) break;
            String text = session.sourceTexts.get(to);
            if (to > from && chars + text.length() > maxChars) break;
            chars += text.length();
            to++;
        }
        return Math.max(from + 1, to);
    }

    private static int[] expandRepairToAdjacentPageLocked(
            Session session,
            int from,
            int to,
            int anchor,
            int maxSpan
    ) {
        int left = Math.max(0, from);
        int right = Math.min(session.states.length, Math.max(to, anchor + 1));
        int hardRight = Math.min(session.states.length, left + Math.max(1, maxSpan));

        // If the first READY page after the missing anchor intersects our planning window, include
        // that whole page. This lets the model move the seam rather than being forced to translate a
        // one- or two-atom fragment in isolation.
        for (int i = anchor; i < right; i++) {
            if (session.states[i] != READY) continue;
            int pf = session.pageFrom[i];
            int pt = session.pageTo[i];
            if (pf >= 0 && pt >= i && pf <= i) {
                right = Math.min(hardRight, Math.max(right, pt + 1));
            }
            break;
        }
        return new int[]{left, Math.max(left + 1, right)};
    }

    private static boolean invalidateReadyPagesLocked(Session session, int from, int to) {
        boolean changed = false;
        int i = Math.max(0, from);
        int limit = Math.min(session.states.length, to);
        while (i < limit) {
            if (session.states[i] != READY) {
                i++;
                continue;
            }
            int pf = session.pageFrom[i];
            int pt = session.pageTo[i];
            if (pf < 0 || pt < pf || pt >= session.states.length) {
                clearReadyAtomLocked(session, i);
                changed = true;
                i++;
                continue;
            }
            int clearFrom = Math.max(0, pf);
            int clearTo = Math.min(session.states.length - 1, pt);
            for (int j = clearFrom; j <= clearTo; j++) {
                clearReadyAtomLocked(session, j);
            }
            changed = true;
            i = Math.max(i + 1, clearTo + 1);
        }
        return changed;
    }

    private static void clearReadyAtomLocked(Session session, int index) {
        if (index < 0 || index >= session.states.length) return;
        session.states[index] = PENDING;
        session.displayTexts[index] = null;
        session.pageFrom[index] = -1;
        session.pageTo[index] = -1;
        session.retryAfterMs[index] = 0L;
        session.priorityAttempts[index] = 0;
        session.backgroundAttempts[index] = 0;
    }

    private static Request claimLocked(Session session, int from, int to, int focus, boolean priority) {
        if (from < 0 || to <= from || from >= session.states.length) return null;
        to = Math.min(to, session.states.length);
        List<Integer> owned = new ArrayList<>();
        for (int i = from; i < to; i++) {
            if (session.states[i] == READY) break;
            if (session.states[i] == PENDING || session.states[i] == FAILED) {
                session.states[i] = IN_FLIGHT;
                owned.add(i);
            }
        }
        if (owned.isEmpty()) return null;
        int effectiveTo = owned.get(owned.size() - 1) + 1;
        return new Request(from, effectiveTo, Math.max(from, Math.min(focus, effectiveTo - 1)), owned,
                priority, ++session.requestSequence);
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
                    "优先规划当前附近 " + (request.to - request.from) + " 个词级时间原子的完整语义页"
            );
        }
        try {
            List<SourceAtomTimeline.Atom> window;
            List<String> before;
            List<String> after;
            synchronized (session.lock) {
                window = new ArrayList<>(session.atoms.subList(request.from, request.to));
                before = context(session.sourceTexts,
                        Math.max(0, request.from - CONTEXT_BEFORE_ATOMS), request.from);
                after = context(session.sourceTexts, request.to,
                        Math.min(session.sourceTexts.size(), request.to + CONTEXT_AFTER_ATOMS));
            }
            SemanticPageApiClient.Result result = SemanticPageApiClient.plan(
                    window,
                    request.focus - request.from,
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
                    request.priority
            );
            finishPlan(session, request, result, started);
        } catch (Throwable failure) {
            failPlan(session, request, failure);
        }
    }

    private static List<String> context(List<String> source, int from, int to) {
        if (from >= to) return Collections.emptyList();
        return new ArrayList<>(source.subList(from, to));
    }

    private static void finishPlan(
            Session session,
            Request request,
            SemanticPageApiClient.Result result,
            long started
    ) {
        boolean firstReadyNow = false;
        int committed = 0;
        int pendingLocal = result.pendingFrom;
        synchronized (session.lock) {
            detachRequestLocked(session, request);
            if (request.cancelled || session.cancelled || !isCurrent(session)) {
                resetOwnedLocked(session, request);
                return;
            }

            for (SemanticPageApiClient.Page page : result.pages) {
                int globalFrom = request.from + page.from;
                int globalTo = request.from + page.to;
                if (globalFrom < 0 || globalTo >= session.states.length || globalTo < globalFrom) continue;
                for (int i = globalFrom; i <= globalTo; i++) {
                    session.displayTexts[i] = page.text;
                    session.pageFrom[i] = globalFrom;
                    session.pageTo[i] = globalTo;
                    session.states[i] = READY;
                    session.retryAfterMs[i] = 0L;
                    committed++;
                }
            }

            for (int index : request.owned) {
                if (index >= 0 && index < session.states.length && session.states[index] == IN_FLIGHT) {
                    session.states[index] = PENDING;
                }
            }

            if (pendingLocal < request.to - request.from) {
                int pendingGlobal = request.from + pendingLocal;
                int growIndex = Math.max(0, Math.min(request.focus, session.states.length - 1));
                if (request.priority) session.priorityAttempts[growIndex]++;
                else if (pendingGlobal >= 0 && pendingGlobal < session.states.length) session.backgroundAttempts[pendingGlobal]++;
                if (pendingGlobal >= 0 && pendingGlobal < session.states.length) {
                    session.retryAfterMs[pendingGlobal] = SystemClock.elapsedRealtime() +
                            (request.priority ? 60L : 140L);
                }
            }

            session.completedRequests++;
            int anchor = anchor(session.atoms, session.currentTimeMs);
            if (anchor >= 0 && anchor < session.states.length && session.states[anchor] == READY) {
                if (!session.firstReady) {
                    session.firstReady = true;
                    firstReadyNow = true;
                }
            }
        }
        request.connection = null;
        session.error = "";

        long took = Math.max(0L, SystemClock.elapsedRealtime() - started);
        if (firstReadyNow) {
            CaptionDiagnostics.mark(
                    session.context,
                    session.visible ? "FIRST_AI_READY" : "PREWARM_FIRST_READY",
                    (session.visible ? "首个 AI 完整语义页已显示" : "首个 AI 完整语义页已静默预热") +
                            "，用时 " + took + " ms"
            );
        } else if (request.priority) {
            CaptionDiagnostics.mark(
                    session.context,
                    "CURRENT_CUE_READY",
                    "当前源语言窗口规划完成，提交 " + committed + " 个词级原子，用时 " + took + " ms"
            );
        } else if (session.completedRequests <= 4 || session.completedRequests % 8 == 0 || took > 2_400L) {
            CaptionDiagnostics.mark(
                    session.context,
                    "CUE_BATCH_READY",
                    "第 " + session.completedRequests + " 个语义窗口完成，提交 " + committed +
                            " 个词级原子，用时 " + took + " ms"
            );
        }
        if (pendingLocal < request.to - request.from) {
            CaptionDiagnostics.mark(
                    session.context,
                    "PAGE_TAIL_DEFERRED",
                    "语义窗口保留 " + (request.to - request.from - pendingLocal) +
                            " 个观察原子，安全前缀已提交，尾部等待更完整后文"
            );
        }

        render(session, session.currentTimeMs);
        persistCacheAsync(session);
        markCompleteIfNeeded(session);
        schedule(session);
    }

    private static void failPlan(Session session, Request request, Throwable failure) {
        boolean retry = false;
        long delay = 0L;
        boolean relevant;
        synchronized (session.lock) {
            detachRequestLocked(session, request);
            if (request.cancelled || session.cancelled || !isCurrent(session)) {
                resetOwnedLocked(session, request);
                return;
            }
            int anchor = anchor(session.atoms, session.currentTimeMs);
            relevant = request.contains(anchor);
            for (int index : request.owned) {
                if (session.states[index] != IN_FLIGHT) continue;
                int attempts = request.priority
                        ? ++session.priorityAttempts[index]
                        : ++session.backgroundAttempts[index];
                int max = request.priority ? MAX_PRIORITY_ATTEMPTS : MAX_BACKGROUND_ATTEMPTS;
                if (attempts < max) {
                    session.states[index] = PENDING;
                    long itemDelay = request.priority ? 180L * attempts : 560L * attempts;
                    session.retryAfterMs[index] = SystemClock.elapsedRealtime() + itemDelay;
                    delay = Math.max(delay, itemDelay);
                    retry = true;
                } else {
                    session.states[index] = FAILED;
                }
            }
        }
        request.connection = null;
        String detail = CaptionDiagnostics.errorDetail(failure);
        if (relevant && !retry) session.error = "AI 翻译失败：" + detail;
        CaptionDiagnostics.mark(
                session.context,
                retry ? (request.priority ? "CURRENT_CUE_RETRY" : "CUE_BATCH_RETRY") : "API_ERROR",
                (retry ? "AI 完整语义页将扩窗重试：" : "AI 语义分页失败：") + detail
        );
        render(session, session.currentTimeMs);
        if (retry && delay > 0L) {
            long wait = delay;
            DISPLAY.postDelayed(() -> {
                if (isCurrent(session) && !session.cancelled) schedule(session);
            }, wait);
        } else {
            schedule(session);
        }
    }

    private static void reprioritizeAfterSeek(Session session, long timeMs) {
        Request cancelPriority = null;
        Request cancelBackground = null;
        synchronized (session.lock) {
            if (session.atoms.isEmpty()) return;
            int anchor = anchor(session.atoms, timeMs);
            for (int i = Math.max(0, anchor - 8); i < Math.min(session.states.length, anchor + 64); i++) {
                if (session.states[i] == FAILED) {
                    session.states[i] = PENDING;
                    session.priorityAttempts[i] = 0;
                    session.backgroundAttempts[i] = 0;
                    session.retryAfterMs[i] = 0L;
                }
            }
            if (session.priorityRequest != null && !session.priorityRequest.contains(anchor)) {
                cancelPriority = session.priorityRequest;
                session.priorityRequest = null;
                resetOwnedLocked(session, cancelPriority);
            }
            if (session.backgroundRequest != null && !session.backgroundRequest.contains(anchor)) {
                cancelBackground = session.backgroundRequest;
                session.backgroundRequest = null;
                resetOwnedLocked(session, cancelBackground);
            }
        }
        if (cancelPriority != null) cancelPriority.cancel();
        if (cancelBackground != null) cancelBackground.cancel();
        CaptionDiagnostics.mark(session.context, "SEEK_REPRIORITIZED", "已优先规划跳转位置附近的完整 AI 语义页");
        schedule(session);
    }

    private static void render(Session session, long timeMs) {
        if (!isCurrent(session) || session.cancelled || !session.visible) return;
        String text = "";
        boolean status = false;
        synchronized (session.lock) {
            if (!session.timelineReady || session.atoms.isEmpty()) {
                text = session.error.isEmpty()
                        ? session.targetLanguage.displayName + " AI 字幕准备中…"
                        : session.error;
                status = true;
            } else {
                int index = indexAtOrBefore(session.atoms, timeMs);
                if (index >= 0 && index < session.states.length && session.states[index] == READY) {
                    int from = session.pageFrom[index];
                    int to = session.pageTo[index];
                    if (from >= 0 && to >= from && to < session.atoms.size()) {
                        long start = session.atoms.get(from).startMs;
                        long end = session.atoms.get(to).endMs;
                        if (timeMs >= start && timeMs < end) {
                            text = session.displayTexts[index] == null ? "" : session.displayTexts[index].trim();
                        }
                    }
                }
            }
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

    private static int restoreCache(Session session) {
        byte[] data = DiskCaptionCache.get(session.context, session.cacheKey);
        if (data == null) return 0;
        try {
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (root.optInt("format", -1) != CACHE_FORMAT ||
                    root.optInt("atom_count", -1) != session.atoms.size()) return 0;
            JSONArray pages = root.optJSONArray("pages");
            if (pages == null) return 0;
            int restored = 0;
            synchronized (session.lock) {
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject value = pages.optJSONObject(i);
                    if (value == null) continue;
                    String text = value.optString("text", "").trim();
                    int from = value.optInt("from", -1);
                    int to = value.optInt("to", -1);
                    if (text.isEmpty() || from < 0 || to < from || to >= session.atoms.size()) continue;
                    for (int atom = from; atom <= to; atom++) {
                        if (session.states[atom] == READY) continue;
                        session.displayTexts[atom] = text;
                        session.pageFrom[atom] = from;
                        session.pageTo[atom] = to;
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
                JSONArray pages = new JSONArray();
                synchronized (session.lock) {
                    for (int i = 0; i < session.states.length; i++) {
                        if (session.states[i] != READY || session.pageFrom[i] != i || session.displayTexts[i] == null) continue;
                        int to = session.pageTo[i];
                        if (to < i || to >= session.states.length) continue;
                        pages.put(new JSONObject()
                                .put("from", i)
                                .put("to", to)
                                .put("text", session.displayTexts[i]));
                    }
                }
                JSONObject root = new JSONObject()
                        .put("format", CACHE_FORMAT)
                        .put("atom_count", session.atoms.size())
                        .put("pages", pages);
                DiskCaptionCache.put(session.context, session.cacheKey,
                        root.toString().getBytes(StandardCharsets.UTF_8));
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
        CaptionDiagnostics.mark(session.context, "TRANSLATION_COMPLETE", "整条视频完整 AI 语义页规划完成");
    }

    private static void detachRequestLocked(Session session, Request request) {
        if (session.priorityRequest == request) session.priorityRequest = null;
        if (session.backgroundRequest == request) session.backgroundRequest = null;
    }

    private static void resetOwnedLocked(Session session, Request request) {
        if (request == null) return;
        for (int index : request.owned) {
            if (index >= 0 && index < session.states.length && session.states[index] == IN_FLIGHT) {
                session.states[index] = PENDING;
            }
        }
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
        return latestVideoTimeMs + Math.round(Math.min(elapsed, MAX_CLOCK_EXTRAPOLATION_MS) * latestPlaybackRate);
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

    private static boolean sameTranslationConfig(DeepSeekConfig.Snapshot first, DeepSeekConfig.Snapshot second) {
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

    private static final class Request {
        final int from;
        final int to;
        final int focus;
        final List<Integer> owned;
        final boolean priority;
        final long sequence;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile Future<?> future;

        Request(int from, int to, int focus, List<Integer> owned, boolean priority, long sequence) {
            this.from = from;
            this.to = to;
            this.focus = focus;
            this.owned = Collections.unmodifiableList(new ArrayList<>(owned));
            this.priority = priority;
            this.sequence = sequence;
        }

        boolean contains(int index) {
            return index >= from && index < to;
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
        volatile int lastVisibleAnchor = -1;
        volatile String error = "";
        volatile String cacheKey = "";
        volatile String lastRenderSignature = "";
        volatile Future<?> sourceTask;
        volatile Request priorityRequest;
        volatile Request backgroundRequest;

        List<SourceAtomTimeline.Atom> atoms = Collections.emptyList();
        List<String> sourceTexts = Collections.emptyList();
        String[] displayTexts = new String[0];
        int[] pageFrom = new int[0];
        int[] pageTo = new int[0];
        int[] states = new int[0];
        int[] priorityAttempts = new int[0];
        int[] backgroundAttempts = new int[0];
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
