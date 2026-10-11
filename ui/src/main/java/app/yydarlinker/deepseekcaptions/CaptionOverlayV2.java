package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import app.yydarlinker.caption.contracts.CaptionContracts.*;
import app.yydarlinker.caption.contracts.CaptionContracts.Window;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** One event in one view. Layout never edits a translation or creates new timeline events. */
final class CaptionOverlay {
  interface RenderGuard {
    boolean isValid();
    default void onApplied() {}
    default void beforeDraw() {}
    default void beforePosition() {}
    default long scheduleRevision(){return 0;}
    default List<Window> displayWindows(Unit unit){return unit.windows;}
    default boolean ownsPosition(long at){return true;}
    default void onPresentationInvalidated(long nanoTime){}
    default void onReadability(String unitId,long timingRevision,int cueIndex,List<Window> windows,boolean eligible){}
    default float playbackSpeed(){return 1;}
    default void onDrawObservation(long nanoTime,long position,boolean valid,int fromUtf16,int toUtf16) {}
    default void onPaintCompleted(long startNanos,long endNanos,long startPosition,long endPosition,boolean crossedWindow) {}
    default void onCaptionBody(String fullHash,String pageHash,long position,boolean drawn) {}
    default long displayPosition(long supplied) { return supplied; }
    default String identity(){return "";}
    default String session(){return "";}
    default long windowStart(){return -1;}
    default long windowEnd(){return -1;}
    default String blankReason(){return "";}
    default String sourceTiming(){return "unbound";}
    /** Credential identity known when this guard was bound; never resolved on the render path. */
    default CaptionCredentialRef credential(){return CaptionCredentialRef.NONE;}
  }

  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final java.util.concurrent.atomic.AtomicLong COMMAND =
      new java.util.concurrent.atomic.AtomicLong();
  private static WeakReference<Activity> activityRef = new WeakReference<>(null);
  private static WeakReference<FrameLayout> hostRef = new WeakReference<>(null),
      anchorRef = new WeakReference<>(null);
  private static CaptionHorizontalPlacement horizontalPlacement;
  private static WeakReference<TextView> textRef = new WeakReference<>(null);

  /** Immutable geometry; background translation can measure without touching Views. */
  static final class LayoutBudget extends CaptionPresentationBudget {
    LayoutBudget(int width,float px){this(width,px,px);}
    LayoutBudget(int width,float minimum,float preferred){this(width,minimum,preferred,CaptionRenderSpec.LEGACY);}
    LayoutBudget(int width,float minimum,float preferred,CaptionRenderSpec spec){super(width,minimum,preferred,spec);}
    LayoutBudget withSpec(CaptionRenderSpec spec){return new LayoutBudget(width,minimumPx,preferredPx,spec);}
  }

  private static volatile LayoutBudget layoutBudget;

  static LayoutBudget budget() {
    return layoutBudget;
  }

  private static String pendingText = "", pendingIdentity = "", lastNotice = "";
  private static String lastBlankIdentity;
  private static CaptionRenderSpec pendingRenderSpec=CaptionRenderSpec.LEGACY;
  private static String lastPresentationNotice="";
  private static long pendingStart = -1, pendingEnd = -1, pendingPosition = -1;
  private static List<CaptionPageLayout.Page> pendingPages = Collections.emptyList();
  private static int shownPage = -1;
  private static Unit pendingUnit;
  private static String lastDisplayResult="",pendingCanonicalHash="";
  static long layoutRevision,planningCalls;
  private static boolean reconcilingDisplayTime;
  private static final CaptionPageExecutor LAYOUTS=new CaptionPageExecutor();
  private static String desiredLayout="",failedLayout="";
  private static int measurementMismatch;

  /** Only the installed application supplies page policy; UI observes absolute page times. */
  private static List<CaptionPageLayout.Page> currentPlan(LayoutBudget budget,CaptionRenderSpec spec){
    String key=pendingIdentity+"|"+pendingCanonicalHash+"|"+pendingStart+"|"+pendingEnd+"|"+spec.measurementKey()+"|"+budget.width+"|"+Float.floatToIntBits(budget.preferredPx);
    if(!key.equals(desiredLayout)){desiredLayout=key;failedLayout="";measurementMismatch=0;layoutRevision++;planningCalls++;}
    List<CaptionPageLayout.Page> ready=LAYOUTS.get(key);if(ready!=null)return ready;
    final String body=pendingText,identity=pendingIdentity;final long start=pendingStart,end=pendingEnd;
    final RenderGuard guard=currentGuard;
    LAYOUTS.request(key,()->CaptionActions.displayPages(body,start,end,budget,spec),pages->MAIN.post(()->{
      if(!key.equals(desiredLayout)||!identity.equals(pendingIdentity)||guard!=currentGuard||guard!=null&&!guard.isValid())return;
      if(pages.isEmpty())failedLayout=key;
      dirty=true;lastBlankIdentity=null;render();
    }));
    return Collections.emptyList();
  }
  private static void resetPresentation(){desiredLayout="";failedLayout="";measurementMismatch=0;}

  /**
   * Adjacent captions change text inside a box that stays up. A finished last page stays visible for
   * a short media time while the engine's next unit (or its hide) arrives, and a shown caption stays
   * while the next unit's pages are laid out off the main thread. Explicit hides, seeks, owner
   * changes and longer pauses still clear the box at once.
   */
  private static final long END_HOLD_MS=300, LAYOUT_HOLD_MS=500;
  private static long layoutHoldSince=-1;
  private static final Runnable HOLD_EXPIRY=()->{dirty=true;render();};
  private static boolean shownText(){TextView view=textRef.get();return anchorVisible()&&view!=null&&view.getText().length()>0;}
  private static boolean holdingEnd(long at){
    boolean held=currentGuard!=null&&currentGuard.isValid()&&pendingUnit!=null&&!pendingStatus&&!pendingWaiting
        &&pendingEnd>pendingStart&&at>=pendingEnd&&at-pendingEnd<END_HOLD_MS
        &&shownPage>=0&&shownPage==pendingPages.size()-1&&shownText();
    if(held){MAIN.removeCallbacks(HOLD_EXPIRY);MAIN.postDelayed(HOLD_EXPIRY,END_HOLD_MS+20);}
    return held;
  }
  private static boolean holdingLayout(){
    if(!shownText())return false;
    long now=SystemClock.uptimeMillis();
    if(layoutHoldSince<0){layoutHoldSince=now;MAIN.removeCallbacks(HOLD_EXPIRY);MAIN.postDelayed(HOLD_EXPIRY,LAYOUT_HOLD_MS+20);}
    return now-layoutHoldSince<LAYOUT_HOLD_MS;
  }
  private static boolean previousShorts, previousFullScreen;
  private static float previousPlaybackSpeed=1;
  private static boolean pendingStatus, pendingWaiting;
  /** Display permission and compact quarantine live in the one player authority, not in this class. */
  private static boolean suppressed(){return !CaptionPlayerAuthority.displayPermitted()
      && CaptionPlayerAuthority.state()!=CaptionPlayerAuthority.UNKNOWN;}
  private static boolean guardedExpansion(){return CaptionPlayerAuthority.state()==CaptionPlayerAuthority.COMPACT
      || CaptionPlayerAuthority.state()==CaptionPlayerAuthority.TRANSITIONING_TO_REGULAR;}
  private static RenderGuard currentGuard;
  private static Supplier<String> fallback;
  private static Rect previous = new Rect();
  private static int normalVideoWidth;
  private static int previousScreenWidth;
  private static boolean referenceShorts, referenceLandscape;
  private static long lastScan, lastLayout;
  private static boolean dirty = true;
  // One frame-aligned UI request. Never postpone model authority, clear or compact quarantine.
  private static volatile long playerRenderEpoch;
  /** Render invalidation only. The real player type is owned by CaptionPlayerAuthority. */
  static long playerDispatchIdentity(){return playerRenderEpoch;}
  private static Runnable playerRenderTask;
  private static WeakReference<View> playerFrameClock = new WeakReference<>(null);
  private static boolean playerRenderPending, applyingPlayerRender;
  static long deferredRenderCount, deferredRenderNanos;

