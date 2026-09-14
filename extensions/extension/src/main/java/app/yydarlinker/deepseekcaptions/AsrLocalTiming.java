package app.yydarlinker.deepseekcaptions;

import java.util.*;

/** Local, unique same-language phrase alignment. Never replaces provider wording or calls AI. */
final class AsrLocalTiming {
    private static final int N = 4;
    static SourceAtomTimeline.Result align(SourceAtomTimeline.Result source, SourceAtomTimeline.Result asr) {
        if(source==null || asr==null || asr.nativeTimedAtoms==0 || source.atoms.size()<8)return source;
        List<SourceAtomTimeline.Atom> p=source.atoms, a=asr.atoms;
        Map<String,Integer> pi=index(p), ai=index(a);
        int[] match=new int[p.size()];Arrays.fill(match,-1);
        int last=-1, matched=0;
        for(int i=0;i+N<=p.size();i++) {
            String key=key(p,i);Integer j=ai.get(key);
            if(j==null || j<0 || pi.get(key)==null || pi.get(key)!=i || j<=last)continue;
            boolean precise=true;
            for(int k=0;k<N;k++) if(!a.get(j+k).precise || Math.abs(p.get(i+k).startMs-a.get(j+k).startMs)>12000)precise=false;
            if(!precise)continue;
            for(int k=0;k<N;k++)match[i+k]=j+k;
            matched+=N;last=j+N-1;i+=N-1;
        }
        // Sparse or repetitive evidence cannot establish a track-wide local timing map.
        if(matched<8 || matched*10<p.size()*7)return source;
        List<SourceAtomTimeline.Atom> out=new ArrayList<>();int exact=0;
        for(int i=0;i<p.size();i++) {
            SourceAtomTimeline.Atom original=p.get(i);long start=original.startMs,end=original.endMs;boolean precise=false;
            if(match[i]>=0){SourceAtomTimeline.Atom clock=a.get(match[i]);start=clock.startMs;end=clock.endMs;precise=true;}
            else {
                int left=i-1,right=i+1;while(left>=0 && match[left]<0)left--;while(right<p.size() && match[right]<0)right++;
                if(left>=0 && right<p.size()) {
                    long from=a.get(match[left]).endMs,to=a.get(match[right]).startMs;
                    long oldFrom=p.get(left).endMs,oldTo=p.get(right).startMs;
                    if(to<=from || to-from>8000 || oldTo<=oldFrom)return source;
                    start=from+Math.round((to-from)*((original.startMs-oldFrom)/(double)(oldTo-oldFrom)));
                    end=from+Math.round((to-from)*((original.endMs-oldFrom)/(double)(oldTo-oldFrom)));
                }
                // Unmatched edge atoms keep provider timing, explicitly estimated.
            }
            if(start<0 || end<=start || (!out.isEmpty() && start<out.get(out.size()-1).endMs))return source;
            out.add(new SourceAtomTimeline.Atom(start,end,original.text,original.cueIndex,precise));if(precise)exact++;
        }
        return new SourceAtomTimeline.Result(Collections.unmodifiableList(out),source.rawCueCount,exact,out.size()-exact,source.json3,source.rollupNormalized);
    }
    private static Map<String,Integer> index(List<SourceAtomTimeline.Atom> a){Map<String,Integer> out=new HashMap<>();for(int i=0;i+N<=a.size();i++){String k=key(a,i);if(out.containsKey(k))out.put(k,-1);else out.put(k,i);}return out;}
    private static String key(List<SourceAtomTimeline.Atom> a,int from){StringBuilder b=new StringBuilder();for(int i=0;i<N;i++){String s=a.get(from+i).text.toLowerCase(Locale.ROOT);s.codePoints().filter(Character::isLetterOrDigit).forEach(b::appendCodePoint);b.append('|');}return b.toString();}
}
