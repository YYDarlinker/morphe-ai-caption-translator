package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which of a video's own caption tracks is translated. YouTube's request names whatever its
 * automatic selection preferred (usually the viewer's own language), which is often the worst
 * source: a machine dub or a translation of the speech, or the target language itself. The speech's
 * own language is recognised from the video's automatic-speech track (YouTube only generates it for
 * what is actually spoken); a human-made track in that language is the best text, then a human
 * English track as the pivot, then the automatic track itself.
 *
 * <p>Pure ranking over the signed native descriptors already collected in {@link SourceCatalog}; no
 * network, no language detection, no model.
 */
final class SourceTrackPolicy {
    private static final String VERSION = "original-first-v1";
    private static final int MAX_REMEMBERED = 16, MAX_LOGGED = 32, MAX_LISTED = 12;

    /** The choice made per video, target and catalogue revision, so a video keeps its source. */
    private static final Map<String, String> remembered = new HashMap<>();

    private static final Set<String> ownLogged = new HashSet<>();

    private SourceTrackPolicy() {}

    private static final class Choice {
        final SourceSelection track;
        final String reason;

        Choice(SourceSelection track, String reason) {
            this.track = track;
            this.reason = reason;
        }
    }

    /**
     * Rewrites an AI-targeted timed-text url to the preferred source track of its video. A url that
     * already names the preferred track, a video without a complete catalogue, or a catalogue with
     * nothing to choose between is returned unchanged.
     */
    /**
     * Is the source track this url names a manually authored one? Answered from the same signed
     * catalogue the choice above was made from; an unknown video or track is not authored, so the
     * local page rules apply as before.
     */
    static synchronized boolean authored(String url) {
        if (url == null) return false;
        String video = CaptionUrls.videoId(url);
        if (video.isEmpty()) return false;
        SourceCatalog.Snapshot snapshot = SourceCatalog.snapshot(video);
        if (snapshot == null) return false;
        String wanted = descriptor(url);
        for (SourceSelection track : snapshot.tracks)
            if (descriptor(track.url).equals(wanted)) return track.kind == SourceSelection.Kind.MANUAL;
        return false;
    }

    static synchronized String apply(Context context, String url) {
        TargetLanguage target = TargetLanguage.fromUrl(url);
        String video = target == null ? "" : CaptionUrls.videoId(url);
        if (video.isEmpty()) return url;
        SourceCatalog.Snapshot snapshot = SourceCatalog.snapshot(video);
        if (!snapshot.complete || snapshot.tracks.size() < 2) return url;

        String key = video + '|' + target.code + '|' + snapshot.revision;
        String requested = descriptor(url);
        SourceSelection chosen = null;
        String prior = remembered.get(key);
        if (prior != null)
            for (SourceSelection track : snapshot.tracks)
                if (descriptor(track.url).equals(prior)) {
                    chosen = track;
                    break;
                }
        if (chosen == null) {
            Choice choice = rank(snapshot.tracks, target.code, snapshot.defaultLanguage);
            if (choice == null) return url;
            chosen = choice.track;
            remembered.put(key, descriptor(chosen.url));
            if (remembered.size() > MAX_REMEMBERED)
                remembered.remove(remembered.keySet().iterator().next());
            CaptionDiagnostics.mark(
                    context,
                    "SOURCE_TRACK_POLICY",
                    "policy="
                            + VERSION
                            + ";video="
                            + video
                            + ";target="
                            + target.code
                            + ";requested="
                            + summary(snapshot.tracks, requested)
                            + ";chosen="
                            + chosen.language
                            + "/"
                            + chosen.kind
                            + ";changed="
                            + !descriptor(chosen.url).equals(requested)
                            + ";default="
                            + snapshot.defaultLanguage
                            + ";reason="
                            + choice.reason
                            + ";candidates="
                            + candidates(snapshot.tracks));
        }
        return descriptor(chosen.url).equals(requested)
                ? url
                : TargetLanguage.withCode(chosen.url, target.code);
    }

    /**
     * The video's own track in the selected target language (human-made first), or null. Such a
     * track is shown as it is: translating text into the language it already is in only costs money
     * and risks the result.
     */
    static synchronized SourceSelection ownTargetTrack(Context context, String url) {
        TargetLanguage target = TargetLanguage.fromUrl(url);
        String video = target == null ? "" : CaptionUrls.videoId(url);
        if (video.isEmpty()) return null;
        SourceCatalog.Snapshot snapshot = SourceCatalog.snapshot(video);
        if (!snapshot.complete) return null;
        SourceSelection best = null;
        for (SourceSelection track : snapshot.tracks)
            if (SourceSelection.sameWritingLanguage(track.language, target.code)
                    && (best == null || kindOrder(track) < kindOrder(best))) best = track;
        if (best != null && ownLogged.add(video + '|' + target.code + '|' + snapshot.revision)) {
            if (ownLogged.size() > MAX_LOGGED) ownLogged.remove(ownLogged.iterator().next());
            CaptionDiagnostics.mark(
                    context,
                    "SOURCE_TRACK_OWN_TARGET",
                    "policy="
                            + VERSION
                            + ";video="
                            + video
                            + ";target="
                            + target.code
                            + ";shown="
                            + best.language
                            + "/"
                            + best.kind
                            + ";translation_request=false;candidates="
                            + candidates(snapshot.tracks));
        }
        return best;
    }