  private static void invalidatePlayerRender() {
    playerRenderEpoch++;
    View clock=playerFrameClock.get();
    if(clock!=null && playerRenderTask!=null)clock.removeCallbacks(playerRenderTask);
    if(playerRenderTask!=null)MAIN.removeCallbacks(playerRenderTask);
    playerRenderTask=null;playerFrameClock.clear();playerRenderPending=false;
  }
  static void requestPlayerRender() {
    if(suppressed() || guardedExpansion()
        || pendingText.isEmpty()&&pendingIdentity.isEmpty())return;
    // An authoritative player transition supersedes a queued render: the obsolete callback is dropped
    // and exactly one new frame-aligned render is requested for the new mode.
    if(playerRenderTask!=null)invalidatePlayerRender();
    Activity owner=activityRef.get();
    if(owner==null || owner.isFinishing() || owner.isDestroyed() || owner.getWindow()==null)return;
    View clock=owner.getWindow().getDecorView();
    final long epoch=playerRenderEpoch;
    playerRenderPending=true;
    playerRenderTask=()->{
      // A stale callback has no attach/render/hide side effects, including on a newer Session.
      if(epoch!=playerRenderEpoch || activityRef.get()!=owner || owner.isFinishing() || owner.isDestroyed())return;
      playerRenderTask=null;playerFrameClock.clear();playerRenderPending=false;
      if(suppressed() || guardedExpansion() || currentGuard!=null&&!currentGuard.isValid()
          || pendingText.isEmpty()&&pendingIdentity.isEmpty())return;
      long started=System.nanoTime();applyingPlayerRender=true;
      try{render();deferredRenderCount++;}finally{applyingPlayerRender=false;deferredRenderNanos+=System.nanoTime()-started;}
      CaptionDiagnostics.mark(owner,"PLAYER_TRANSITION_RENDER_DISPATCH",
          "type="+CaptionPlayerAuthority.playerType()+";state="+CaptionPlayerAuthority.stateName()+";deferred_render_count="+deferredRenderCount+";render_total_us="+(deferredRenderNanos/1000)
          +";geometry_search_count="+CaptionSurface.refreshSearchCount+";native_scan_count="+CaptionMusicSuppressor.scanCount
          +";tree_summary_count="+CaptionMusicSuppressor.treeSummaryCount);
    };
    playerFrameClock=new WeakReference<>(clock);
    clock.postOnAnimation(playerRenderTask);
  }
  private static float downY, initial;
  private static long downAt;
  private static boolean dragging;
  private static final android.view.ViewTreeObserver.OnPreDrawListener WATCH =
      () -> {
        advancePreparedPage();
        long now = SystemClock.uptimeMillis();
        if (now - lastLayout >= 100 && !guardedExpansion() && !suppressed()
            && (!pendingText.isEmpty() || !pendingIdentity.isEmpty())) {
          lastLayout = now;
          render();
        }
        TextView pending=textRef.get();return !anchorVisible()||pending==null||!pending.isLayoutRequested();
      };

  static void setActivity(Activity a) {
    CaptionPlayerAuthority.setOwner(a);
    main(
        () -> {
          if (activityRef.get() != a) {
            invalidatePlayerRender();
            detach();
            normalVideoWidth = 0;
          }
          activityRef = new WeakReference<>(a);
          CaptionSurface.activity(a);
          dirty = true;
          render();
        });
  }

  static void showCaption(String s) {
    show(s, false, null, null);
  }

  static void showCaption(String s, RenderGuard g) {
    show(s, false, g, null);
  }

  static void showCaption(String s, RenderGuard g, Supplier<String> f) {
    show(s, false, g, f);
  }

  static void showStatus(String s) {
    show(s, true, null, null);
  }

  static void showStatus(String s, RenderGuard g) {
    show(s, true, g, null);
  }

  static void showEvent(String text,RenderGuard guard,Supplier<String> fallback,String id,long start,long end,long at,CaptionRenderSpec spec,CaptionDisplayCandidate candidate){
    if(candidate!=null){LayoutBudget geometry=layoutBudget;
      if(geometry!=null&&spec.fits(candidate.text,geometry.preferredPx,geometry.width,2)){text=candidate.text;start=candidate.start;end=candidate.end;}
    }
    // A failed or withheld event carries no text: it clears the box (the contract Unit rejects blanks).
    if(text==null||text.trim().isEmpty()){hide(guard);return;}
    Unit unit=start>=0&&end>start?new Unit(id,0,1,text,Collections.singletonList(new Window(start,end)),Basis.ESTIMATED,0):null;
    show(text,false,guard,fallback,id,start,end,at,false,spec,unit,app.yydarlinker.caption.contracts.CaptionContracts.hash(text));
  }

  /** Advance an already prepared page on paint entry, using the same clock as frame selection. */
  static void advancePreparedPage() {
    if(currentGuard==null||!currentGuard.isValid()||pendingUnit==null||!anchorVisible())return;
    // Pages still being laid out: render() owns that state, including the held previous text.
    if(pendingPages.isEmpty())return;
    long at=currentGuard.displayPosition(pendingPosition);int next=CaptionPageLayout.indexAt(pendingPages,at);
    pendingPosition=at;TextView text=textRef.get();
    if(next<0||!ownsDisplayPosition(at)){
      if(holdingEnd(at))return;

      if(text!=null)text.setText("");hideView();shownPage=-1;return;
    }
    if(next==shownPage)return;
    CaptionPageLayout.Page page=pendingPages.get(next);LayoutBudget budget=layoutBudget;
    if(text==null||budget==null)return;
    final long applyStarted=SystemClock.uptimeMillis();
    if(applyVisibleText(text,page.text,budget.preferredPx,budget.width,pendingRenderSpec,page.linePlan)<0){text.setText("");hideView();return;}
    placeVisibleText(activityRef.get(),hostRef.get(),anchorRef.get(),text,previous,budget.width+text.getPaddingLeft()+text.getPaddingRight());
    text.invalidate();
    displayResult(activityRef.get(),"caption_page","",true,budget.width,budget.preferredPx,"",applyStarted);
    shownPage=next;

  }

  static void showWaitingEvent(String s, RenderGuard g, String id,
      long start, long end, long position) {
    show(s,false,g,null,id,start,end,position,true,CaptionRenderSpec.LEGACY,null,"");
  }

  static void position(long position) { position(position,null); }

