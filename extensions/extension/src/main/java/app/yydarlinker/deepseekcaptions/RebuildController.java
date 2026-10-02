package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.media.session.*;
import android.os.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;

/**
 * Generation-safe orchestration. Every block has one owner and an immutable accepted event plan.
 */
final class RebuildController {
  private static final java.util.concurrent.atomic.AtomicLong IDS =
      new java.util.concurrent.atomic.AtomicLong();
  static final int WAITING = 0, RUNNING = 1, READY = 2, FAILED = 3;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  /** Foreground translation slots. A new landing can use the free slot without waiting for the old one. */
  static final int MAX_FOCUS_CONCURRENCY = 2;
  /** Background prefetch slots. Background work never occupies a foreground slot. */
  static final int MAX_PREFETCH_CONCURRENCY = 2;
  /** Client translation budget: at most four translation requests in flight in total. */
  static final int MAX_TRANSLATION_CONCURRENCY = MAX_FOCUS_CONCURRENCY + MAX_PREFETCH_CONCURRENCY;
  static final long SEEK_STORM_WINDOW_MS = 3000, SEEK_STORM_PAUSE_MS = 5000;
  private static final ExecutorService SOURCE_IO = lane("CaptionSourceIO", 1);
  private static final ExecutorService PRIORITY_IO = lane("CaptionPriorityIO", MAX_FOCUS_CONCURRENCY);
  private static final ExecutorService PREFETCH_IO = lane("CaptionPrefetchIO", MAX_PREFETCH_CONCURRENCY);

