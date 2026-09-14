package app.yydarlinker.deepseekcaptions;
import org.json.*;import java.net.URI;import java.net.URLDecoder;import java.nio.charset.StandardCharsets;
/** Only extracts this video's public English ASR track; never executes page scripts. */
final class WordTimingReference {
 static String find(String html,String videoId)throws Exception{
  String marker="ytInitialPlayerResponse";int from=0;
  while((from=html.indexOf(marker,from))>=0){int eq=html.indexOf('=',from+marker.length());if(eq<0)break;int start=eq+1;while(start<html.length()&&Character.isWhitespace(html.charAt(start)))start++;from=start;
   if(start>=html.length()||html.charAt(start)!='{')continue;
   boolean string=false,escape=false;int depth=0,end=-1;
   for(int i=start;i<html.length();i++){char c=html.charAt(i);if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}
    else if(c=='"')string=true;else if(c=='{')depth++;else if(c=='}'&&--depth==0){end=i+1;break;}}
   if(end<0)continue;from=end;JSONObject root=new JSONObject(html.substring(start,end));JSONObject details=root.optJSONObject("videoDetails");if(details==null||!videoId.equals(details.optString("videoId")))continue;
   JSONObject captions=root.optJSONObject("captions");if(captions==null)continue;JSONObject list=captions.optJSONObject("playerCaptionsTracklistRenderer");if(list==null)continue;JSONArray tracks=list.optJSONArray("captionTracks");if(tracks==null)continue;
   for(int i=0;i<tracks.length();i++){JSONObject t=tracks.optJSONObject(i);if(t==null||!"en".equals(t.optString("languageCode"))||!"asr".equals(t.optString("kind")))continue;String url=t.optString("baseUrl");if(safe(url,videoId))return SourceFormatPolicy.json3(url);}
  }return "";
 }
 static boolean safe(String url,String video){try{URI u=URI.create(url);String host=u.getHost();if(!"https".equals(u.getScheme())||!("www.youtube.com".equals(host)||"youtube.com".equals(host))||!"/api/timedtext".equals(u.getPath()))return false;
  if(u.getRawQuery()==null)return false;for(String part:u.getRawQuery().split("&")){String[] x=part.split("=",2);if(x.length==2&&"v".equals(x[0])&&video.equals(URLDecoder.decode(x[1],"UTF-8")))return true;}return false;
 }catch(Exception e){return false;}}
 static boolean sameWords(CaptionDocument.Parsed a,CaptionDocument.Parsed b){StringBuilder x=new StringBuilder(),y=new StringBuilder();for(CaptionDocument.Cue c:a.cues())x.append(SourcePhraseAlignment.canonical(c.text));for(CaptionDocument.Cue c:b.cues())y.append(SourcePhraseAlignment.canonical(c.text));return x.length()>0&&x.toString().equals(y.toString());}
}
