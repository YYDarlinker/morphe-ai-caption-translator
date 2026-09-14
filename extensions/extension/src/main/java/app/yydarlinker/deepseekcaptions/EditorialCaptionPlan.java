package app.yydarlinker.deepseekcaptions;
import org.json.JSONArray;
import java.util.*;
/** Merge only at model-authorized optional boundaries; never split/rewrite a translated event. */
final class EditorialCaptionPlan {
    static final long MAX_JOIN_MS=6000, MAX_GAP_MS=200;
    static List<AnchoredCaptionPlan.Segment> pack(List<AnchoredCaptionPlan.Segment> input,JSONArray marks){
        Set<Integer> permitted=new HashSet<>();
        if(marks!=null)for(int i=0;i<Math.min(marks.length(),input.size());i++){
            try{permitted.add((int)AnchoredCaptionPlan.exactIndex(marks.get(i)));}catch(Exception ignored){}
        }
        List<AnchoredCaptionPlan.Segment> out=new ArrayList<>();
        for(AnchoredCaptionPlan.Segment b:input){
            if(!out.isEmpty()){
                AnchoredCaptionPlan.Segment a=out.get(out.size()-1);
                String text=ReadableCaptionPlan.join(a.text,b.text);
                long span=b.endMs-a.startMs,gap=b.startMs-a.endMs;
                int limit=CaptionPresentationPolicy.cjk(text)?32:84;
                // Normal complete events need not be merged just to fill the two-line area.
                boolean brief=a.endMs-a.startMs<1200 || b.endMs-b.startMs<1200;
                if(brief && permitted.contains(a.to) && a.to+1==b.from && !a.text.isEmpty() && !b.text.isEmpty()
                        && gap>=0 && gap<=MAX_GAP_MS && span<=MAX_JOIN_MS
                        && CaptionPresentationPolicy.visible(text)<=limit){
                    out.set(out.size()-1,new AnchoredCaptionPlan.Segment(a.from,b.to,a.startMs,b.endMs,text));continue;
                }
            }
            out.add(b);
        }
        return Collections.unmodifiableList(out);
    }
    static JSONArray pauses(List<SourceAtomTimeline.Atom> atoms,TranslationUnitTimeline.Unit unit){
        JSONArray result=new JSONArray();
        for(int i=unit.fromAtom+1;i<=unit.toAtom && result.length()<8;i++){
            long gap=atoms.get(i).startMs-atoms.get(i-1).endMs;
            if(gap>=250)result.put(new JSONArray().put(i-unit.fromAtom).put(gap));
        }
        return result;
    }
}
