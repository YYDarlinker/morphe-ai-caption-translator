package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import java.util.*;
/** Stable UI keys resolved from the effective Morphe settings locale, with no global mutation. */
public final class CaptionStrings {
    private static final Map<String,String> english=new HashMap<>();
    static{for(String[] entry:CaptionTranslationCatalog.ENGLISH)english.put(entry[0],entry[1]);}
    public static String get(Context c,String key){
        c=CaptionUiLocale.context(c);
        if(c!=null)try{int id=c.getResources().getIdentifier("cap_"+key,"string",c.getPackageName());if(id!=0)return c.getString(id);}catch(Exception ignored){}
        return english.getOrDefault(key,"");
    }
    static String settings(Context c,String key){
        return get(c,key);
    }
    public static String localize(Context c,CharSequence value){
        if(value==null)return "";String source=value.toString();
        // Compatibility for known whole static UI sentences only. User/provider data is never scanned.
        for(String[] entry:CaptionTranslationCatalog.SOURCES)if(source.equals(entry[0]))return settings(c,entry[1]);
        return source;
    }
}
