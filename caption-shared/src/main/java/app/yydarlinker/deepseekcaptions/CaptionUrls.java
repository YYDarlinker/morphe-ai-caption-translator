package app.yydarlinker.deepseekcaptions;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** Source URL and video identity helpers shared by UI, host and core. */
final class CaptionUrls {
    private CaptionUrls() {}

    static boolean isTimedText(String value) {
        try { java.net.URI u=java.net.URI.create(value);String h=u.getHost();
            return "https".equals(u.getScheme()) && ("www.youtube.com".equals(h)||"youtube.com".equals(h))
                && u.getRawUserInfo()==null && (u.getPort()==-1||u.getPort()==443) && "/api/timedtext".equals(u.getPath());
        } catch(Exception e){return false;}
    }
    static String videoId(String value) {
        try { java.util.List<String> ids=Uri.parse(value).getQueryParameters("v");return ids.size()==1?ids.get(0):""; }
        catch(Exception e){return "";}
    }
    static String sourceCaptionUrl(String translatedUrl) {
        if (translatedUrl == null) return null;
        int fragmentIndex = translatedUrl.indexOf('#');
        String fragment = fragmentIndex >= 0 ? translatedUrl.substring(fragmentIndex) : "";
        String withoutFragment = fragmentIndex >= 0
                ? translatedUrl.substring(0, fragmentIndex)
                : translatedUrl;

        int queryStart = withoutFragment.indexOf('?');
        if (queryStart < 0 || queryStart == withoutFragment.length() - 1) {
            return withoutFragment + fragment;
        }

        String prefix = withoutFragment.substring(0, queryStart);
        String rawQuery = withoutFragment.substring(queryStart + 1);
        String[] parts = rawQuery.split("&", -1);
        List<String> kept = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int equals = part.indexOf('=');
            String rawKey = equals < 0 ? part : part.substring(0, equals);
            String decodedKey;
            try {
                decodedKey = Uri.decode(rawKey);
            } catch (Throwable ignored) {
                decodedKey = rawKey;
            }
            if ("tlang".equalsIgnoreCase(decodedKey)) continue;
            kept.add(part);
        }
        return prefix + (kept.isEmpty() ? "" : "?" + String.join("&", kept)) + fragment;
    }

}
