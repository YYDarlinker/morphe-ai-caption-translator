package app.yydarlinker.deepseekcaptions;
final class CaptionHostPolicy { static boolean shouldPassThroughUnresolvedActivation(boolean installed,boolean requested,String urlVideo,String foreground){return installed&&requested&&(urlVideo==null||urlVideo.trim().isEmpty())&&(foreground==null||foreground.trim().isEmpty());} }
