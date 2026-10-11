package app.yydarlinker.deepseekcaptions;
final class CaptionSourceIdentity { static boolean safe(String url,String video){try{
  if(video==null||!video.matches("[A-Za-z0-9_-]{11}"))return false;
  java.net.URI u=java.net.URI.create(url);String host=u.getHost();
  if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443)||!("www.youtube.com".equals(host)||"youtube.com".equals(host))||!"/api/timedtext".equals(u.getPath())||u.getRawQuery()==null)return false;
  int videos=0;boolean same=false;
  for(String part:u.getRawQuery().split("&")){String[] x=part.split("=",2);String key=java.net.URLDecoder.decode(x[0],"UTF-8");String value=x.length==2?java.net.URLDecoder.decode(x[1],"UTF-8"):"";
   if("v".equals(key)){videos++;same=video.equals(value);}if("tlang".equals(key)&&!value.isEmpty())return false;}
  return videos==1&&same;
 }catch(Exception e){return false;}}

 static boolean sameLanguage(String a,String b){if(a==null||b==null)return false;return java.util.Locale.forLanguageTag(a).getLanguage().equals(java.util.Locale.forLanguageTag(b).getLanguage());} }
