package app.yydarlinker.deepseekcaptions;
import java.util.*;
/** Live-video adaptation of documented timed-text guidelines; source clock always wins. */
final class CaptionPresentationPolicy {
    static final int CJK_LINE=16,CJK_EVENT=32;
    static final long MIN_MS=950,MAX_MS=7000;
    static boolean cjk(String s){for(int cp:s.codePoints().toArray())if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)return true;return false;}
    static int visible(String s){return (int)s.codePoints().filter(cp->!Character.isWhitespace(cp)).count();}
    static String issue(String text,long duration){
        int limit=cjk(text)?CJK_EVENT:84;
        if(duration>MAX_MS)return "duration_over_7s";
        if(visible(text)>limit)return "text_over_two_lines";
        if(duration<MIN_MS)return "brief_source_interval";
        return "";
    }
    static String requestRules(){return "Choose one complete clause or short sentence per subtitle, never combine different speakers. "
        +"Do not cut a sentence merely because a YouTube ASR cue ended: ASR cues are timing atoms, not sentence boundaries. Prefer 1.2-6 seconds; avoid flashes below 0.95s. "
        +"Chinese: aim for 12-24 visible characters, at most 32 per event (two 16-character lines), and about 9 chars/s. "
        +"Other languages: at most two 42-character lines, about 17 chars/s. "
        +"Split only at sentence-final punctuation, a genuine clause boundary, a speaker change, or a clear breath/pause. Never split verb/object, preposition/object, article/noun, model numbers, number/unit, or dependent/main clauses. Attach a short fragment to the nearest clause. Preserve decimal points, versions, initials, URLs and model names such as GPT-5.6 Sol exactly. "
        +"Timing array is [id,endDeciseconds] relative to window start, approximate when word timing is estimated. "
        +"Never invent extra speech or alter the indexed source coverage to meet reading speed.";}
    // Visual line wrap only: no timestamp changes or dictionary-free time splitting.
    static String wrap(String text){
        if(text==null)return "";String s=text.replace('\n',' ').trim();
        int limit=cjk(s)?16:42;if(s.codePointCount(0,s.length())<=limit)return s;
        int center=s.offsetByCodePoints(0,s.codePointCount(0,s.length())/2),best=-1;double score=Double.MAX_VALUE;
        for(int i=1;i<s.length();i++){
            char left=s.charAt(i-1),right=s.charAt(i);
            boolean natural=Character.isWhitespace(left)||",;:，；：。！？!?".indexOf(left)>=0;
            if(!natural || Character.isLowSurrogate(right))continue;
            if(Character.isDigit(left)&&Character.isDigit(right))continue;
            int a=s.substring(0,i).codePointCount(0,i),b=s.substring(i).codePointCount(0,s.length()-i);
            double v=Math.abs(i-center)+Math.max(0,Math.max(a,b)-limit)*10;
            if(v<score){score=v;best=i;}
        }
        if(best<0)return s; // Let the TextView wrap; do not cut an unknown compound just to balance.
        return s.substring(0,best).trim()+"\n"+s.substring(best).trim();
    }
}
