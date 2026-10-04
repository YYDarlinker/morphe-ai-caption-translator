package app.yydarlinker.deepseekcaptions;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Immutable evidence, not subtitles. Native segment bounds are never inferred from target text. */
final class RebuildSource {
  enum Precision {
    NATIVE,
    ESTIMATED,
    ALIGNED
  }

  static final class Word {
    final String text, key;
    final long start, end;
    final int cue;
    final Precision precision;

    Word(String t, long s, long e, int c, Precision p) {
      text = t;
      key = key(t);
      start = s;
      end = e;
      cue = c;
      precision = p;
    }
  }

  /** Coarse spans are ordered BEFORE interpolation; never sort invented word onsets. */
  private static final class Span {
    final String text;
    final long start, end;
    final int cue;
    final boolean nativeOffset, speakerBreak;
    final List<Integer> speakerOffsets;

    Span(String t, long a, long z, int c, boolean n) {
      this(t,a,z,c,n,false);
    }
    Span(String t,long a,long z,int c,boolean n,boolean speaker) {
      this(t,a,z,c,n,speaker,Collections.emptyList());
    }
    Span(String t,long a,long z,int c,boolean n,boolean speaker,List<Integer> offsets) {
      speakerBreak=speaker;speakerOffsets=offsets;
      text = t;
      start = a;
      end = z;
      cue = c;
      nativeOffset = n;
    }
  }

  final List<Word> words;
  final boolean coarseCueReconstructed;
  final int coarseCueCount;
  final String formatEvidence;

  RebuildSource(List<Word> values) {
    this(values, false, 0);
  }

  private RebuildSource(List<Word> values, boolean reconstructed, int count) {
    this(values,reconstructed,count,"");
  }
  private RebuildSource(List<Word> values,boolean reconstructed,int count,String evidence) {
    formatEvidence=evidence;
    words = Collections.unmodifiableList(new ArrayList<>(values));
    coarseCueReconstructed = reconstructed;
    coarseCueCount = count;
  }

  static final Pattern TOKEN =
      Pattern.compile(
          "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]|[+-]?\\p{N}+(?:[.,:/-]\\p{N}+)*(?:[a-zA-Z]+)?(?:%|％)?|[\\p{L}\\p{M}]+(?:[.'’_\\-][\\p{L}\\p{M}\\p{N}]+)*|[^\\s]");

  static List<String> tokens(String text) {
    List<String> result = new ArrayList<>();
    Matcher m = TOKEN.matcher(text == null ? "" : text);
    while (m.find()) {
      String t = m.group();
      if (!lexical(t) && !result.isEmpty() && !opening(t))
        result.set(result.size() - 1, result.get(result.size() - 1) + t);
      else result.add(t);
    }
    return result;
  }

  static boolean lexical(String s) {
    return s.codePoints().anyMatch(Character::isLetterOrDigit);
  }

  private static boolean opening(String s) {
    return "([{（【《“‘\"".contains(s);
  }

