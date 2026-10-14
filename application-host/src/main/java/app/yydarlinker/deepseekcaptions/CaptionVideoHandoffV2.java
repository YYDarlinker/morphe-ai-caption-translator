package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.os.SystemClock;

/**
 * Keeps caption-on intent across videos without carrying a video's temporary Auto-translate target.
 *
 * <p>A language chosen from YouTube's Auto-translate menu belongs to that video only. When a real
 * video-id change happens while AI captions are still active, the next video's first timed-text
 * request is allowed to restore the configured default AI target exactly once. This includes the
 * ordinary source-language request YouTube commonly emits when it reopens native captions on the
 * new video.
 */
final class CaptionVideoHandoffV2 {
    private static final long WINDOW_MS = 12_000L;

    private static volatile String currentVideoId = "";
    private static volatile String pendingVideoId = "";
    private static volatile long pendingUntilMs;

    private CaptionVideoHandoffV2() {}

    static void onVideoId(String value) {
        String next = value == null ? "" : value.trim();
        if (next.isEmpty()) return;
        String previous = currentVideoId;
        currentVideoId = next;
        if (previous.isEmpty() || previous.equals(next)) return;
        // Native and source-track choices are video-local; only the caption ON/OFF intent crosses
        // the boundary.
        if (CaptionAddonSupport.memoryInstalled() && CaptionChoice.isOn()) {
            pendingVideoId = next;
            pendingUntilMs = SystemClock.elapsedRealtime() + WINDOW_MS;
        } else clearPending();
    }

    /** Completes a pending handoff once the new video reaches a concrete AI target. */
    static void noteAiTarget(String url) {
        if (pendingVideoId.isEmpty()) return;
        String video = CaptionUrls.videoId(url);
        if (video.isEmpty() || pendingVideoId.equals(video)) clearPending();
    }

    /**
     * Restores the configured default AI target on the new video's first caption request. This is
     * deliberately one-shot and video-bound: it does not keep rerouting source requests while
     * captions are on, so player and lifecycle rebuilds cannot start an activate/deactivate loop. A
     * source-only request is eligible too: the AI runtime fetches that source directly and uses
     * tlang only as the target marker.
     */
    static String restoreForNextVideo(Context context, String url) {
        if (context == null
                || url == null
                || pendingVideoId.isEmpty()
                || !DeepSeekCaptionHook.isYouTubeTimedTextUrl(url)) return url;
        if (SystemClock.elapsedRealtime() > pendingUntilMs) {
            clearPending();
            return url;
        }
        String requested = CaptionUrls.videoId(url);
        if (!requested.isEmpty() && !pendingVideoId.equals(requested)) return url;
        // A long-press menu on the new video is explicit authority for that video: keep its choice.
        if (!CaptionButtonController.mayActivateAiTarget()) {
            clearPending();
            return url;
        }
        String code = DeepSeekConfig.defaultTargetLanguage(context).trim();
        clearPending();
        if (code.isEmpty()) return url;
        TargetLanguage current = TargetLanguage.fromUrl(url);
        return current != null && current.code.equalsIgnoreCase(code)
                ? url
                : TargetLanguage.withCode(url, code);
    }

    private static void clearPending() {
        pendingVideoId = "";
        pendingUntilMs = 0L;
    }
}
