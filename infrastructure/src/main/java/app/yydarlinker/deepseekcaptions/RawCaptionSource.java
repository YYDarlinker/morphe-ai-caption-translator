package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import app.yydarlinker.caption.contracts.CaptionContracts;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The transport of caption tracks: fetching them from YouTube, through the single-flight loader and
 * the source cache. What the text and timing of a track mean is decided by {@link RebuildSource}.
 */
final class RawCaptionSource {
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_REFERENCE_ATTEMPTS = 3;
    private static final int REFERENCE_DEADLINE_MS = 1500;
    private static final long LOAD_DEADLINE_SECONDS = 12;
    private static final String SOURCE = "SOURCE", REFERENCE = "ASR_REFERENCE";

    private RawCaptionSource() {}

    /** A track that was loaded, with what was learned about its timing. */
    static final class Source {
        final byte[] body;
        final CaptionDocument.Parsed document;
        final String timingProfile;
        final int timingQuality;
        final RebuildSource timingEvidence;

        Source(LoadedTrack track, String profile, int quality, RebuildSource evidence) {
            body = track.body;
            document = track.document;
            timingProfile = profile;
            timingQuality = quality;
            timingEvidence = evidence;
        }
    }

    /** A track that was read and holds at least one cue. */
    private static final class LoadedTrack {
        final byte[] body;
        final String contentType;
        final CaptionDocument.Parsed document;

        LoadedTrack(byte[] body, String contentType) throws Exception {
            document = CaptionDocument.parse(body, contentType);
            if (document.cues().isEmpty())
                throw new SourceRecoveryPolicy.Failure("source_empty", false, 0, null);
            this.body = body;
            this.contentType = contentType;
        }
    }

    /** Marks how long a step of the startup took. */
    private static void stage(Context context, String name, long began, String detail) {
        long now = SystemClock.elapsedRealtime();
        CaptionDiagnostics.mark(
                context,
                "SOURCE_STARTUP_STAGE",
                "stage="
                        + name
                        + ";elapsed_realtime_ms="
                        + now
                        + ";duration_ms="
                        + (now - began)
                        + (detail.isEmpty() ? "" : ";" + detail));
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * The caption track of [url]. For a translation the JSON3 form is asked for first, and the
     * track as it was is the fallback of a format the server does not serve.
     */
    static Source load(Context context, String url, boolean translate, CaptionSourceControl control)
            throws Exception {
        long began = now();
        stage(context, "load_begin", began, "");
        String original = CaptionUrls.sourceCaptionUrl(url);
        String preferred = translate ? SourceFormatPolicy.json3(original) : original;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOAD_DEADLINE_SECONDS);
        LoadedTrack track;
        try {
            track = loadTrack(context, preferred, SOURCE, true, remaining(deadline), control);
        } catch (Exception error) {
            String name = "error=" + error.getClass().getSimpleName();
            if (preferred.equals(original) || !SourceRecoveryPolicy.formatFallback(error)) {
                stage(context, "load_failed", began, name);
                throw error;
            }
            stage(context, "format_retry", began, name);
            track = loadTrack(context, original, SOURCE, true, remaining(deadline), control);
        }
        stage(context, "load_end", began, "bytes=" + track.body.length);
        return new Source(track, "", 0, null);
    }

