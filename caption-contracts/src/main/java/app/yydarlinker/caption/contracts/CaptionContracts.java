package app.yydarlinker.caption.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure immutable product values. Source ranges are half-open token IDs; target offsets are UTF-16. */
public final class CaptionContracts {
    private CaptionContracts() {}
    public static final String SOURCE_EVIDENCE_POLICY="raw-sequence-time-index-speaker-empty-event-v3";
    public enum Basis { WORD, SEGMENT, CUE, ESTIMATED, UNKNOWN }
    public enum FailureKind { AUTH_CONFIGURATION, TRANSIENT, STRUCTURE, CANCELLED, EXPIRED, POLICY_DENIED, NEED_CONTEXT, UNKNOWN }
    public enum Status { OFF, NO_SOURCE, WAITING, READY, SILENCE, TIMING_UNCERTAIN, ERROR }
    public static final class RawRange {
        public final int fragment,fromUtf16,toUtf16;
        public RawRange(int fragment,int from,int to){
            if(fragment<0||from<0||to<=from)throw new IllegalArgumentException("invalid_raw_range");
            this.fragment=fragment;fromUtf16=from;toUtf16=to;
        }
    }
    /** Raw token order is content order, NOT a claim of monotonic acoustic time. */
    public static final class Token {
        public final int id, cue, fragment, rawFromUtf16, rawToUtf16;
        public final String raw, separatorBefore;
        public final List<RawRange> originalRanges;
        public final boolean captionedSpeech;
        public final long startMs, endMs;
        public final Basis basis;

        public Token(int id, int cue, String raw, long start, long end, Basis basis) {
            this(id, cue, raw, start, end, basis, " ", -1, -1, -1);
        }

        public Token(int id, int cue, String raw, long start, long end, Basis basis,
                     String separator, int fragment, int rawFrom, int rawTo) {
            this(id,cue,raw,start,end,basis,separator,fragment,rawFrom,rawTo,Collections.emptyList());
        }
        public Token(int id,int cue,String raw,long start,long end,Basis basis,String separator,
                     int fragment,int rawFrom,int rawTo,List<RawRange> ranges) {
            this(id,cue,raw,start,end,basis,separator,fragment,rawFrom,rawTo,ranges,true);
        }
        public Token(int id,int cue,String raw,long start,long end,Basis basis,String separator,
                     int fragment,int rawFrom,int rawTo,List<RawRange> ranges,boolean captionedSpeech) {
            if (id < 0 || raw == null || raw.trim().isEmpty()
                    || !(start >= 0 && end >= start || start == -1 && end == -1)) {
                throw new IllegalArgumentException("invalid_source_token");
            }
            this.id = id;
            this.cue = cue;
            this.raw = raw;
            startMs = start;
            endMs = end;
            this.basis = Objects.requireNonNull(basis);
            separatorBefore = Objects.requireNonNull(separator);
            this.fragment = fragment;
            rawFromUtf16 = rawFrom;
            rawToUtf16 = rawTo;
            originalRanges=Collections.unmodifiableList(new ArrayList<>(ranges));
            this.captionedSpeech=captionedSpeech;
        }

        public boolean timed() { return startMs >= 0 && endMs > startMs; }
    }

    public enum SpeakerBoundaryKind { OPEN, CLOSE, CARRY }
    /** Explicit source-format event, not an acoustic speaker identification or speech token. */
    public static final class SpeakerMarker {
        public final int offsetUtf16;
        public final String label, rawMarkup;
        public final SpeakerBoundaryKind kind;
        public SpeakerMarker(int offset, String label, String rawMarkup, SpeakerBoundaryKind kind) {
            if (offset < 0) throw new IllegalArgumentException("invalid_speaker_offset");
            offsetUtf16 = offset;
            this.label = Objects.requireNonNull(label);
            this.rawMarkup = Objects.requireNonNull(rawMarkup);
            this.kind = Objects.requireNonNull(kind);
        }
    }

    /** Immutable decoded fragment and exact format metadata; rawHash binds the original document. */
    public static final class SourceFragment {
        public final int id, cue;
        public final String raw, origin;
        public final long startMs, endMs;
        public final boolean explicitOffset;
        public final List<SpeakerMarker> speakerMarkers;

