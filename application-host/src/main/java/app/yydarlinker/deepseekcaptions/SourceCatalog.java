package app.yydarlinker.deepseekcaptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** The native source tracks of recent videos, collected even while the overlay is inactive. */
final class SourceCatalog {
    private static final int MAX_VIDEOS = 6;

    /** What is known of one video; [revision] changes whenever the tracks or the default change. */
    static final class Snapshot {
        final String defaultLanguage;
        final boolean complete;
        final long revision;
        final List<SourceSelection> tracks;

        Snapshot(String language, boolean complete, long revision, List<SourceSelection> tracks) {
            defaultLanguage = language;
            this.complete = complete;
            this.revision = revision;
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        }
    }

    /** Oldest first. */
    private static final LinkedHashMap<String, Snapshot> videos = new LinkedHashMap<>();

    private static long revision;

    private SourceCatalog() {}

    static synchronized Snapshot snapshot(String video) {
        Snapshot known = videos.get(video);
        return known != null
                ? known
                : new Snapshot("", false, revision, Collections.<SourceSelection>emptyList());
    }

    /** Stores the tracks of [video]; an identical registration changes nothing. */
    static synchronized void register(
            String video, List<SourceSelection> tracks, boolean complete) {
        Snapshot old = videos.get(video);
        if (old != null && old.complete == complete && sameTracks(old.tracks, tracks)) return;
        put(
                video,
                new Snapshot(old == null ? "" : old.defaultLanguage, complete, ++revision, tracks));
    }

    /** The first automatic native selection establishes the default source language of a video. */
    static synchronized void defaultLanguage(String video, String language) {
        Snapshot old = snapshot(video);
        if (old.defaultLanguage.isEmpty())
            put(video, new Snapshot(language, old.complete, ++revision, old.tracks));
    }

    static synchronized void clear() {
        videos.clear();
        revision++;
    }

    private static void put(String video, Snapshot value) {
        videos.put(video, value);
        while (videos.size() > MAX_VIDEOS) videos.remove(videos.keySet().iterator().next());
    }

    private static boolean sameTracks(List<SourceSelection> a, List<SourceSelection> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).identity().equals(b.get(i).identity())
                    || !a.get(i).url.equals(b.get(i).url)) return false;
        return true;
    }
}
