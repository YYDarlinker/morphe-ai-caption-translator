package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.net.Uri;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads YouTube's source-caption text while keeping timing provenance explicit.
 *
 * <p>The selected provider track remains the semantic/text source. When that source is English,
 * YouTube's English auto-generated track is fetched only as a clock reference. Whether calibration
 * is needed is decided from real provider-vs-ASR text anchors, never from a broadcast/roll-up
 * heuristic. Provider words and the semantic translation path stay untouched.</p>
 */
final class RawCaptionSource {
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int ENGLISH_TEXT_THRESHOLD = 78;

    private RawCaptionSource() {}

    static Source load(Context context, String translatedUrl) throws Exception {
        return load(context, translatedUrl, true);
    }

    static Source load(
            Context context,
            String translatedUrl,
            boolean publishSharedTimeline
    ) throws Exception {
        return load(context,translatedUrl,publishSharedTimeline,true);
    }
    static Source load(Context context,String translatedUrl,boolean publishSharedTimeline,boolean calibrate) throws Exception {
        String sourceUrl = CaptionEngine.sourceCaptionUrl(translatedUrl);
        LoadedTrack provider;
        String timedUrl=calibrate ? SourceFormatPolicy.json3(sourceUrl) : sourceUrl;
        try {
            provider=loadTrack(context,timedUrl,"SOURCE",true);
            CaptionDocument.parse(provider.body,provider.contentType); // Reject unreadable preferred format.
            sourceUrl=timedUrl;
        } catch(Exception unavailable) {
            if(timedUrl.equals(sourceUrl)) throw unavailable;
            CaptionDiagnostics.mark(context,"SOURCE_FORMAT_FALLBACK","JSON3 不可用；单次回退原格式，时间精度会明确报告");
            provider=loadTrack(context,sourceUrl,"SOURCE",true);
        }

        byte[] body = provider.body;
        String contentType = provider.contentType;
        CaptionDocument.Parsed document = CaptionDocument.parse(body, contentType);
        TrackIdentity identity = classifyTrack(sourceUrl, document);
        SourceAtomTimeline.Result alignedAtoms = null;

        CaptionDiagnostics.mark(
                context,
                "SOURCE_TRACK_CLASSIFIED",
                identity.diagnostic()
        );

        if (identity.englishAsr) {
            CaptionDiagnostics.mark(
                    context,
                    "SOURCE_TIMING_BASE",
                    "当前底层轨就是英语（自动生成），直接使用其同步时间轴"
            );
        } else if (calibrate && identity.englishProvider) {
            CaptionDiagnostics.mark(
                    context,
                    "ASR_TIMING_PROBE",
                    "英语源轨不再经过广播类型预筛选；直接尝试英语（自动生成）时间参照"
            );
            try {
                TimingAnchor anchor = loadEnglishAsrAnchor(context, sourceUrl);
                if (anchor == null) {
                    CaptionDiagnostics.mark(
                            context,
                            "SOURCE_TIMING_FALLBACK",
                            "未取得可用的英语（自动生成）时间锚，保持原轨时间"
                    );
                } else {
                    SourceAtomTimeline.Result originalAtoms = SourceAtomTimeline.build(body, document);
                    SourceAtomTimeline.Result referenceAtoms = SourceAtomTimeline.build(anchor.body, anchor.document);
                    alignedAtoms = AsrLocalTiming.align(originalAtoms, referenceAtoms);
                    if(alignedAtoms == originalAtoms) alignedAtoms = null;
                    CaptionTimingCalibrator.Calibration calibration =
                            CaptionTimingCalibrator.compare(document, anchor.document);
                    CaptionDiagnostics.mark(
                            context,
                            "ASR_TIMING_MATCH",
                            calibration.diagnostic()
                    );
                    if (alignedAtoms != null) {
                        CaptionDiagnostics.mark(context,"ASR_LOCAL_TIMING_APPLIED",
                            "保留原轨文本；局部英语自动字幕原生时间锚="+alignedAtoms.nativeTimedAtoms+"；估计="+alignedAtoms.estimatedAtoms);
                    } else if (calibration.apply) {
                        byte[] shiftedJson3 = CaptionTimingCalibrator.shiftJson3(body, calibration.offsetMs);
                        if (shiftedJson3 != null) {
                            body = shiftedJson3;
                            document = CaptionDocument.parse(body, contentType);
                        } else {
                            document = CaptionTimingCalibrator.shiftDocument(document, calibration.offsetMs);
                        }
                        CaptionDiagnostics.mark(
                                context,
                                "SOURCE_TIMING_CALIBRATED",
                                "保留原轨文本，仅以英语（自动生成）校时：原轨减去校准偏移 " +
                                        calibration.offsetMs + " ms；" + calibration.anchors +
                                        " 个文本锚，MAD " + calibration.madMs + " ms"
                        );
                    } else if (calibration.alreadySynced) {
                        CaptionDiagnostics.mark(
                                context,
                                "SOURCE_TIMING_CONFIRMED",
                                "原轨与英语（自动生成）时间已基本一致：中位差 " +
                                        calibration.offsetMs + " ms；无需修正"
                        );
                    } else {
                        CaptionDiagnostics.mark(
                                context,
                                "SOURCE_TIMING_FALLBACK",
                                "未对原轨改时：" + calibration.reason
                        );
                    }
                }
            } catch (Throwable failure) {
                CaptionDiagnostics.mark(
                        context,
                        "SOURCE_TIMING_FALLBACK",
                        "英语（自动生成）时间锚不可用，保持原轨时间：" +
                                CaptionDiagnostics.errorDetail(failure)
                );
            }
        } else {
            CaptionDiagnostics.mark(
                    context,
                    "SOURCE_TIMING_NOT_PROBED",
                    "当前源轨无法确认是英文；为避免错配英语 ASR，不修改时间轴"
            );
        }

        if (publishSharedTimeline) {
            String videoId = PageCaptionController.videoIdFromUrl(translatedUrl);
            if (videoId.isEmpty()) videoId = SemanticCaptionTimeline.currentVideoId();
            publishSharedTimeline(videoId, document.cues());
        }
        CaptionDiagnostics.mark(
                context,
                "RAW_TIMELINE_READY",
                "保留 YouTube 原轨 " + document.cues().size() + " 个时间原子；分句译文由模型单次生成，本地保留原词时间锚"
        );
        return new Source(body, contentType, sourceUrl, document, alignedAtoms);
    }

