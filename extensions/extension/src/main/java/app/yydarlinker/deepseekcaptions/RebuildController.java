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
  private static WeakReference<Activity> activity = new WeakReference<>(null);
  private static boolean tickPosted;
  private static volatile boolean compact;
  private static long restoreUntil;

  static final class Session {
    final long id = IDS.incrementAndGet();
    final Context context;
    final String owner, identity, target;
    final DeepSeekConfig.Snapshot config;
    final boolean sourceOnly;
    final long activatedAtMs = SystemClock.elapsedRealtime();
    volatile String url;
    volatile boolean visible, cancelled, loading, terminal;
    volatile long sourceRetry, position, providerRetry;
    long lastSeekAt = -1, prefetchPausedUntil;
    volatile long pausedDisplayPosition = -1;
    private PlaybackState pausedHookState;
    volatile String status = "";
    int sourceFailures, repairCount;
    volatile int generation;
    volatile long renderRevision;
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
      context = c;
      url = u;
      owner = o;
      identity = key;
      this.target = target;
      config = cfg;
      sourceOnly = original;
      visible = show;
    }

    void cancel() {
      synchronized(this){
        endFallback(this,position,"session_end");
        pausedDisplayPosition = -1;
        pausedHookState = null;
        pendingFocus = null;
      }
      cancelled = true;
      generation++;
      synchronized (connections) {
        for (HttpURLConnection c : connections)
          try {
            c.disconnect();
          } catch (Exception ignored) {
          }
        connections.clear();
      }
    }

    void noteSeek(long now) {
      if (lastSeekAt >= 0 && now - lastSeekAt <= SEEK_STORM_WINDOW_MS) {
        prefetchPausedUntil = now + SEEK_STORM_PAUSE_MS;
        CaptionDiagnostics.mark(context, "REBUILD_PREFETCH_PAUSED",
            "session=" + id + ";pause_ms=" + SEEK_STORM_PAUSE_MS + ";until_ms=" + prefetchPausedUntil);
      }
      lastSeekAt = now;
    }
  }

  static final class Job implements DeepSeekApiClient.RequestControl {
    final long traceId = IDS.incrementAndGet();
    final Session session;
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
      index = i;
      priority = focus;
    }

    public boolean isCancelled() {
      return cancelled || !current(session);
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

  static synchronized boolean current(Session s) {
    return s != null && active == s && !s.cancelled && (video.isEmpty() || video.equals(s.owner));
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

  static synchronized void video(String id) {
    if (id == null || id.trim().isEmpty()) return;
    id = id.trim();
    boolean changed = !id.equals(video);
    video = id;
    if (changed) {
      CLOCK.reset(SystemClock.elapsedRealtime());
      Activity currentActivity = activity.get();
      if (currentActivity != null)
        CaptionDiagnostics.mark(currentActivity, "REBUILD_STARTUP_PHASE",
            "phase=video_loaded;elapsed_ms=" + SystemClock.elapsedRealtime() + ";video=" + id);
      Session s = active;
      if (s != null && !id.equals(s.owner)) stop();
    }
    SemanticCaptionTimeline.onVideoId(id);
  }

  static synchronized void stop() {
    Session s = active;
    active = null;
    if (s != null) s.cancel();
    CaptionOverlay.clear();
    CaptionMusicSuppressor.kick();
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
    String key =
        RebuildCache.hash(
            CaptionEngine.sourceCaptionUrl(url)
                    .replaceAll("([?&])(?:expire|signature|sig)=[^&]*", "$1")
                + "|"
                + cfg.fingerprint()
                + "|"
                + RebuildCache.hash(cfg.apiKey)
                + "|"
                + target
                + "|"
                + original);
    Session s;
    synchronized (RebuildController.class) {
      if (!video.isEmpty() && !video.equals(owner)) return;
      Session prev = active;
      // Track identity uses cache identity, not an expiring signature.
      try {
        key =
            SourceCaptionCache.key(CaptionEngine.sourceCaptionUrl(url))
                + "|"
                + cfg.fingerprint()
                + "|"
                + RebuildCache.hash(cfg.apiKey)
                + "|"
                + target
                + "|"
                + original;
      } catch (Exception ignored) {
      }
      if (current(prev) && prev.identity.equals(key)) {
        prev.url = url;
        prev.visible |= show;
        if (prev.source == null && prev.sourceFailures > 0) {
          prev.terminal = false;
          prev.sourceRetry = 0;
        }
        s = prev;
      } else {
        if (prev != null) prev.cancel();
        s = new Session(c.getApplicationContext(), url, owner, key, target, cfg, original, show);
        active = s;
        CaptionOverlay.clear();
      }
    }
    s.position = position(s);
    if (!original && !cfg.ready()) {
      s.terminal = true;
      s.status = CaptionStrings.get(c, "configure_api");
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
    synchronized (s) {
      if(seek){endFallback(s,s.position,"seek"); CaptionDiagnostics.mark(s.context,"REBUILD_SEEK","session="+s.id+";from="+s.position+";to="+ms); }
      s.position = CLOCK.presentation(now);
      // Explicit hooks take precedence over paused media jitter, including a small rewind.
      s.pausedDisplayPosition = state != null && state.getState() == PlaybackState.STATE_PAUSED
          ? Math.max(0, ms) : -1;
      s.pausedHookState = s.pausedDisplayPosition >= 0 ? state : null;
      if (seek) {
        s.noteSeek(now);
        s.generation++;
        s.lastShown = "";
        s.displayedEvent=""; s.withheldEvent="";
        if (s.jobs != null)
          for (Job j : s.jobs)
            if (j != null && !j.sent && s.blocks != null && !covers(s.blocks.get(j.index), ms)) {
              j.cancelled = true;
              if (j.connection != null) j.connection.disconnect();
            }
      }
    }
    if (seek) CaptionOverlay.hide();
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
    synchronized (s) {
      s.position = position(s);
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
      if (s.source == null
          && !s.loading
          && !s.terminal
          && SystemClock.elapsedRealtime() >= s.sourceRetry) {
        s.loading = true;
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
    Job control = new Job(s, -1, false);
    try {
      startup(s, "source_load_start", "");
      RawCaptionSource.Source raw =
          RawCaptionSource.load(s.context, s.url, false, !s.sourceOnly, control);
      if (!current(s)) return;
      synchronized (s) {
        if (!current(s)) return;
        s.raw = raw;
        s.position = position(s);
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
      List<RebuildPlanner.Block> blocks = RebuildPlanner.plan(source);
      CaptionDiagnostics.mark(s.context,"REBUILD_SOURCE_PHASE","phase=planner;ms="+(SystemClock.elapsedRealtime()-phase));
      phase=SystemClock.elapsedRealtime();
      String key = RebuildCache.identity(source, s.config, s.target);
      RebuildProtocol.Plan[] plans = new RebuildProtocol.Plan[blocks.size()];
      int[] states = new int[blocks.size()];
      int restored = 0;
      boolean[] checked=new boolean[blocks.size()];
      long focus=position();int focusIndex=0;
      for(RebuildPlanner.Block b:blocks)if(b.start<=focus)focusIndex=b.index;
      for (RebuildPlanner.Block b : blocks) {
        if(b.index!=focusIndex && b.index!=focusIndex+1)continue;
        checked[b.index]=true;
        if (!current(s)) return;
        plans[b.index] = s.sourceOnly ? null : RebuildCache.read(s.context, key, source, b);
        if (plans[b.index] != null) {
          states[b.index] = READY;
          restored++;
        }
      }
      CaptionDiagnostics.mark(s.context,"REBUILD_SOURCE_PHASE","phase=cache;ms="+(SystemClock.elapsedRealtime()-phase));
      synchronized (s) {
        if (!current(s)) return;
        s.source = source;
        s.cacheKey = key;
        s.plans = plans;
        s.pendingPlans = new RebuildProtocol.Plan[plans.length];
        s.states = states;
        s.attempts = new int[blocks.size()];
        s.retryAt = new long[blocks.size()];
        s.reasons = new String[blocks.size()];
        Arrays.fill(s.reasons, "");
        s.jobs = new Job[blocks.size()];
        s.cacheChecked=checked;
        s.loading = false;
        s.status = "";
        s.everReady = restored > 0;
        s.blocks = blocks;
        s.position = position(s);
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
      synchronized (s) {
        s.loading = false;
        s.sourceFailures++;
        s.terminal = !f.retryable;
        s.sourceRetry =
            SystemClock.elapsedRealtime()
                + SourceRecoveryPolicy.delay(s.sourceFailures, f.retryAfterMs);
        s.status =
            CaptionStrings.get(s.context, s.terminal ? "source_unavailable" : "source_retry");
      }
      CaptionDiagnostics.mark(
          s.context, "REBUILD_SOURCE_ERROR", f.category + ";attempt=" + s.sourceFailures);
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
  private static void retirePendingFocus(Session s, String reason, int nextBlock) {
    Job pending = s.pendingFocus;
    if (pending == null) return;
    s.pendingFocus = null;
    s.replacedFocus++;
    if (s.jobs[pending.index] == pending) s.jobs[pending.index] = null;
    if (s.states[pending.index] == RUNNING) {
      s.states[pending.index] = WAITING;
      s.retryAt[pending.index] = SystemClock.elapsedRealtime() + 500;
    }
    CaptionDiagnostics.mark(s.context, "REBUILD_FOCUS_PENDING_REPLACED",
        "session=" + s.id + ";request=" + pending.traceId + ";block=" + pending.index
            + ";reason=" + reason + ";next_block=" + nextBlock
            + ";attempts_consumed=0;session_repairs_consumed=0"
            + ";replaced_total=" + s.replacedFocus);
  }

  private static void schedule(Session s) {
    List<Job> start = new ArrayList<>();
    long now = SystemClock.elapsedRealtime();
    synchronized (s) {
      if (!current(s) || s.blocks == null || now < s.providerRetry) return;
      int index = currentIndex(s);
      int focusDispatched = dispatched(s, true);
      int prefetchDispatched = dispatched(s, false);
      // A retained pending request is only valid for the landing it was chosen for.
      if (s.pendingFocus != null
          && (s.pendingFocus.cancelled || s.pendingFocus.index != index)) {
        retirePendingFocus(s,
            s.pendingFocus.cancelled ? "cancelled_by_seek" : "superseded_by_seek", index);
      }
      // D2: the newest landing takes the free foreground slot immediately when one exists.
      if (s.pendingFocus != null && focusDispatched < MAX_FOCUS_CONCURRENCY) {
        Job promote = s.pendingFocus;
        s.pendingFocus = null;
        markDispatched(s, promote);
        start.add(promote);
        focusDispatched++;
        CaptionDiagnostics.mark(s.context, "REBUILD_FOCUS_PENDING_PROMOTED",
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
              && !paused()
              && CLOCK.fresh(now);
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
            CaptionDiagnostics.mark(s.context, "REBUILD_BLOCK_REUSED",
                "session=" + s.id + ";block=" + b.index
                    + ";reason=" + (s.jobs[i].priority ? "in_flight_focus" : "in_flight_prefetch")
                    + ";request=" + s.jobs[i].traceId + ";dispatched=" + s.jobs[i].dispatched);
          continue;
        }
        // A ready memory plan wins first; lazy disk restoration never waits for a network lane.
        if (s.plans[i] == null && s.cacheChecked != null && !s.cacheChecked[i]) {
          s.cacheChecked[i] = true;
          RebuildProtocol.Plan cached = RebuildCache.read(s.context, s.cacheKey, s.source, b);
          TokenCostAudit.recordUnitCacheOutcome(1, cached == null ? 0 : 1);
          if (cached != null) {
            s.plans[i] = cached;
            s.states[i] = READY;
            s.everReady = true;
            CaptionDiagnostics.mark(s.context, "REBUILD_CACHE_RESTORED",
                "session=" + s.id + ";block=" + b.index + ";network_calls=0"
                    + ";path=memory_then_disk_before_network");
            continue;
          }
        }
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
          if (s.pendingFocus != null) retirePendingFocus(s, "superseded_by_newer_focus", i);
          s.states[i] = RUNNING;
          s.pendingFocus = job;
          CaptionDiagnostics.mark(s.context, "REBUILD_FOCUS_PENDING_HELD",
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
    try {
      job.slotWaitMs = SystemClock.elapsedRealtime() - job.queuedAt;
      RawCaptionSource.checkActive(job);
      boolean restoredFromCache=false;
      if(accepted==null) accepted =
          RebuildApi.translate(
              s.source, b, s.config, s.target, job, job.priority, s.reasons[job.index]);
      synchronized (s) {
        if (!current(s)) return;
        if (job.cancelled) { finishCancelled(s, job); return; }
        if(restoredFromCache)s.attempts[job.index]=Math.max(0,s.attempts[job.index]-1);
        RebuildProtocol.Plan candidate=accepted;
        RebuildProtocol.Plan old=s.plans[job.index];
        boolean subjectSplit=RebuildReview.splitsFlaggedSubject(s.source,old,candidate);
        accepted = RebuildReview.prefer(old,candidate,s.source);
        if(subjectSplit)
          CaptionDiagnostics.mark(s.context,"REBUILD_REPAIR_SUBJECT_SPLIT_REJECTED",
              "session="+s.id+";request="+job.traceId+";block="+b.index+";attempts="+s.attempts[job.index]);
        if(old!=null && accepted==old && candidate!=old && RebuildReview.score(old.issues)>0)
          CaptionDiagnostics.mark(s.context,"REBUILD_REPAIR_NO_PROGRESS",
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
      for(RebuildReview.Issue issue:accepted.issues)
        CaptionDiagnostics.mark(s.context,"REBUILD_QUALITY_WARNING","session="+s.id+";request="+job.traceId+";block="+b.index+";advisory=true;repair_candidate="+issue.repair+";"+issue.describe());
      if(DeepSeekConfig.displayTextDebugEnabled(s.context)) for(RebuildProtocol.Event event:accepted.events) {
        int count=(int)event.text.codePoints().filter(c->!Character.isWhitespace(c)).count();
        double cps=count*1000.0/Math.max(1,event.end-event.start);
        if(count>48 || cps>12)CaptionDiagnostics.mark(s.context,"REBUILD_READABILITY_WARNING","block="+b.index+";range="+event.from+"-"+event.to+";duration="+(event.end-event.start)+";characters="+count+";cps="+String.format(Locale.ROOT,"%.2f",cps)+";advisory_only=true");
      }
      // Finish durable storage before reporting acceptance, so a new session cannot
      // observe the accepted block while its cache write is still queued.
      if (!restoredFromCache && RebuildReview.score(accepted.issues) == 0
          && !RebuildCache.write(s.context, s.cacheKey, b, accepted))
        CaptionDiagnostics.mark(s.context, "REBUILD_CACHE_WRITE_FAILED",
            "session=" + s.id + ";block=" + b.index);
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
      synchronized (s) {
        if (job.cancelled) {
          finishCancelled(s, job);
        } else {
          s.jobs[job.index] = null;
          String code =
              e instanceof RebuildProtocol.Invalid
                  ? ((RebuildProtocol.Invalid) e).code
                  : e instanceof RebuildApi.Failure
                      ? ((RebuildApi.Failure) e).code
                      : e.getClass().getSimpleName();
          s.reasons[job.index] = code + (e instanceof RebuildProtocol.Invalid && !((RebuildProtocol.Invalid)e).detail.isEmpty() ? "; "+((RebuildProtocol.Invalid)e).detail : "");
          boolean fatal = e instanceof RebuildApi.Failure && ((RebuildApi.Failure) e).configuration;
          if (fatal) {
            s.terminal = true;
            s.status = "字幕 API 配置错误：" + code;
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
          CaptionDiagnostics.mark(
              s.context,
              "REBUILD_EVENTS_REJECTED",
              "block="
                  + b.index
                  + ";reason="
                  + code
                  + ";session="+s.id+";request="+job.traceId
                  + ";detail="+CaptionQualityTrace.redact(s.reasons[job.index],s.config.apiKey,400)
                  + ";attempts="
                  + s.attempts[b.index]
                  + ";session_repairs="
                  + s.repairCount);
          // A malformed repair must not destroy an already structurally validated candidate.
          if(s.plans[job.index]!=null && !fatal) s.states[job.index]=READY;
        }
      }
    } finally {
      job.trace("REBUILD_WAIT_BREAKDOWN", "purpose=" + (job.priority ? "focus" : "prefetch")
          + ";slot_wait_ms=" + job.slotWaitMs + ";network_ms=" + job.networkMs
          + ";validation_ms=" + Math.max(0, SystemClock.elapsedRealtime() - job.queuedAt - job.slotWaitMs - job.networkMs)
          + ";validation_repair_retries=" + Math.max(0, s.attempts[job.index] - 1)
          + ";http_rounds=" + job.httpRounds + ";cancelled=" + job.cancelled
          + ";dispatched=" + job.dispatched + ";sent=" + job.sent);
      synchronized (s) {
        int focusLeft = dispatched(s, true, job), prefetchLeft = dispatched(s, false, job);
        if (job.dispatched)
          CaptionDiagnostics.mark(s.context, "REBUILD_LANE_RELEASED",
              "session=" + s.id + ";request=" + job.traceId + ";block=" + job.index
                  + ";purpose=" + (job.priority ? "focus" : "prefetch")
                  + ";focus_in_flight=" + focusLeft + ";prefetch_in_flight=" + prefetchLeft
                  + ";translation_in_flight=" + (focusLeft + prefetchLeft)
                  + ";pending_focus_block=" + (s.pendingFocus == null ? -1 : s.pendingFocus.index));
      }
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

  private static void endFallback(Session s,long position,String cause) {
    if(!s.fallbackReason.isEmpty())CaptionDiagnostics.mark(s.context,"REBUILD_FALLBACK_END",
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
    if (s.source == null || event == null) return null;
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
    String text = "";
    String fallbackReason = "";
    boolean status = false;
    int generation;
    long revision, selectedAt, observedAt;
    String eventId = "none";
    long eventStart = -1, eventEnd = -1;
    synchronized (s) {
      generation = s.generation;
      observedAt = s.position;
      selectedAt = displayPosition(s);
      if (s.source == null && !s.status.isEmpty()) {
        text = s.status;
        status = true;
      } else if (s.source == null && s.raw != null && !s.sourceOnly && !s.terminal) {
        CaptionDocument.Cue cue = originalCue(s, selectedAt);
        if (cue != null) {
          text = CaptionStrings.get(s.context, "caption_translating");
          eventId = "source:raw:" + cue.startMs + "_" + cue.endMs;
          eventStart = cue.startMs;
          eventEnd = cue.endMs;
          fallbackReason = "pending_engine";
        }
      } else if (s.source == null) {
        text = s.status.isEmpty() ? CaptionStrings.get(s.context, "caption_translating") : s.status;
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
                  && (eventId.equals(s.withheldEvent) || lateUnreadable(e,selectedAt));
              if(blocked || late) {
                if(late && !eventId.equals(s.withheldEvent))CaptionDiagnostics.mark(s.context,"REBUILD_LATE_UNREADABLE","session="+s.id+";event="+eventId+";remaining="+(e.end-s.position));
                if(late)s.withheldEvent=eventId;
                // Keep the existing owned window; only the diagnostic explains a rejection.
                text = blocked ? "" : CaptionStrings.get(s.context, "caption_translating");
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
                text=s.states[i]==FAILED ? "" : CaptionStrings.get(s.context, "caption_translating");
                eventId="source:"+i+":"+eventStart+"_"+eventEnd;
              }
            }
          }
        }
      }
      if(!fallbackReason.equals(s.fallbackReason)) {
        endFallback(s,s.position,"state_change");
        s.fallbackReason=fallbackReason;s.fallbackStart=s.position;
        if(!fallbackReason.isEmpty())CaptionDiagnostics.mark(s.context,"REBUILD_FALLBACK_BEGIN",
            "session="+s.id+";position="+s.position+";reason="+CaptionQualityTrace.redact(fallbackReason,s.config.apiKey,400));
      }
      String signature = (status ? "status:" : "caption:") + eventId + "|" + text + "|";
      if (signature.equals(s.lastShown)) return;
      s.lastShown = signature;
      revision = ++s.renderRevision;
    }
    CaptionOverlay.RenderGuard guard =
        () -> current(s) && s.generation == generation && s.renderRevision == revision && s.visible;
    if (status) CaptionOverlay.showStatus(text, guard);
    else if (text.isEmpty() && fallbackReason.isEmpty()) CaptionOverlay.hide(guard);
    else if (fallbackReason.equals("pending_engine") || fallbackReason.equals("pending_translation")
        || fallbackReason.equals("late_unreadable"))
      CaptionOverlay.showWaitingEvent(text, guard, s.id + ":" + generation + ":" + eventId,
          eventStart, eventEnd, selectedAt);
    else
      CaptionOverlay.showEvent(
          text, guard, () -> "", s.id + ":" + generation + ":" + eventId,
          eventStart, eventEnd, selectedAt);
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