  private static ExecutorService lane(String name, int concurrency) {
    return Executors.newFixedThreadPool(concurrency, r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    });
  }

  static void dispatch(boolean priority, Runnable work) {
    (priority ? PRIORITY_IO : PREFETCH_IO).submit(work);
  }
  private static final RebuildClock CLOCK = new RebuildClock();
  private static volatile Session active;
  private static volatile String video = "";
  private static volatile long publicationEpoch;
  private static WeakReference<Activity> activity = new WeakReference<>(null);
  private static boolean tickPosted;
  private static volatile boolean compact;
  private static long restoreUntil;

  /** Preview reads the already-bound target; no UI-locale guess or mutable language setting. */
  static CaptionRenderSpec previewRenderSpec() {
    Session s=active;
    return s==null || s.cancelled ? CaptionRenderSpec.LEGACY : s.languageContext.renderSpec;
  }

  static final class Session {
    final long id = IDS.incrementAndGet();
    final Context context;
    final String owner, identity, target;
    final DeepSeekConfig.Snapshot config;
    final boolean sourceOnly;
    final CaptionLanguageContext languageContext;
    final long activatedAtMs = SystemClock.elapsedRealtime();
    volatile String url;
    volatile boolean visible, cancelled, retired, loading, terminal;
    volatile long sourceRetry, position, providerRetry;
    long lastSeekAt = -1, prefetchPausedUntil;
    volatile long pausedDisplayPosition = -1;
    private PlaybackState pausedHookState;
    volatile String status = "";
    int sourceFailures, repairCount;
    volatile int generation;
    volatile long renderRevision;
    volatile long appliedRenderRevision;
    volatile long renderSubmission;
    final RebuildCache.Publication publication = new RebuildCache.Publication();
    boolean[] cacheReading;
    Job sourceJob;
    String cacheKey = "", lastShown = "";
    String fallbackReason = "";
    long fallbackStart = -1;
    boolean everReady;
    String displayedEvent = "", withheldEvent = "";
    RawCaptionSource.Source raw;
    RebuildSource source;
    List<RebuildPlanner.Block> blocks;
    RebuildProtocol.Plan[] plans, pendingPlans;
    int[] states, attempts;
    long[] retryAt;
    String[] reasons;
    Job[] jobs;
    boolean[] cacheChecked;
    /**
     * The single newest foreground request that has been selected but not handed to a lane yet. It exists
     * only while both foreground slots are busy, and a later seek replaces it instead of queueing behind it.
     */
    Job pendingFocus;
    /** Diagnostics: how many pending foreground requests have been replaced or retired this session. */
    int replacedFocus;
    final Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<>());

    Session(
        Context c,
        String u,
        String o,
        String key,
        String target,
        DeepSeekConfig.Snapshot cfg,
        boolean original,
        boolean show) {
      this(c,u,o,key,target,cfg,original,show,CaptionLanguageContext.observe(u,target));
    }
    Session(Context c,String u,String o,String key,String target,DeepSeekConfig.Snapshot cfg,
            boolean original,boolean show,CaptionLanguageContext context) {
      this.context = c;
      url = u;
      owner = o;
      identity = key;
      this.target = target;
      languageContext = context;
      config = cfg;
      sourceOnly = original;
      visible = show;
    }

    void cancel() {
      publication.revoke(true);
      finishRetirement();
      List<HttpURLConnection> disconnect;
      synchronized (connections) {
        disconnect = new ArrayList<>(connections);
        connections.clear();
      }
      for (HttpURLConnection c : disconnect)
        try { c.disconnect(); } catch (Exception ignored) { }
    }

    /** Revoke publication on a scope change; an already sent HTTP may finish in its own scope. */
    void retire() {
      publication.revoke(false);
      finishRetirement();
    }

    private void finishRetirement() {
      publication.drain();
      List<Notice> notices = new ArrayList<>();
      synchronized (this) {
        if (!cancelled) generation++;
        cancelled = true;
        retired = publication.finishSent();
        endFallback(this, position, "session_end", notices);
        pausedDisplayPosition = -1;
        pausedHookState = null;
        pendingFocus = null;
        if (pendingPlans != null) Arrays.fill(pendingPlans, null);
        sourceJob = null;
        loading = false;
        renderRevision++;
      }
      flush(this, notices);
    }

    void noteSeek(long now) {
      List<Notice> notices = new ArrayList<>();
      synchronized (this) { noteSeek(now, notices); }
      flush(this, notices);
    }

    private void noteSeek(long now, List<Notice> notices) {
      if (lastSeekAt >= 0 && now - lastSeekAt <= SEEK_STORM_WINDOW_MS) {
        prefetchPausedUntil = now + SEEK_STORM_PAUSE_MS;
        notice(notices, "REBUILD_PREFETCH_PAUSED",
            "session=" + id + ";pause_ms=" + SEEK_STORM_PAUSE_MS + ";until_ms=" + prefetchPausedUntil);
      }
      lastSeekAt = now;
    }
  }

  static final class Job implements DeepSeekApiClient.RequestControl {
    final long traceId = IDS.incrementAndGet();
    final Session session;
    final CaptionLanguageContext languageContext;
    final int index;
    final boolean priority;
    volatile HttpURLConnection connection;
    /**
     * {@code dispatched} means the job holds a lane slot (or is about to), {@code sent} means the provider
     * request body has already been written. Only dispatched jobs count against the translation budget, and
     * only the not-yet-sent newest foreground job may be replaced by a later seek.
     */
    volatile boolean cancelled, sent, dispatched;
    final long queuedAt = SystemClock.elapsedRealtime();
    long slotWaitMs, httpStartedAt, networkMs;
    int httpRounds;

    Job(Session s, int i) {
      this(s, i, false);
    }

    Job(Session s, int i, boolean focus) {
      session = s;
      languageContext = s.languageContext;
      index = i;
      priority = focus;
    }

    public boolean isCancelled() {
      return cancelled || (!current(session) && !(sent && session.publication.finishSent()));
    }

    public void onConnection(HttpURLConnection c) {
      if (connection != null) session.connections.remove(connection);
      connection = c;
      if (c != null) {
        session.connections.add(c);
        if (isCancelled()) c.disconnect();
      }
    }

    public void onRequestBodySent() {
      sent = true;
    }

    void trace(String stage,String detail) {
      long now = SystemClock.elapsedRealtime();
      if (stage.equals("REBUILD_HTTP_BEGIN")) { httpStartedAt = now; httpRounds++; }
      if (stage.equals("REBUILD_HTTP_RESPONSE") || stage.equals("REBUILD_HTTP_FAILURE")) {
        long roundTrip = now - httpStartedAt;
        networkMs += roundTrip;
        detail += ";network_round_trip_ms=" + roundTrip;
      }
      CaptionDiagnostics.mark(session.context,stage,"session="+session.id+";request="+traceId+";block="+index+";"+detail);
    }

    public void onQualityEvidence(org.json.JSONObject source, String response, String metadata) {
      if (!DeepSeekConfig.displayTextDebugEnabled(session.context)) return;
      org.json.JSONObject evidence = source;
      try {
        evidence = new org.json.JSONObject(source.toString());
        if (index >= 0 && session.blocks != null) {
          RebuildPlanner.Block block = session.blocks.get(index);
          org.json.JSONArray times = new org.json.JSONArray();
          for (int i = block.from; i <= block.to; i++) {
            RebuildSource.Word w = session.source.words.get(i);
            times.put(
                new org.json.JSONArray()
                    .put(i)
                    .put(w.start)
                    .put(w.end)
                    .put(w.cue)
                    .put(w.precision));
          }
          evidence.put("diagnostic_only_source_times", times);
          evidence.put("diagnostic_only_request_purpose", priority ? "focus" : "prefetch");
        }
      } catch (Exception ignored) {
      }
      CaptionQualityTrace.record(
          session.context,
          session.config.apiKey,
          traceId,
          evidence,
          response,
          metadata + ";session=" + session.id + ";block=" + index);
    }
  }

  static boolean current(Session s) {
    String owner = video;
    return s != null && active == s && s.publication.isOpen() && !s.cancelled
        && (owner.isEmpty() || owner.equals(s.owner));
  }

  static boolean visible() {
    Session s = active;
    return current(s) && s.visible;
  }

  static boolean ownsNativeTrack() {
    Session s = active;
    return current(s) && !s.sourceOnly;
  }

  static String activeUrl() {
    Session s = active;
    return current(s) ? s.url : "";
  }

  static void activity(Activity a) {
    activity = new WeakReference<>(a);
    CaptionOverlay.setActivity(a);
    tick();
  }

  static void video(String id) {
    if (id == null || id.trim().isEmpty()) return;
    id = id.trim();
    boolean changed;
    Session previous = null;
    long epoch;
    synchronized (RebuildController.class) {
      changed = !id.equals(video);
      video = id;
      if (changed && active != null && !id.equals(active.owner)) {
        previous = active;
        previous.publication.revoke(true);
        active = null;
        publicationEpoch++;
      }
      epoch = publicationEpoch;
    }
    if (previous != null) {
      previous.cancel();
      clear(epoch);
      CaptionMusicSuppressor.kick();
    }
    if (changed) {
      CLOCK.reset(SystemClock.elapsedRealtime());
      Activity currentActivity = activity.get();
      if (currentActivity != null)
        CaptionDiagnostics.mark(currentActivity, "REBUILD_STARTUP_PHASE",
            "phase=video_loaded;elapsed_ms=" + SystemClock.elapsedRealtime() + ";video=" + id);
    }
    SemanticCaptionTimeline.onVideoId(id);
  }

  static void stop() {
    Session s;
    long epoch;
    synchronized (RebuildController.class) {
      s = active;
      if (s != null) s.publication.revoke(true);
      active = null;
      epoch = ++publicationEpoch;
    }
    if (s != null) s.cancel();
    clear(epoch);
    CaptionMusicSuppressor.kick();
  }

  private static void clear(long epoch) {
    CaptionOverlay.clear(() -> publicationEpoch == epoch
        && (active == null || active.renderRevision == 0));
  }

  private static final class Notice {
    final String stage, detail;
    Notice(String stage, String detail) { this.stage = stage; this.detail = detail; }
  }

  private static void notice(List<Notice> notices, String stage, String detail) {
    notices.add(new Notice(stage, detail));
  }

  private static void flush(Session s, List<Notice> notices) {
    for (Notice n : notices) CaptionDiagnostics.mark(s.context, n.stage, n.detail);
  }

  static void player(String type) {
    String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
    boolean small =
        t.contains("MINIM")
            || t.contains("HIDDEN")
            || t.contains("DISMISSED")
            || t.contains("PICTURE_IN_PICTURE");
    if (compact && !small) restoreUntil = SystemClock.elapsedRealtime() + 3000;
    compact = small && !CaptionSurface.isShorts();
    CaptionOverlay.setPlayerType(type);
  }

  static String restore(String url) {
    Session s = active;
    if (!current(s)
        || s.sourceOnly
        || !s.visible
        || !CaptionChoice.translates()
        || (!compact && SystemClock.elapsedRealtime() > restoreUntil)) return url;
    String owner = PageCaptionController.videoIdFromUrl(url);
    return DeepSeekCaptionHook.isYouTubeTimedTextUrl(url)
            && (owner.isEmpty() || owner.equals(s.owner))
        ? TargetLanguage.withCode(url, s.target)
        : url;
  }

  static void observe(String url) {
    /* NativeCaptionBridge owns explicit selection. Network prefetch is not a user command. */
  }

  static void refresh(Context c) {
    Session s = active;
    if (s == null) return;
    boolean show = s.visible, original = s.sourceOnly;
    String url = s.url;
    stop();
    if (DeepSeekConfig.enabled(c)) activate(c, url, original, show);
  }

  static void prewarm(Context c, String url) {
    if (c == null
        || !CaptionChoice.isOn()
        || !CaptionChoice.translates()
        || !DeepSeekConfig.isReady(c)) return;
    if (active != null) return;
    String target = DeepSeekConfig.defaultTargetLanguage(c);
    if (!target.isEmpty()) activate(c, TargetLanguage.withCode(url, target), false, false);
  }

  static void activate(Context c, String url, boolean original, boolean show) {
    if (c == null || !DeepSeekCaptionHook.isYouTubeTimedTextUrl(url)) return;
    DeepSeekConfig.Snapshot cfg = DeepSeekConfig.load(c);
    if (!cfg.enabled) return;
    String owner = PageCaptionController.videoIdFromUrl(url);
    if (owner.isEmpty()) owner = video;
    if (owner.isEmpty() || !video.isEmpty() && !owner.equals(video)) return;
    TargetLanguage language =
        original ? TargetLanguage.fromCode(CaptionChoice.language()) : TargetLanguage.fromUrl(url);
    String target = language == null ? (original ? "source" : "") : language.code;
    if (target.isEmpty()) return;
    CaptionLanguageContext languageContext=CaptionLanguageContext.observe(url,target);
    if(!original && languageContext.targetCode.equals("UNKNOWN"))return;
    String key =
        RebuildCache.hash(
            CaptionEngine.sourceCaptionUrl(url)
                    .replaceAll("([?&])(?:expire|signature|sig)=[^&]*", "$1")
                + "|"
                + languageContext.fingerprint(cfg)
                + "|"
                + RebuildCache.hash(cfg.apiKey)
                + "|"
                + target
                + "|"
                + original);
    // Track identity uses cache identity, not an expiring signature. All preparation is lock-free.
    try {
      key =
            SourceCaptionCache.key(CaptionEngine.sourceCaptionUrl(url))
                + "|"
                + languageContext.fingerprint(cfg)
                + "|"
                + RebuildCache.hash(cfg.apiKey)
                + "|"
                + target
                + "|"
                + original;
    } catch (Exception ignored) {
    }
    if(!languageContext.canApplyEnglishToChinese)key += "|"+languageContext.scope();
    Session candidate = new Session(c.getApplicationContext(), url, owner, key, target, cfg,
        original, show, languageContext);
    boolean unavailable = !original && !cfg.ready();
    String unavailableStatus = unavailable ? CaptionStrings.get(c, "configure_api") : "";
    Session s, previous = null;
    long epoch;
    synchronized (RebuildController.class) {
      if (!video.isEmpty() && !video.equals(owner)) return;
      Session prev = active;
      if (current(prev) && prev.identity.equals(key)
          && prev.languageContext.scope().equals(languageContext.scope())) {
        s = prev;
      } else {
        previous = prev;
        if (prev != null) prev.publication.revoke(false);
        s = candidate;
        active = s;
        publicationEpoch++;
      }
      epoch = publicationEpoch;
    }
    if (previous != null) previous.retire();
    long initialPosition = position(s);
    synchronized (s) {
      if (!current(s)) return;
      s.url = url;
      s.visible |= show;
      if (s.source == null && s.sourceFailures > 0) {
        s.terminal = false;
        s.sourceRetry = 0;
      }
      s.position = initialPosition;
      if (unavailable) {
        s.terminal = true;
        s.status = unavailableStatus;
      }
    }
    if (s == candidate) {
      CaptionDiagnostics.mark(s.context, "LANGUAGE_PROFILE_BOUND",
          "session=" + s.id + ";" + s.languageContext.diagnosticFields());
      clear(epoch);
    }
    CaptionDiagnostics.mark(
        c,
        "CAPTION_REBUILD",
        "engine="+RebuildProtocol.VERSION+";session="+s.id+";video="+s.owner+";"+(original ? "source_passthrough" : "single_pass_events;source_owned_time;no_legacy_core"));
    startup(s, "engine_session_created", "visible=" + s.visible);
    CaptionMusicSuppressor.forceNativeRendererScan();
    kick(s);
    scheduleTick();
  }

  static void time(long ms) {
    long now = SystemClock.elapsedRealtime();
    boolean seek = CLOCK.update(ms, now);
    Session s = active;
    if (!current(s)) return;
    PlaybackState state = playbackState();
    long presentation = CLOCK.presentation(now);
    List<Notice> notices = new ArrayList<>();
    List<HttpURLConnection> disconnect = new ArrayList<>();
    int generation;
    synchronized (s) {
      if (!current(s)) return;
      if(seek){endFallback(s,s.position,"seek",notices); notice(notices,"REBUILD_SEEK","session="+s.id+";from="+s.position+";to="+ms); }
      s.position = presentation;
      // Explicit hooks take precedence over paused media jitter, including a small rewind.
      s.pausedDisplayPosition = state != null && state.getState() == PlaybackState.STATE_PAUSED
          ? Math.max(0, ms) : -1;
      s.pausedHookState = s.pausedDisplayPosition >= 0 ? state : null;
      if (seek) {
        s.noteSeek(now, notices);
        s.generation++;
        s.lastShown = "";
        s.displayedEvent=""; s.withheldEvent="";
        if (s.jobs != null)
          for (Job j : s.jobs)
            if (j != null && !j.sent && s.blocks != null && !covers(s.blocks.get(j.index), ms)) {
              j.cancelled = true;
              if (j.connection != null) disconnect.add(j.connection);
            }
      }
      generation = s.generation;
    }
    flush(s, notices);
    for (HttpURLConnection c : disconnect) c.disconnect();
    if (seek) CaptionOverlay.hide(() -> current(s) && s.generation == generation);
    CaptionOverlay.position(displayPosition(s));
    kick(s);
    scheduleTick();
  }

  private static PlaybackState playbackState() {
    Activity a = activity.get();
    try {
      MediaController c = a == null ? null : a.getMediaController();
      return c == null || !a.getPackageName().equals(c.getPackageName())
          ? null : c.getPlaybackState();
    } catch (Exception ignored) {
    }
    return null;
  }

  private static long position() {
    return position(null);
  }

  private static long position(Session s) {
    long now = SystemClock.elapsedRealtime();
    PlaybackState state = playbackState();
    long reported = state == null ? -1 : state.getPosition();
    long regular = CLOCK.position(now, reported,
        state == null ? 0 : state.getLastPositionUpdateTime(),
        state == null ? 0 : state.getPlaybackSpeed(),
        state == null ? 0 : state.getState());
    if (s != null) {
      synchronized (s) {
        if (state == null || state.getState() != PlaybackState.STATE_PAUSED) {
          s.pausedDisplayPosition = -1;
          s.pausedHookState = null;
        } else {
          // Read the raw report only for the first pause and the real-change safety valve.
          // A report already seen by an explicit hook cannot undo that hook's seek.
          boolean oldHookReport = s.pausedHookState != null
              && reported == s.pausedHookState.getPosition()
              && state.getLastPositionUpdateTime() == s.pausedHookState.getLastPositionUpdateTime();
          if (!oldHookReport) s.pausedHookState = null;
          if (s.pausedDisplayPosition >= 0 && reported >= 0
              && !oldHookReport
              && Math.abs(reported - s.pausedDisplayPosition) > 1500)
            s.pausedDisplayPosition = -1;
          if (s.pausedDisplayPosition < 0)
            s.pausedDisplayPosition = reported >= 0 ? reported : regular;
        }
      }
    }
    return regular;
  }

  private static long displayPosition(Session s) {
    long frozen = s.pausedDisplayPosition;
    return frozen >= 0 ? frozen : s.position;
  }

  private static boolean paused() {
    try {
      Activity a = activity.get();
      MediaController c = a == null ? null : a.getMediaController();
      PlaybackState s = c == null ? null : c.getPlaybackState();
      return s != null && s.getState() == PlaybackState.STATE_PAUSED;
    } catch (Exception e) {
      return false;
    }
  }

  private static void scheduleTick() {
    synchronized (RebuildController.class) {
      if (tickPosted || active == null) return;
      tickPosted = true;
    }
    MAIN.postDelayed(RebuildController::tick, 80);
  }

  private static void tick() {
    synchronized (RebuildController.class) {
      tickPosted = false;
    }
    Session s = active;
    if (!current(s)) return;
    // Surface detection must also run while a former miniplayer has hidden the overlay.
    CaptionOverlay.refreshSurface();
    long observed = position(s);
    synchronized (s) {
      if (!current(s)) return;
      s.position = observed;
    }
    CaptionOverlay.position(displayPosition(s));
    kick(s);
    scheduleTick();
  }

  private static void kick(Session s) {
    if (!current(s)) return;
    CaptionMusicSuppressor.kick();
    boolean load = false;
    synchronized (s) {
      if (!current(s)) return;
      if (s.source == null
          && !s.loading
          && !s.terminal
          && SystemClock.elapsedRealtime() >= s.sourceRetry) {
        s.loading = true;
        s.sourceJob = new Job(s, -1, false);
        load = true;
      }
    }
    if (load) {
      startup(s, "source_worker_queued", "");
      SOURCE_IO.submit(() -> load(s));
    }
    if (s.blocks != null && !s.terminal && !s.sourceOnly && s.visible) schedule(s);
    render(s);
  }

  private static void load(Session s) {
    Job control;
    synchronized (s) {
      if (!current(s) || s.sourceJob == null) return;
      control = s.sourceJob;
    }
    try {
      startup(s, "source_load_start", "");
      RawCaptionSource.Source raw =
          RawCaptionSource.load(s.context, s.url, false, !s.sourceOnly, control);
      if (!current(s)) return;
      long availablePosition = position(s);
      synchronized (s) {
        if (!current(s) || s.sourceJob != control) return;
        s.raw = raw;
        s.position = availablePosition;
      }
      startup(s, "source_available", "position_ms=" + s.position);
      // The parsed source cues are already time-bounded. Show the current one while
      // rebuilding/planning runs; translation requests still wait for the full plan.
      render(s);
      long phase=SystemClock.elapsedRealtime();
      RebuildSource source = RebuildSource.read(raw.body, raw.document);
      CaptionDiagnostics.mark(s.context,"REBUILD_SOURCE_PHASE","phase=rebuild;ms="+(SystemClock.elapsedRealtime()-phase));
      phase=SystemClock.elapsedRealtime();
      if (!s.sourceOnly) {
        int precise = 0;
        for (RebuildSource.Word w : source.words)
          if (w.precision != RebuildSource.Precision.ESTIMATED) precise++;
        if (precise * 10 < source.words.size() * 8)
          try {
            RawCaptionSource.Source ref = RawCaptionSource.reference(s.context, s.url, control);
            if (ref != null) source = source.align(RebuildSource.read(ref.body, ref.document));
          } catch (Exception ignored) {
            RawCaptionSource.checkActive(control);
          }
      }
      CaptionDiagnostics.mark(s.context,"REBUILD_SOURCE_PHASE","phase=reference;ms="+(SystemClock.elapsedRealtime()-phase));
      phase=SystemClock.elapsedRealtime();
      List<RebuildPlanner.Block> blocks = RebuildPlanner.plan(source,s.languageContext);
      CaptionDiagnostics.mark(s.context,"REBUILD_SOURCE_PHASE","phase=planner;ms="+(SystemClock.elapsedRealtime()-phase));
      phase=SystemClock.elapsedRealtime();
      String key = RebuildCache.identity(source, s.config, s.target,s.languageContext);
      RebuildProtocol.Plan[] plans = new RebuildProtocol.Plan[blocks.size()];
      int[] states = new int[blocks.size()];
      int restored = 0;
      boolean[] checked=new boolean[blocks.size()];
      int cacheGeneration = s.generation;
      long focus=position();int focusIndex=0;
      for(RebuildPlanner.Block b:blocks)if(b.start<=focus)focusIndex=b.index;
      for (RebuildPlanner.Block b : blocks) {
        if(b.index!=focusIndex && b.index!=focusIndex+1)continue;
        checked[b.index]=true;
        if (!current(s)) return;
        plans[b.index] = s.sourceOnly ? null : RebuildCache.read(s.context, key, source, b,s.languageContext);
        if (plans[b.index] != null) {
          states[b.index] = READY;
          restored++;
        }
      }
      CaptionDiagnostics.mark(s.context,"REBUILD_SOURCE_PHASE","phase=cache;ms="+(SystemClock.elapsedRealtime()-phase));
      long readyPosition = position(s);
      RebuildProtocol.Plan[] pending = new RebuildProtocol.Plan[plans.length];
      int[] attempts = new int[blocks.size()];
      long[] retryAt = new long[blocks.size()];
      String[] reasons = new String[blocks.size()];
      Arrays.fill(reasons, "");
      Job[] jobs = new Job[blocks.size()];
      synchronized (s) {
        if (!current(s) || s.sourceJob != control) return;
        if (cacheGeneration != s.generation) {
          Arrays.fill(plans, null);
          Arrays.fill(states, WAITING);
          Arrays.fill(checked, false);
          restored = 0;
        }
        s.source = source;
        s.cacheKey = key;
        s.plans = plans;
        s.pendingPlans = pending;
        s.states = states;
        s.attempts = attempts;
        s.retryAt = retryAt;
        s.reasons = reasons;
        s.jobs = jobs;
        s.cacheChecked=checked;
        s.loading = false;
        s.sourceJob = null;
        s.status = "";
        s.everReady = restored > 0;
        s.blocks = blocks;
        s.position = readyPosition;
      }
      startup(s, "engine_ready", "position_ms=" + s.position);
      if (!blocks.isEmpty() && s.position >= blocks.get(0).end)
        CaptionDiagnostics.mark(s.context, "REBUILD_STARTUP_SKIP",
            "session=" + s.id + ";reason=skipped_due_to_late_ready;first_end_ms="
                + blocks.get(0).end + ";position_ms=" + s.position);
      int precise = 0;
      for (RebuildSource.Word w : source.words)
        if (w.precision != RebuildSource.Precision.ESTIMATED) precise++;
      CaptionDiagnostics.mark(
          s.context,
          "REBUILD_SOURCE_READY",
          "words="
              + source.words.size()
              + ";measured_or_aligned="
              + precise
              + ";estimated="
              + (source.words.size() - precise)
              + ";coarse_reconstructed="
              + source.coarseCueReconstructed
              + ";coarse_cues="
              + source.coarseCueCount
              + ";blocks="
              + blocks.size()
              + ";cache_hits="
              + restored);
      TokenCostAudit.recordUnitCacheOutcome(Math.min(2,blocks.size()-focusIndex), restored);
      RawCaptionSource.publishSharedTimeline(s.owner, raw.document.cues());
      render(s);
      kick(s);
    } catch (Exception e) {
      if (!current(s)) return;
      SourceRecoveryPolicy.Failure f = SourceRecoveryPolicy.classify(e);
      String failureStatus = CaptionStrings.get(s.context,
          !f.retryable ? "source_unavailable" : "source_retry");
      int failureCount;
      synchronized (s) {
        if (!current(s) || s.sourceJob != control) return;
        s.loading = false;
        s.sourceJob = null;
        s.sourceFailures++;
        s.terminal = !f.retryable;
        s.sourceRetry =
            SystemClock.elapsedRealtime()
                + SourceRecoveryPolicy.delay(s.sourceFailures, f.retryAfterMs);
        s.status = failureStatus;
        failureCount = s.sourceFailures;
      }
      CaptionDiagnostics.mark(
          s.context, "REBUILD_SOURCE_ERROR", f.category + ";attempt=" + failureCount);
      render(s);
    }
  }

  private static int blockAt(Session s, long time) {
    if (s.blocks == null) return -1;
    int lo = 0, hi = s.blocks.size() - 1;
    while (lo <= hi) {
      int m = (lo + hi) >>> 1;
      if (s.blocks.get(m).start <= time) lo = m + 1;
      else hi = m - 1;
    }
    return hi;
  }

  private static boolean covers(RebuildPlanner.Block b, long time) {
    return time >= b.start && time < b.end;
  }

  /** The block the user is on right now, with the same rounding the planner already used. */
  private static int currentIndex(Session s) {
    int index = blockAt(s, s.position);
    if (index < 0) return 0;
    if (!covers(s.blocks.get(index), s.position) && index + 1 < s.blocks.size()) index++;
    return index;
  }

  /** Jobs that hold a lane slot and have not finished yet. Unsent pending work is deliberately excluded. */
  private static int dispatched(Session s, boolean focus) {
    return dispatched(s, focus, null);
  }

  /** Same count, optionally ignoring one job that is releasing its slot right now. */
  private static int dispatched(Session s, boolean focus, Job released) {
    int count = 0;
    if (s.jobs != null)
      for (Job j : s.jobs)
        if (j != null && j != released && j.dispatched && j.priority == focus) count++;
    return count;
  }

  /**
   * Hands a selected job to its lane. Attempt and repair accounting happens exactly here, so a job that is
   * replaced before it is dispatched never consumes a translation attempt or a session repair.
   */
  private static void markDispatched(Session s, Job job) {
    if (s.attempts[job.index] > 0) s.repairCount++;
    s.attempts[job.index]++;
    s.states[job.index] = RUNNING;
    job.dispatched = true;
  }

  /**
   * Drops the retained newest foreground request without dispatching it: the block goes back to WAITING so
   * the next landing can reuse it, and no attempt or repair quota is charged.
   */
  private static void retirePendingFocus(Session s, String reason, int nextBlock, List<Notice> notices) {
    Job pending = s.pendingFocus;
    if (pending == null) return;
    s.pendingFocus = null;
    s.replacedFocus++;
    if (s.jobs[pending.index] == pending) s.jobs[pending.index] = null;
    if (s.states[pending.index] == RUNNING) {
      s.states[pending.index] = WAITING;
      s.retryAt[pending.index] = SystemClock.elapsedRealtime() + 500;
    }
    notice(notices, "REBUILD_FOCUS_PENDING_REPLACED",
        "session=" + s.id + ";request=" + pending.traceId + ";block=" + pending.index
            + ";reason=" + reason + ";next_block=" + nextBlock
            + ";attempts_consumed=0;session_repairs_consumed=0"
            + ";replaced_total=" + s.replacedFocus);
  }

  private static final class CacheLookup {
    final int generation;
    final RebuildSource source;
    final RebuildPlanner.Block block;
    final String key;
    final Job job;
    CacheLookup(Session s, RebuildPlanner.Block block) {
      generation = s.generation; source = s.source; key = s.cacheKey;
      this.block = block; job = s.jobs[block.index];
    }
  }

  private static void restoreCandidates(Session s, long now, boolean playbackAhead) {
    List<CacheLookup> lookups = new ArrayList<>();
    synchronized (s) {
      if (!current(s) || s.blocks == null || s.blocks.isEmpty() || s.cacheChecked == null
          || now < s.providerRetry) return;
      if (s.cacheReading == null) s.cacheReading = new boolean[s.blocks.size()];
      int index = currentIndex(s);
      boolean ahead = playbackAhead && now >= s.prefetchPausedUntil
          && (s.plans[index] != null || s.states[index] == FAILED
              || s.jobs[index] != null && s.jobs[index].sent);
      int prefetch = dispatched(s, false);
      for (int i = index; i < s.blocks.size(); i++) {
        RebuildPlanner.Block b = s.blocks.get(i);
        if (b.start > s.position + 30000 || i > index
            && (!ahead || !s.everReady || prefetch >= MAX_PREFETCH_CONCURRENCY)) break;
        if (s.states[i] == WAITING && s.retryAt[i] <= now && s.plans[i] == null
            && !s.cacheChecked[i] && !s.cacheReading[i]) {
          s.cacheReading[i] = true;
          lookups.add(new CacheLookup(s, b));
        }
      }
    }
    for (CacheLookup lookup : lookups) {
      RebuildProtocol.Plan cached = RebuildCache.read(s.context, lookup.key, lookup.source,
          lookup.block, s.languageContext);
      boolean admitted = false;
      synchronized (s) {
        s.cacheReading[lookup.block.index] = false;
        if (current(s) && s.generation == lookup.generation && s.source == lookup.source
            && s.cacheKey.equals(lookup.key) && s.jobs[lookup.block.index] == lookup.job) {
          s.cacheChecked[lookup.block.index] = true;
          if (cached != null) {
            s.plans[lookup.block.index] = cached;
            s.states[lookup.block.index] = READY;
            s.everReady = true;
            admitted = true;
          }
        }
      }
      TokenCostAudit.recordUnitCacheOutcome(1, admitted ? 1 : 0);
      if (admitted) CaptionDiagnostics.mark(s.context, "REBUILD_CACHE_RESTORED",
          "session=" + s.id + ";block=" + lookup.block.index + ";network_calls=0"
              + ";path=memory_then_disk_before_network");
    }
  }

  private static void schedule(Session s) {
    List<Job> start = new ArrayList<>();
    List<Notice> notices = new ArrayList<>();
    long now = SystemClock.elapsedRealtime();
    boolean playbackAhead = !paused() && CLOCK.fresh(now);
    restoreCandidates(s, now, playbackAhead);
    synchronized (s) {
      if (!current(s) || s.blocks == null || s.blocks.isEmpty() || now < s.providerRetry) return;
      int index = currentIndex(s);
      int focusDispatched = dispatched(s, true);
      int prefetchDispatched = dispatched(s, false);
      // A retained pending request is only valid for the landing it was chosen for.
      if (s.pendingFocus != null
          && (s.pendingFocus.cancelled || s.pendingFocus.index != index)) {
        retirePendingFocus(s,
            s.pendingFocus.cancelled ? "cancelled_by_seek" : "superseded_by_seek", index, notices);
      }
      // D2: the newest landing takes the free foreground slot immediately when one exists.
      if (s.pendingFocus != null && focusDispatched < MAX_FOCUS_CONCURRENCY) {
        Job promote = s.pendingFocus;
        s.pendingFocus = null;
        markDispatched(s, promote);
        start.add(promote);
        focusDispatched++;
        notice(notices, "REBUILD_FOCUS_PENDING_PROMOTED",
            "session=" + s.id + ";request=" + promote.traceId + ";block=" + promote.index
                + ";focus_in_flight=" + focusDispatched);
      }
      boolean focusUnderWay =
          s.plans[index] != null
              || s.states[index] == FAILED
              || (s.jobs[index] != null && s.jobs[index].sent);
      // D4: the prefetch budget, not the mere existence of another in-flight job, bounds look-ahead.
      boolean allowAhead =
          focusUnderWay
              && now >= s.prefetchPausedUntil
              && playbackAhead;
      for (int i = index; i < s.blocks.size(); i++) {
        RebuildPlanner.Block b = s.blocks.get(i);
        if (b.start > s.position + 30000) break;
        if (i > index && !allowAhead) break;
        if (i > index && prefetchDispatched >= MAX_PREFETCH_CONCURRENCY) break;
        boolean focus = i == index;
        if (!focus && !s.everReady) break;
        if (s.states[i] != WAITING || s.retryAt[i] > now) {
          // D3: an existing job for this block is reused, whichever lane it came from.
          if (s.jobs[i] != null && s.plans[i] == null && s.states[i] == RUNNING)
            notice(notices, "REBUILD_BLOCK_REUSED",
                "session=" + s.id + ";block=" + b.index
                    + ";reason=" + (s.jobs[i].priority ? "in_flight_focus" : "in_flight_prefetch")
                    + ";request=" + s.jobs[i].traceId + ";dispatched=" + s.jobs[i].dispatched);
          continue;
        }
        // Disk candidates are reserved/read outside the monitor before any lane is charged.
        if (s.cacheReading != null && s.cacheReading[i]
            || s.cacheChecked != null && !s.cacheChecked[i]) continue;
        int maxAttempts =
            RebuildReview.hasSemanticRepairRisk(s.plans[i])
                ? RebuildReview.MAX_SEMANTIC_ATTEMPTS
                : s.attempts[i] == 2
                        && s.plans[i] == null
                        && RebuildReview.structuralRetry(s.reasons[i])
                        && s.repairCount < RebuildReview.MAX_SESSION_REPAIRS
                    ? 3
                    : 2;
        if (s.attempts[i] >= maxAttempts) {
          s.states[i] = s.plans[i] == null ? FAILED : READY;
          continue;
        }
        if (s.attempts[i] > 0 && s.repairCount >= RebuildReview.MAX_SESSION_REPAIRS) {
          s.states[i] = s.plans[i] == null ? FAILED : READY;
          continue;
        }
        Job job = new Job(s, i, focus);
        s.jobs[i] = job;
        if (focus && focusDispatched >= MAX_FOCUS_CONCURRENCY) {
          // Both foreground slots are busy: keep only this newest landing and drop any older pending one.
          if (s.pendingFocus != null) retirePendingFocus(s, "superseded_by_newer_focus", i, notices);
          s.states[i] = RUNNING;
          s.pendingFocus = job;
          notice(notices, "REBUILD_FOCUS_PENDING_HELD",
              "session=" + s.id + ";request=" + job.traceId + ";block=" + b.index
                  + ";focus_in_flight=" + focusDispatched + ";prefetch_in_flight=" + prefetchDispatched);
          break;
        }
        markDispatched(s, job);
        start.add(job);
        if (focus) focusDispatched++;
        else prefetchDispatched++;
      }
    }
    flush(s, notices);
    for (Job j : start) {
      RebuildPlanner.Block b = s.blocks.get(j.index);
      CaptionDiagnostics.mark(
          s.context,
          "REBUILD_REQUEST",
          "session="
              + s.id
              + ";request=" + j.traceId
              + ";block="
              + b.id()
              + ";purpose="
              + (j.priority ? "focus" : "prefetch")
              + ";position="
              + s.position
              + ";range="
              + b.start
              + "-"
              + b.end
              + ";focus_in_flight="
              + dispatched(s, true)
              + ";prefetch_in_flight="
              + dispatched(s, false)
              + ";pending_focus_block="
              + (s.pendingFocus == null ? -1 : s.pendingFocus.index));
      dispatch(j.priority, () -> translate(j));
    }
  }

  /**
   * Releases a job that will not produce a plan. Unsent work returns its attempt and repair quota; sent work
   * keeps the attempt it spent but still goes back to WAITING so the block can be requested again.
   */
  private static void finishCancelled(Session s, Job job) {
    s.jobs[job.index] = null;
    if (!job.sent) {
      if (s.attempts[job.index] > 1) s.repairCount = Math.max(0, s.repairCount - 1);
      s.attempts[job.index] = Math.max(0, s.attempts[job.index] - 1);
    }
    s.states[job.index] = WAITING;
    s.retryAt[job.index] = SystemClock.elapsedRealtime() + 500;
  }

  private static void translate(Job job) {
    Session s = job.session;
    RebuildPlanner.Block b = s.blocks.get(job.index);
    RebuildProtocol.Plan accepted = null;
    RebuildCache.Prepared prepared = null;
    RebuildCache.Permit permit = null;
    List<Notice> notices = new ArrayList<>();
    try {
      job.slotWaitMs = SystemClock.elapsedRealtime() - job.queuedAt;
      RawCaptionSource.checkActive(job);
      if(accepted==null) accepted =
          RebuildApi.translate(
              s.source, b, s.config, s.target, job, job.priority, s.reasons[job.index],job.languageContext);
      RebuildProtocol.Plan candidate = accepted, old;
      int generation;
      boolean subjectSplit;
      for (;;) {
        synchronized (s) {
          if (!current(s) || s.jobs[job.index] != job) return;
          if (job.cancelled) { finishCancelled(s, job); return; }
          old = s.plans[job.index];
          generation = s.generation;
        }
        subjectSplit = RebuildReview.splitsFlaggedSubject(s.source, old, candidate, job.languageContext);
        accepted = RebuildReview.prefer(old, candidate, s.source, job.languageContext);
        if (prepared != null) { prepared.close(); prepared = null; }
        if (RebuildReview.score(accepted.issues) == 0)
          prepared = RebuildCache.prepare(s.context, s.cacheKey, s.source, b, accepted, job.languageContext);
        synchronized (s) {
          if (!current(s) || s.jobs[job.index] != job) return;
          if (job.cancelled) { finishCancelled(s, job); return; }
          if (s.generation != generation || s.plans[job.index] != old) continue;
          permit = s.publication.reserve();
          if (permit == null) return;
        }
        break;
      }
      // Preparation/fsync is outside all lifecycle monitors. Admission and revoke share one CAS.
      boolean durable = prepared != null && prepared.commit();
      synchronized (s) {
        if (!current(s) || s.jobs[job.index] != job) return;
        if (job.cancelled || s.generation != generation) {
          finishCancelled(s, job);
          if (s.cacheChecked != null) s.cacheChecked[job.index] = false;
          return;
        }
        if(subjectSplit)
          notice(notices,"REBUILD_REPAIR_SUBJECT_SPLIT_REJECTED",
              "session="+s.id+";request="+job.traceId+";block="+b.index+";attempts="+s.attempts[job.index]);
        if(old!=null && accepted==old && candidate!=old && RebuildReview.score(old.issues)>0)
          notice(notices,"REBUILD_REPAIR_NO_PROGRESS",
              "session="+s.id+";request="+job.traceId+";block="+b.index+";old_risks="+RebuildReview.score(old.issues)+";candidate_risks="+RebuildReview.score(candidate.issues));
        RebuildProtocol.Event onScreen=old==null?null:old.at(s.position);
        String onScreenId=onScreen==null?"":job.index+":"+onScreen.from+"-"+onScreen.to;
        if(old!=null && accepted!=old && onScreen!=null && onScreenId.equals(s.displayedEvent) && !RebuildReview.semanticBlocked(old,onScreen))
          s.pendingPlans[job.index]=accepted;
        else s.plans[job.index] = accepted;
        boolean review=RebuildReview.shouldRepair(accepted,s.attempts[job.index],s.repairCount,s.position,b.end);
        s.states[job.index] = review ? WAITING : READY;
        if(review) {
          s.reasons[job.index]=RebuildReview.repair(accepted.issues);
          s.retryAt[job.index]=SystemClock.elapsedRealtime()+1200;
        }
        s.jobs[job.index] = null;
        s.everReady = true;
      }
      permit.close();
      permit = null;
      if (RebuildReview.score(accepted.issues) == 0) {
        if (!durable) CaptionDiagnostics.mark(s.context, "REBUILD_CACHE_WRITE_FAILED",
            "session=" + s.id + ";block=" + b.index);
        else RebuildCache.trim(s.context);
      }
      flush(s, notices);
      for(RebuildReview.Issue issue:accepted.issues)
        CaptionDiagnostics.mark(s.context,"REBUILD_QUALITY_WARNING","session="+s.id+";request="+job.traceId+";block="+b.index+";advisory=true;repair_candidate="+issue.repair+";"+issue.describe());
      if(s.languageContext.renderSpec.legacy && DeepSeekConfig.displayTextDebugEnabled(s.context)) for(RebuildProtocol.Event event:accepted.events) {
        int count=(int)event.text.codePoints().filter(c->!Character.isWhitespace(c)).count();
        double cps=count*1000.0/Math.max(1,event.end-event.start);
        if(count>48 || cps>12)CaptionDiagnostics.mark(s.context,"REBUILD_READABILITY_WARNING","block="+b.index+";range="+event.from+"-"+event.to+";duration="+(event.end-event.start)+";characters="+count+";cps="+String.format(Locale.ROOT,"%.2f",cps)+";advisory_only=true");
      }
      if (!current(s)) return;
      CaptionDiagnostics.mark(
          s.context,
          "REBUILD_EVENTS_ACCEPTED",
          "block="
              + b.index
              + ";events="
              + accepted.events.size()
              + ";session="+s.id+";request="+job.traceId+";review_risks="+RebuildReview.score(accepted.issues)
              + ";attempts="
              + s.attempts[b.index]);
    } catch (Exception e) {
      if (!current(s)) return;
      String code = e instanceof RebuildProtocol.Invalid ? ((RebuildProtocol.Invalid) e).code
          : e instanceof RebuildApi.Failure ? ((RebuildApi.Failure) e).code
          : e.getClass().getSimpleName();
      boolean fatal = e instanceof RebuildApi.Failure && ((RebuildApi.Failure) e).configuration;
      String configurationStatus = fatal
          ? String.format(Locale.ROOT, CaptionStrings.settings(s.context, "api_config_error"), code) : "";
      String rejectionReason = code + (e instanceof RebuildProtocol.Invalid
          && !((RebuildProtocol.Invalid)e).detail.isEmpty() ? "; " + ((RebuildProtocol.Invalid)e).detail : "");
      String redactedReason = CaptionQualityTrace.redact(rejectionReason, s.config.apiKey, 400);
      notices.clear();
      synchronized (s) {
        if (!current(s) || s.jobs[job.index] != job) return;
        if (job.cancelled) {
          finishCancelled(s, job);
        } else {
          s.jobs[job.index] = null;
          s.reasons[job.index] = rejectionReason;
          if (fatal) {
            s.terminal = true;
            s.status = configurationStatus;
          }
          boolean filtered =
              e instanceof RebuildApi.Failure
                  && "content_filter".equals(((RebuildApi.Failure) e).code);
          boolean structuralRetry =
              s.plans[job.index] == null
                  && s.attempts[job.index] < 3
                  && RebuildReview.structuralRetry(code)
                  && s.repairCount < RebuildReview.MAX_SESSION_REPAIRS;
          s.states[job.index] =
              fatal || filtered || (!structuralRetry && s.attempts[job.index] >= 2)
                  || s.attempts[job.index] >= 3
                  || s.repairCount >= RebuildReview.MAX_SESSION_REPAIRS
                  ? FAILED
                  : WAITING;
          long delay = e instanceof RebuildApi.Failure ? ((RebuildApi.Failure) e).delay : 0;
          s.retryAt[job.index] = SystemClock.elapsedRealtime() + Math.max(1200, delay);
          if (code.equals("http_429") || code.startsWith("http_5"))
            s.providerRetry =
                Math.max(s.providerRetry, SystemClock.elapsedRealtime() + Math.max(5000, delay));
          notice(
              notices,
              "REBUILD_EVENTS_REJECTED",
              "block="
                  + b.index
                  + ";reason="
                  + code
                  + ";session="+s.id+";request="+job.traceId
                  + ";detail="+redactedReason
                  + ";attempts="
                  + s.attempts[b.index]
                  + ";session_repairs="
                  + s.repairCount);
          // A malformed repair must not destroy an already structurally validated candidate.
          if(s.plans[job.index]!=null && !fatal) s.states[job.index]=READY;
        }
      }
      flush(s, notices);
    } finally {
      if (permit != null) permit.close();
      if (prepared != null) prepared.close();
      job.trace("REBUILD_WAIT_BREAKDOWN", "purpose=" + (job.priority ? "focus" : "prefetch")
          + ";slot_wait_ms=" + job.slotWaitMs + ";network_ms=" + job.networkMs
          + ";validation_ms=" + Math.max(0, SystemClock.elapsedRealtime() - job.queuedAt - job.slotWaitMs - job.networkMs)
          + ";validation_repair_retries=" + Math.max(0, s.attempts[job.index] - 1)
          + ";http_rounds=" + job.httpRounds + ";cancelled=" + job.cancelled
          + ";dispatched=" + job.dispatched + ";sent=" + job.sent);
      notices.clear();
      synchronized (s) {
        int focusLeft = dispatched(s, true, job), prefetchLeft = dispatched(s, false, job);
        if (job.dispatched)
          notice(notices, "REBUILD_LANE_RELEASED",
              "session=" + s.id + ";request=" + job.traceId + ";block=" + job.index
                  + ";purpose=" + (job.priority ? "focus" : "prefetch")
                  + ";focus_in_flight=" + focusLeft + ";prefetch_in_flight=" + prefetchLeft
                  + ";translation_in_flight=" + (focusLeft + prefetchLeft)
                  + ";pending_focus_block=" + (s.pendingFocus == null ? -1 : s.pendingFocus.index));
      }
      flush(s, notices);
      if (current(s)) kick(s);
    }
  }

  private static CaptionDocument.Cue originalCue(Session s, long time) {
    if (s.raw == null) return null;
    CaptionDocument.Cue latest = null;
    for (CaptionDocument.Cue c : s.raw.document.cues())
      if (time >= c.startMs && time < c.endMs && (latest == null || c.startMs >= latest.startMs))
        latest = c;
    return latest;
  }

  private static void startup(Session s, String phase, String detail) {
    long now = SystemClock.elapsedRealtime();
    CaptionDiagnostics.mark(s.context, "REBUILD_STARTUP_PHASE",
        "session=" + s.id + ";phase=" + phase + ";elapsed_ms=" + now
            + ";since_activation_ms=" + (now - s.activatedAtMs)
            + (detail.isEmpty() ? "" : ";" + detail));
  }

  private static String original(Session s, long time) {
    CaptionDocument.Cue cue = originalCue(s, time);
    return cue == null ? "" : cue.text;
  }

  private static void endFallback(Session s,long position,String cause,List<Notice> notices) {
    if(!s.fallbackReason.isEmpty())notice(notices,"REBUILD_FALLBACK_END",
        "session="+s.id+";start="+s.fallbackStart+";end="+position+";cause="+cause+";reason="+CaptionQualityTrace.redact(s.fallbackReason,s.config.apiKey,400));
    s.fallbackReason="";s.fallbackStart=-1;
  }

  static boolean lateUnreadable(RebuildProtocol.Event e,long position) {
    return position-e.start>1000 && e.end-position<1000 && e.text.codePointCount(0,e.text.length())>12;
  }
  private static RebuildProtocol.Event adjacentEvent(Session s, RebuildProtocol.Event event,
      boolean next) {
    if (s.plans == null || event == null) return null;
    RebuildProtocol.Event found = null;
    int sourceId = next ? event.to + 1 : event.from - 1;
    for (RebuildProtocol.Plan plan : s.plans) {
      if (plan == null) continue;
      for (RebuildProtocol.Event candidate : plan.events) {
        if (next ? candidate.from != sourceId : candidate.to != sourceId) continue;
        if (next && candidate.start < event.end || !next && candidate.end > event.start) continue;
        if (found == null || (next ? candidate.from < found.from : candidate.to > found.to))
          found = candidate;
      }
    }
    return found;
  }

  private static RebuildDisplayMerge.Merged displayMergeForCurrent(Session s,
      RebuildProtocol.Event event, long position) {
    if (!s.languageContext.renderSpec.legacy || s.source == null || event == null) return null;
    if (RebuildDisplayMerge.isLead(event)) {
      RebuildProtocol.Event next = adjacentEvent(s, event, true);
      RebuildDisplayMerge.Merged deferred = RebuildDisplayMerge.merge(s.source, event, next);
      if (deferred != null && position < deferred.right.start) return deferred;
    }
    RebuildProtocol.Event previous = adjacentEvent(s, event, false);
    if (previous != null && (RebuildDisplayMerge.isLead(previous)
        || RebuildDisplayMerge.isShort(event)))
      return RebuildDisplayMerge.merge(s.source, previous, event);
    return null;
  }

  // A retry is still the same unresolved caption, not a new fallback phase.
  // Rejection details and attempt counts remain in REBUILD_EVENTS_REJECTED.
  static String unresolvedPhase(int state, String reason) {
    if (state == READY) return "";
    return state == FAILED ? "failed:" + reason : "pending_translation";
  }

  private static void render(Session s) {
    if (!current(s) || !s.visible) return;
    String translating = CaptionStrings.get(s.context, "caption_translating");
    List<Notice> notices = new ArrayList<>();
    String text = "";
    String fallbackReason = "";
    boolean status = false;
    int generation;
    long revision, submission, selectedAt, observedAt;
    String eventId = "none";
    long eventStart = -1, eventEnd = -1;
    synchronized (s) {
      if (!current(s) || !s.visible) return;
      generation = s.generation;
      observedAt = s.position;
      selectedAt = displayPosition(s);
      if (s.source == null && !s.status.isEmpty()) {
        text = s.status;
        status = true;
      } else if (s.source == null && s.raw != null && !s.sourceOnly && !s.terminal) {
        CaptionDocument.Cue cue = originalCue(s, selectedAt);
        if (cue != null) {
          text = translating;
          eventId = "source:raw:" + cue.startMs + "_" + cue.endMs;
          eventStart = cue.startMs;
          eventEnd = cue.endMs;
          fallbackReason = "pending_engine";
        }
      } else if (s.source == null) {
        text = s.status.isEmpty() ? translating : s.status;
        status = true;
      } else if (s.sourceOnly) text = original(s, selectedAt);
      else if (s.terminal) {
        text = s.status;
        status = true;
      } else {
        int i = blockAt(s, selectedAt);
        if (i >= 0 && covers(s.blocks.get(i), selectedAt)) {
          if(s.pendingPlans!=null && s.pendingPlans[i]!=null) {
            RebuildProtocol.Event previous=s.plans[i]==null?null:s.plans[i].at(selectedAt);
            String previousId=previous==null?"":i+":"+previous.from+"-"+previous.to;
            if(previous==null || !previousId.equals(s.displayedEvent)) {s.plans[i]=s.pendingPlans[i];s.pendingPlans[i]=null;}
          }
          RebuildProtocol.Plan p = s.plans[i];
          RebuildProtocol.Event e = p == null ? null : p.at(selectedAt);
          if (e != null) {
            eventId = i + ":" + e.from + "-" + e.to;
            eventStart = e.start;
            eventEnd = e.end;
            text = RebuildReview.uncertainNumbers(p,e) ? "〔原字幕数字存疑〕"+e.text : e.text;
            boolean blocked = RebuildReview.semanticBlocked(p,e);
            RebuildDisplayMerge.Merged merged = blocked ? null : displayMergeForCurrent(s,e,selectedAt);
            boolean deferredLead = merged != null && RebuildDisplayMerge.isLead(e)
                && merged.left == e && selectedAt < merged.right.start;
            if (deferredLead) {
              // Do not show the lead by itself, and do not reveal the continuation early.
              eventId = "deferred:" + i + ":" + e.from + "-" + e.to;
              text = "";
            } else {
              if (merged != null) {
                eventId = merged.id();
                eventStart = merged.start;
                eventEnd = merged.end;
                text = merged.text;
              }
              boolean late = merged == null && !eventId.equals(s.displayedEvent)
                  && s.languageContext.renderSpec.legacy
                  && (eventId.equals(s.withheldEvent) || lateUnreadable(e,selectedAt));
              if(blocked || late) {
                if(late && !eventId.equals(s.withheldEvent))notice(notices,"REBUILD_LATE_UNREADABLE","session="+s.id+";event="+eventId+";remaining="+(e.end-s.position));
                if(late)s.withheldEvent=eventId;
                // Keep the existing owned window; only the diagnostic explains a rejection.
                text = blocked ? "" : translating;
                eventId = "source:" + eventId;
                fallbackReason=blocked?"event_review":"late_unreadable";
              } else s.displayedEvent=eventId;
            }
          } else if (p == null) {
            fallbackReason = unresolvedPhase(s.states[i], s.reasons[i]);
            // Waiting and blank failure displays retain each source cue's own window.
            // A block-wide display must not announce a later cue before its onset.
            CaptionDocument.Cue cue=originalCue(s,selectedAt);
            RebuildPlanner.Block block=s.blocks.get(i);
            if(cue!=null && cue.startMs<block.end && cue.endMs>block.start) {
              eventStart=Math.max(cue.startMs,block.start);
              eventEnd=Math.min(cue.endMs,block.end);
              if(eventStart<=selectedAt && selectedAt<eventEnd) {
                text=s.states[i]==FAILED ? "" : translating;
                eventId="source:"+i+":"+eventStart+"_"+eventEnd;
              }
            }
          }
        }
      }
      if(!fallbackReason.equals(s.fallbackReason)) {
        endFallback(s,s.position,"state_change",notices);
        s.fallbackReason=fallbackReason;s.fallbackStart=s.position;
        if(!fallbackReason.isEmpty())notice(notices,"REBUILD_FALLBACK_BEGIN",
            "session="+s.id+";position="+s.position+";reason="+CaptionQualityTrace.redact(fallbackReason,s.config.apiKey,400));
      }
      String signature = (status ? "status:" : "caption:") + eventId + "|" + text + "|";
      if (signature.equals(s.lastShown) && s.appliedRenderRevision == s.renderRevision) {
        revision = -1;
        submission = -1;
      } else {
        if (!signature.equals(s.lastShown)) {
          s.lastShown = signature;
          s.renderRevision++;
        }
        revision = s.renderRevision;
        submission = ++s.renderSubmission;
      }
    }
    flush(s, notices);
    if (revision < 0) return;
    CaptionOverlay.RenderGuard guard = new CaptionOverlay.RenderGuard() {
      public boolean isValid() {
        return current(s) && s.generation == generation && s.renderRevision == revision
            && s.renderSubmission == submission && s.visible;
      }
      public void onApplied() { if (isValid()) s.appliedRenderRevision = revision; }
    };
    if (status) CaptionOverlay.showStatus(text, guard);
    else if (text.isEmpty() && fallbackReason.isEmpty()) CaptionOverlay.hide(guard);
    else if (fallbackReason.equals("pending_engine") || fallbackReason.equals("pending_translation")
        || fallbackReason.equals("late_unreadable"))
      CaptionOverlay.showWaitingEvent(text, guard, s.id + ":" + generation + ":" + eventId,
          eventStart, eventEnd, selectedAt);
    else
      CaptionOverlay.showEvent(
          text, guard, () -> "", s.id + ":" + generation + ":" + eventId,
          eventStart, eventEnd, selectedAt,s.languageContext.renderSpec);
    if (DeepSeekConfig.displayTextDebugEnabled(s.context))
      CaptionDiagnostics.mark(
          s.context,
          "REBUILD_SELECTED",
          "id="
              + s.id
              + ":"
              + generation
              + ":"
              + eventId
              + ";time="
              + observedAt
              + ";range="
              + eventStart
              + "-"
              + eventEnd
              + ";text="
              + CaptionQualityTrace.redact(text, s.config.apiKey, 500));
  }
}
