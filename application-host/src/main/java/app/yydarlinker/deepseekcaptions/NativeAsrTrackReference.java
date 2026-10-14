package app.yydarlinker.deepseekcaptions;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded, process-only, same-video descriptors of the automatic-speech tracks. A language may
 * expose more than one signed descriptor (for example a rich and a coarse variant), so each keeps
 * several urls: a parseable coarse response must not evict the useful one before the source worker
 * has inspected its timing capability.
 */
final class NativeAsrTrackReference {
    private static final int MAX_VIDEOS = 4, MAX_LANGUAGES = 16, MAX_URLS_PER_LANGUAGE = 4;

    /** Per video, per lowercase language, the urls with the newest last. */
    private static final Map<String, Map<String, List<String>>> tracks = new LinkedHashMap<>();

    private NativeAsrTrackReference() {}

    static synchronized void remember(String language, String vss, String url) {
        if (language == null
                || language.isEmpty()
                || vss == null
                || !vss.startsWith("a.")
                || url == null) return;
        String video = query(url, "v");
        if (!CaptionSourceIdentity.safe(url, video)) return;
        String declared = query(url, "lang");
        if (!declared.isEmpty() && !declared.equalsIgnoreCase(language)) return;
        if (!tracks.containsKey(video) && tracks.size() >= MAX_VIDEOS)
            tracks.remove(tracks.keySet().iterator().next());
        Map<String, List<String>> languages =
                tracks.computeIfAbsent(video, key -> new LinkedHashMap<>());
        String key = languageKey(language);
        if (!languages.containsKey(key) && languages.size() >= MAX_LANGUAGES) return;
        List<String> urls = languages.computeIfAbsent(key, k -> new ArrayList<>());
        urls.remove(url);
        urls.add(url);
        while (urls.size() > MAX_URLS_PER_LANGUAGE) urls.remove(0);
    }

    /** The urls of [video]: those of [language] first, then its same-language aliases, then all. */
    static synchronized List<String> candidates(String video, String language) {
        Map<String, List<String>> languages = tracks.get(video);
        if (languages == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        String exact = languageKey(language);
        append(languages.get(exact), out);
        for (Map.Entry<String, List<String>> entry : languages.entrySet())
            if (!entry.getKey().equals(exact)
                    && CaptionSourceIdentity.sameLanguage(entry.getKey(), language))
                append(entry.getValue(), out);
        for (List<String> urls : languages.values()) append(urls, out);
        return out;
    }

    private static void append(List<String> urls, List<String> out) {
        if (urls == null) return;
        for (String url : urls) if (!out.contains(url)) out.add(url);
    }

    private static String languageKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String query(String url, String key) {
        try {
            String query = URI.create(url).getRawQuery();
            if (query != null)
                for (String part : query.split("&")) {
                    String[] pair = part.split("=", 2);
                    if (pair.length == 2 && pair[0].equals(key))
                        return URLDecoder.decode(pair[1], "UTF-8");
                }
        } catch (Exception malformed) {
            // Not a url: no value.
        }
        return "";
    }
}