  static void position(long position,RenderGuard owner) {
    if(owner!=null && !owner.isValid())return;
    main(() -> {
      if(owner!=null && !owner.isValid())return;
      // A state-lane tick may precede the real clock boundary. Select prepared current supply
      // before an old unit's end can hide the View (a hidden View would never reach beforeDraw).
      RenderGuard guard=currentGuard;if(guard!=null)guard.beforePosition();
      long latest=currentGuard!=null && currentGuard.isValid() ? currentGuard.displayPosition(position)
          : owner==null ? position : owner.displayPosition(position);
      long before=pendingPosition;pendingPosition=latest;
      int next=CaptionPageLayout.indexAt(pendingPages,latest);
      boolean timed=pendingStart>=0 && pendingEnd>pendingStart;
      if(next!=shownPage || pendingUnit!=null&&(!ownsDisplayPosition(latest)||!ownsDisplayPosition(before)) || timed && (latest<pendingStart || latest>=pendingEnd
          || before<pendingStart || before>=pendingEnd)) {
        dirty=true;render();
      }
    });
  }

  private static void show(String s,boolean status,RenderGuard g,Supplier<String> f){
    show(s,status,g,f,"",-1,-1,-1,false,CaptionRenderSpec.LEGACY,null,"");
  }
  private static void show(String s,boolean status,RenderGuard g,Supplier<String> f,String id,long start,long end,long position,boolean waiting,CaptionRenderSpec spec,Unit sourceUnit,String canonicalHash){
    if (g != null && !g.isValid()) return;
    long command = g == null ? COMMAND.incrementAndGet() : COMMAND.get();
    final long renderEpoch=playerRenderEpoch;
    main(
        () -> {
          if(renderEpoch!=playerRenderEpoch)return;
          if (g != null) {
            if (!g.isValid()) return;
            COMMAND.incrementAndGet();
          } else if (command != COMMAND.get()) return;
          pendingRenderSpec=spec;pendingCanonicalHash=canonicalHash;
          pendingUnit=sourceUnit;
          pendingText = s == null ? "" : s;
          pendingIdentity = id;
          pendingStart = start;
          pendingEnd = end;
          pendingPosition = g==null ? position : g.displayPosition(position);
          pendingPages = Collections.emptyList();
          shownPage = -1;
          pendingStatus = status;
          pendingWaiting = waiting;
          currentGuard = g;
          fallback = f;
          dirty = true;
          render();
          if (g != null) g.onApplied();
        });
  }

  static void hide() {
    hide(null);
  }

  static void hide(RenderGuard g) {
    if (g != null && !g.isValid()) return;
    long command = g == null ? COMMAND.incrementAndGet() : COMMAND.get();
    final long renderEpoch=playerRenderEpoch;
    main(
        () -> {
          if(renderEpoch!=playerRenderEpoch)return;
          if (g != null) {
            if (!g.isValid()) return;
            COMMAND.incrementAndGet();
          } else if (command != COMMAND.get()) return;
          final long applyStarted=SystemClock.uptimeMillis();
          invalidatePlayerRender();
          pendingText = "";
          pendingUnit=null;resetPresentation();
          pendingStart=-1;pendingEnd=-1;
          pendingIdentity = "";
          pendingPages = Collections.emptyList();
          shownPage = -1;
          fallback = null;
          currentGuard = g;
          hideView();
          if(g!=null){
            pendingIdentity=g.identity();pendingStart=g.windowStart();pendingEnd=g.windowEnd();
            pendingPosition=g.displayPosition(pendingPosition);
            displayResult(activityRef.get(),"empty",g.blankReason(),false,0,0,"",applyStarted);
            pendingIdentity="";pendingStart=-1;pendingEnd=-1; // A diagnostic identity is not a pending render.
          }
          if (g != null) g.onApplied();
        });
  }

  static void clear() {
    clear(null);
  }

  /** Session cleanup cannot invalidate a newer session's queued render command. */
  static void clear(RenderGuard guard) {
    if (guard != null && !guard.isValid()) return;
    long command = guard == null ? COMMAND.incrementAndGet() : COMMAND.get();
    main(
        () -> {
          if (command != COMMAND.get() || guard != null && !guard.isValid()) return;
          pendingText = "";
          pendingUnit=null;resetPresentation();
          pendingStart=-1;pendingEnd=-1;
          pendingIdentity = "";
          pendingPages = Collections.emptyList();
          shownPage = -1;
          invalidatePlayerRender();
          pendingStatus = false;
          fallback = null;
          currentGuard = null;
          normalVideoWidth = 0;
          hideView();
        });
  }

  static void refreshStyle(Context c) {
    main(
        () -> {
          // An explicit user style refresh is not a player notification; apply the latest mode now.
          invalidatePlayerRender();
          dirty = true;
          render();
        });
  }

  /**
   * Geometry refresh only, and never during a player transition: the transition coordinator is the
   * single place that decides when a new surface may be discovered. Shorts keep their existing
   * isolation, which is a surface fact rather than a player-transition fact.
   */
  static void refreshSurface() {
    main(
        () -> {
          CaptionPlayerAuthority.reconcileCurrentPlayerType();
          if(CaptionPlayerAuthority.state()==CaptionPlayerAuthority.COMPACT)
            return;
          boolean surfaceDirty=CaptionPlayerAuthority.consumeSurfaceDirty();
          if (CaptionSurface.isShorts()) {
            // Shorts keeps its existing isolation and is never the compact miniplayer state.
            CaptionPlayerAuthority.noteShortsSurface();
            long now = SystemClock.uptimeMillis();
            if (!surfaceDirty && now >= lastScan && now - lastScan < 500L) return;
            CaptionSurface.refresh();
            lastScan = SystemClock.uptimeMillis();
            CaptionSurface.invalidateGeometry();
            dirty = true;
            render();
            return;
          }
          if(CaptionPlayerAuthority.state()==CaptionPlayerAuthority.TRANSITIONING_TO_REGULAR)return;
          long now = SystemClock.uptimeMillis();
          // The 500 ms throttle bounds repeated expensive searches. A genuinely new authoritative
          // player notification is a real surface change, so it also stamps the window: the search it
          // is allowed to perform replaces the throttled one instead of adding to it.
          if (!surfaceDirty && now >= lastScan && now - lastScan < 500L) return;
          CaptionSurface.refresh();
          lastScan = SystemClock.uptimeMillis();
          CaptionSurface.invalidateGeometry();
          dirty = true;
          render();
        });
  }

  /**
   * Routes a raw player type into the one authority. This method no longer decides anything about
   * display permission, and a caption clear / style change cannot invalidate the notification.
   */
  static void setPlayerType(String type) {
    CaptionPlayerAuthority.onNotification(type, false);
    main(
        () -> {
          final long applyStarted=SystemClock.uptimeMillis();
          if (CaptionPlayerAuthority.state()==CaptionPlayerAuthority.COMPACT
              || CaptionPlayerAuthority.state()==CaptionPlayerAuthority.CLOSED) {
            invalidatePlayerRender();
            hideView();
            displayResult(activityRef.get(),"suppressed","player_suppressed",false,0,0,"",applyStarted);
            return;
          }
          normalVideoWidth=0;
          CaptionSurface.invalidateGeometry();
          dirty=true;
          requestPlayerRender();
        });
  }

  static void beginGuardedExpansion() {
    main(
        () -> {
          invalidatePlayerRender();
          hideView();
        });
  }