    static boolean publishSharedTimeline(
            String videoId,
            List<CaptionDocument.Cue> values
    ) {
        String owner = videoId == null ? "" : videoId.trim();
        if (owner.isEmpty()) return false;
        return SemanticCaptionTimeline.replace(owner, values);
    }

    private static LoadedTrack loadTrack(
            Context context,
            String url,
            String diagnosticPrefix,
            boolean cacheAsPrimary
    ) throws Exception {
        String cacheKey = SourceCaptionCache.key(url);
        SourceCaptionCache.Entry cached = SourceCaptionCache.get(context, cacheKey);
        if (cached != null) {
            CaptionDiagnostics.mark(
                    context,
                    diagnosticPrefix + "_CACHE_HIT",
                    (cacheAsPrimary ? "复用原始字幕缓存 " : "复用时间锚缓存 ") +
                            cached.body.length + " bytes"
            );
            return new LoadedTrack(cached.body, cached.contentType, url);
        }

        CaptionDiagnostics.mark(
                context,
                diagnosticPrefix + "_FETCH",
                cacheAsPrimary ? "正在获取原始字幕" : "正在获取英语（自动生成）时间锚"
        );
        Fetch fetched = fetch(url, false);
        SourceCaptionCache.put(context, cacheKey, fetched.body, fetched.contentType);
        CaptionDiagnostics.mark(
                context,
                diagnosticPrefix + "_OK",
                (cacheAsPrimary ? "原始字幕 " : "时间锚 ") + fetched.body.length + " bytes"
        );
        return new LoadedTrack(fetched.body, fetched.contentType, url);
    }

