package app.yydarlinker.caption.contracts;

import app.yydarlinker.caption.contracts.CaptionContracts.*;
import java.util.*;

/** Derived reservations only. No target text and no modification of original source time. */
public final class DisplayTimeline {
    public static final class Slot {
        public final String unitId, issue;
        public final int from,to;
        public final List<Window> windows,originalWindows;
        Slot(String content,int from,int to,List<Window> windows,List<Window> original,String issue) {
            unitId=CaptionContracts.hash(content+':'+from+':'+to);this.from=from;this.to=to;
            this.windows=Collections.unmodifiableList(new ArrayList<>(windows));
            originalWindows=Collections.unmodifiableList(new ArrayList<>(original));this.issue=issue;
        }
        public boolean contains(long at) { for(Window w:windows)if(w.contains(at))return true;return false; }
    }
    public final long revision;
    public final List<Slot> slots;
    public final String signature;
    private DisplayTimeline(List<Slot> slots,long revision) {
        this.slots=Collections.unmodifiableList(slots);this.revision=revision;
        StringBuilder key=new StringBuilder();
        for(Slot s:slots) {key.append(s.unitId).append(s.issue);for(Window w:s.windows)key.append('/').append(w.startMs).append('-').append(w.endMs);}
        signature=CaptionContracts.hash(key.toString());
    }
    private static final class Span {final int from,to;Span(int from,int to){this.from=from;this.to=to;}}
    public static DisplayTimeline fromUnits(String content,Source source,List<Unit> units,long revision) {
        List<Span> ranges=new ArrayList<>();
        for(Unit u:units)ranges.add(new Span(u.from,u.to));
        return allocate(content,source,ranges,units,Collections.emptyMap(),revision);
    }
    private static DisplayTimeline allocate(String content,Source source,List<Span> plans,List<Unit> ledger,
                                          Map<String,List<Window>> frozen,long revision) {
        NavigableMap<Integer,Span> spans=new TreeMap<>();
        for(Span p:plans)spans.put(p.from,p);
        for(Unit u:ledger)spans.putIfAbsent(u.from,new Span(u.from,u.to));
        List<Span> ordered=new ArrayList<>(spans.values());List<Slot> result=new ArrayList<>();
        for(int i=0;i<ordered.size();i++) {
            Span p=ordered.get(i);List<Window> raw=windows(source,ledger,p);
            String id=CaptionContracts.hash(content+':'+p.from+':'+p.to),issue="";
            List<Window> allocated=new ArrayList<>();long upper=Long.MAX_VALUE;
            if(raw.isEmpty())issue="unknown_source_time";
            if(source!=null && unknown(source,p))issue="partially_unknown_source_time";
            if(source!=null && speakers(source,p).size()>1)issue="multiple_speakers_not_linearizable";
            if(i+1<ordered.size()) {
                Span next=ordered.get(i+1);List<Window> nw=windows(source,ledger,next);
                if(!raw.isEmpty()&&!nw.isEmpty()&&nw.get(0).startMs<raw.get(raw.size()-1).endMs) {
                    if(nw.get(0).startMs<=raw.get(0).startMs)issue="nonmonotonic_source_groups";
                    else if(source!=null&&!speakers(source,p).isEmpty()&&!speakers(source,next).isEmpty()
                        &&!speakers(source,p).equals(speakers(source,next)))issue="overlapping_speakers";
                    else upper=nw.get(0).startMs;
                }
            }
            if(i>0 && !raw.isEmpty()) {
                List<Window> prev=windows(source,ledger,ordered.get(i-1));
                if(!prev.isEmpty()&&raw.get(0).startMs<=prev.get(0).startMs)issue="nonmonotonic_source_groups";
                else if(source!=null&&!prev.isEmpty()&&raw.get(0).startMs<prev.get(prev.size()-1).endMs
                    &&!speakers(source,p).isEmpty()&&!speakers(source,ordered.get(i-1)).isEmpty()
                    &&!speakers(source,p).equals(speakers(source,ordered.get(i-1))))issue="overlapping_speakers";
            }
            if(issue.isEmpty())for(Window w:raw) {long end=Math.min(upper,w.endMs);if(end>w.startMs)allocated.add(new Window(w.startMs,end));}
            if(frozen.containsKey(id)) {allocated=new ArrayList<>(frozen.get(id));issue="";}
            // A new reservation cannot rewrite an already drawn slot or steal its remaining time.
            for(Map.Entry<String,List<Window>> old:frozen.entrySet())if(!old.getKey().equals(id))
                for(Window w:allocated)for(Window f:old.getValue())if(w.startMs<f.endMs&&f.startMs<w.endMs)issue="conflict_with_drawn_reservation";
            if(!issue.isEmpty())allocated.clear();
            if(allocated.isEmpty()&&issue.isEmpty())issue="unallocatable_source_window";
            result.add(new Slot(content,p.from,p.to,allocated,raw,issue));
        }
        // Reject any remaining ambiguous display time instead of selecting last-audible.
        Set<String> ambiguous=new HashSet<>();
        for(int i=0;i<result.size();i++)for(int j=i+1;j<result.size();j++)
            for(Window a:result.get(i).windows)for(Window b:result.get(j).windows)
                if(a.startMs<b.endMs&&b.startMs<a.endMs){ambiguous.add(result.get(i).unitId);ambiguous.add(result.get(j).unitId);}
        for(int i=0;i<result.size();i++){Slot s=result.get(i);if(ambiguous.contains(s.unitId))result.set(i,new Slot(content,s.from,s.to,Collections.emptyList(),s.originalWindows,"ambiguous_schedule"));}
        return new DisplayTimeline(result,revision);
    }
    private static boolean unknown(Source source,Span p) {
        for(int i=p.from;i<p.to;i++)if(source.tokens.get(i).captionedSpeech&&!source.tokens.get(i).timed())return true;
        return false;
    }
    private static Set<String> speakers(Source source,Span p) {
        Set<String> result=new HashSet<>();Set<Integer> fragments=new HashSet<>();
        for(int i=p.from;i<p.to;i++)fragments.add(source.tokens.get(i).fragment);
        for(SourceFragment f:source.fragments)if(fragments.contains(f.id))
            for(SpeakerMarker m:f.speakerMarkers)if(!m.label.isEmpty())result.add(m.label);
        return result;
    }
    private static List<Window> windows(Source source,List<Unit> units,Span p) {
        if(source!=null)return source.windows(p.from,p.to);
        for(Unit u:units)if(u.from==p.from&&u.to==p.to)return u.windows;
        return Collections.emptyList();
    }
    public Slot forUnit(String id) {for(Slot slot:slots)if(slot.unitId.equals(id))return slot;return null;}
    public Slot at(long at) {for(Slot slot:slots)if(slot.contains(at))return slot;return null;}
    public boolean uncertainAt(long at) {for(Slot slot:slots)if(!slot.issue.isEmpty())for(Window w:slot.originalWindows)if(w.contains(at))return true;return false;}
}