  /**
   * The transition coordinator already moved the authority to REGULAR. This only re-enables drawing;
   * it never re-derives a player type and never starts a geometry tail of its own.
   */
  static void restoreAfterGuardedExpansion(String type) {
    CaptionPlayerAuthority.settleRegular();
    main(
        () -> {
          CaptionSurface.invalidateGeometry();
          dirty = true;
          requestPlayerRender();
        });
  }

  private static void main(Runnable r) {
    if (Looper.myLooper() == Looper.getMainLooper()) r.run();
    else MAIN.post(r);
  }

  /**
   * Verification reset of the presentation dedup memory. Production reaches the same effect through
   * the next genuine caption identity; this exists so a cleared session can be observed again.
   */
  static void resetPresentationDedupForTests() {
    lastDisplayResult = "";
    lastNotice = "";
    lastPresentationNotice = "";
    lastBlankIdentity = null;
    lastScan = -500;
    lastLayout = 0;
    reconcilingDisplayTime = false;
  }
  /** Main-thread constant-time retraction. Accepted plans/cache/positions are deliberately retained. */
  static void denyDisplay(long ownerEpoch) {
    if(Looper.myLooper()!=Looper.getMainLooper()) {
      MAIN.post(() -> denyDisplay(ownerEpoch));
      return;
    }
    if(ownerEpoch!=CaptionPlayerAuthority.ownerEpoch())return;
    invalidatePlayerRender();
    COMMAND.incrementAndGet();
    dragging=false;
    View anchor=anchorRef.get();
    TextView text=textRef.get();
    if(text!=null){text.cancelPendingInputEvents();text.clearFocus();}
    if(anchor!=null){
      anchor.cancelPendingInputEvents();anchor.clearFocus();
      anchor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      anchor.setVisibility(View.INVISIBLE);
    }
  }

  private static void hideView() {
    layoutHoldSince=-1;
    if(currentGuard!=null)currentGuard.onPresentationInvalidated(System.nanoTime());
    if(horizontalPlacement!=null)horizontalPlacement.cancel();
    lastBlankIdentity = null;
    FrameLayout a = anchorRef.get();
    if(a!=null)a.setVisibility(View.GONE);
  }

  /** Read-only bounded evidence: is the current overlay anchor visible right now? */
  static boolean anchorVisible(){
    FrameLayout a=anchorRef.get();
    return a!=null&&a.getVisibility()==View.VISIBLE;
  }

  private static void detach() {
    if(horizontalPlacement!=null)horizontalPlacement.cancel();
    horizontalPlacement=null;
    FrameLayout h = hostRef.get(), a = anchorRef.get();
    if (h != null && h.getViewTreeObserver().isAlive())
      h.getViewTreeObserver().removeOnPreDrawListener(WATCH);
    if (a != null && a.getParent() instanceof ViewGroup) ((ViewGroup) a.getParent()).removeView(a);
    // A detached overlay must never keep a visible state: a later owner renders only after it has
    // proven a permitted player surface, so nothing inherits the previous anchor visibility.
    if (a != null) a.setVisibility(View.GONE);
    hostRef = new WeakReference<>(null);
    anchorRef = new WeakReference<>(null);
    textRef = new WeakReference<>(null);
    previous.setEmpty();
    previousScreenWidth = 0;
    lastScan = -500;
    lastLayout = 0;
    lastNotice = "";
    lastBlankIdentity = null;
    layoutBudget = null;
    pendingPages = Collections.emptyList();
    resetPresentation();
    shownPage = -1;
  }

