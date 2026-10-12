package app.yydarlinker.deepseekcaptions;

import java.util.*;

/**
 * Chooses which of a video's own caption tracks is translated. YouTube's request names whatever
 * its automatic selection preferred (usually the viewer's own language), which is often the worst
 * source: a machine dub or a translation of the speech, or the target language itself. The
 * speech's own language is recognised from the video's automatic-speech track (YouTube only
 * generates it for what is actually spoken); a human-made track in that language is the best
 * text, then a human English track as the pivot, then the automatic track itself.
 *
 * <p>Pure ranking over the signed native descriptors already collected in {@link SourceCatalog};
 * no network, no language detection, no model.</p>
 */
final class SourceTrackPolicy {
    static final String VERSION = "original-first-v1";
    private SourceTrackPolicy() {}

    static final class Choice {
        final SourceSelection track;
        final String reason, original;
        Choice(SourceSelection track, String reason, String original) {
            this.track = track; this.reason = reason; this.original = original;
        }
    }

    /** The best source among {@code tracks}, or null when there is nothing to choose from. */
    static Choice rank(List<SourceSelection> tracks, String targetCode, String defaultLanguage) {
        List<SourceSelection> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SourceSelection track : tracks)
            if (seen.add(track.kind + "|" + track.language.toLowerCase(Locale.ROOT))) distinct.add(track);
        if (distinct.isEmpty()) return null;

