package app.yydarlinker.deepseekcaptions;

import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, process-only lookup of same-video ASR URLs already exposed by the native track list. */
final class NativeAsrTrackReference {
    private static final Map<String,String> tracks=new LinkedHashMap<>();
    static synchronized void remember(String language,String vss,String url){
        if(language==null || !(language.equals("en")||language.startsWith("en-")))return;
        if(vss==null || !vss.startsWith("a."))return;
        String video=video(url);if(!WordTimingReference.safe(url,video))return;
        if(!tracks.containsKey(video)&&tracks.size()>=4)tracks.remove(tracks.keySet().iterator().next());
        tracks.put(video,url);
    }
    static synchronized String find(String video){String url=tracks.get(video);return url==null?"":url;}
    static synchronized void clear(){tracks.clear();}
    private static String video(String url){try{String query=URI.create(url).getRawQuery();if(query!=null)for(String part:query.split("&")){String[] pair=part.split("=",2);if(pair.length==2&&pair[0].equals("v"))return URLDecoder.decode(pair[1],"UTF-8");}}catch(Exception ignored){}return "";}
}