  private static boolean attach(Activity a) {
    FrameLayout h = hostRef.get();
    if (h != null && h.isAttachedToWindow() && anchorRef.get() != null) return true;
    detach();
    View content = a.findViewById(android.R.id.content);
    if (!(content instanceof FrameLayout)) return false;
    h = (FrameLayout) content;
    FrameLayout anchor = new FrameLayout(a);
    // A freshly attached overlay starts hidden. Only a render that passed the authority, guard and
    // geometry checks may make it visible, so a transition can never expose an unverified overlay.
    anchor.setVisibility(View.GONE);
    anchor.setTag("yydarlinker.deepseek.caption.anchor");
    // Only the outer coordinate system is physical LTR; the TextView keeps its target spec.
    anchor.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    anchor.setClipChildren(false);
    anchor.setClipToPadding(false);
    anchor.setElevation(dp(a, 12));
    TextView text = new TextView(a) {
      @Override protected void onDraw(Canvas canvas){
        RenderGuard guard=currentGuard;
        if(guard!=null)guard.beforeDraw();
        // Select and observe the same immutable projection at actual TextView paint entry.
        // Media time is not frozen or rewound; completion separately reports crossed windows.
        guard=currentGuard;long paintAt=guard==null?pendingPosition:guard.displayPosition(pendingPosition);
        if(guard!=null && pendingUnit!=null && (!ownsDisplayPosition(paintAt)
            || shownPage<0 || shownPage>=pendingPages.size() || !pendingPages.get(shownPage).contains(paintAt))){
            guard.beforeDraw();guard=currentGuard;paintAt=guard==null?pendingPosition:guard.displayPosition(pendingPosition);
        }
        long paintStart=System.nanoTime();
        if(guard!=null&&(!guard.isValid()||pendingUnit!=null&&(!ownsDisplayPosition(paintAt)
            ||shownPage<0||shownPage>=pendingPages.size()||!pendingPages.get(shownPage).contains(paintAt))
            &&layoutHoldSince<0&&!holdingEnd(paintAt))){
          setText("");hideView();guard.onDrawObservation(paintStart,paintAt,false,-1,-1);return;
        }
        super.onDraw(canvas);
        observeCaptionBody(this,true,paintAt,paintStart);
        if(guard!=null){long endAt=guard.displayPosition(pendingPosition);boolean crossed=pendingUnit!=null && (!ownsDisplayPosition(endAt)
            || shownPage<0 || shownPage>=pendingPages.size() || !pendingPages.get(shownPage).contains(endAt));
            guard.onPaintCompleted(paintStart,System.nanoTime(),paintAt,endAt,crossed);}

        // Time-dependent content cannot rely on a 50ms state tick to invalidate a cached display list.
        // Keep only the visible owned caption on the compositor cadence; hidden/waiting views stop.
        if(currentGuard!=null && currentGuard.isValid() && pendingUnit!=null && isShown())postInvalidateOnAnimation();
      }
    };
    text.setTag("yydarlinker.deepseek.caption.overlay");
    text.setTextColor(Color.WHITE);
    text.setGravity(Gravity.CENTER);
    text.setIncludeFontPadding(false);
    text.setPadding(dp(a, 6), dp(a, 4), dp(a, 6), dp(a, 4));
    text.setShadowLayer(dp(a, 1), 0, dp(a, 1), 0xD0000000);
    text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
    text.setSingleLine(false);
    text.setMaxLines(2);
    text.setEllipsize(null);
    text.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    text.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
    text.setOnTouchListener((v, e) -> drag(v, e));
    anchor.addView(
        text,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    h.addView(anchor, new FrameLayout.LayoutParams(1, 1));
    hostRef = new WeakReference<>(h);
    anchorRef = new WeakReference<>(anchor);
    textRef = new WeakReference<>(text);
    horizontalPlacement=new CaptionHorizontalPlacement(h,anchor,text);
    h.getViewTreeObserver().addOnPreDrawListener(WATCH);
    dirty = true;
    return true;
  }

  private static boolean reselectingPreparedSupply;
  /** An expired projection is not a supply gap when the authority already prepared the next fact. */
  private static boolean reselectPreparedSupply() {
    RenderGuard previous=currentGuard;String identity=pendingIdentity;
    if(previous==null||!previous.isValid()||reselectingPreparedSupply)return false;
    reselectingPreparedSupply=true;
    try{
      previous.beforePosition();
      return currentGuard!=previous||!identity.equals(pendingIdentity);
    }finally{reselectingPreparedSupply=false;}
  }

  private static void render() {
    final long applyStarted=SystemClock.uptimeMillis();
    Activity a = activityRef.get();
    if(currentGuard!=null && !currentGuard.isValid()) {
      hideView();displayResult(a,"suppressed","owner_invalid",false,0,0,"",applyStarted);return;
    }
    if(currentGuard!=null)pendingPosition=currentGuard.displayPosition(pendingPosition);
    if(pendingStart>=0 && pendingEnd>pendingStart
        && (pendingPosition<pendingStart || pendingPosition>=pendingEnd)) {
      if(reselectPreparedSupply())return;
      if(holdingEnd(pendingPosition))return;
      TextView stale=textRef.get();if(stale!=null)stale.setText("");
      shownPage=-1;hideView();dirty=true;
      displayResult(a,"empty","outside_owned_window",false,0,0,"",applyStarted);return;
    }
    // A time expiry is authoritative even while a geometry frame is queued.
    if(playerRenderPending && !applyingPlayerRender){requestPlayerRender();return;}
    if (a == null
        || a.isFinishing()
        || a.isDestroyed()
        || pendingText.isEmpty() && pendingIdentity.isEmpty()
        || suppressed()
        || guardedExpansion()
        || currentGuard != null && !currentGuard.isValid()) {
      hideView();
      if(a!=null && (suppressed() || guardedExpansion()))displayResult(a,"suppressed","player_suppressed",false,0,0,"",applyStarted);
      return;
    }
    if (!attach(a)) return;
    FrameLayout host = hostRef.get(), anchor = anchorRef.get();
    TextView text = textRef.get();
    CaptionRenderSpec displaySpec = pendingRenderSpec;
    if (pendingStatus || pendingWaiting) {
      String code = LanguageMenuOrder.locale().toLanguageTag();
      displaySpec = new CaptionRenderSpec(code, CaptionLanguageProfile.fromCode(code), false);
    }
    displaySpec.apply(text);
    long now = SystemClock.uptimeMillis();
    if (now - lastScan >= 500) {
      CaptionSurface.refresh();
      lastScan = now;
    }
    Rect b = CaptionSurface.videoBounds(host);
    if (b == null || b.width() < 50 || b.height() < 50) {
      layoutBudget = null;
      hideView();
      return;
    }
    boolean shorts = CaptionSurface.isShorts();
    android.util.DisplayMetrics metrics = a.getResources().getDisplayMetrics();
    int screenWidth = metrics.widthPixels;
    // Mode callbacks can precede the orientation layout. Use current screen geometry
    // for the calibrated portrait-details / landscape-full-screen modes.
    boolean fullScreen = !shorts && metrics.widthPixels > metrics.heightPixels;
    float playbackSpeed=currentGuard==null?1:currentGuard.playbackSpeed();
    if(playbackSpeed!=previousPlaybackSpeed){dirty=true;previousPlaybackSpeed=playbackSpeed;}
    if (!dirty
        && b.equals(previous)
        && screenWidth == previousScreenWidth
        && shorts == previousShorts
        && fullScreen == previousFullScreen
        && (anchor.getVisibility() == View.VISIBLE
            || anchor.getVisibility() == View.GONE
                && pendingIdentity.equals(lastBlankIdentity))) return;
    previousShorts = shorts;
    previousFullScreen = fullScreen;
    dirty = false;
    previous.set(b);
    // N36: a new surface may only be committed outside a player transition. The authority is the one
    // place that decides when the geometry is settled, so a transition never reflows this caption.
    // "Proven" means an explicit player authority decision, not merely that nothing has been reported
    // yet: a long-lived session that never receives a player callback keeps the legacy non-compact
    // behaviour, while a real COMPACT / TRANSITIONING window never commits a new surface.
    boolean authorityTransitioning=CaptionPlayerAuthority.state()==CaptionPlayerAuthority.COMPACT
        ||CaptionPlayerAuthority.state()==CaptionPlayerAuthority.TRANSITIONING_TO_REGULAR;
    if(authorityTransitioning&&!CaptionSurface.isShorts()){
      hideView();
      return;
    }
    previousScreenWidth = screenWidth;
    DeepSeekConfig.Appearance cfg = DeepSeekConfig.displayStyle(a);
    boolean landscapeHost = host.getWidth() > host.getHeight();
    if (normalVideoWidth == 0 || shorts != referenceShorts
        || landscapeHost != referenceLandscape) {
      normalVideoWidth = b.width();
      referenceShorts = shorts;
      referenceLandscape = landscapeHost;
    } else {
      normalVideoWidth = Math.max(normalVideoWidth, b.width());
    }
    float targetGlyphHeight = SubtitleStyleMetrics.renderedGlyphHeightPx(
        cfg.captionSizeTier,screenWidth,fullScreen,b.width(),normalVideoWidth);
    float preferred = SubtitleStyleMetrics.textSizePxForGlyphHeight(text.getPaint(),targetGlyphHeight);
    float minimum = SubtitleStyleMetrics.textSizePxForGlyphHeight(text.getPaint(),
        SubtitleStyleMetrics.renderedGlyphHeightPx(
            0,screenWidth,fullScreen,b.width(),normalVideoWidth));
    int width = Math.max(1, Math.round(b.width() * (CaptionSurface.isShorts() ? .78f : .92f)));
    int inner = Math.max(1, width - text.getPaddingLeft() - text.getPaddingRight());
    CaptionRenderSpec measuredSpec=displaySpec.withPaint(text);
    layoutBudget = new LayoutBudget(inner,minimum,preferred,measuredSpec);
    float size = preferred;
    String shown = pendingText;
    String mode = pendingStatus ? "status" : "caption";
    boolean ownedCaption = !pendingStatus && !pendingWaiting && !pendingText.isEmpty()
        && pendingStart >= 0 && pendingEnd > pendingStart;
    pendingPages=ownedCaption ? currentPlan(layoutBudget,measuredSpec) : Collections.emptyList();

    shownPage=pendingUnit!=null&&!ownsDisplayPosition(pendingPosition)?-1:CaptionPageLayout.indexAt(pendingPages,pendingPosition);
    if(pendingUnit!=null && shownPage>=0) {
      // Small clock jitter must not replay a consumed prefix. Real seek changes the owner identity.

    }
    if(shownPage>=0) {
      shown=pendingPages.get(shownPage).text;

      if(pendingPages.size()>1)mode="caption_page";
    } else if(!ownedCaption) {
      while(size>minimum && linesPx(shown,size,inner,measuredSpec)>2)size=Math.max(minimum,size-.5f);
    }
    String reason=shown.isEmpty() && currentGuard!=null ? currentGuard.blankReason() : "";
    if(ownedCaption && shownPage<0 && pendingPages.isEmpty() && !failedLayout.equals(desiredLayout)
        && holdingLayout()) {
      displayResult(a,"caption_hold","preparing_layout",true,inner,size,"",applyStarted);return;
    }
    if(ownedCaption && shownPage<0) {
      shown="";mode="overflow_status";
      reason=pendingPages.isEmpty() ? (failedLayout.equals(desiredLayout)?"unprojectable_pixel_width":"preparing_layout")
          : !pendingPages.get(0).displayable ? pendingPages.get(0).cutReason : "outside_owned_window";
    } else if(!ownedCaption && linesPx(shown,size,inner,measuredSpec)>2) {
      shown=fallback==null ? "" : fallback.get();mode="original_fallback";
      if(shown==null || linesPx(shown,size,inner,measuredSpec)>2){shown="";mode="overflow_status";}
    }
    text.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);
    text.setSingleLine(false);text.setMaxLines(2);text.setEllipsize(null);
    text.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
    text.setTextColor(pendingStatus ? 0xE6FFFFFF : Color.WHITE);
    int compact=0;
    if(!shown.isEmpty()) {
      compact=applyVisibleText(text,shown,size,inner,measuredSpec,ownedCaption&&shownPage>=0?pendingPages.get(shownPage).linePlan:null);

      if(compact<0){
        shown="";mode="overflow_status";reason="measurement_mismatch";
        if(ownedCaption){
          if(measurementMismatch==0){measurementMismatch=1;LAYOUTS.remove(desiredLayout);layoutRevision++;dirty=true;
            final String identity=pendingIdentity;MAIN.post(()->{if(identity.equals(pendingIdentity)){dirty=true;lastBlankIdentity=null;render();}});}
          else {failedLayout=desiredLayout;reason="unprojectable_pixel_width";}
        }
      }
    }
    String detail = DeepSeekConfig.displayTextDebugEnabled(a)?
          "id="
              + pendingIdentity
              + ";mode="
              + mode
              + ";width="
              + inner
              + ";sp=" + size / metrics.scaledDensity
              + ";text_size_px="
              + size
              + ";target_glyph_height_px=" + targetGlyphHeight
              + ";rendered_glyph_target_px=" + targetGlyphHeight * size / preferred
              + ";glyph_height_px=" + SubtitleStyleMetrics.measuredGlyphHeightPx(text.getPaint())
              + ";font_metrics_height_px=" + SubtitleStyleMetrics.fontMetricsHeightPx(text.getPaint())
              + ";screen_width_px=" + screenWidth
              + ";video_width_px=" + b.width()
              + ";normal_video_width_px=" + normalVideoWidth
              + ";size_tier=" + cfg.captionSizeTier
              + ";size_mode=" + (fullScreen ? "full_screen" : "detail")
              + ";detail_glyph_height_px=" + CaptionFontSize.detailGlyphHeightPx(cfg.captionSizeTier)
              + ";full_screen_glyph_height_px=" + CaptionFontSize.fullScreenGlyphHeightPx(cfg.captionSizeTier)
              + ";glyph_height_ratio=" + (fullScreen
                  ? CaptionFontSize.fullScreenRatio(cfg.captionSizeTier)
                  : CaptionFontSize.detailRatio(cfg.captionSizeTier))
              + ";effective_glyph_height_ratio=" + targetGlyphHeight / Math.max(1,screenWidth)
              + ";density=" + metrics.density
              + ";fontScale=" + a.getResources().getConfiguration().fontScale
              + ";lines="
              + (shown.isEmpty()?0:text.getLineCount())
              + (shownPage >= 0 ? ";page=" + (shownPage + 1) + "/" + pendingPages.size()
                  + ";page_range=" + pendingPages.get(shownPage).start + "-"
                  + pendingPages.get(shownPage).end
                  + ";minimum_page_wall_ms="+measuredSpec.profile.minimumPageMs
                   + ";page_basis=" + pendingPages.get(shownPage).timingBasis
                   + ";page_cut_reason="+pendingPages.get(shownPage).cutReason
                   + ";target_offset_unit=UTF16_GRAPHEME_BOUNDARY"
                   + ";source_timing="+(currentGuard==null?"unbound":currentGuard.sourceTiming())
                   + ";target_from="+(pendingPages.get(shownPage).targetFrom)
                   + ";target_to="+(pendingPages.get(shownPage).targetTo)
                   : ";pagination_unresolved=true"):"id="+pendingIdentity+";mode="+mode;
    detail+=";available_width_px="+inner+";measured_width_px="+Math.max(0,compact);
    if(shown.isEmpty()) {
      text.setText("");hideView();lastBlankIdentity=pendingIdentity;
      if(ownedCaption && !reason.isEmpty())presentationDiagnostics(a,pendingText,preferred,inner,pendingEnd-pendingStart,reason);
      if(mode.equals("overflow_status") && !detail.equals(lastNotice))CaptionDiagnostics.mark(a,"REBUILD_LAYOUT_FALLBACK",detail+";reason="+reason);
      lastNotice=detail;displayResult(a,mode,reason,false,inner,size,detail,applyStarted);return;
    }
    lastBlankIdentity=null;
    GradientDrawable bg=new GradientDrawable();bg.setColor(SubtitleStyleMetrics.alpha(cfg.backgroundOpacity)<<24);
    bg.setCornerRadius(dp(a,4));text.setBackground(bg);
    // Time and owner are sampled again at UI application, after potentially expensive layout.
    if(currentGuard!=null) {
      if(!currentGuard.isValid()){text.setText("");hideView();displayResult(a,"empty","owner_invalid",false,inner,size,detail,applyStarted);return;}
      long latest=currentGuard.displayPosition(pendingPosition);
      if(pendingStart>=0 && pendingEnd>pendingStart && (latest<pendingStart || latest>=pendingEnd)) {
        // A layout can straddle a boundary: reselect the immutable prepared supply before hiding.
        if(reselectPreparedSupply())return;
        pendingPosition=latest;text.setText("");shownPage=-1;hideView();dirty=true;
        displayResult(a,"empty","outside_owned_window",false,inner,size,"",applyStarted);return;
      }
      int page=CaptionPageLayout.indexAt(pendingPages,latest);
      boolean correction=ownedCaption && page!=shownPage;
      pendingPosition=latest;
      if(correction) {
        dirty=true;
        if(!reconcilingDisplayTime){reconcilingDisplayTime=true;try{render();}finally{reconcilingDisplayTime=false;}}
        else {text.setText("");hideView();requestPlayerRender();}
        return;
      }
    }
    int height = text.getMeasuredHeight();
    if(ownedCaption && height>b.height()) {
      text.setText("");hideView();presentationDiagnostics(a,shown,size,inner,pendingEnd-pendingStart,"hard_textview_geometry");
      displayResult(a,"empty","hard_textview_geometry",false,inner,size,detail,applyStarted);return;
    }
    if(ownedCaption)presentationDiagnostics(a,shown,size,compact-text.getPaddingLeft()-text.getPaddingRight(),
        pendingPages.get(shownPage).end-pendingPages.get(shownPage).start,"");
    placeVisibleText(a,host,anchor,text,b,width);
    layoutHoldSince=-1;
    if(anchor.getVisibility() != View.VISIBLE) {
      anchor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
      anchor.setVisibility(View.VISIBLE);
    }
    final long appliedOwner=CaptionPlayerAuthority.ownerEpoch(), appliedRender=playerRenderEpoch;
    final String appliedIdentity=pendingIdentity;
    final RenderGuard appliedGuard=currentGuard;
    observeCaptionBody(text,false);
    horizontalPlacement.observe(a,b,shorts?"shorts":fullScreen?"fullscreen":"detail",
        appliedGuard==null?"":appliedGuard.session(),appliedOwner,appliedRender,
        ()->appliedOwner==CaptionPlayerAuthority.ownerEpoch() && appliedRender==playerRenderEpoch
            && appliedIdentity.equals(pendingIdentity) && appliedGuard==currentGuard
            && (appliedGuard==null || appliedGuard.isValid()) && !suppressed() && !guardedExpansion());
    if((mode.equals("original_fallback") || mode.equals("overflow_status")) && !detail.equals(lastNotice))
      CaptionDiagnostics.mark(a,"REBUILD_LAYOUT_FALLBACK",detail);
    lastNotice=detail;
    displayResult(a,mode,pendingWaiting ? "pending_translation" : "",true,inner,size,detail,applyStarted);
  }

