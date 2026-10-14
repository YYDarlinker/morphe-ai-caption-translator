package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.ImageView;

import java.nio.charset.StandardCharsets;

/**
 * The callbacks the patch inserts into YouTube. They add the native-renderer masking and the
 * cross-video default handoff around {@link DeepSeekCaptionHook}.
 */
public final class DeepSeekCaptionHookV2 {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object PLAYER_LOCK = new Object();

    /** The newest player type reported off the main thread, until the main thread takes it. */
    private static Enum<?> queuedPlayerType;

    private static boolean playerNotificationPosted;
    private static long queuedOwnerEpoch;

    private DeepSeekCaptionHookV2() {}

    public static void setMainActivity(Activity activity) {
        CaptionMusicSuppressor.setActivity(activity);
        DeepSeekCaptionHook.setMainActivity(activity);
        CaptionMusicSuppressor.kick();
    }

    public static void onVideoId(String videoId) {
        // The old video's ON/OFF intent is observed before the controller tears its session down.
        CaptionVideoHandoffV2.onVideoId(videoId);
        DeepSeekCaptionHook.onVideoId(videoId);
        CaptionMusicSuppressor.forceNativeRendererScan();
    }

    public static void onPlayerType(Enum<?> playerType) {
        if (deferToMainThread(playerType)) return;
        if (playerType != null) DeepSeekCaptionHook.onPlayerType(playerType.name());
        // YouTube can rebuild subtitle windows during fullscreen and miniplayer transitions while
        // the previous renderer view stays attached: force one player-local recheck. The
        // transition guard still owns the animation; this adds no timer of its own.
        CaptionMusicSuppressor.requestNativeRendererScanAfterTransition();
        CaptionMusicSuppressor.kick();
    }

    /**
     * Off-main player notifications are coalesced to the newest type and merged on the main thread.
     * They are guarded by the player <em>owner</em> epoch, not a caption render identity: a caption
     * clear, an AI toggle or a style change must not discard a valid player callback, while a real
     * Activity or video change still rejects a stale one.
     */
    private static boolean deferToMainThread(Enum<?> type) {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        synchronized (PLAYER_LOCK) {
            queuedPlayerType = type;
            queuedOwnerEpoch = CaptionPlayerAuthority.ownerEpoch();
            if (!playerNotificationPosted) {
                playerNotificationPosted = true;
                MAIN.post(DeepSeekCaptionHookV2::deliverQueuedPlayerType);
            }
        }
        return true;
    }

    private static void deliverQueuedPlayerType() {
        Enum<?> type;
        long epoch;
        synchronized (PLAYER_LOCK) {
            type = queuedPlayerType;
            epoch = queuedOwnerEpoch;
            queuedPlayerType = null;
            playerNotificationPosted = false;
        }
        if (epoch == CaptionPlayerAuthority.ownerEpoch()) onPlayerType(type);
    }

    /**
     * The player's caption button controller. Binding waits until the controller method has
     * returned, so YouTube has the last word on its own listener and state setup and the observer
     * reads only the final, settled view.
     */
    public static void onNativeCaptionButtonController(ImageView button) {
        if (button != null)
            button.post(() -> CaptionButtonController.onNativeCaptionButtonController(button));
    }

    public static void onVideoTime(long timeMs) {
        DeepSeekCaptionHook.onVideoTime(Math.max(0L, timeMs));
        CaptionMusicSuppressor.kick();
    }

    public static String rewriteUrl(Object cronetEngine, String originalUrl) {
        Context context = DeepSeekCaptionHook.context();
        boolean aiEnabled = context != null && DeepSeekConfig.enabled(context);

        // Only the cross-video default restoration is decided here; the native and source track
        // authority is decided once, inside the hook. A long-press menu makes mayActivateAiTarget()
        // false, so the handoff yields cleanly.
        String selected =
                aiEnabled
                        ? CaptionVideoHandoffV2.restoreForNextVideo(context, originalUrl)
                        : originalUrl;
        String rewritten = DeepSeekCaptionHook.rewriteUrl(cronetEngine, selected);
        // The hook activates the visible session before it returns the loopback url, so the native
        // renderer is masked before FIRST_AI_READY instead of at the next player-time callback.
        CaptionMusicSuppressor.forceNativeRendererScan();
        CaptionMusicSuppressor.kick();

        if (aiEnabled) {
            String target = concreteAiUrl(selected, rewritten);
            if (!target.isEmpty()) CaptionVideoHandoffV2.noteAiTarget(target);
            if (originalUrl != null && !originalUrl.equals(selected)) {
                TargetLanguage language = TargetLanguage.fromUrl(selected);
                CaptionDiagnostics.mark(
                        context,
                        "VIDEO_DEFAULT_AI_RESTORED",
                        "Default AI captions restored after switching videos"
                                + (language == null ? "" : ";target=" + language.promptLabel()));
            }
        }
        return rewritten;
    }

    /** The translated timed-text url that the rewritten url stands for; empty if there is none. */
    private static String concreteAiUrl(String selected, String rewritten) {
        String decoded = loopbackTarget(rewritten);
        if (!decoded.isEmpty()) return decoded;
        return DeepSeekCaptionHook.isYouTubeTimedTextUrl(selected)
                        && TargetLanguage.fromUrl(selected) != null
                ? selected
                : "";
    }

    /** The translated timed-text url inside a loopback server url, or empty. */
    private static String loopbackTarget(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(value);
            String encoded = uri.getQueryParameter("u");
            if (!"127.0.0.1".equals(uri.getHost()) || encoded == null || encoded.isEmpty())
                return "";
            String decoded =
                    new String(
                            Base64.decode(
                                    encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                            StandardCharsets.UTF_8);
            return DeepSeekCaptionHook.isYouTubeTimedTextUrl(decoded)
                            && TargetLanguage.fromUrl(decoded) != null
                    ? decoded
                    : "";
        } catch (Throwable malformed) {
            return "";
        }
    }
}
