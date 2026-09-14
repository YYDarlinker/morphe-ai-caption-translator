package app.yydarlinker.deepseekcaptions;
import java.util.*;
/** Live-video adaptation of documented timed-text guidelines; source clock always wins. */
final class CaptionPresentationPolicy {
    static final int CJK_LINE=16,CJK_EVENT=32;
    static final long MIN_MS=950,MAX_MS=7000;
    static boolean cjk(String s){for(int cp:s.codePoints().toArray())if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN||Character.UnicodeScript.of(cp)==Character.UnicodeScript.HIRAGANA||Character.UnicodeScript.of(cp)==Character.UnicodeScript.KATAKANA||Character.UnicodeScript.of(cp)==Character.UnicodeScript.HANGUL)return true;return false;}
    static int visible(String s){return (int)s.codePoints().filter(cp->!Character.isWhitespace(cp)).count();}
    static String issue(String text,long duration){
        int limit=cjk(text)?CJK_EVENT:84;
        if(duration>MAX_MS)return "duration_over_7s";
        if(visible(text)>limit)return "text_over_two_lines";
        if(duration<MIN_MS)return "brief_source_interval";
        return "";
    }
    static String requestRules(){return "Translate naturally in the target language, not word by word. Then divide speech into readable sense groups, normally a short sentence or coherent clause per subtitle. "
        +"An event is NOT a visual line. Split multi-sentence explanations instead of placing a paragraph on screen. A transition such as 'So to put it simply' begins its new explanation, not the previous event. "
        +"A numbered point or a new contrast begins a new event rather than attaching it to the previous claim. Keep names, quantities, modifiers and their nouns, verbs and their objects together. A long sentence may span events, but do not leave an orphaned connector or filler. "
        +"Aim for 1.5-6 seconds and 10-32 Chinese characters (other languages roughly two 42-character lines); these are goals, not excuses to invent, drop, or prematurely translate future speech. "
        +"Use timed_words [source text,end deciseconds] and pauses_before_ms as source timing evidence. Never equate every cue edge or pause with a speaker/shot change. "
        +"Prefer faithful natural technical terms; translate multiplicative comparisons as ratios, not additive increases. Do not include read-only context in output.";}
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