  private static boolean ownsDisplayPosition(long at) {
    return pendingUnit!=null&&pendingUnit.audible(at)&&(currentGuard==null||currentGuard.ownsPosition(at));
  }
  /** Read-only observation of the real caption view; status/waiting/hidden ACKs never qualify. */
  private static String lastBodyAppliedIdentity="",lastBodyDrawnIdentity="";
  private static void observeCaptionBody(TextView view,boolean drawn) {observeCaptionBody(view,drawn,Long.MIN_VALUE,0);}
  private static void observeCaptionBody(TextView view,boolean drawn,long paintPosition,long paintStart) {
    RenderGuard guard=currentGuard;View anchor=anchorRef.get();
    if(drawn && guard!=null) {
      long at=paintPosition==Long.MIN_VALUE?guard.displayPosition(pendingPosition):paintPosition;
      boolean pageValid=shownPage>=0 && shownPage<pendingPages.size() && pendingPages.get(shownPage).displayable
          && pendingPages.get(shownPage).contains(at);
      boolean valid=guard.isValid() && !pendingStatus && !pendingWaiting && !suppressed() && !guardedExpansion()
          && anchor!=null && view.isAttachedToWindow() && view.isShown() && anchor.getVisibility()==View.VISIBLE
          && view.getText().length()>0 && pendingUnit!=null && ownsDisplayPosition(at) && pageValid;
      if(valid) {

      }
      guard.onDrawObservation(paintStart==0?System.nanoTime():paintStart,at,valid,
          pageValid?pendingPages.get(shownPage).targetFrom:-1,
          pageValid?pendingPages.get(shownPage).targetTo:-1);
    }
    if(guard==null || !guard.isValid() || pendingStatus || pendingWaiting || suppressed() || guardedExpansion()
        || anchor==null || !view.isAttachedToWindow() || !view.isShown() || anchor.getVisibility()!=View.VISIBLE
        || pendingText.isEmpty() || view.getText().length()==0 || view.getLayout()==null || view.getLayout().getLineCount()==0
        || view.getMeasuredWidth()<=0 || view.getMeasuredHeight()<=0 || pendingStart<0 || pendingEnd<=pendingStart)return;
    long position=paintPosition==Long.MIN_VALUE?guard.displayPosition(pendingPosition):paintPosition;
    if(position<pendingStart || position>=pendingEnd
        || pendingUnit!=null && !ownsDisplayPosition(position)
        || shownPage<0 || shownPage>=pendingPages.size()
        || !pendingPages.get(shownPage).displayable
        || !pendingPages.get(shownPage).contains(position))return;
    Activity debugOwner=activityRef.get();if(debugOwner==null||!DeepSeekConfig.displayTextDebugEnabled(debugOwner))return;
    String identity=pendingIdentity+"|"+shownPage+"|"+view.getText();
    if(identity.equals(drawn?lastBodyDrawnIdentity:lastBodyAppliedIdentity))return;
    if(drawn)lastBodyDrawnIdentity=identity;else lastBodyAppliedIdentity=identity;
    guard.onCaptionBody(app.yydarlinker.caption.contracts.CaptionContracts.hash(pendingText),app.yydarlinker.caption.contracts.CaptionContracts.hash(view.getText().toString()),position,drawn);
  }