        public SourceFragment(int id, int cue, String raw, long start, long end,
                              boolean explicitOffset, String origin) {
            this(id, cue, raw, start, end, explicitOffset, origin, Collections.emptyList());
        }
        public SourceFragment(int id, int cue, String raw, long start, long end,
                              boolean explicitOffset, String origin, List<SpeakerMarker> markers) {
            this.id = id;
            this.cue = cue;
            this.raw = Objects.requireNonNull(raw);
            startMs = start;
            endMs = end;
            this.explicitOffset = explicitOffset;
            this.origin = Objects.requireNonNull(origin);
            int previous = -1;
            for (SpeakerMarker marker : markers) {
                if (marker.offsetUtf16 < previous || marker.offsetUtf16 > raw.length())
                    throw new IllegalArgumentException("speaker_offset_outside_decoded_fragment");
                previous = marker.offsetUtf16;
            }
            speakerMarkers = Collections.unmodifiableList(new ArrayList<>(markers));
        }
    }

    public static final class Window {
        public final long startMs, endMs;
        public Window(long start, long end) {
            if (start < 0 || end <= start) throw new IllegalArgumentException("invalid_window");
            startMs = start;
            endMs = end;
        }
        public boolean contains(long at) { return at >= startMs && at < endMs; }
    }

    public static final class Source {
        public final String videoId, language, textHash, rawHash;
        public final List<Token> tokens;
        public final List<SourceFragment> fragments;
        public final List<String> timingIssues;
        private final List<Token> timeIndex;
        private final long[] precedingMaxEnd;

        public Source(String video, String language, List<Token> tokens) {
            this(video, language, tokens, "", Collections.emptyList(), Collections.emptyList());
        }