  private static final Pattern KEY_EDGE=Pattern.compile("^[^\\p{L}\\p{N}+\\-]+|[^\\p{L}\\p{N}%]+$");
  static String key(String s) {
    return KEY_EDGE.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replace('’', '\'')
        .replace('−', '-')
        ).replaceAll("");
  }

  static boolean cjk(int c) {
    Character.UnicodeScript s = Character.UnicodeScript.of(c);
    return s == Character.UnicodeScript.HAN
        || s == Character.UnicodeScript.HIRAGANA
        || s == Character.UnicodeScript.KATAKANA
        || s == Character.UnicodeScript.HANGUL;
  }

  static String join(List<Word> w, int from, int to) {
    StringBuilder b = new StringBuilder();
    for (int i = from; i <= to; i++) {
      String s = w.get(i).text;
      if (b.length() > 0 && !s.isEmpty()) {
        int l = b.codePointBefore(b.length()), q = s.codePointAt(0);
        if (!cjk(l)
            && !cjk(q)
            && Character.isLetterOrDigit(q)
            && !"([{（【《“‘".contains(new String(Character.toChars(l)))) b.append(' ');
      }
      b.append(s);
    }
    return b.toString();
  }

  String text(int a, int b) {
    return join(words, a, b);
  }

  static boolean terminal(String s) {
    return s.matches("(?s).*[.!?。！？][\\\"'”’）)]*$")
        && !s.matches("(?i).*(?:\\bMr|\\bMrs|\\bDr|\\bProf|\\bvs|\\betc)\\.");
  }

  static boolean nonSpeech(String s) {
    String t = s.trim().toLowerCase(Locale.ROOT);
    return t.matches("[♪♫\\s]+")
        || t.matches("[\\[（(【]\\s*(music|applause|laughter|音乐|掌声|笑声)\\s*[\\]）)】]");
  }

  static RebuildSource read(byte[] body, CaptionDocument.Parsed parsed) throws Exception {
    List<Span> spans = new ArrayList<>();
    Map<Integer, CoarseFrame> frames = new HashMap<>();
    String raw = new String(body, StandardCharsets.UTF_8).trim();
    if(raw.startsWith("\uFEFF"))raw=raw.substring(1).trim();
    boolean webVtt=raw.startsWith("WEBVTT");String formatEvidence="";
    if(webVtt) {
      WebVttSourceReader.Result vtt=WebVttSourceReader.read(raw);formatEvidence=vtt.evidence();
      for(WebVttSourceReader.Range range:vtt.ranges)
        spans.add(new Span(range.text,range.start,range.end,range.cue,range.nativeStart,range.speakerBreak,range.speakerOffsets));
    }
    if (raw.startsWith("{")) {
      JSONArray es = new JSONObject(raw).optJSONArray("events");
      if (es != null)
        for (int i = 0; i < es.length(); i++) {
          JSONObject e = es.optJSONObject(i);
          if (e == null) continue;
          JSONArray ss = e.optJSONArray("segs");
          if (ss == null) continue;
          long start = e.optLong("tStartMs", -1), duration = e.optLong("dDurationMs", -1);
          if (start < 0) continue;
          long next = Long.MAX_VALUE;
          for (int j = i + 1; j < es.length(); j++) {
            JSONObject n = es.optJSONObject(j);
            if (n != null && n.optLong("tStartMs", -1) > start && n.optJSONArray("segs") != null) {
              next = n.optLong("tStartMs");
              break;
            }
          }
          long end = duration > 0 ? start + duration : next == Long.MAX_VALUE ? start + 2000 : next;
          if (end <= start) continue;
          boolean offsets = false;
          for (int j = 0; j < ss.length(); j++) {
            JSONObject part = ss.optJSONObject(j);
            offsets |= part != null && part.has("tOffsetMs");
          }
          if (!offsets) {
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < ss.length(); j++) {
              JSONObject part = ss.optJSONObject(j);
              if (part != null) text.append(part.optString("utf8", ""));
            }
            if (e.has("wWinId")) {
              int window = e.optInt("wWinId");
              frames.put(
                  window,
                  appendFrame(
                      spans,
                      text.toString(),
                      start,
                      end,
                      i,
                      e.optInt("aAppend", 0) == 1,
                      frames.get(window)));
            } else spans.add(new Span(text.toString(), start, end, i, false));
          } else {
            // Merge runs with no independent onset instead of inventing simultaneous word order.
            long offset = 0;
            StringBuilder text = new StringBuilder();
            boolean nativeStart = true;
            for (int j = 0; j < ss.length(); j++) {
              JSONObject part = ss.optJSONObject(j);
              if (part == null) continue;
              if (part.has("tOffsetMs")) {
                long value = part.optLong("tOffsetMs", -1);
                if (value < offset || value < 0 || start + value >= end)
                  throw new IllegalArgumentException("source_offset_order");
                if (value > offset) {
                  if (text.length() > 0)
                    spans.add(
                        new Span(text.toString(), start + offset, start + value, i, nativeStart));
                  text.setLength(0);
                  offset = value;
                  nativeStart = true;
                }
              }
              text.append(part.optString("utf8", ""));
            }
            if (text.length() > 0)
              spans.add(new Span(text.toString(), start + offset, end, i, nativeStart));
          }
        }
    }
    if (spans.isEmpty() && !webVtt) {
      int i = 0;
      for (CaptionDocument.Cue c : parsed.cues())
        spans.add(new Span(c.text, c.startMs, c.endMs, i++, false));
    }
    // Ordering comes from real cue/segment onsets. A multiword coarse span remains indivisible
    // during ordering. Equal real native onsets may be repeated by an overlapping JSON3 window.
    spans.sort(Comparator.comparingLong((Span v) -> v.start));
    List<Span> ordered = new ArrayList<>();
    Set<String> nativeSeen = new HashSet<>();
    for (Span span : spans) {
      List<String> ts = tokens(span.text);
      if (ts.isEmpty() || span.end <= span.start) continue;
      if (!webVtt && span.nativeOffset && ts.size() == 1 && !nativeSeen.add(span.start + "|" + key(ts.get(0))))
        continue;
      if (!ordered.isEmpty() && ordered.get(ordered.size() - 1).start == span.start) {
        Span a = ordered.remove(ordered.size() - 1);
        // Simultaneous coarse observations have no word-order timing proof. Keep both texts.
        ordered.add(
            new Span(
                a.text.trim() + " " + span.text.trim(),
                a.start,
                Math.max(a.end, span.end),
                a.cue,
                false));
      } else ordered.add(span);
    }
    List<Word> out = new ArrayList<>();
    boolean repaired = false;
    int coarse = 0;
    for (int i = 0; i < ordered.size(); i++) {
      Span span = ordered.get(i);
      boolean estimated = !span.nativeOffset || tokens(span.text).size() != 1;
      if (estimated) coarse++;
      long end = span.end;
      if (i + 1 < ordered.size() && ordered.get(i + 1).start < end) {
        end = ordered.get(i + 1).start;
        repaired |= estimated;
      }
      int first=out.size();Set<Integer> speakerWords=new HashSet<>();if(span.speakerBreak)speakerWords.add(0);
      if(span.speakerOffsets.isEmpty())add(out,span.text,span.start,end,span.cue,span.nativeOffset);
      else {
        List<String> speech=new ArrayList<>();int from=0;
        for(int mark:span.speakerOffsets)if(mark>=from && mark<span.text.length()) {
          speech.addAll(tokens(span.text.substring(from,mark).replace('\u00a0',' ')));
          speakerWords.add(speech.size());from=mark;
        }
        speech.addAll(tokens(span.text.substring(from).replace('\u00a0',' ')));
        if(end-span.start<speech.size())throw new IllegalArgumentException("vtt_voice_time_capacity");
        addTokens(out,speech,span.text,span.start,end,span.cue,span.nativeOffset);
      }
      for(int index:speakerWords)if(first+index>=first && first+index<out.size()){Word word=out.get(first+index);
        out.set(first+index,new Word(">>"+word.text,word.start,word.end,word.cue,word.precision));}
    }
    if (out.isEmpty()) throw new IllegalArgumentException("source_empty");
    for (int i = 1; i < out.size(); i++)
      if (out.get(i).start < out.get(i - 1).end)
        throw new IllegalArgumentException("source_time_order");
    return new RebuildSource(out, repaired, coarse,formatEvidence);
  }

  /**
   * Only explicit, overlapping display windows may carry earlier text. Repetition alone is not
   * evidence.
   */
  private static final class CoarseFrame {
    final List<String> tokens;
    final long end;
    final boolean rolling;
    final int lastCue;

    CoarseFrame(List<String> ts, long e, boolean r, int c) {
      tokens = ts;
      end = e;
      rolling = r;
      lastCue = c;
    }
  }

  private static CoarseFrame appendFrame(
      List<Span> out,
      String text,
      long start,
      long end,
      int cue,
      boolean append,
      CoarseFrame previous) {
    List<String> now = tokens(text);
    if (now.isEmpty()) return previous;
    int carry = 0;
    boolean rolling = false;
    if (previous != null && !append && start < previous.end) {
      boolean grows =
          now.size() > previous.tokens.size()
              && sameRange(previous.tokens, 0, now, 0, previous.tokens.size());
      if (grows) {
        carry = previous.tokens.size();
        rolling = true;
      } else if (previous.rolling) {
        for (int n = Math.min(previous.tokens.size(), now.size()); n > 0; n--)
          if (sameRange(previous.tokens, previous.tokens.size() - n, now, 0, n)) {
            carry = n;
            rolling = true;
            break;
          }
      }
    }
    int lastCue = previous == null ? cue : previous.lastCue;
    if (carry < now.size()) {
      StringBuilder fresh = new StringBuilder();
      for (int n = carry; n < now.size(); n++) {
        String value = now.get(n);
        if (fresh.length() > 0
            && !value.isEmpty()
            && !cjk(fresh.codePointBefore(fresh.length()))
            && !cjk(value.codePointAt(0))) fresh.append(' ');
        fresh.append(value);
      }
      out.add(new Span(fresh.toString(), start, end, cue, false));
      lastCue = cue;
    }
    return new CoarseFrame(now, end, rolling, lastCue);
  }

  private static boolean sameRange(List<String> a, int x, List<String> b, int y, int count) {
    for (int k = 0; k < count; k++) if (!key(a.get(x + k)).equals(key(b.get(y + k)))) return false;
    return count > 0;
  }

  private static void add(
      List<Word> out, String text, long start, long end, int cue, boolean nativeOffset) {
    List<String> ts = tokens(text.replace('\u00a0', ' '));
    addTokens(out,ts,text,start,end,cue,nativeOffset);
  }
  private static void addTokens(List<Word> out,List<String> ts,String text,long start,long end,int cue,boolean nativeOffset) {
    if (ts.isEmpty() || end <= start) return;
    if (end - start < ts.size()) {
      out.add(new Word(text.trim(), start, end, cue, Precision.ESTIMATED));
      return;
    }
    // Uniform lexical allocation is explicitly estimated; no spelling-length timing heuristic.
    for (int i = 0; i < ts.size(); i++) {
      long a = start + (end - start) * i / ts.size(),
          b = start + (end - start) * (i + 1) / ts.size();
      if (b > a)
        out.add(
            new Word(
                ts.get(i),
                a,
                b,
                cue,
                nativeOffset && ts.size() == 1 ? Precision.NATIVE : Precision.ESTIMATED));
    }
  }

  /** Local evidence alignment, not a global shift. Ambiguous words are deliberately left alone. */
  RebuildSource align(RebuildSource reference) { return align(reference,null); }

  RebuildSource align(RebuildSource reference,java.util.function.Consumer<String> report) {
    if(reference==null){alignmentReport(report,"reason=missing_reference;adopted_segments=0");return this;}
    Map<String,Integer> left=ngrams(words),right=ngrams(reference.words);
    Map<Integer,Integer> matches=new TreeMap<>();Set<Integer> anchors=new HashSet<>();int last=-1;
    for(int i=0;i+2<words.size();i++) {
      String key=gram(words,i);Integer a=left.get(key),j=right.get(key);
      if(a==null || a!=i || j==null || j<0 || j<=last)continue;
      boolean valid=true;
      for(int n=0;n<3;n++)if(Math.abs(words.get(i+n).start-reference.words.get(j+n).start)>10000)valid=false;
      if(valid){for(int n=0;n<3;n++)matches.put(i+n,j+n);anchors.add(i);last=j+2;i+=2;}
    }
    if(matches.size()<6){alignmentReport(report,"reason=insufficient_unique_anchors;matched_words="+matches.size()+";adopted_segments=0");return this;}
    List<Word> proposed=new ArrayList<>(words);int adopted=0,skipped=0,aligned=0;StringBuilder evidence=new StringBuilder();
    List<Integer> eligible=new ArrayList<>();
    for(Map.Entry<Integer,Integer> e:matches.entrySet()) {
      Word a=words.get(e.getKey()),b=reference.words.get(e.getValue());
      if(a.precision==Precision.ESTIMATED && b.precision==Precision.NATIVE && b.start<b.end)eligible.add(e.getKey());
    }
    for(int at=0;at<eligible.size();) {
      int from=eligible.get(at),to=from,jFrom=matches.get(from);at++;
      while(at<eligible.size()) {
        int next=eligible.get(at);
        if(next!=to+1 || matches.get(next)!=matches.get(to)+1
            || RebuildPlanner.hardBreakBefore(this,next) || RebuildPlanner.hardBreakBefore(reference,matches.get(next)))break;
        to=next;at++;
      }
      boolean complete=false;
      for(int anchor:anchors)if(anchor>=from && anchor+2<=to){complete=true;break;}
      String reason=complete ? "" : "incomplete_native_anchor";
      if(reason.isEmpty()) {
        long prior=from==0 ? -1 : proposed.get(from-1).end;
        for(int i=from;i<=to;i++) {
          Word b=reference.words.get(matches.get(i));
          if(b.start<0 || b.start>=b.end || b.start<prior){reason="segment_time_or_boundary_conflict";break;}
          prior=b.end;
        }
        if(reason.isEmpty() && to+1<words.size() && prior>proposed.get(to+1).start)reason="segment_time_or_boundary_conflict";
      }
      if(reason.isEmpty()) {
        for(int cut=Math.max(1,from);cut<=Math.min(words.size()-1,to+1);cut++) {
          Word oldLeft=words.get(cut-1),oldRight=words.get(cut);
          Word newLeft=cut-1>=from && cut-1<=to ? reference.words.get(matches.get(cut-1)) : proposed.get(cut-1);
          Word newRight=cut>=from && cut<=to ? reference.words.get(matches.get(cut)) : proposed.get(cut);
          if(!oldRight.text.startsWith(">>") && (oldRight.start-oldLeft.end>=650)!=(newRight.start-newLeft.end>=650)) {
            reason="hard_source_gap_changed";break;
          }
        }
      }
      if(reason.isEmpty()) {
        for(int i=from;i<=to;i++){Word a=words.get(i),b=reference.words.get(matches.get(i));
          proposed.set(i,new Word(a.text,b.start,b.end,a.cue,Precision.ALIGNED));aligned++;}
        adopted++;reason="adopted";
      } else skipped++;
      if(adopted+skipped<=16){if(evidence.length()>0)evidence.append(',');evidence.append(from).append('-').append(to)
          .append('@').append(jFrom).append(':').append(reason);}
    }
    alignmentReport(report,"matched_words="+matches.size()+";native_candidates="+eligible.size()
        +";adopted_segments="+adopted+";skipped_segments="+skipped+";aligned_words="+aligned
        +";segments_bounded="+evidence+";segments_omitted="+Math.max(0,adopted+skipped-16));
    return adopted==0 ? this : new RebuildSource(proposed,coarseCueReconstructed,coarseCueCount,formatEvidence);
  }
  String precisionEvidence() {
    int nativeWords=0,estimatedWords=0,alignedWords=0;
    for(Word word:words) switch(word.precision){case NATIVE:nativeWords++;break;case ALIGNED:alignedWords++;break;default:estimatedWords++;}
    return "native="+nativeWords+";estimated="+estimatedWords+";aligned="+alignedWords
        +(formatEvidence.isEmpty()?"":";"+formatEvidence);
  }
  private static void alignmentReport(java.util.function.Consumer<String> report,String detail) {
    if(report!=null)report.accept(detail);
  }

  private static String gram(List<Word> w, int i) {
    return key(w.get(i).text) + "|" + key(w.get(i + 1).text) + "|" + key(w.get(i + 2).text);
  }

  private static Map<String, Integer> ngrams(List<Word> w) {
    Map<String, Integer> m = new HashMap<>();
    for (int i = 0; i + 2 < w.size(); i++) {
      String k = gram(w, i);
      m.put(k, m.containsKey(k) ? -1 : i);
    }
    return m;
  }
}