        String original = spokenLanguage(distinct, targetCode, defaultLanguage);
        boolean speechIsTarget = !original.isEmpty() && SourceSelection.sameWritingLanguage(original, targetCode);
        SourceSelection best = null;
        int bestClass = Integer.MAX_VALUE;
        for (SourceSelection track : distinct) {
            int rank = classOf(track, original, speechIsTarget, targetCode);
            if (best == null || rank < bestClass
                    || (rank == bestClass && better(track, best, defaultLanguage))) {
                best = track;
                bestClass = rank;
            }
        }
        return new Choice(best, "class" + bestClass + (original.isEmpty() ? ";speech=unknown" : ";speech=" + original),
                original);
    }

    /**
     * The language the video's speech is in: its automatic-speech track, when it has one. If
     * there are several (for example a dubbed audio track), the one that is not the target
     * language is the original speech; then the one YouTube itself prefers.
     */
    private static String spokenLanguage(List<SourceSelection> tracks, String targetCode, String defaultLanguage) {
        SourceSelection found = null;
        for (SourceSelection track : tracks) {
            if (track.kind != SourceSelection.Kind.ASR) continue;
            if (found == null || asrBetter(track, found, targetCode, defaultLanguage)) found = track;
        }
        return found == null ? "" : found.language;
    }

    private static boolean asrBetter(SourceSelection a, SourceSelection b, String targetCode, String defaultLanguage) {
        boolean at = SourceSelection.sameWritingLanguage(a.language, targetCode), bt = SourceSelection.sameWritingLanguage(b.language, targetCode);
        if (at != bt) return !at;
        boolean ad = family(a.language).equals(family(defaultLanguage)), bd = family(b.language).equals(family(defaultLanguage));
        return ad && !bd;
    }

    private static int classOf(SourceSelection track, String original, boolean speechIsTarget, String target) {
        boolean ofTarget = SourceSelection.sameWritingLanguage(track.language, target);
        boolean human = track.kind != SourceSelection.Kind.ASR;
        boolean spoken = !original.isEmpty() && family(track.language).equals(family(original));
        boolean english = family(track.language).equals("en");
        if (speechIsTarget) {
            // The speech already is the target language: translating back from a translation is
            // never better than the speech's own text.
            if (spoken && human) return 0;
            if (spoken) return 1;
            return ofTarget ? 5 : human ? 2 : 3;
        }
        if (ofTarget) return human ? 6 : 7;
        if (spoken && human) return 0;
        if (english && human) return 1;
        if (spoken) return 2;
        if (human) return 3;
        return 4;
    }

    private static boolean better(SourceSelection a, SourceSelection b, String defaultLanguage) {
        boolean ad = family(a.language).equals(family(defaultLanguage)), bd = family(b.language).equals(family(defaultLanguage));
        if (ad != bd) return ad;
        boolean ae = family(a.language).equals("en"), be = family(b.language).equals("en");
        if (ae != be) return ae;
        int byLanguage = a.language.compareToIgnoreCase(b.language);
        return byLanguage != 0 ? byLanguage < 0 : a.trackId.compareTo(b.trackId) < 0;
    }

    private static String family(String code) {
        if (code == null || code.isEmpty()) return "";
        return Locale.forLanguageTag(code).getLanguage();
    }

    private static final Map<String, String> remembered = new HashMap<>();

    /**
     * Rewrites an AI-targeted timed-text URL to the preferred source track of its video. A URL
     * that already names the preferred track, a video without a complete catalogue, or a
     * catalogue with nothing to choose between is returned unchanged.
     */
    static synchronized String apply(android.content.Context context, String url) {
        TargetLanguage target = TargetLanguage.fromUrl(url);
        if (target == null) return url;
        String video = CaptionUrls.videoId(url);
        if (video.isEmpty()) return url;
        SourceCatalog.Snapshot snapshot = SourceCatalog.snapshot(video);
        if (!snapshot.complete || snapshot.tracks.size() < 2) return url;
        String key = video + '|' + target.code + '|' + snapshot.revision;
        String requested = descriptor(url);
        String prior = remembered.get(key);
        SourceSelection chosen = null;
        String reason = "";
        if (prior != null) {
            for (SourceSelection track : snapshot.tracks) if (descriptor(track.url).equals(prior)) { chosen = track; break; }
            reason = "remembered";
        }
        if (chosen == null) {
            Choice choice = rank(snapshot.tracks, target.code, snapshot.defaultLanguage);
            if (choice == null) return url;
            chosen = choice.track;
            reason = choice.reason;
            remembered.put(key, descriptor(chosen.url));
            if (remembered.size() > 16) remembered.remove(remembered.keySet().iterator().next());
            CaptionDiagnostics.mark(context, "SOURCE_TRACK_POLICY",
                    "policy=" + VERSION + ";video=" + video + ";target=" + target.code
                            + ";requested=" + summary(snapshot.tracks, requested)
                            + ";chosen=" + chosen.language + "/" + chosen.kind
                            + ";changed=" + !descriptor(chosen.url).equals(requested)
                            + ";default=" + snapshot.defaultLanguage + ";reason=" + reason
                            + ";candidates=" + candidates(snapshot.tracks));
        }
        if (descriptor(chosen.url).equals(requested)) return url;
        return TargetLanguage.withCode(chosen.url, target.code);
    }

    private static final Set<String> ownLogged = new HashSet<>();

    /**
     * The video's own track in the selected target language (human-made first), or null. Such a
     * track is shown as it is: translating text into the language it already is in only costs
     * money and risks the result.
     */
    static synchronized SourceSelection ownTargetTrack(android.content.Context context, String url) {
        TargetLanguage target = TargetLanguage.fromUrl(url);
        if (target == null) return null;
        String video = CaptionUrls.videoId(url);
        if (video.isEmpty()) return null;
        SourceCatalog.Snapshot snapshot = SourceCatalog.snapshot(video);
        if (!snapshot.complete) return null;
        SourceSelection best = null;
        for (SourceSelection track : snapshot.tracks) {
            if (!SourceSelection.sameWritingLanguage(track.language, target.code)) continue;
            if (best == null || kindOrder(track) < kindOrder(best)) best = track;
        }
        if (best != null) {
            String key = video + '|' + target.code + '|' + snapshot.revision;
            if (ownLogged.add(key)) {
                if (ownLogged.size() > 32) ownLogged.remove(ownLogged.iterator().next());
                CaptionDiagnostics.mark(context, "SOURCE_TRACK_OWN_TARGET",
                        "policy=" + VERSION + ";video=" + video + ";target=" + target.code
                                + ";shown=" + best.language + "/" + best.kind
                                + ";translation_request=false;candidates=" + candidates(snapshot.tracks));
            }
        }
        return best;
    }

    private static int kindOrder(SourceSelection track) {
        return track.kind == SourceSelection.Kind.MANUAL ? 0 : track.kind == SourceSelection.Kind.UNKNOWN ? 1 : 2;
    }

    /** A track's identity inside one video: language, kind and name. Signatures never differ it. */
    static String descriptor(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            return value(uri, "lang") + '|' + value(uri, "kind") + '|' + value(uri, "name");
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private static String value(android.net.Uri uri, String key) {
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
        int count = 0;
        for (SourceSelection track : tracks) {
            if (count++ >= 12) { out.append(",+").append(tracks.size() - 12); break; }
            if (out.length() > 0) out.append(',');
            out.append(track.language).append('/').append(track.kind.name().charAt(0));
        }
        return out.toString();
    }

    static synchronized void reset() { remembered.clear(); ownLogged.clear(); }
}
