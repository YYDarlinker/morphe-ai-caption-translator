package app.yydarlinker.deepseekcaptions;
import android.content.*;import org.json.*;
/** On-device, opt-in bounded evidence. Never stores an HTTP body, key, cookie or endpoint. */
final class CaptionQualityTrace {
    private static final String PREFS="caption_quality_evidence";
    private static final int MAX_RECORDS=6,MAX_CHARS=36_000;
    static synchronized void record(Context context,String key,long request,JSONObject source,String response,String metadata){
        if(context==null || !DeepSeekConfig.displayTextDebugEnabled(context))return;
        try {
            SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            JSONArray old=new JSONArray(p.getString("records","[]")),next=new JSONArray();
            JSONObject item=new JSONObject().put("at",System.currentTimeMillis()).put("request",request)
                    .put("settings",redact(metadata,key,700));
            if(source!=null)item.put("source",redact(source.toString(),key,12000));
            if(response!=null&&!response.isEmpty())item.put("response",redact(response,key,12000));
            next.put(item);int chars=item.toString().length();
            for(int i=0;i<old.length()&&next.length()<MAX_RECORDS;i++){
                JSONObject entry=old.optJSONObject(i);if(entry==null)continue;
                if(System.currentTimeMillis()-entry.optLong("at",0)>24*60*60*1000L)continue;
                int size=entry.toString().length();if(chars+size>MAX_CHARS)break;next.put(entry);chars+=size;
            }
            p.edit().putString("records",next.toString()).apply();
        }catch(Exception ignored){}
    }
    static String redact(String value,String key,int limit){
        String text=value==null?"":value;
        if(key!=null&&!key.isEmpty())text=text.replace(key,"[redacted]");
        text=text.replaceAll("(?i)https?://[^\\s\\\"<>]+","[URL redacted]")
                .replaceAll("(?i)(bearer\\s+|sk-)[A-Za-z0-9_.-]{8,}","[credential redacted]");
        return text.length()<=limit?text:text.substring(0,limit)+" [truncated]";
    }
    static synchronized String text(Context c){
        if(c==null||!DeepSeekConfig.displayTextDebugEnabled(c))return "";
        try{JSONArray old=new JSONArray(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("records","[]"));
            JSONArray keep=new JSONArray();for(int i=0;i<old.length();i++){
                JSONObject row=old.optJSONObject(i);if(row!=null&&System.currentTimeMillis()-row.optLong("at",0)<=24*60*60*1000L)keep.put(row);
            }
            return keep.length()==0?"":"\n\n[Quality evidence / local, opt-in, bounded, 24h]\n"+keep.toString(2);
        }catch(Exception ignored){return "";}
    }
    static void clear(Context c){if(c!=null)c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();}
}
