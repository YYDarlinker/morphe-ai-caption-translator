package app.yydarlinker.deepseekcaptions;

/**
 * Keeps the user's active AI caption target across lifecycle and player-layout transitions.
 *
 * <p>YouTube may re-request its source-language timed-text track while an AI session is visible.
 * Native source-track choices are decided explicitly by {@link CaptionButtonController} before this
 * class is consulted, so an ordinary same-video source request that reaches here is
 * renderer/lifecycle noise, not a user command. It stays on the current AI target for the whole
 * visible session, so YouTube never paints a second native source track under the AI overlay.
 */
final class CaptionLifecycleRestore {
    private static volatile String retainedTargetCode = "";
    private static volatile String retainedVideoId = "";

    private CaptionLifecycleRestore() {}

    /** Records the concrete target once the hook has decided that this is an AI-owned request. */
    static void noteAiTarget(String url) {
        TargetLanguage target = TargetLanguage.fromUrl(url);
        if (target == null) return;
        retainedTargetCode = target.code;
        String video = CaptionUrls.videoId(url);
        if (!video.isEmpty()) retainedVideoId = video;
    }

    /**
     * Keeps every ordinary source-language request for the active video on the AI target while the
     * AI overlay is visible. An explicit native track selection has already returned from the hook
     * before this runs, and an Auto-translate choice already has tlang and is left untouched.
     */
    static String restoreAfterLifecycle(String url) {
        if (!CaptionChoice.translates()
                || !CaptionActions.isVisibleActive()
                || retainedTargetCode.isEmpty()
                || !DeepSeekCaptionHook.isYouTubeTimedTextUrl(url)
                || TargetLanguage.fromUrl(url) != null) return url;
        String requested = CaptionUrls.videoId(url);
        if (!requested.isEmpty()
                && !retainedVideoId.isEmpty()
                && !requested.equals(retainedVideoId)) return url;
        return TargetLanguage.withCode(url, retainedTargetCode);
    }
}
