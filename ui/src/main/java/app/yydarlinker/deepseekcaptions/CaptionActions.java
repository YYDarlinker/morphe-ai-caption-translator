package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import app.yydarlinker.caption.contracts.CaptionContracts.*;
import java.util.*;

/** UI-to-application command port, not an engine. Fake host replaces this without provider loading. */
final class CaptionActions {
    private static volatile CaptionPort port=new CaptionPort(){public void command(Action a,String v){}public boolean active(){return false;}public boolean ownsNativeTrack(){return false;}public String restoreTarget(String u){return u;}public String videoId(){return "";}public void playback(long t){}};
    interface SettingsServices {
        String testApi() throws Exception; List<String> models(String endpoint) throws Exception;
        default List<CaptionDisplayPage> displayPages(String text,long start,long end,CaptionPresentationBudget geometry,CaptionRenderSpec spec){return Collections.emptyList();}
    }
    private static volatile SettingsServices services=new SettingsServices(){
        public String testApi(){throw new IllegalStateException("Translation service is not ready.");}
        public List<String> models(String endpoint){throw new IllegalStateException("Translation service is not ready.");}
    };
    static void install(CaptionPort p,SettingsServices s){port=Objects.requireNonNull(p);services=Objects.requireNonNull(s);}
    static void command(Action a,String v){port.command(a,v==null?"":v);}
    static void refreshConfiguration(Context c){command(Action.CONFIGURATION,"");}
    static void appearanceChanged(){command(Action.APPEARANCE,"");}
    static void clearCache(Context c){command(Action.CLEAR_CACHE,"");}
    static void deactivateFromCaptionButton(){command(Action.DEACTIVATE,"caption_button");}
    static void deactivateFromNativeCaptionState(){command(Action.DEACTIVATE,"native_selection");}
    static void activate(Context c,String u){command(Action.ACTIVATE,u);}
    static void activate(Context c,String u,boolean original,boolean show){command(original?Action.SOURCE:show?Action.ACTIVATE:Action.PREWARM,u);}
    static void activateSource(Context c,String u){command(Action.SOURCE,u);}
    static void observeTimedTextUrl(String u){command(Action.SOURCE,u);}
    static void prewarm(Context c,String u){command(Action.SOURCE,u);}
    static boolean isVisibleActive(){return port.active();}
    static boolean visible(){return port.active();}
    static boolean ownsNativeTrack(){return port.ownsNativeTrack();}
    static String restoreTargetAfterMiniplayer(String u){return port.restoreTarget(u);}
    static String currentVideoIdSnapshot(){return port.videoId();}
    static String currentVideo(){return port.videoId();}
    static void onVideoId(String v){command(Action.VIDEO,v);}
    static void onPlayerType(String t){command(Action.PLAYER_TYPE,t);}
    static void onVideoTime(long t){port.playback(t);}
    static List<CaptionDisplayPage> displayPages(String text,long start,long end,CaptionPresentationBudget geometry,CaptionRenderSpec spec){return services.displayPages(text,start,end,geometry,spec);}
    static String testApi() throws Exception{return services.testApi();}
    static List<String> models(String endpoint) throws Exception{return services.models(endpoint);}
    static String videoIdFromUrl(String u){try{java.util.List<String> ids=android.net.Uri.parse(u).getQueryParameters("v");return ids.size()==1?ids.get(0):"";}catch(Exception e){return "";}}
}