  /** Both ordinary render and prepared-page advance apply the same content geometry. */
  static long textMeasureCalls,compactMeasureRetries;
  private static int applyVisibleText(TextView text,String shown,float size,int maximumInnerPx,CaptionRenderSpec spec,CaptionLinePlan line) {
    boolean same=CaptionSettingPreference.sameText(text.getText(),shown)&&text.getTextSize()==size&&text.getPaint().equalsForTextMeasurement(spec.paint(size));
    spec.apply(text);if(text.getTextSize()!=size)text.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);if(!CaptionSettingPreference.sameText(text.getText(),shown))text.setText(shown);
    if(text.getMaxLines()!=2){text.setSingleLine(false);text.setMaxLines(2);}if(text.getEllipsize()!=null)text.setEllipsize(null);
    if(text.getAutoSizeTextType()!=TextView.AUTO_SIZE_TEXT_TYPE_NONE)text.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
    if(text.getMinEms()!=-1)text.setMinEms(-1);if(text.getMaxEms()!=Integer.MAX_VALUE)text.setMaxEms(Integer.MAX_VALUE);if(text.getMinWidth()!=0)text.setMinWidth(0);if(text.getMinimumWidth()!=0)text.setMinimumWidth(0);
    int padding=text.getPaddingLeft()+text.getPaddingRight();
    float contentWidthPx=line!=null?Math.max(line.line1Px,line.line2Px):same&&text.getLayout()!=null&&text.getLayout().getLineCount()==1?text.getLayout().getLineMax(0):spec.actualWidth(spec.layout(shown,size,maximumInnerPx));
    int boxWidthPx=Math.min(maximumInnerPx,Math.max(1,(int)Math.ceil(contentWidthPx)+2))+padding;
    for(int pass=0;pass<2;pass++) {
      ViewGroup.LayoutParams params=text.getLayoutParams();
      if(same&&!text.isLayoutRequested()&&params.width==boxWidthPx&&params.height==ViewGroup.LayoutParams.WRAP_CONTENT&&text.getMeasuredWidth()==boxWidthPx&&spec.fits(shown,text.getLayout(),boxWidthPx-padding,2))return boxWidthPx;
      if(text.getMaxWidth()!=boxWidthPx)text.setMaxWidth(boxWidthPx);
      if(params.width!=boxWidthPx||params.height!=ViewGroup.LayoutParams.WRAP_CONTENT){params.width=boxWidthPx;params.height=ViewGroup.LayoutParams.WRAP_CONTENT;text.setLayoutParams(params);}textMeasureCalls++;
      text.measure(View.MeasureSpec.makeMeasureSpec(boxWidthPx,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
      if(spec.fits(shown,text.getLayout(),boxWidthPx-padding,2))return boxWidthPx;
      if(boxWidthPx==maximumInnerPx+padding)break;
      compactMeasureRetries++;boxWidthPx=maximumInnerPx+padding;
      Activity owner=activityRef.get();if(owner!=null&&DeepSeekConfig.displayTextDebugEnabled(owner))CaptionDiagnostics.mark(owner,"CAPTION_COMPACT_MEASURE_RETRY","reason=font_geometry_mismatch;maximum_inner_px="+maximumInnerPx);
    }
    return -1;
  }
  private static void placeVisibleText(Activity owner,FrameLayout host,FrameLayout anchor,TextView text,Rect bounds,int anchorWidthPx){
    if(owner==null||host==null||anchor==null||bounds.isEmpty())return;
    int height=text.getMeasuredHeight();boolean landscape=bounds.width()>bounds.height();
    float y=CaptionSurface.isShorts()?DeepSeekConfig.shortsPosition(owner):DeepSeekConfig.captionPositionY(owner,landscape);
    int top=Math.max(bounds.top,Math.min(bounds.bottom-height,bounds.top+Math.round(bounds.height()*y)-height/2));
    CaptionHorizontalPlacement.place(host,anchor,bounds,anchorWidthPx,height,top);
  }
  private static void displayResult(Activity a,String mode,String reason,boolean visible,int width,float size,String detail,long applyStarted) {
    if(a==null)return;
    TextView view=textRef.get();String shown=visible && view!=null ? view.getText().toString() : "";
    String geometry=layoutBudget==null?"":layoutBudget.renderSpec.measurementKey();long timing=0;
    String projection="|font="+geometry+"|event="+pendingIdentity+"|timing="+timing+"|owner="+CaptionPlayerAuthority.ownerEpoch();
    String key=pendingIdentity+"|"+mode+"|"+reason+"|"+visible+"|"+shown+"|"+shownPage+"|"+width+"|"+size+projection;
    if(key.equals(lastDisplayResult))return;lastDisplayResult=key;
    String result=(detail.isEmpty()?"id="+pendingIdentity+";mode="+mode:detail)
        +";ui_applied=true;visible="+visible+";reason="+reason+";render_position="+pendingPosition
        +";window="+pendingStart+"-"+pendingEnd+";applied_wall_ms="+System.currentTimeMillis()
        +";applied_uptime_ms="+SystemClock.uptimeMillis()+";dispatch_uptime_ms="+applyStarted
         +";layout_cost_ms="+Math.max(0,SystemClock.uptimeMillis()-applyStarted)
         +";main_apply_elapsed_ms="+Math.max(0,SystemClock.uptimeMillis()-applyStarted)
         +";desired_box_width_px="+(view==null?0:view.getLayoutParams().width)+";measured_box_width_px="+(view==null?0:view.getMeasuredWidth())+";laid_out_box_width_px="+(view==null?0:view.getWidth())+";layout_cost_basis=ui_path_including_measure_and_diagnostics_not_background_dp;presentation_revision=upstream-ac65fe8"
        +";text="+shown;
    CaptionCredentialRef credential=currentGuard==null?CaptionCredentialRef.NONE:currentGuard.credential();
    // Records for the same event, page and applied geometry are one stable observation: they merge in
    // the bounded queue and are exported once with their total count, keeping the first display and any
    // real state change exact.
    String recordKey="DISPLAY|"+pendingIdentity+"|"+shownPage+"|"+mode+"|"+reason+"|"+visible+"|"+width+"|"+size+projection;
    if(DeepSeekConfig.displayTextDebugEnabled(a))CaptionDiagnostics.mark(a,"REBUILD_PRESENTED",result,credential,recordKey);
    CaptionDiagnostics.mark(a,"REBUILD_DISPLAY_RESULT",result,credential,recordKey);
  }

  private static void presentationDiagnostics(Activity a,String shown,float size,int width,
      long duration,String reason) {
    String notice=pendingIdentity+"|"+pendingRenderSpec.targetCode+"|"+shown+"|"+size+"|"+width
        +"|"+duration+"|"+reason+"|"+shownPage;
    if(notice.equals(lastPresentationNotice)) return;
    lastPresentationNotice=notice;
    CaptionPageLayout.Page page=shownPage>=0&&shownPage<pendingPages.size()?pendingPages.get(shownPage):null;
    String detail="id="+pendingIdentity+";target_code="+pendingRenderSpec.targetCode+";presentation_policy="+pendingRenderSpec.presentationPolicy
        +";duration_ms="+duration+";available_width_px="+width
        +";reading_units="+pendingRenderSpec.readingUnits(shown)+";reading_rate_limit="+pendingRenderSpec.profile.referenceCps
        +";minimum_page_wall_ms="+pendingRenderSpec.profile.minimumPageMs
        +";maximum_recommended_page_wall_ms="+pendingRenderSpec.profile.softPageDurationMs
        +";page_duration_above_recommendation="+(duration/Math.max(.01f,currentGuard==null?1:currentGuard.playbackSpeed())>pendingRenderSpec.profile.softPageDurationMs)
        +(page==null?"": ";line1_px="+page.linePlan.line1Px+";line2_px="+page.linePlan.line2Px+";cut="+page.cutReason)
        +";geometry_reused=true";
    if(!reason.isEmpty())CaptionDiagnostics.mark(a,"REBUILD_PRESENTATION_HARD_REJECT",detail+";reason="+reason);
    else CaptionDiagnostics.mark(a,"REBUILD_PRESENTATION",detail+";hard_reject=false");
  }

  static int compactWidthPx(String value,float sizePx,int maximum){
    return compactWidthPx(value,sizePx,maximum,CaptionRenderSpec.LEGACY);
  }
  static int compactWidthPx(String value,float sizePx,int maximum,CaptionRenderSpec spec){
    StaticLayout layout=spec.layout(value,sizePx,maximum);
    return Math.min(maximum,Math.max(1,(int)Math.ceil(spec.actualWidth(layout))+2));
  }
  static int linesPx(String value,float sizePx,int width,CaptionRenderSpec spec) {
    return spec.layout(value,sizePx,width).getLineCount();
  }

  static int lines(Context a, String s, float sp, int width) {
    return linesPx(s,TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,sp,a.getResources().getDisplayMetrics()),width);
  }

