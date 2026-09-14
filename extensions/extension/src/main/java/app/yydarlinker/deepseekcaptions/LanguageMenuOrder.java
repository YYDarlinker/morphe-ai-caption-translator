package app.yydarlinker.deepseekcaptions;
import java.text.Collator;
import java.util.*;
final class LanguageMenuOrder {
    static int rank(String code){String s=code==null?"":code.toLowerCase(Locale.ROOT);if(s.equals("zh-hans")||s.equals("zh-cn"))return 1;if(s.equals("zh-hant")||s.equals("zh-tw")||s.equals("zh-hk"))return 2;return 0;}
    static int compare(String a,String b){int x=rank(a),y=rank(b);if(x!=y)return Integer.compare(x,y);
        return Collator.getInstance(Locale.CHINA).compare(TargetLanguage.fromCode(a).displayName,TargetLanguage.fromCode(b).displayName);}
    static <T> List<T> sorted(List<T> values,java.util.function.Function<T,String> code){List<T> out=new ArrayList<>(values);out.sort((a,b)->compare(code.apply(a),code.apply(b)));return out;}
}
