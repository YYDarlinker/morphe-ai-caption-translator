package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

/**
 * The AI switch inside the native player's flyout menu. It has the same state as the settings; only
 * the player's recognised top menu receives the entry. Methods marked "bound at patch time" are
 * stubs the patch replaces with accesses to the player's menu classes.
 */
public final class CaptionQuickToggle {
    private static final long SHORTS_MENU_WINDOW_MS = 1500;
    private static final long[] ACTIVATION_RETRY_MS = {120, 450, 1200};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static long shortsMenuAt;
    private static String shortsVideo = "";
    private static int activationRetry;
    private static Runnable activationRetryTask;

    private CaptionQuickToggle() {}

    /** Notes when the Shorts menu is about to show its captions entry. */
    public static void observeMenuPath(CharSequence path, byte[] buffer) {
        if (path != null) observeMenuPath(path.toString(), buffer);
    }

    public static void observeMenuPath(String path, byte[] buffer) {
        if (!shortsOpen()
                || path == null
                || buffer == null
                || !path.startsWith("overflow_menu_item.e")
                || path.contains("captions_sheet")
                || path.contains("quality_sheet")) return;
        String data = new String(buffer, StandardCharsets.ISO_8859_1);
        if (data.contains("closed_caption") || data.contains("closed_captions")) {
            shortsMenuAt = SystemClock.uptimeMillis();
            shortsVideo = CaptionActions.currentVideoIdSnapshot();
        }
    }

    /** Adds the entry to the menu [panel] at [index]; returns the index after it. */
    public static int onMenu(Object panel, int index) {
        Activity a = CaptionAddonSupport.activity();
        if (a == null || !CaptionAddonSupport.aiInstalled()) return index;
        // The setting is chosen by the player state, not by the broad top-menu signal: Shorts can
        // also report topMenu. A disabled Shorts row must not leak through it.
        boolean inShorts = shortsOpen();
        if (!(inShorts
                ? DeepSeekConfig.shortsFlyoutMenuEnabled(a)
                : DeepSeekConfig.flyoutMenuEnabled(a))) {
            shortsMenuAt = 0;
            shortsVideo = "";
            return index;
        }
        boolean shortsMenu =
                inShorts
                        && shortsMenuAt > 0
                        && SystemClock.uptimeMillis() - shortsMenuAt < SHORTS_MENU_WINDOW_MS
                        && shortsVideo.equals(CaptionActions.currentVideoIdSnapshot());
        if (!topMenu() && !shortsMenu) return index;
        shortsMenuAt = 0;
        String text =
                CaptionStrings.get(
                        a,
                        DeepSeekConfig.enabled(a) ? "ai_quick_toggle_on" : "ai_quick_toggle_off");
        int id =
                a.getResources()
                        .getIdentifier("deepseek_caption_settings", "drawable", a.getPackageName());
        int next =
                addNativeRow(
                        panel,
                        id == 0 ? null : a.getDrawable(id),
                        text,
                        v -> {
                            if (toggle(a)) dismissNative();
                        },
                        index);
        if (next < 0) return index;
        normalizeNativeGap(nativeContainer(panel));
        return next;
    }

    static boolean toggle(Context context) {
        return setEngine(context, !DeepSeekConfig.enabled(context));
    }

