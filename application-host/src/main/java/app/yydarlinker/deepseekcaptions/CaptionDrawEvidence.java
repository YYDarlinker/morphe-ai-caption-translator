package app.yydarlinker.deepseekcaptions;

import app.yydarlinker.caption.contracts.CaptionContracts.*;
import java.util.*;

/** Actual paint evidence, never text authority, scheduling input, or a human-read acknowledgement. */
final class CaptionDrawEvidence {
    static final class Receipt {
        final long owner,intent,cacheEpoch,contentRevision,layoutRevision,scheduleRevision;
        final long paintStartNanos,paintEndNanos,mediaStartMs,mediaEndMs;
        final String contentId,unitId,spanKey;
        final int fromUtf16,toUtf16;
        final boolean valid,crossedWindow;
        Receipt(Frame f,long owner,long layout,long start,long end,long mediaStart,long mediaEnd,
                int from,int to,boolean valid,boolean crossed) {
            this.owner=owner;intent=f==null?-1:f.intent;cacheEpoch=f==null?-1:f.cacheEpoch;
            contentId=f==null?"":f.contentId;unitId=f==null||f.unit==null?"":f.unit.id;
            spanKey=f==null||f.unit==null?"":f.contentId+"/"+f.unit.from+"-"+f.unit.to;
            contentRevision=f==null||f.unit==null?-1:f.unit.revision;
            scheduleRevision=f==null?-1:f.timeline.revision;layoutRevision=layout;
            paintStartNanos=start;paintEndNanos=end;mediaStartMs=mediaStart;mediaEndMs=mediaEnd;
            fromUtf16=from;toUtf16=to;crossedWindow=crossed;
            this.valid=valid&&!crossed&&from>=0&&to>from&&f!=null&&f.unit!=null&&to<=f.unit.text.length();
        }
        String identity(){return owner+"/"+intent+"/"+cacheEpoch+"/"+unitId+"/"+contentRevision+"/"+layoutRevision+"/"+scheduleRevision+"/"+fromUtf16+"-"+toUtf16;}
    }
    static final class Residence {
        final Receipt receipt;
        final long observedWallNanos,firstPaintNanos,lastConfirmedPaintNanos;
        final int paints;
        Residence(Receipt r,long duration,long first,long last,int paints){receipt=r;observedWallNanos=duration;firstPaintNanos=first;lastConfirmedPaintNanos=last;this.paints=paints;}
    }
    private static final class MutableResidence {
        final Receipt receipt;long wall,first,last;int paints;
        MutableResidence(Receipt r){receipt=r;first=r.paintEndNanos;last=first;}
        Residence snapshot(){return new Residence(receipt,wall,first,last,paints);}
    }
    private final LinkedHashMap<String,MutableResidence> pages=new LinkedHashMap<>();
    private final Map<String,BitSet> coverage=new HashMap<>();
    private Receipt prior;
    synchronized void observe(Receipt receipt) {
        if(prior!=null&&prior.valid) {
            MutableResidence page=pages.get(prior.identity());
            // Only the interval between two confirmed paints of the same still-owned page is counted.
            // Unobserved tails, application ACKs and hypothetical planned duration get no residency.
            if(page!=null&&receipt.valid&&receipt.identity().equals(prior.identity())) {
                page.wall+=Math.max(0,receipt.paintStartNanos-prior.paintEndNanos);
                page.last=receipt.paintEndNanos;
            }
        }
        if(receipt.valid) {
            coverage.computeIfAbsent(receipt.owner+"/"+receipt.intent+"/"+receipt.cacheEpoch+"/"+receipt.unitId,k->new BitSet())
                .set(receipt.fromUtf16,receipt.toUtf16);
            MutableResidence page=pages.computeIfAbsent(receipt.identity(),k->new MutableResidence(receipt));page.paints++;
        }
        prior=receipt;
        // Bounded diagnostic memory; dropping old aggregates is not full-session certification.
        while(pages.size()>512){String id=pages.keySet().iterator().next();pages.remove(id);}
        while(coverage.size()>256){String id=coverage.keySet().iterator().next();coverage.remove(id);}
    }
    synchronized void invalidated(long at){prior=null;}
    synchronized List<Residence> snapshot(){List<Residence> out=new ArrayList<>();for(MutableResidence p:pages.values())out.add(p.snapshot());return Collections.unmodifiableList(out);}
    synchronized int covered(long owner,long intent,long epoch,String unit){BitSet bits=coverage.get(owner+"/"+intent+"/"+epoch+"/"+unit);return bits==null?0:bits.cardinality();}
}