        public Source(String video, String language, List<Token> tokens, String rawHash,
                      List<SourceFragment> fragments, List<String> timingIssues) {
            videoId = Objects.requireNonNull(video);
            this.language = Objects.requireNonNull(language);
            if (tokens.isEmpty()) throw new IllegalArgumentException("empty_source");
            StringBuilder raw = new StringBuilder();
            List<Token> timed = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.id != i) throw new IllegalArgumentException("source_identity_order");
                raw.append(t.separatorBefore).append(t.raw).append('\n');
                if (t.timed() && t.captionedSpeech) timed.add(t);
            }
            this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens));
            this.fragments = Collections.unmodifiableList(new ArrayList<>(fragments));
            this.timingIssues = Collections.unmodifiableList(new ArrayList<>(timingIssues));
            this.rawHash = Objects.requireNonNull(rawHash);
            textHash = hash(raw.toString());
            timed.sort(Comparator.comparingLong((Token t) -> t.startMs).thenComparingInt(t -> t.id));
            timeIndex = Collections.unmodifiableList(timed);
            precedingMaxEnd = new long[timed.size()];
            long maximum = -1;
            for (int i = 0; i < timed.size(); i++) {
                maximum = Math.max(maximum, timed.get(i).endMs);
                precedingMaxEnd[i] = maximum;
            }
        }

        private int afterStart(long at, boolean inclusive) {
            int lo = 0, hi = timeIndex.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                long start = timeIndex.get(mid).startMs;
                if (start < at || inclusive && start == at) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        /** Overlapping cues keep their raw order; the latest raw active token owns the focus. */
        public int tokenAt(long at) {
            int best = -1;
            for (int i = afterStart(at, true) - 1; i >= 0 && precedingMaxEnd[i] > at; i--) {
                Token t = timeIndex.get(i);
                if (t.endMs > at) best = Math.max(best, t.id);
            }
            return best;
        }

        public boolean hasUnprojectableSpeech(long at) {
            for(Token t:tokens)if(t.captionedSpeech&&!t.timed()){
                if(t.startMs>=0){if(at>=t.startMs)return true;continue;}
                if(t.fragment<0||t.fragment>=fragments.size())return true;
                SourceFragment fragment=fragments.get(t.fragment);
                if(fragment.startMs<0||at>=fragment.startMs)return true;
            }
            return false;
        }

        public int nextToken(long at) {
            int current = tokenAt(at);
            if (current >= 0) return current;
            int next = afterStart(at, false);
            return next < timeIndex.size() ? timeIndex.get(next).id : -1;
        }

        public String text(int from, int to) {
            StringBuilder out = new StringBuilder();
            for (int i = from; i < to; i++) {
                Token t = tokens.get(i);
                if (i > from) out.append(t.separatorBefore);
                out.append(t.raw);
            }
            return out.toString();
        }

        /** Adopt timing only under exact video/language/token identity; original content/provenance stays. */
        public Source refineTiming(Source reference) {
            if(!videoId.equals(reference.videoId) || !Locale.forLanguageTag(language).getLanguage().equals(Locale.forLanguageTag(reference.language).getLanguage()))
                throw new IllegalArgumentException("timing_identity_mismatch");
            StringBuilder ownText=new StringBuilder(),refText=new StringBuilder();
            List<Integer> ownEnds=new ArrayList<>(),refEnds=new ArrayList<>();Map<Integer,Integer> refBoundary=new HashMap<>();
            for(Token token:tokens){ownText.append(withoutWhitespace(token.raw));ownEnds.add(ownText.length());}
            for(Token token:reference.tokens){refText.append(withoutWhitespace(token.raw));refEnds.add(refText.length());refBoundary.put(refText.length(),refEnds.size()-1);}
            if(!ownText.toString().equals(refText.toString()))throw new IllegalArgumentException("timing_content_mismatch");
            List<Token> refined=new ArrayList<>();boolean improvement=false;int begin=0;
            for(int i=0;i<tokens.size();i++) {
                Token original=tokens.get(i);int end=ownEnds.get(i),first=begin==0?0:refBoundary.getOrDefault(begin,-2)+1,last=refBoundary.getOrDefault(end,-1);
                boolean exactEdges=(begin==0||refBoundary.containsKey(begin))&&last>=first&&first>=0;
                boolean stronger=exactEdges&&(original.basis==Basis.ESTIMATED||original.basis==Basis.UNKNOWN);
                long start=-1,finish=-1;Basis basis=Basis.WORD;long prior=-1;
                if(stronger)for(int j=first;j<=last;j++){
                    Token next=reference.tokens.get(j);
                    if(!next.timed()||next.basis==Basis.ESTIMATED||next.basis==Basis.UNKNOWN||next.startMs<prior){stronger=false;break;}
                    if(start<0)start=next.startMs;finish=next.endMs;prior=next.startMs;
                    if(next.basis==Basis.SEGMENT||last>first)basis=Basis.SEGMENT;
                }
                improvement|=stronger;
                refined.add(new Token(original.id,original.cue,original.raw,stronger?start:original.startMs,
                    stronger?finish:original.endMs,stronger?basis:original.basis,original.separatorBefore,
                    original.fragment,original.rawFromUtf16,original.rawToUtf16,original.originalRanges,original.captionedSpeech));
                begin=end;
            }
            if(!improvement)return this;
            List<String> issues=new ArrayList<>(timingIssues);issues.add("adopted_exact_boundary_content_reference:"+reference.rawHash);
            return new Source(videoId,language,refined,rawHash,fragments,issues);
        }
        private static String withoutWhitespace(String raw) {
            StringBuilder out=new StringBuilder();for(int i=0;i<raw.length();){int cp=raw.codePointAt(i);i+=Character.charCount(cp);
                if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))out.appendCodePoint(cp);}
            return out.toString();
        }

        public String quote(int from,int to) {
            return text(from,to).replaceAll("(?U)\\s+"," ");
        }

        /** Union of known audible spans; sorting this projection never reorders source tokens. */
        public List<Window> windows(int from, int to) {
            List<Token> timed = new ArrayList<>();
            for (int i = from; i < to; i++) if (tokens.get(i).timed() && tokens.get(i).captionedSpeech) timed.add(tokens.get(i));
            timed.sort(Comparator.comparingLong((Token t) -> t.startMs).thenComparingInt(t -> t.id));
            List<Window> result = new ArrayList<>();
            for (Token t : timed) {
                if (!result.isEmpty() && t.startMs <= result.get(result.size() - 1).endMs) {
                    Window prior = result.remove(result.size() - 1);
                    result.add(new Window(prior.startMs, Math.max(prior.endMs, t.endMs)));
                } else result.add(new Window(t.startMs, t.endMs));
            }
            return Collections.unmodifiableList(result);
        }
    }
    /**
     * A source-bound planning result. It is an intermediate request fact, never a second
     * accepted-meaning ledger or a semantic correctness certificate.
     */
    /** Source/range binding is provable; semantic completeness is not certified by a provider label. */
    public static final class Unit {
        public final String contentId,id,text;
        public final int from,to;
        public final List<Window> windows;
        public final Basis basis;
        public final long revision;
        public Unit(String content,int from,int to,String text,List<Window> windows,Basis basis,long revision) {
            if(from<0||to<=from||text==null||text.trim().isEmpty()||windows.isEmpty()&&basis!=Basis.UNKNOWN)throw new IllegalArgumentException("invalid_unit");
            contentId=Objects.requireNonNull(content);this.from=from;this.to=to;this.text=text;
            id=hash(content+':'+from+':'+to);this.windows=Collections.unmodifiableList(new ArrayList<>(windows));this.basis=basis;this.revision=revision;
        }
        public long start(){return windows.isEmpty()?-1:windows.get(0).startMs;}
        public long end(){return windows.isEmpty()?-1:windows.get(windows.size()-1).endMs;}
        public boolean audible(long at){for(Window w:windows)if(w.contains(at))return true;return false;}
    }
    public static final class Failure {
        public final FailureKind kind;
        public final String code,type,stage;
        public final int httpStatus;
        public final long retryAfterMs;
        public Failure(FailureKind kind,String code,String type,String stage,int http,long retry){this.kind=kind;this.code=safe(code);this.type=safe(type);this.stage=safe(stage);httpStatus=http;retryAfterMs=Math.max(0,retry);}
        private static String safe(String v){return v==null?"":v.replaceAll("[^A-Za-z0-9_.-]","_").substring(0,Math.min(80,v.length()));}
    }
    public static final class Frame {
        public final String contentId;
        public final long intent, cacheEpoch, positionMs;
        public final Status status;
        public final Unit unit;
        public final Failure failure;
        public final List<Unit> supply;
        public final Source sourceEvidence;
        public final DisplayTimeline timeline;
        public final int failureFrom,failureTo;
        public Frame(String content,long intent,long epoch,long position,Status status,Unit unit,Failure error){
            this(content,intent,epoch,position,status,unit,error,unit==null?Collections.emptyList():Collections.singletonList(unit),null,null,-1,-1);
        }
        private Frame(String content,long intent,long epoch,long position,Status status,Unit unit,Failure error,
                      List<Unit> supply,Source source,DisplayTimeline timeline,int failureFrom,int failureTo){
            contentId=content;this.intent=intent;cacheEpoch=epoch;positionMs=position;this.status=status;this.unit=unit;failure=error;
            this.supply=Collections.unmodifiableList(new ArrayList<>(supply));sourceEvidence=source;
            this.timeline=timeline==null?DisplayTimeline.fromUnits(content,source,supply,0):timeline;this.failureFrom=failureFrom;this.failureTo=failureTo;
        }
        /** All projections consume the same immutable reservations; source.tokenAt never selects text. */
        public Frame atPosition(long at) {
            DisplayTimeline.Slot slot=timeline.at(at);
            Unit current=null;
            if(slot!=null) for(Unit u:supply) if(u.id.equals(slot.unitId)&&u.contentId.equals(contentId)) {
                current=u;break;
            }
            int demandToken=sourceEvidence==null?-1:sourceEvidence.tokenAt(at);
            Failure scopedError=failureFrom>=0?(failureFrom<=demandToken&&demandToken<failureTo?failure:null):at==positionMs?failure:null;
            Status projected=current!=null?Status.READY:status==Status.OFF?Status.OFF
                :timeline.uncertainAt(at)?Status.TIMING_UNCERTAIN:sourceEvidence==null?Status.WAITING
                :sourceEvidence.tokenAt(at)<0?(sourceEvidence.hasUnprojectableSpeech(at)?Status.TIMING_UNCERTAIN:Status.SILENCE)
                :scopedError!=null?Status.ERROR:Status.WAITING;
            return new Frame(contentId,intent,cacheEpoch,at,projected,current,scopedError,supply,sourceEvidence,timeline,failureFrom,failureTo);
        }
        public List<Window> displayWindows() {
            DisplayTimeline.Slot slot=unit==null?null:timeline.forUnit(unit.id);
            return slot==null?Collections.emptyList():slot.windows;
        }
    }

    public enum Action { CONFIGURATION, APPEARANCE, UI_LOCALE, CLEAR_CACHE, DEACTIVATE, ACTIVATE, SOURCE, PLAYER_TYPE, VIDEO, POSITION_RESET, RECOVER, SOURCE_CATALOG, PREWARM }
    /** UI dependency. An independent fake can replace this without core/provider/Android imports. */
    public interface CaptionPort {
        void command(Action action,String value);
        boolean active();
        boolean ownsNativeTrack();
        String restoreTarget(String url);
        String videoId();
        void playback(long mediaTimeMs);
    }
    public static String hash(String value) {
        return hashBytes(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String hashBytes(byte[] value) {
        try{byte[] d=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
}
