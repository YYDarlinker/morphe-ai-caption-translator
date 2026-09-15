package app.yydarlinker.deepseekcaptions;
import android.app.Activity;import android.app.AlertDialog;import android.content.Context;import android.graphics.drawable.Drawable;import android.view.View;import android.widget.Toast;import java.nio.charset.StandardCharsets;import java.util.*;
/** Same state as settings. Only the native player's recognized top menu receives the entry. */
public final class CaptionQuickToggle {
    private static long shortsMenuAt;
    private static String shortsVideo="";
    public static void observeMenuPath(String path,byte[] buffer){
        if(!shortsOpen()||path==null||!path.startsWith("overflow_menu_item.e")||buffer==null)return;
        if(path.contains("captions_sheet")||path.contains("quality_sheet"))return;
        String data=new String(buffer,StandardCharsets.ISO_8859_1);
        if(data.contains("closed_caption")||data.contains("closed_captions")){shortsMenuAt=android.os.SystemClock.uptimeMillis();shortsVideo=PageCaptionController.currentVideoIdSnapshot();}
    }
    public static void onMenu(Object panel){
        Activity a=CaptionAddonSupport.activity();if(a==null||!CaptionAddonSupport.aiInstalled())return;
        boolean shorts=shortsMenuAt>0&&shortsOpen()&&android.os.SystemClock.uptimeMillis()-shortsMenuAt<1500&&shortsVideo.equals(PageCaptionController.currentVideoIdSnapshot());
        if(!topMenu()&&!shorts)return;shortsMenuAt=0;
        String text=CaptionStrings.get(a,"ai_title")+" · "+CaptionStrings.get(a,DeepSeekConfig.enabled(a)?"on":"off");
        int id=a.getResources().getIdentifier("deepseek_caption_settings","drawable",a.getPackageName());Drawable icon=id==0?null:a.getDrawable(id);
        addNativeRow(panel,icon,text,v->{dismissNative();show(a);},0);
    }
    static void show(Activity activity){
        String[] labels={CaptionStrings.get(activity,"use_youtube"),CaptionStrings.get(activity,"use_ai")};
        new AlertDialog.Builder(activity).setTitle(CaptionStrings.get(activity,"ai_title"))
            .setSingleChoiceItems(labels,DeepSeekConfig.enabled(activity)?1:0,(dialog,which)->{if(setEngine(activity,which==1)){dialog.dismiss();}})
            .setNegativeButton(CaptionStrings.get(activity,"cancel"),null).show();
    }
    static boolean setEngine(Context context,boolean enabled){
        if(enabled && DeepSeekConfig.load(context).apiKey.isEmpty()){Toast.makeText(context,CaptionStrings.get(context,"configure_api"),Toast.LENGTH_LONG).show();return false;}
        boolean previous=DeepSeekConfig.enabled(context);if(previous==enabled)return true;
        if(CaptionChoice.isOn()&&!NativeCaptionBridge.canReselect()){Toast.makeText(context,CaptionStrings.get(context,"switch_failed"),Toast.LENGTH_LONG).show();return false;}
        DeepSeekConfig.saveEnabled(context,enabled);DynamicCaptionController.refreshConfiguration(context);
        try{NativeCaptionBridge.refreshNativeTrack();CaptionMusicSuppressor.kick();}
        catch(Exception failed){DeepSeekConfig.saveEnabled(context,previous);DynamicCaptionController.refreshConfiguration(context);Toast.makeText(context,CaptionStrings.get(context,"switch_failed"),Toast.LENGTH_LONG).show();return false;}
        if(enabled&&CaptionChoice.isOn()&&!CaptionChoice.translates())Toast.makeText(context,CaptionStrings.get(context,"choose_translation"),Toast.LENGTH_LONG).show();
        return true;
    }
    public static boolean shortsOpen(){return false;}
    public static boolean topMenu(){return false;}
    public static int addNativeRow(Object panel,Drawable icon,String label,View.OnClickListener click,int index){return -1;}
    public static void dismissNative(){}
}