    private static TimingAnchor loadEnglishAsrAnchor(Context context, String sourceUrl) throws Exception {
        List<String> candidates = autoGeneratedEnglishCandidates(sourceUrl);
        Throwable last = null;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                LoadedTrack track = loadTrack(context, candidate, "ASR_TIMING", false);
                CaptionDocument.Parsed parsed = CaptionDocument.parse(track.body, track.contentType);
                if (parsed.cues().size() >= 12) {
                    CaptionDiagnostics.mark(
                            context,
                            "ASR_TIMING_READY",
                            "已取得英语（自动生成）时间参照：" + parsed.cues().size() +
                                    " 个 cue；候选 " + (i + 1) + "/" + candidates.size()
                    );
                    return new TimingAnchor(track.body, track.contentType, candidate, parsed);
                }
                CaptionDiagnostics.mark(
                        context,
                        "ASR_TIMING_CANDIDATE_REJECTED",
                        "候选 " + (i + 1) + "/" + candidates.size() +
                                " 可解析 cue 过少：" + parsed.cues().size()
                );
            } catch (Throwable failure) {
                last = failure;
                CaptionDiagnostics.mark(
                        context,
                        "ASR_TIMING_CANDIDATE_FAILED",
                        "候选 " + (i + 1) + "/" + candidates.size() + "：" +
                                CaptionDiagnostics.errorDetail(failure)
                );
            }
        }
        if (last instanceof Exception) throw (Exception) last;
        if (last != null) throw new IllegalStateException(last);
        return null;
    }

    /**
     * Try several identity spellings because YouTube caption base URLs are not perfectly uniform
     * across app/server experiments. All candidates still point at the same video's English ASR
     * track; none is allowed to become a text source.
     */
    private static List<String> autoGeneratedEnglishCandidates(String sourceUrl) {
        List<String> out = new ArrayList<>();
        String videoId = PageCaptionController.videoIdFromUrl(sourceUrl);
        if (videoId.isEmpty()) return out;

        try {
            Uri original = Uri.parse(sourceUrl);
            Uri.Builder baseBuilder = original.buildUpon().clearQuery();
            Set<String> names = original.getQueryParameterNames();
            for (String name : names) {
                String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (lower.equals("tlang") || lower.equals("lang") || lower.equals("kind") ||
                        lower.equals("name") || lower.equals("fmt") || lower.equals("xtags") ||
                        lower.equals("vss_id") || lower.equals("vssid") || lower.equals("caps")) {
                    continue;
                }
                for (String value : original.getQueryParameters(name)) {
                    baseBuilder.appendQueryParameter(name, value);
                }
            }
            String base = baseBuilder
                    .appendQueryParameter("lang", "en")
                    .appendQueryParameter("kind", "asr")
                    .appendQueryParameter("caps", "asr")
                    .appendQueryParameter("fmt", "json3")
                    .build()
                    .toString();
            addCandidate(out, base);
            addCandidate(out, appendQuery(base, "vss_id", "a.en"));
            addCandidate(out, appendQuery(base, "vssId", "a.en"));
        } catch (Throwable ignored) {
        }

        String minimal = new Uri.Builder()
                .scheme("https")
                .authority("www.youtube.com")
                .path("/api/timedtext")
                .appendQueryParameter("v", videoId)
                .appendQueryParameter("lang", "en")
                .appendQueryParameter("kind", "asr")
                .appendQueryParameter("caps", "asr")
                .appendQueryParameter("fmt", "json3")
                .build()
                .toString();
        addCandidate(out, minimal);
        return out;
    }

    private static void addCandidate(List<String> out, String value) {
        if (value == null || value.isEmpty()) return;
        if (!DeepSeekCaptionHook.isYouTubeTimedTextUrl(value)) return;
        if (!out.contains(value)) out.add(value);
    }

    private static String appendQuery(String url, String name, String value) {
        try {
            return Uri.parse(url).buildUpon().appendQueryParameter(name, value).build().toString();
        } catch (Throwable ignored) {
            return url;
        }
    }

    private static TrackIdentity classifyTrack(String url, CaptionDocument.Parsed document) {
        String lang = query(url, "lang");
        String kind = query(url, "kind");
        String vss = query(url, "vss_id");
        if (vss.isEmpty()) vss = query(url, "vssId");

        int confidence = englishTextConfidence(document);
        String lowerLang = lang.toLowerCase(Locale.ROOT);
        String lowerVss = vss.toLowerCase(Locale.ROOT);
        boolean englishHint = lowerLang.startsWith("en") ||
                lowerVss.equals("en") || lowerVss.endsWith(".en") ||
                lowerVss.startsWith("a.en") || lowerVss.contains(".en-");
        boolean asrHint = "asr".equalsIgnoreCase(kind.trim()) || lowerVss.startsWith("a.");
        boolean english = englishHint || confidence >= ENGLISH_TEXT_THRESHOLD;
        return new TrackIdentity(
                english && asrHint,
                english && !asrHint,
                lang,
                kind,
                vss,
                confidence
        );
    }

    /**
     * URL metadata is sometimes incomplete. Use the actual caption text as a second independent
     * signal so an English provider track cannot silently miss ASR calibration merely because a
     * YouTube experiment omitted lang=en from the request URL.
     */
    private static int englishTextConfidence(CaptionDocument.Parsed document) {
        if (document == null || document.cues().isEmpty()) return 0;
        int latinLetters = 0;
        int otherLetters = 0;
        int words = 0;
        int commonEnglish = 0;
        int budget = 24_000;
        StringBuilder word = new StringBuilder();

        int cueLimit = Math.min(document.cues().size(), 260);
        for (int i = 0; i < cueLimit && budget > 0; i++) {
            String text = document.cues().get(i).text;
            if (text == null) continue;
            int limit = Math.min(text.length(), budget);
            for (int j = 0; j < limit; j++) {
                char raw = text.charAt(j);
                if (Character.isLetter(raw)) {
                    Character.UnicodeScript script = Character.UnicodeScript.of(raw);
                    if (script == Character.UnicodeScript.LATIN) latinLetters++;
                    else otherLetters++;
                }

                char c = Character.toLowerCase(raw);
                if (c >= 'a' && c <= 'z') {
                    word.append(c);
                } else if ((c == '\'' || c == '’') && word.length() > 0) {
                    word.append(c);
                } else {
                    if (word.length() > 0) {
                        words++;
                        if (isCommonEnglishWord(word.toString())) commonEnglish++;
                        word.setLength(0);
                    }
                }
            }
            budget -= limit;
            if (word.length() > 0) {
                words++;
                if (isCommonEnglishWord(word.toString())) commonEnglish++;
                word.setLength(0);
            }
        }

        int letters = latinLetters + otherLetters;
        if (letters < 80 || words < 20) return 0;
        double latinRatio = latinLetters / (double) letters;
        double commonRatio = commonEnglish / (double) words;
        double lexicalEvidence = Math.min(1d, commonRatio / 0.12d);
        int score = (int) Math.round(60d * latinRatio + 40d * lexicalEvidence);
        return Math.max(0, Math.min(100, score));
    }

    private static boolean isCommonEnglishWord(String raw) {
        String value = raw == null ? "" : raw.replace("'", "").replace("’", "");
        switch (value) {
            case "the": case "and": case "of": case "to": case "in": case "a":
            case "is": case "that": case "for": case "it": case "on": case "with":
            case "as": case "was": case "at": case "by": case "this": case "from":
            case "be": case "are": case "or": case "have": case "an": case "not":
            case "but": case "we": case "you": case "they": case "he": case "she":
            case "his": case "her": case "their": case "our": case "your": case "will":
            case "would": case "can": case "could": case "should": case "has": case "had":
            case "were": case "been": case "do": case "does": case "did": case "what":
            case "who": case "when": case "where": case "why": case "how":
                return true;
            default:
                return false;
        }
    }

    private static String query(String url, String name) {
        try {
            String value = Uri.parse(url).getQueryParameter(name);
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Fetch fetch(String sourceUrl, boolean refreshed) throws Exception {
        if (!DeepSeekCaptionHook.isYouTubeTimedTextUrl(sourceUrl)) {
            throw new IllegalArgumentException("非法原字幕 URL");
        }

        URL url = new URL(sourceUrl);
        HttpURLConnection connection = DeepSeekCaptionHook.openWithYouTubeCronet(url);
        if (connection == null) connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", FreshYouTubeCookies.userAgent());
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
        String cookies = FreshYouTubeCookies.get(refreshed);
        if (!cookies.isEmpty() && cookies.indexOf('\r') < 0 && cookies.indexOf('\n') < 0) {
            connection.setRequestProperty("Cookie", cookies);
        }

        int status = connection.getResponseCode();
        String contentType = connection.getContentType();
        if ((status == 401 || status == 403 || status == 429) && !refreshed) {
            connection.disconnect();
            return fetch(sourceUrl, true);
        }
        if (status < 200 || status >= 300) {
            InputStream error = connection.getErrorStream();
            String detail = error == null ? "" : new String(
                    DeepSeekApiClient.readFully(error, 24 * 1024),
                    java.nio.charset.StandardCharsets.UTF_8
            );
            connection.disconnect();
            throw new IllegalStateException("原字幕 HTTP " + status + ": " + abbreviate(detail));
        }

        byte[] body;
        try {
            body = DeepSeekApiClient.readFully(connection.getInputStream(), MAX_SOURCE_BYTES);
        } finally {
            connection.disconnect();
        }
        if (body.length == 0) throw new IllegalStateException("原字幕响应为空");
        return new Fetch(body, contentType == null ? "application/octet-stream" : contentType);
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        String one = value.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 180 ? one : one.substring(0, 180);
    }

    static final class Source {
        final byte[] body;
        final String contentType;
        final String sourceUrl;
        final CaptionDocument.Parsed document;

        final SourceAtomTimeline.Result alignedAtoms;
        Source(byte[] body, String contentType, String sourceUrl, CaptionDocument.Parsed document) {
            this(body,contentType,sourceUrl,document,null);
        }
        Source(byte[] body, String contentType, String sourceUrl, CaptionDocument.Parsed document, SourceAtomTimeline.Result alignedAtoms) {
            this.alignedAtoms=alignedAtoms;
            this.body = body;
            this.contentType = contentType;
            this.sourceUrl = sourceUrl;
            this.document = document;
        }
    }

    private static final class TrackIdentity {
        final boolean englishAsr;
        final boolean englishProvider;
        final String lang;
        final String kind;
        final String vss;
        final int englishConfidence;

        TrackIdentity(
                boolean englishAsr,
                boolean englishProvider,
                String lang,
                String kind,
                String vss,
                int englishConfidence
        ) {
            this.englishAsr = englishAsr;
            this.englishProvider = englishProvider;
            this.lang = lang == null ? "" : lang;
            this.kind = kind == null ? "" : kind;
            this.vss = vss == null ? "" : vss;
            this.englishConfidence = englishConfidence;
        }

        String diagnostic() {
            String type = englishAsr ? "英语自动生成" : englishProvider ? "英语原轨" : "非英语/未确认";
            return type + "；lang=" + safe(lang) + "，kind=" + safe(kind) +
                    "，vss=" + safe(vss) + "，正文英文置信度 " + englishConfidence + "%";
        }

        private static String safe(String value) {
            if (value == null || value.trim().isEmpty()) return "-";
            String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
            return clean.length() <= 48 ? clean : clean.substring(0, 48);
        }
    }

    private static final class LoadedTrack {
        final byte[] body;
        final String contentType;
        final String url;

        LoadedTrack(byte[] body, String contentType, String url) {
            this.body = body;
            this.contentType = contentType;
            this.url = url;
        }
    }

    private static final class TimingAnchor {
        final byte[] body;
        final String contentType;
        final String url;
        final CaptionDocument.Parsed document;

        TimingAnchor(byte[] body, String contentType, String url, CaptionDocument.Parsed document) {
            this.body = body;
            this.contentType = contentType;
            this.url = url;
            this.document = document;
        }
    }

    private static final class Fetch {
        final byte[] body;
        final String contentType;
        Fetch(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }
    }
}