    /**
     * The best timing reference for the track of [url]: another track of the same video and
     * language, from the signed descriptors the native player gave, that has finer timing than
     * [primary]. At most three attempts within 1.5 s; null when none improves the timing.
     */
    static Source reference(
            Context context, String url, RebuildSource primary, CaptionSourceControl control)
            throws Exception {
        String language = query(url, "lang");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REFERENCE_DEADLINE_MS);
        Source best = null;
        int bestQuality = 0, attempts = 0;
        String bestDetail = "";
        outer:
        for (String candidate :
                NativeAsrTrackReference.candidates(CaptionUrls.videoId(url), language)) {
            if (!WordTimingReference.sameLanguage(language, query(candidate, "lang"))
                    || CaptionUrls.sourceCaptionUrl(url).equals(candidate)) continue;
            for (SourceFormatPolicy.Profile profile :
                    SourceFormatPolicy.referenceProfiles(candidate)) {
                if (attempts++ >= MAX_REFERENCE_ATTEMPTS) break outer;
                checkActive(control);
                try {
                    LoadedTrack track =
                            loadTrack(
                                    context,
                                    profile.url,
                                    REFERENCE,
                                    false,
                                    remaining(deadline),
                                    control);
                    RebuildSource evidence = RebuildSource.read(track.body, track.document);
                    int quality = evidence.timingQualityAgainst(primary);
                    String detail =
                            "profile="
                                    + profile.name
                                    + ";quality="
                                    + quality
                                    + ";matched_words="
                                    + (primary == null ? 0 : primary.matchedWordCount(evidence))
                                    + ";"
                                    + evidence.timingCapabilities();
                    CaptionDiagnostics.mark(context, "ASR_REFERENCE_CANDIDATE", detail);
                    if (quality > bestQuality) {
                        bestQuality = quality;
                        best = new Source(track, profile.name, quality, evidence);
                        bestDetail = detail;
                    }
                    // Words of their own are the best evidence there is; a later candidate cannot
                    // improve it, and the budget must not turn into a serial wait.
                    if (quality >= 3) break outer;
                } catch (Exception failure) {
                    CaptionDiagnostics.mark(
                            context,
                            "ASR_REFERENCE_CANDIDATE_FAILED",
                            "profile="
                                    + profile.name
                                    + ";reason="
                                    + CaptionDiagnostics.errorDetail(failure));
                    if (System.nanoTime() >= deadline) break outer;
                }
            }
        }
        String tried = "attempts=" + Math.min(attempts, MAX_REFERENCE_ATTEMPTS);
        if (best == null || bestQuality <= 0) {
            CaptionDiagnostics.mark(
                    context, "ASR_REFERENCE_UNAVAILABLE", tried + ";reason=no_usable_timing");
            return null;
        }
        CaptionDiagnostics.mark(
                context,
                "ASR_REFERENCE_SELECTED",
                tried + ";quality=" + bestQuality + ";" + bestDetail);
        return best;
    }

    static String sourceLanguage(String url) {
        return query(url, "lang");
    }

    private static String query(String url, String key) {
        try {
            String value = Uri.parse(url).getQueryParameter(key);
            return value == null ? "" : value;
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    /** One load of a track for all that ask for it at once; the others join the one in flight. */
    private static LoadedTrack loadTrack(
            Context context,
            String url,
            String kind,
            boolean primary,
            int budgetMs,
            CaptionSourceControl control)
            throws Exception {
        String flight = CaptionContracts.hash(context.getCacheDir().getAbsolutePath() + "\n" + url);
        SourceLoadFlights.Result<LoadedTrack> result =
                SourceLoadFlights.load(
                        flight,
                        budgetMs,
                        control,
                        shared -> loadTrackOwned(context, url, kind, primary, budgetMs, shared));
        if (result.joined) {
            CaptionDiagnostics.mark(
                    context,
                    "SOURCE_SINGLE_FLIGHT_JOIN",
                    "kind="
                            + kind
                            + ";descriptor_profile_hash="
                            + CaptionContracts.hash(url)
                            + ";network_attempts_added=0");
            // Each consumer may need its own cache namespace; the bytes keep their provenance.
            SourceCaptionCache.put(
                    context, cacheKey(url, primary), result.value.body, result.value.contentType);
        }
        return result.value;
    }

    private static String cacheKey(String url, boolean primary) {
        return primary ? SourceCaptionCache.key(url) : SourceCaptionCache.referenceKey(url);
    }

    private static LoadedTrack loadTrackOwned(
            Context context,
            String url,
            String kind,
            boolean primary,
            int budgetMs,
            CaptionSourceControl control)
            throws Exception {
        checkActive(control);
        String key = cacheKey(url, primary);
        long began = now();
        stage(context, "cache_lookup_begin", began, "kind=" + kind);
        SourceCaptionCache.Entry cached = SourceCaptionCache.get(context, key);
        stage(context, "cache_lookup_end", began, "kind=" + kind + ";hit=" + (cached != null));
        if (cached != null) {
            try {
                began = now();
                LoadedTrack track = new LoadedTrack(cached.body, cached.contentType);
                stage(
                        context,
                        "parse_end",
                        began,
                        "kind=" + kind + ";from_cache=true;bytes=" + cached.body.length);
                CaptionDiagnostics.mark(
                        context,
                        kind + "_CACHE_HIT",
                        (primary
                                        ? "Reused source caption cache: "
                                        : "Reused timing reference cache: ")
                                + cached.body.length
                                + " bytes");
                evidence(context, track, kind);
                return track;
            } catch (Exception unreadable) {
                // Older releases cached HTML and malformed tracks before parsing them.
                SourceCaptionCache.remove(context, key);
                CaptionDiagnostics.mark(
                        context, "SOURCE_CACHE_REJECTED", "unreadable_cached_track");
            }
        }
        CaptionDiagnostics.mark(
                context,
                kind + "_FETCH",
                primary ? "Fetching source captions" : "Fetching ASR timing reference");
        began = now();
        stage(context, "fetch_begin", began, "kind=" + kind);
        Fetch fetched =
                fetch(
                        context,
                        url,
                        false,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs),
                        control);
        stage(context, "fetch_end", began, "kind=" + kind + ";bytes=" + fetched.body.length);
        began = now();
        LoadedTrack track = new LoadedTrack(fetched.body, fetched.contentType);
        stage(
                context,
                "parse_end",
                began,
                "kind=" + kind + ";from_cache=false;bytes=" + fetched.body.length);
        checkActive(control);
        began = now();
        SourceCaptionCache.put(context, key, fetched.body, fetched.contentType);
        stage(context, "cache_write_end", began, "kind=" + kind);
        CaptionDiagnostics.mark(
                context,
                kind + "_OK",
                (primary ? "Source captions: " : "ASR timing reference: ")
                        + fetched.body.length
                        + " bytes");
        evidence(context, track, kind);
        return track;
    }

    private static void evidence(Context context, LoadedTrack track, String kind) {
        CaptionDiagnostics.mark(
                context,
                "SOURCE_EVIDENCE",
                "kind="
                        + kind
                        + ";body_hash="
                        + CaptionContracts.hash(new String(track.body, StandardCharsets.UTF_8))
                        + ";raw_preserved=true");
    }

    static void checkActive(CaptionSourceControl control) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || (control != null && control.isCancelled()))
            throw new InterruptedException("source_cancelled");
    }

    /** The milliseconds left until [deadline] (nanoseconds); a timeout once there are none. */
    static int remaining(long deadline) throws SocketTimeoutException {
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left <= 0) throw new SocketTimeoutException("source_deadline");
        return (int) Math.min(Integer.MAX_VALUE, left);
    }

    private static final class Fetch {
        final byte[] body;
        final String contentType;

        Fetch(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }
    }

    /**
     * Downloads [sourceUrl]. A refusal with 401 or 403 is tried once more with fresh cookies. The
     * connection is closed on every way out.
     */
    private static Fetch fetch(
            Context context,
            String sourceUrl,
            boolean refreshed,
            long deadline,
            CaptionSourceControl control)
            throws Exception {
        checkActive(control);
        if (!DeepSeekCaptionHook.isYouTubeTimedTextUrl(sourceUrl))
            throw new IllegalArgumentException("invalid_source_url");
        long began = now();
        String cookies =
                refreshed
                        ? FreshYouTubeCookies.refresh(deadline, control)
                        : FreshYouTubeCookies.cached();
        stage(
                context,
                "cookies_end",
                began,
                "refreshed=" + refreshed + ";available=" + !cookies.isEmpty());
        checkActive(control);
        remaining(deadline);
        URL url = new URL(sourceUrl);
        began = now();
        stage(context, "connection_open_begin", began, "refreshed=" + refreshed);
        HttpURLConnection connection = DeepSeekCaptionHook.openWithYouTubeCronet(url);
        boolean cronet = connection != null;
        if (!cronet) connection = (HttpURLConnection) url.openConnection();
        stage(context, "connection_open_end", began, "cronet=" + cronet);
        try (NetworkDeadline guard = new NetworkDeadline(connection, deadline)) {
            if (control != null) control.onConnection(connection);
            checkActive(control);
            connection.setConnectTimeout(Math.min(5_000, remaining(deadline)));
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", FreshYouTubeCookies.userAgent());
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Referer", "https://www.youtube.com/");
            if (!cookies.isEmpty() && cookies.indexOf('\r') < 0 && cookies.indexOf('\n') < 0)
                connection.setRequestProperty("Cookie", cookies);
            checkActive(control);
            connection.setReadTimeout(remaining(deadline));
            began = now();
            stage(context, "response_headers_begin", began, "refreshed=" + refreshed);
            int status = connection.getResponseCode();
            stage(context, "response_headers_end", began, "status=" + status);
            checkActive(control);
            remaining(deadline);
            if ((status == 401 || status == 403) && !refreshed) {
                connection.disconnect();
                connection = null;
            } else if (status < 200 || status >= 300) {
                throw SourceRecoveryPolicy.http(status, connection.getHeaderField("Retry-After"));
            } else {
                return readBody(context, connection, deadline, control);
            }
        } finally {
            // Also after a failure in the headers or the body, not only after a success.
            if (connection != null) connection.disconnect();
            if (control != null) control.onConnection(null);
        }
        checkActive(control);
        remaining(deadline);
        stage(context, "auth_retry", now(), "status=401_or_403");
        return fetch(context, sourceUrl, true, deadline, control);
    }

    private static Fetch readBody(
            Context context,
            HttpURLConnection connection,
            long deadline,
            CaptionSourceControl control)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long began = now();
        stage(context, "response_body_begin", began, "");
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[8192];
            while (true) {
                checkActive(control);
                connection.setReadTimeout(remaining(deadline));
                int n = input.read(buffer);
                if (n < 0) break;
                if (bytes.size() + n > MAX_SOURCE_BYTES)
                    throw new SourceRecoveryPolicy.Failure("source_too_large", false, 0, null);
                bytes.write(buffer, 0, n);
            }
        }
        checkActive(control);
        remaining(deadline);
        if (bytes.size() == 0)
            throw new SourceRecoveryPolicy.Failure("empty_response", true, 0, null);
        stage(context, "response_body_end", began, "bytes=" + bytes.size());
        String type = connection.getContentType();
        return new Fetch(bytes.toByteArray(), type == null ? "application/octet-stream" : type);
    }
}
