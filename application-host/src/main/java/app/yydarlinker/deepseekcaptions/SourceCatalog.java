package app.yydarlinker.deepseekcaptions;

import java.util.*;

/** Bounded native catalogue, collected even when the overlay or source prewarm is inactive. */
final class SourceCatalog {
    static final class Snapshot {
        final String video, defaultLanguage;
        final boolean complete;
        final long revision;
        final List<SourceSelection> tracks;
        Snapshot(String video, String language, boolean complete, long revision, List<SourceSelection> tracks) {
            this.video = video; defaultLanguage = language; this.complete = complete; this.revision = revision;
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        }
    }
    private static final LinkedHashMap<String, Snapshot> videos = new LinkedHashMap<>();
    private static long revision;
    private static String currentVideo="";
    static synchronized void pin(String video){currentVideo=video==null?"":video;}
    private static void trim(){
        while(videos.size()>6){
            String victim=null;for(String video:videos.keySet())if(!video.equals(currentVideo)){victim=video;break;}
            if(victim==null)break;videos.remove(victim);
        }
    }

    static synchronized Snapshot snapshot(String video) {
        Snapshot known = videos.get(video);
        return known == null ? new Snapshot(video, "", false, revision, Collections.emptyList()) : known;
    }

    static synchronized void register(String video, List<SourceSelection> tracks, boolean complete) {
        Snapshot old = videos.get(video);
        if (old != null && old.complete == complete && old.tracks.size() == tracks.size()) {
            boolean same = true;
            for (int i = 0; i < tracks.size(); i++) if (!old.tracks.get(i).identity().equals(tracks.get(i).identity())
                    || !old.tracks.get(i).url.equals(tracks.get(i).url)) { same = false; break; }
            if (same) return;
        }
        String language = old == null ? "" : old.defaultLanguage;
        videos.put(video, new Snapshot(video, language, complete, ++revision, tracks));
        trim();
    }

    static synchronized void defaultLanguage(String video, String language) {
        Snapshot old = snapshot(video);
        // The first automatic native selection establishes the video's default source language.
        if (!old.defaultLanguage.isEmpty()) return;
        videos.put(video, new Snapshot(video, language, old.complete, ++revision, old.tracks));
        trim();
    }

    static List<SourceSelection> ranked(Snapshot snapshot, String requestedLanguage) {
        List<SourceSelection> result = new ArrayList<>(snapshot.tracks);
        Comparator<SourceSelection> order = Comparator
                .comparingInt((SourceSelection t) -> t.kind == SourceSelection.Kind.MANUAL ? 0 : t.kind == SourceSelection.Kind.ASR ? 1 : 2)
                .thenComparingInt(t -> match(t.language, requestedLanguage))
                .thenComparingInt(t -> match(t.language, snapshot.defaultLanguage))
                .thenComparing(t -> t.language).thenComparing(t -> t.trackId);
        result.sort(order);
        return result;
    }

    private static int match(String a, String b) {
        if (a.equalsIgnoreCase(b) && !b.isEmpty()) return 0;
        return !b.isEmpty() && Locale.forLanguageTag(a).getLanguage().equals(Locale.forLanguageTag(b).getLanguage()) ? 1 : 2;
    }
    static synchronized void clear() { videos.clear(); currentVideo="";revision++; }
}