  static int linesPx(String s, float sizePx, int width) {
    android.text.TextPaint paint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    paint.setTypeface(Typeface.DEFAULT);
    paint.setTextSize(sizePx);
    return StaticLayout.Builder.obtain(s, 0, s.length(), paint, Math.max(1, width))
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
        .getLineCount();
  }

  private static boolean drag(View v, MotionEvent e) {
    Activity a = activityRef.get();
    if (a == null || previous.height() <= 0 || !anchorVisible()
        || suppressed() || guardedExpansion()) { dragging=false; return false; }
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downY = e.getRawY();
        downAt = SystemClock.uptimeMillis();
        dragging = false;
        initial =
            CaptionSurface.isShorts()
                ? DeepSeekConfig.shortsPosition(a)
                : DeepSeekConfig.captionPositionY(a, previous.width() > previous.height());
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!dragging && SystemClock.uptimeMillis() - downAt >= 350) {
          dragging = true;
          v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        if (dragging) {
          float y =
              Math.max(.08f, Math.min(.92f, initial + (e.getRawY() - downY) / previous.height()));
          if (CaptionSurface.isShorts()) DeepSeekConfig.saveShortsPosition(a, y);
          else DeepSeekConfig.saveCaptionPosition(a, previous.width() > previous.height(), y);
          dirty = true;
          render();
        }
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        dragging = false;
        return true;
      default:
        return false;
    }
  }

  private static int dp(Context c, float x) {
    return Math.round(x * c.getResources().getDisplayMetrics().density);
  }
}