    static synchronized void reset() {
        remembered.clear();
        ownLogged.clear();
    }

    // ---- Ranking ----

    /** The best source among [tracks], or null when there is nothing to choose from. */
    private static Choice rank(
            List<SourceSelection> tracks, String targetCode, String defaultLanguage) {
        List<SourceSelection> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SourceSelection track : tracks)
            if (seen.add(track.kind + "|" + track.language.toLowerCase(Locale.ROOT)))
                distinct.add(track);
        if (distinct.isEmpty()) return null;

        String original = spokenLanguage(distinct, targetCode, defaultLanguage);
        boolean speechIsTarget =
                !original.isEmpty() && SourceSelection.sameWritingLanguage(original, targetCode);
        SourceSelection best = null;
        int bestClass = Integer.MAX_VALUE;
        for (SourceSelection track : distinct) {
            int rank = classOf(track, original, speechIsTarget, targetCode);
            if (best == null
                    || rank < bestClass
                    || (rank == bestClass && better(track, best, defaultLanguage))) {
                best = track;
                bestClass = rank;
            }
        }
        return new Choice(
                best,
                "class" + bestClass + ";speech=" + (original.isEmpty() ? "unknown" : original));
    }

    /**
     * The language the video's speech is in: its automatic-speech track, when it has one. If there
     * are several (for example a dubbed audio track), the one that is not the target language is
     * the original speech; then the one YouTube itself prefers.
     */
    private static String spokenLanguage(
            List<SourceSelection> tracks, String targetCode, String defaultLanguage) {
        SourceSelection found = null;
        for (SourceSelection track : tracks)
            if (track.kind == SourceSelection.Kind.ASR
                    && (found == null || asrBetter(track, found, targetCode, defaultLanguage)))
                found = track;
        return found == null ? "" : found.language;
    }

    private static boolean asrBetter(
            SourceSelection a, SourceSelection b, String targetCode, String defaultLanguage) {
        boolean aTarget = SourceSelection.sameWritingLanguage(a.language, targetCode);
        boolean bTarget = SourceSelection.sameWritingLanguage(b.language, targetCode);
        if (aTarget != bTarget) return !aTarget;
        return sameFamily(a.language, defaultLanguage) && !sameFamily(b.language, defaultLanguage);
    }

    /** Lower is better. Human text in the speech's language first; the target language last. */
    private static int classOf(
            SourceSelection track, String original, boolean speechIsTarget, String target) {
        boolean ofTarget = SourceSelection.sameWritingLanguage(track.language, target);
        boolean human = track.kind != SourceSelection.Kind.ASR;
        boolean spoken = !original.isEmpty() && sameFamily(track.language, original);
        if (speechIsTarget) {
            // The speech already is the target language: translating back from a translation is
            // never better than the speech's own text.
            if (spoken) return human ? 0 : 1;
            return ofTarget ? 5 : human ? 2 : 3;
        }
        if (ofTarget) return human ? 6 : 7;
        if (spoken && human) return 0;
        if (human && family(track.language).equals("en")) return 1;
        if (spoken) return 2;
        return human ? 3 : 4;
    }

    /** The tie-break inside one class: YouTube's default, then English, then by name. */
    private static boolean better(SourceSelection a, SourceSelection b, String defaultLanguage) {
        boolean aDefault = sameFamily(a.language, defaultLanguage);
        if (aDefault != sameFamily(b.language, defaultLanguage)) return aDefault;
        boolean aEnglish = family(a.language).equals("en");
        if (aEnglish != family(b.language).equals("en")) return aEnglish;
        int byLanguage = a.language.compareToIgnoreCase(b.language);
        return byLanguage != 0 ? byLanguage < 0 : a.trackId.compareTo(b.trackId) < 0;
    }

    private static boolean sameFamily(String a, String b) {
        return family(a).equals(family(b));
    }

    private static String family(String code) {
        return code == null || code.isEmpty() ? "" : Locale.forLanguageTag(code).getLanguage();
    }

    private static int kindOrder(SourceSelection track) {
        return track.kind == SourceSelection.Kind.MANUAL
                ? 0
                : track.kind == SourceSelection.Kind.UNKNOWN ? 1 : 2;
    }

    // ---- Identity and diagnostics ----

    /** A track's identity inside one video: language, kind and name. Signatures never differ it. */
    static String descriptor(String url) {
        try {
            Uri uri = Uri.parse(url);
            return value(uri, "lang") + '|' + value(uri, "kind") + '|' + value(uri, "name");
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private static String value(Uri uri, String key) {
        String value = uri.getQueryParameter(key);
        return value == null ? "" : value;
    }

    private static String summary(List<SourceSelection> tracks, String descriptor) {
        for (SourceSelection track : tracks)
            if (descriptor(track.url).equals(descriptor)) return track.language + "/" + track.kind;
        return "unlisted";
    }

    private static String candidates(List<SourceSelection> tracks) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            if (i == MAX_LISTED) {
                out.append(",+").append(tracks.size() - MAX_LISTED);
                break;
            }
            if (i > 0) out.append(',');
            SourceSelection track = tracks.get(i);
            out.append(track.language).append('/').append(track.kind.name().charAt(0));
        }
        return out.toString();
    }
}