    /** Only the known native list's top inset: the Morphe group already clears the drag handle. */
    static void normalizeNativeGap(LinearLayout container) {
        if (container == null || container.getChildCount() < 2) return;
        View list = container.getChildAt(container.getChildCount() - 1);
        if (!(list instanceof ViewGroup)) return;
        if (list.getPaddingTop() > 0)
            list.setPadding(
                    list.getPaddingLeft(), 0, list.getPaddingRight(), list.getPaddingBottom());
        if (list.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) list.getLayoutParams();
            if (params.topMargin > 0) {
                params.topMargin = 0;
                list.setLayoutParams(params);
            }
        }
    }

    /**
     * Turns the engine on or off. The mode is saved first, independently of any native player or
     * track reference: OFF must never be rolled back to ON by a stale Shorts manager.
     */
    static boolean setEngine(Context context, boolean enabled) {
        if (enabled && !DeepSeekConfig.load(context).hasCredential) {
            toast(context, "configure_api");
            return false;
        }
        DeepSeekConfig.saveEnabled(context, enabled);
        // Turning the engine on is a user decision: it clears a previous caption/CC Off latch so
        // the current native selection can start again. It still creates no language choice; the
        // language remains whatever the native player selected. Turning it off preserves the
        // selection so the next enable can adopt it without waiting for another native event.
        if (enabled) CaptionChoice.toggle(true);
        // Turning the engine back on never creates a language choice. The current native
        // selection, if any, is adopted by refreshNativeTrack/onCaptionsShown.
        CaptionActions.refreshConfiguration(context);
        NativeCaptionBridge.Refresh refreshed = NativeCaptionBridge.Refresh.DEFERRED;
        try {
            refreshed = NativeCaptionBridge.refreshNativeTrack();
            if (enabled && refreshed == NativeCaptionBridge.Refresh.DEFERRED)
                refreshed = NativeCaptionBridge.refreshFromCatalog();
        } catch (Exception failed) {
            CaptionDiagnostics.mark(
                    context, "ENGINE_NATIVE_REFRESH_DEFERRED", failed.getClass().getSimpleName());
        }
        CaptionMusicSuppressor.forceNativeRendererScan();
        CaptionMusicSuppressor.kick();
        if (enabled
                && refreshed == NativeCaptionBridge.Refresh.DEFERRED
                && !CaptionChoice.isOn()
                && CaptionButtonController.captionsKnownOn()) {
            try {
                NativeCaptionBridge.onCaptionsShown();
            } catch (Exception failed) {
                CaptionDiagnostics.mark(
                        context,
                        "ENGINE_CAPTIONS_SHOWN_DEFERRED",
                        failed.getClass().getSimpleName());
            }
        }
        if (enabled && refreshed == NativeCaptionBridge.Refresh.DEFERRED)
            scheduleActivationRetry();
        else cancelActivationRetry();
        CaptionDiagnostics.mark(
                context,
                "ENGINE_MODE_SAVED",
                "enabled="
                        + enabled
                        + ";native="
                        + refreshed.name()
                        + ";session="
                        + CaptionActions.isVisibleActive()
                        + ";choice_known="
                        + CaptionChoice.known());
        return true;
    }

    /**
     * The native track list can arrive just after the menu click; a deferred activation is retried
     * a few times instead of waiting for the next video or another manual toggle. The retries stop
     * as soon as the engine starts, and are cancelled when the engine is turned off.
     */
    private static void scheduleActivationRetry() {
        cancelActivationRetry();
        activationRetry = 0;
        postActivationRetry(ACTIVATION_RETRY_MS[0]);
    }

    private static void postActivationRetry(long delayMs) {
        Runnable task =
                () -> {
                    activationRetryTask = null;
                    Activity a = CaptionAddonSupport.activity();
                    if (a == null || !DeepSeekConfig.enabled(a)) {
                        activationRetry = 0;
                        return;
                    }
                    NativeCaptionBridge.Refresh refreshed = NativeCaptionBridge.Refresh.DEFERRED;
                    try {
                        refreshed = NativeCaptionBridge.refreshNativeTrack();
                        if (refreshed == NativeCaptionBridge.Refresh.DEFERRED)
                            refreshed = NativeCaptionBridge.refreshFromCatalog();
                    } catch (Exception failed) {
                        CaptionDiagnostics.mark(
                                a,
                                "ENGINE_ACTIVATION_RETRY_FAILED",
                                failed.getClass().getSimpleName());
                    }
                    CaptionDiagnostics.mark(
                            a,
                            "ENGINE_ACTIVATION_RETRY",
                            "attempt="
                                    + (activationRetry + 1)
                                    + ";result="
                                    + refreshed.name()
                                    + ";active="
                                    + CaptionActions.isVisibleActive());
                    if (refreshed != NativeCaptionBridge.Refresh.DEFERRED) {
                        activationRetry = 0;
                        return;
                    }
                    activationRetry++;
                    if (activationRetry < ACTIVATION_RETRY_MS.length)
                        postActivationRetry(ACTIVATION_RETRY_MS[activationRetry]);
                };
        activationRetryTask = task;
        MAIN.postDelayed(task, Math.max(0L, delayMs));
    }

    static void cancelActivationRetry() {
        if (activationRetryTask != null) MAIN.removeCallbacks(activationRetryTask);
        activationRetryTask = null;
        activationRetry = 0;
    }

    private static void toast(Context context, String key) {
        Toast.makeText(context, CaptionStrings.get(context, key), Toast.LENGTH_LONG).show();
    }

    // ---- Bound at patch time ----

    public static LinearLayout nativeContainer(Object panel) {
        return null;
    }

    public static boolean shortsOpen() {
        return false;
    }

    public static boolean topMenu() {
        return false;
    }

    public static int addNativeRow(
            Object panel, Drawable icon, String label, View.OnClickListener click, int index) {
        return -1;
    }

    public static void dismissNative() {}
}
