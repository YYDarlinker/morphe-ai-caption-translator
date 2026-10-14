package app.yydarlinker.deepseekcaptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable evidence, not subtitles. Native segment bounds are never inferred from target text. */
final class RebuildSource {
    enum Precision {
        NATIVE,
        ESTIMATED,
        ALIGNED
    }

    enum AnchorKind {
        WORD_ONSET,
        SEGMENT_ONSET,
        CUE_ONSET,
        CUE_END_BOUND
    }

    /** A time the track itself states for a word boundary, kept to retime another track later. */
    static final class TimeAnchor {
        final int boundary;
        final long timeMs;
        final AnchorKind kind;

        TimeAnchor(int boundary, long timeMs, AnchorKind kind) {
            this.boundary = boundary;
            this.timeMs = timeMs;
            this.kind = kind;
        }
    }

    static final class TimedWindow {
        final int from, to;
        final long start, end;

        TimedWindow(int from, int to, long start, long end) {
            this.from = from;
            this.to = to;
            this.start = start;
            this.end = end;
        }

        boolean contains(long time) {
            return start <= time && time < end;
        }
    }

    static final class Word {
        final String text, key;
        final long start, end;
        final int cue;
        final Precision precision;

        Word(String text, long start, long end, int cue, Precision precision) {
            this.text = text;
            key = key(text);
            this.start = start;
            this.end = end;
            this.cue = cue;
            this.precision = precision;
        }
    }

    /** Coarse spans are ordered BEFORE interpolation; never sort invented word onsets. */
    private static final class Span {
        final String text;
        final long start, end;
        final int cue;
        final boolean nativeOffset, speakerBreak;
        final List<Integer> speakerOffsets;

        Span(String text, long start, long end, int cue, boolean nativeOffset) {
            this(text, start, end, cue, nativeOffset, false, Collections.emptyList());
        }

        Span(
                String text,
                long start,
                long end,
                int cue,
                boolean nativeOffset,
                boolean speakerBreak,
                List<Integer> speakerOffsets) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.cue = cue;
            this.nativeOffset = nativeOffset;
            this.speakerBreak = speakerBreak;
            this.speakerOffsets = speakerOffsets;
        }

        Span withText(String value) {
            return new Span(value, start, end, cue, nativeOffset, speakerBreak, speakerOffsets);
        }

        Span withEnd(long value) {
            return new Span(text, start, value, cue, nativeOffset, speakerBreak, speakerOffsets);
        }
    }

    /** Two words further apart than this are a hard source break. */
    static final long HARD_GAP_MS = 650;

    final List<Word> words;
    final List<TimeAnchor> anchors;
    final boolean coarseCueReconstructed;
    final int coarseCueCount;
    final String formatEvidence;
    private List<TimedWindow> windows;

    RebuildSource(List<Word> values) {
        this(values, Collections.emptyList(), false, 0, "");
    }

    private RebuildSource(
            List<Word> values,
            List<TimeAnchor> anchors,
            boolean reconstructed,
            int count,
            String evidence) {
        words = Collections.unmodifiableList(new ArrayList<>(values));
        this.anchors = Collections.unmodifiableList(new ArrayList<>(anchors));
        coarseCueReconstructed = reconstructed;
        coarseCueCount = count;
        formatEvidence = evidence;
    }

    /** The same evidence with other word times. */
    private RebuildSource retimed(List<Word> values) {
        return new RebuildSource(
                values, anchors, coarseCueReconstructed, coarseCueCount, formatEvidence);
    }

    // ---- Tokens ----

    static final Pattern TOKEN =
            Pattern.compile(
                    "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]"
                            + "|[+-]?\\p{N}+(?:[.,:/-]\\p{N}+)*(?:[a-zA-Z]+)?(?:%|％)?"
                            + "|[\\p{L}\\p{M}]+(?:[.'’_\\-][\\p{L}\\p{M}\\p{N}]+)*|[^\\s]");

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

    private static final Pattern KEY_EDGE =
            Pattern.compile("^[^\\p{L}\\p{N}+\\-]+|[^\\p{L}\\p{N}%]+$");

    static String key(String s) {
        String ascii = asciiKey(s);
        if (ascii != null) return ascii;
        String folded =
                Normalizer.normalize(s, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .replace('’', '\'')
                        .replace('−', '-');
        return KEY_EDGE.matcher(folded).replaceAll("");
    }

    /** Printable ASCII with alphanumeric edges: NFKC, both replacements and KEY_EDGE are no-ops. */
    private static String asciiKey(String s) {
        int n = s.length();
        if (n == 0) return null;
        for (int i = 0; i < n; i++) if (s.charAt(i) < 0x20 || s.charAt(i) > 0x7e) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        char first = lower.charAt(0), last = lower.charAt(n - 1);
        boolean head = Character.isLetterOrDigit(first) || first == '+' || first == '-';
        boolean tail = Character.isLetterOrDigit(last) || last == '%';
        return head && tail ? lower : null;
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

    String text(int from, int to) {
        return join(words, from, to);
    }

    // ---- Timing evidence ----

    int nativeWordCount() {
        int count = 0;
        for (Word word : words) if (word.precision == Precision.NATIVE) count++;
        return count;
    }

    private int anchorCount(AnchorKind... kinds) {
        int count = 0;
        for (TimeAnchor anchor : anchors)
            for (AnchorKind kind : kinds)
                if (anchor.kind == kind) {
                    count++;
                    break;
                }
        return count;
    }

    private int segmentAnchorCount() {
        return anchorCount(AnchorKind.SEGMENT_ONSET);
    }

    private int cueAnchorCount() {
        return anchorCount(AnchorKind.CUE_ONSET, AnchorKind.CUE_END_BOUND);
    }

    boolean hasPartialTiming() {
        return segmentAnchorCount() > 0 || cueAnchorCount() > 0;
    }

    int matchedWordCount(RebuildSource reference) {
        return reference == null ? 0 : uniqueWordMatches(words, reference.words).size();
    }

    /**
     * How much this track can teach {@code primary} about timing: 0 (nothing) to 3 (word times).
     */
    int timingQualityAgainst(RebuildSource primary) {
        int matched = primary == null ? 6 : primary.matchedWordCount(this);
        if (matched < 6) return 0;
        if (nativeWordCount() > 0) return 3;
        if (segmentAnchorCount() > 0) return 2;
        if (cueAnchorCount() > 0) return 1;
        return 0;
    }

    String timingCapabilities() {
        return "word_direct="
                + nativeWordCount()
                + ";segment_anchors="
                + segmentAnchorCount()
                + ";cue_anchors="
                + cueAnchorCount()
                + ";words="
                + words.size()
                + ";format="
                + (formatEvidence.isEmpty() ? "unknown" : formatEvidence);
    }

    String precisionEvidence() {
        int nativeWords = 0, alignedWords = 0;
        for (Word word : words) {
            if (word.precision == Precision.NATIVE) nativeWords++;
            else if (word.precision == Precision.ALIGNED) alignedWords++;
        }
        return "native="
                + nativeWords
                + ";estimated="
                + (words.size() - nativeWords - alignedWords)
                + ";aligned="
                + alignedWords
                + ";segment_anchors="
                + segmentAnchorCount()
                + ";cue_anchors="
                + cueAnchorCount()
                + (formatEvidence.isEmpty() ? "" : ";" + formatEvidence);
    }

    /** The speech runs of this source: a new run starts at a new cue, a hard gap or a speaker. */
    List<TimedWindow> windows() {
        if (windows == null) windows = computeWindows();
        return windows;
    }

    private List<TimedWindow> computeWindows() {
        if (words.isEmpty()) return Collections.emptyList();
        List<TimedWindow> result = new ArrayList<>();
        int from = 0, cue = words.get(0).cue;
        long start = words.get(0).start, end = words.get(0).end;
        for (int i = 1; i < words.size(); i++) {
            Word prior = words.get(i - 1), now = words.get(i);
            if (now.cue != cue
                    || now.start - prior.end >= HARD_GAP_MS
                    || now.text.startsWith(">>")) {
                if (end > start) result.add(new TimedWindow(from, i - 1, start, end));
                from = i;
                start = now.start;
                cue = now.cue;
            }
            end = Math.max(end, now.end);
        }
        if (end > start) result.add(new TimedWindow(from, words.size() - 1, start, end));
        return Collections.unmodifiableList(result);
    }

    TimedWindow windowAt(long time) {
        for (TimedWindow window : windows()) if (window.contains(time)) return window;
        return null;
    }

    // ---- Word classes ----

    // Compiled once: terminal() runs for every source word while request blocks are planned.
    private static final Pattern TERMINAL = Pattern.compile("(?s).*[.!?。！？][\\\"'”’）)]*$"),
            ABBREVIATION = Pattern.compile("(?i).*(?:\\bMr|\\bMrs|\\bDr|\\bProf|\\bvs|\\betc)\\."),
            MUSIC = Pattern.compile("[♪♫\\s]+"),
            NON_SPEECH =
                    Pattern.compile("[\\[（(【]\\s*(music|applause|laughter|音乐|掌声|笑声)\\s*[\\]）)】]");

    static boolean terminal(String s) {
        // Necessary condition for TERMINAL; unpunctuated ASR words never reach the pattern.
        boolean candidate = false;
        for (int i = 0; i < s.length() && !candidate; i++)
            candidate = ".!?。！？".indexOf(s.charAt(i)) >= 0;
        return candidate && TERMINAL.matcher(s).matches() && !ABBREVIATION.matcher(s).matches();
    }

    static boolean nonSpeech(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return MUSIC.matcher(t).matches() || NON_SPEECH.matcher(t).matches();
    }

    // ---- Reading a track ----

    /**
     * Zero-width spaces (U+200B, U+2060, U+FEFF) pad the cues of some tracks. They are not speech,
     * but each one becomes a token whose exact count a model cannot reproduce when it must quote
     * the source, so every such block was rejected.
     */
    private static final Pattern ZERO_WIDTH_SPACES = Pattern.compile("[\u200B\u2060\uFEFF]");

    private static List<Span> withoutZeroWidthSpaces(List<Span> spans) {
        List<Span> out = new ArrayList<>(spans.size());
        for (Span span : spans)
            out.add(
                    span.speakerOffsets.isEmpty()
                            ? span.withText(ZERO_WIDTH_SPACES.matcher(span.text).replaceAll(""))
                            : span);
        return out;
    }

    /**
     * Some tracks carry every coarse cue twice (an overlapping second display window, or a pop-on
     * line shown again as it moves up). Repetition alone is not evidence, so this applies only when
     * the repetition is systematic over the whole track: an isolated repeated sentence is real
     * speech and stays. The second copy only extends the first copy's display time.
     */
    private static final long DOUBLED_CUE_GAP_MS = 600;

    private static final int DOUBLED_CUE_MIN_REPEATS = 6;

    private static boolean sameCoarseText(Span a, Span b) {
        if (a.nativeOffset || b.nativeOffset) return false;
        // Zero-width padding around a cue is not text: the copy may carry different padding.
        List<String> x = tokens(visibleText(a.text)), y = tokens(visibleText(b.text));
        if (x.isEmpty() || x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) if (!key(x.get(i)).equals(key(y.get(i)))) return false;
        return true;
    }

    private static String visibleText(String text) {
        return text == null ? "" : text.replaceAll("[\u200B-\u200F\u2060\uFEFF]", "");
    }

    private static boolean repeatedBlock(List<Span> spans, int at, int size) {
        // The copy begins right after the block it repeats (or while it is still shown).
        if (spans.get(at + size).start > spans.get(at + size - 1).end + DOUBLED_CUE_GAP_MS)
            return false;
        for (int j = 0; j < size; j++) {
            Span first = spans.get(at + j), copy = spans.get(at + size + j);
            if (copy.start < first.start || !sameCoarseText(first, copy)) return false;
        }
        return true;
    }

    private static List<Span> collapseDoubledCues(List<Span> spans) {
        int n = spans.size();
        List<int[]> blocks = new ArrayList<>();
        int repeated = 0;
        for (int i = 0; i < n; ) {
            int advance = 1;
            for (int size = 1; size <= 3 && i + 2 * size <= n; size++) {
                if (!repeatedBlock(spans, i, size)) continue;
                blocks.add(new int[] {i, size});
                repeated += size;
                advance = 2 * size;
                break;
            }
            i += advance;
        }
        if (repeated < DOUBLED_CUE_MIN_REPEATS || repeated * 5 < n) return spans;
        Span[] result = spans.toArray(new Span[0]);
        boolean[] dropped = new boolean[n];
        for (int[] block : blocks)
            for (int j = 0; j < block[1]; j++) {
                Span first = result[block[0] + j], copy = spans.get(block[0] + block[1] + j);
                if (copy.end > first.end) result[block[0] + j] = first.withEnd(copy.end);
                dropped[block[0] + block[1] + j] = true;
            }
        List<Span> out = new ArrayList<>(n - repeated);
        for (int i = 0; i < n; i++) if (!dropped[i]) out.add(result[i]);
        return out;
    }

    /**
     * Uploaders write ruby (furigana) into YouTube's own format as base text followed by the
     * reading in brackets: 漢字(かんじ). YouTube draws the reading above the text; read as plain text it
     * is a second copy of the sentence that translations would then repeat. A track that does this
     * systematically is read as its base text; one stray bracket is real text.
     */
    private static final Pattern RUBY = Pattern.compile("([㐀-鿿々〆ヶ]+)[（(]([぀-ゟ゠-ヿー]+)[）)]");

    private static List<Span> withoutRuby(List<Span> spans) {
        int found = 0;
        for (Span span : spans) {
            if (span.nativeOffset) continue;
            Matcher m = RUBY.matcher(span.text);
            while (m.find()) found++;
        }
        if (found < 3) return spans;
        List<Span> out = new ArrayList<>(spans.size());
        for (Span span : spans)
            out.add(
                    span.nativeOffset || !RUBY.matcher(span.text).find()
                            ? span
                            : span.withText(RUBY.matcher(span.text).replaceAll("$1")));
        return out;
    }

    static RebuildSource read(byte[] body, CaptionDocument.Parsed parsed) throws Exception {
        return read(body, parsed, false);
    }

    static RebuildSource read(byte[] body, CaptionDocument.Parsed parsed, boolean translating)
            throws Exception {
        String raw = new String(body, StandardCharsets.UTF_8).trim();
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1).trim();
        boolean webVtt = raw.startsWith("WEBVTT"), json3 = raw.startsWith("{");
        String formatEvidence = "";
        List<Span> spans = new ArrayList<>();
        if (webVtt) {
            WebVttSourceReader.Result vtt = WebVttSourceReader.read(raw);
            formatEvidence = vtt.evidence();
            for (WebVttSourceReader.Range range : vtt.ranges)
                spans.add(
                        new Span(
                                range.text,
                                range.start,
                                range.end,
                                range.cue,
                                range.nativeStart,
                                range.speakerBreak,
                                range.speakerOffsets));
        }
        if (json3) json3Spans(new JSONObject(raw).optJSONArray("events"), spans);
        if (spans.isEmpty() && !webVtt) {
            int i = 0;
            for (CaptionDocument.Cue c : parsed.cues())
                spans.add(new Span(c.text, c.startMs, c.endMs, i++, false));
        }
        // Ordering comes from real cue/segment onsets. A multiword coarse span remains
        // indivisible during ordering. Equal real native onsets may be repeated by an overlapping
        // JSON3 window.
        spans.sort(Comparator.comparingLong((Span v) -> v.start));
        spans = collapseDoubledCues(withoutZeroWidthSpaces(spans));
        if (translating) spans = withoutRuby(spans);
        List<Span> ordered = orderedSpans(spans, webVtt);

        List<Word> out = new ArrayList<>();
        List<TimeAnchor> observed = new ArrayList<>();
        boolean repaired = false;
        int coarse = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Span span = ordered.get(i);
            List<String> spanTokens = tokens(span.text);
            boolean estimated = !span.nativeOffset || spanTokens.size() != 1;
            if (estimated) coarse++;
            long end = span.end;
            if (i + 1 < ordered.size() && ordered.get(i + 1).start < end) {
                end = ordered.get(i + 1).start;
                repaired |= estimated;
            }
            int first = out.size();
            AnchorKind onset =
                    span.nativeOffset && spanTokens.size() == 1
                            ? AnchorKind.WORD_ONSET
                            : span.nativeOffset ? AnchorKind.SEGMENT_ONSET : AnchorKind.CUE_ONSET;
            if (!spanTokens.isEmpty()) observed.add(new TimeAnchor(first, span.start, onset));
            Set<Integer> speakerWords = new HashSet<>();
            if (span.speakerBreak) speakerWords.add(0);
            if (span.speakerOffsets.isEmpty())
                addTokens(
                        out,
                        tokens(span.text.replace(' ', ' ')),
                        span.text,
                        span.start,
                        end,
                        span.cue,
                        span.nativeOffset);
            else {
                List<String> speech = new ArrayList<>();
                int from = 0;
                for (int mark : span.speakerOffsets)
                    if (mark >= from && mark < span.text.length()) {
                        speech.addAll(tokens(span.text.substring(from, mark).replace(' ', ' ')));
                        speakerWords.add(speech.size());
                        from = mark;
                    }
                speech.addAll(tokens(span.text.substring(from).replace(' ', ' ')));
                if (end - span.start < speech.size())
                    throw new IllegalArgumentException("vtt_voice_time_capacity");
                addTokens(out, speech, span.text, span.start, end, span.cue, span.nativeOffset);
            }
            if (!spanTokens.isEmpty())
                observed.add(new TimeAnchor(out.size(), end, AnchorKind.CUE_END_BOUND));
            for (int index : speakerWords)
                if (first + index < out.size()) {
                    Word word = out.get(first + index);
                    out.set(
                            first + index,
                            new Word(
                                    ">>" + word.text,
                                    word.start,
                                    word.end,
                                    word.cue,
                                    word.precision));
                }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("source_empty");
        for (int i = 1; i < out.size(); i++)
            if (out.get(i).start < out.get(i - 1).end)
                throw new IllegalArgumentException("source_time_order");
        return new RebuildSource(out, observed, repaired, coarse, formatEvidence);
    }

    /**
     * The spans that carry words, in onset order. Simultaneous coarse observations have no
     * word-order timing proof, so they are kept as one span holding both texts.
     */
    private static List<Span> orderedSpans(List<Span> spans, boolean webVtt) {
        List<Span> ordered = new ArrayList<>();
        Set<String> nativeSeen = new HashSet<>();
        for (Span span : spans) {
            List<String> ts = tokens(span.text);
            if (ts.isEmpty() || span.end <= span.start) continue;
            if (!webVtt
                    && span.nativeOffset
                    && ts.size() == 1
                    && !nativeSeen.add(span.start + "|" + key(ts.get(0)))) continue;
            Span last = ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);
            if (last != null && last.start == span.start)
                ordered.set(
                        ordered.size() - 1,
                        new Span(
                                last.text.trim() + " " + span.text.trim(),
                                last.start,
                                Math.max(last.end, span.end),
                                last.cue,
                                false));
            else ordered.add(span);
        }
        return ordered;
    }

    /** The spans of a JSON3 track: whole events, or runs between explicit segment offsets. */
    private static void json3Spans(JSONArray events, List<Span> spans) {
        if (events == null) return;
        Map<Integer, CoarseFrame> frames = new HashMap<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            JSONArray segs = event.optJSONArray("segs");
            if (segs == null) continue;
            long start = event.optLong("tStartMs", -1), duration = event.optLong("dDurationMs", -1);
            if (start < 0) continue;
            long next = Long.MAX_VALUE;
            for (int j = i + 1; j < events.length(); j++) {
                JSONObject later = events.optJSONObject(j);
                if (later != null
                        && later.optLong("tStartMs", -1) > start
                        && later.optJSONArray("segs") != null) {
                    next = later.optLong("tStartMs");
                    break;
                }
            }
            long end =
                    duration > 0 ? start + duration : next == Long.MAX_VALUE ? start + 2000 : next;
            if (end <= start) continue;
            boolean offsets = false;
            for (int j = 0; j < segs.length(); j++) {
                JSONObject part = segs.optJSONObject(j);
                offsets |= part != null && part.has("tOffsetMs");
            }
            if (offsets) {
                offsetSpans(segs, start, end, i, spans);
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < segs.length(); j++) {
                JSONObject part = segs.optJSONObject(j);
                if (part != null) text.append(part.optString("utf8", ""));
            }
            if (event.has("wWinId")) {
                int window = event.optInt("wWinId");
                frames.put(
                        window,
                        appendFrame(
                                spans,
                                text.toString(),
                                start,
                                end,
                                i,
                                event.optInt("aAppend", 0) == 1,
                                frames.get(window)));
            } else spans.add(new Span(text.toString(), start, end, i, false));
        }
    }

    /** Merges runs with no independent onset instead of inventing simultaneous word order. */
    private static void offsetSpans(
            JSONArray segs, long start, long end, int cue, List<Span> spans) {
        long offset = 0;
        StringBuilder text = new StringBuilder();
        boolean nativeStart = true;
        for (int j = 0; j < segs.length(); j++) {
            JSONObject part = segs.optJSONObject(j);
            if (part == null) continue;
            if (part.has("tOffsetMs")) {
                long value = part.optLong("tOffsetMs", -1);
                if (value < offset || value < 0 || start + value >= end)
                    throw new IllegalArgumentException("source_offset_order");
                if (value > offset) {
                    if (text.length() > 0)
                        spans.add(
                                new Span(
                                        text.toString(),
                                        start + offset,
                                        start + value,
                                        cue,
                                        nativeStart));
                    text.setLength(0);
                    offset = value;
                    nativeStart = true;
                }
            }
            text.append(part.optString("utf8", ""));
        }
        if (text.length() > 0)
            spans.add(new Span(text.toString(), start + offset, end, cue, nativeStart));
    }

    /**
     * Only explicit, overlapping display windows may carry earlier text. Repetition alone is not
     * evidence.
     */
    private static final class CoarseFrame {
        final List<String> tokens;
        final long end;
        final boolean rolling;

        CoarseFrame(List<String> tokens, long end, boolean rolling) {
            this.tokens = tokens;
            this.end = end;
            this.rolling = rolling;
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
        }
        return new CoarseFrame(now, end, rolling);
    }

    private static boolean sameRange(List<String> a, int x, List<String> b, int y, int count) {
        for (int k = 0; k < count; k++)
            if (!key(a.get(x + k)).equals(key(b.get(y + k)))) return false;
        return count > 0;
    }

    /** Spreads the tokens uniformly over the span: explicitly estimated, never length-weighted. */
    private static void addTokens(
            List<Word> out,
            List<String> ts,
            String text,
            long start,
            long end,
            int cue,
            boolean nativeOffset) {
        if (ts.isEmpty() || end <= start) return;
        if (end - start < ts.size()) {
            out.add(new Word(text.trim(), start, end, cue, Precision.ESTIMATED));
            return;
        }
        for (int i = 0; i < ts.size(); i++) {
            long a = start + (end - start) * i / ts.size();
            long b = start + (end - start) * (i + 1) / ts.size();
            if (b > a)
                out.add(
                        new Word(
                                ts.get(i),
                                a,
                                b,
                                cue,
                                nativeOffset && ts.size() == 1
                                        ? Precision.NATIVE
                                        : Precision.ESTIMATED));
        }
    }

    // ---- Aligning to a reference track ----

    /**
     * Local evidence alignment, not a global shift. Ambiguous words are deliberately left alone.
     */
    RebuildSource align(RebuildSource reference) {
        return align(reference, null);
    }

    RebuildSource align(RebuildSource reference, Consumer<String> report) {
        RebuildSource direct = alignNativeOnly(reference, report);
        return direct != this ? direct : direct.alignPartial(reference, report);
    }

    private RebuildSource alignNativeOnly(RebuildSource reference, Consumer<String> report) {
        if (reference == null) {
            report(report, "reason=missing_reference;adopted_segments=0");
            return this;
        }
        Map<String, Integer> left = ngrams(words), right = ngrams(reference.words);
        Map<Integer, Integer> matches = new TreeMap<>();
        Set<Integer> triples = new HashSet<>();
        int last = -1;
        for (int i = 0; i + 2 < words.size(); i++) {
            String key = gram(words, i);
            Integer a = left.get(key), j = right.get(key);
            if (a == null || a != i || j == null || j < 0 || j <= last) continue;
            boolean valid = true;
            for (int n = 0; n < 3; n++)
                if (Math.abs(words.get(i + n).start - reference.words.get(j + n).start) > 10000)
                    valid = false;
            if (valid) {
                for (int n = 0; n < 3; n++) matches.put(i + n, j + n);
                triples.add(i);
                last = j + 2;
                i += 2;
            }
        }
        if (matches.size() < 6) {
            report(
                    report,
                    "reason=insufficient_unique_anchors;matched_words="
                            + matches.size()
                            + ";adopted_segments=0");
            return this;
        }
        List<Word> proposed = new ArrayList<>(words);
        int adopted = 0, skipped = 0, aligned = 0;
        StringBuilder evidence = new StringBuilder();
        List<Integer> eligible = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : matches.entrySet()) {
            Word a = words.get(e.getKey()), b = reference.words.get(e.getValue());
            if (a.precision == Precision.ESTIMATED
                    && b.precision == Precision.NATIVE
                    && b.start < b.end) eligible.add(e.getKey());
        }
        for (int at = 0; at < eligible.size(); ) {
            int from = eligible.get(at), to = from, referenceFrom = matches.get(from);
            at++;
            while (at < eligible.size()) {
                int next = eligible.get(at);
                if (next != to + 1
                        || matches.get(next) != matches.get(to) + 1
                        || RebuildPlanner.hardBreakBefore(this, next)
                        || RebuildPlanner.hardBreakBefore(reference, matches.get(next))) break;
                to = next;
                at++;
            }
            String reason = segmentConflict(reference, matches, triples, proposed, from, to);
            if (reason.isEmpty()) {
                for (int i = from; i <= to; i++) {
                    Word a = words.get(i), b = reference.words.get(matches.get(i));
                    proposed.set(i, new Word(a.text, b.start, b.end, a.cue, Precision.ALIGNED));
                    aligned++;
                }
                adopted++;
                reason = "adopted";
            } else skipped++;
            if (adopted + skipped <= 16) {
                if (evidence.length() > 0) evidence.append(',');
                evidence.append(from)
                        .append('-')
                        .append(to)
                        .append('@')
                        .append(referenceFrom)
                        .append(':')
                        .append(reason);
            }
        }
        int hardBreakRestored = restoreHardBreaks(proposed);
        int effective = 0;
        for (int i = 0; i < proposed.size(); i++) if (proposed.get(i) != words.get(i)) effective++;
        report(
                report,
                "matched_words="
                        + matches.size()
                        + ";native_candidates="
                        + eligible.size()
                        + ";adopted_segments="
                        + adopted
                        + ";skipped_segments="
                        + skipped
                        + ";aligned_words="
                        + aligned
                        + ";hard_break_restored="
                        + hardBreakRestored
                        + ";segments_bounded="
                        + evidence
                        + ";segments_omitted="
                        + Math.max(0, adopted + skipped - 16));
        return effective == 0 ? this : retimed(proposed);
    }

    /** Why the run {@code from..to} must not take the reference's times; empty when it may. */
    private String segmentConflict(
            RebuildSource reference,
            Map<Integer, Integer> matches,
            Set<Integer> triples,
            List<Word> proposed,
            int from,
            int to) {
        boolean complete = false;
        for (int anchor : triples)
            if (anchor >= from && anchor + 2 <= to) {
                complete = true;
                break;
            }
        if (!complete) return "incomplete_native_anchor";
        long prior = from == 0 ? -1 : proposed.get(from - 1).end;
        for (int i = from; i <= to; i++) {
            Word b = reference.words.get(matches.get(i));
            if (b.start < 0 || b.start >= b.end || b.start < prior)
                return "segment_time_or_boundary_conflict";
            prior = b.end;
        }
        if (to + 1 < words.size() && prior > proposed.get(to + 1).start)
            return "segment_time_or_boundary_conflict";
        for (int cut = Math.max(1, from); cut <= Math.min(words.size() - 1, to + 1); cut++) {
            Word oldLeft = words.get(cut - 1), oldRight = words.get(cut);
            Word newLeft =
                    cut - 1 >= from && cut - 1 <= to
                            ? reference.words.get(matches.get(cut - 1))
                            : proposed.get(cut - 1);
            Word newRight =
                    cut >= from && cut <= to
                            ? reference.words.get(matches.get(cut))
                            : proposed.get(cut);
            if (!oldRight.text.startsWith(">>")
                    && (oldRight.start - oldLeft.end >= HARD_GAP_MS)
                            != (newRight.start - newLeft.end >= HARD_GAP_MS))
                return "hard_source_gap_changed";
        }
        return "";
    }

    /**
     * Puts back the original words around a boundary whose hard-gap status the retiming changed.
     */
    private int restoreHardBreaks(List<Word> proposed) {
        int restored = 0;
        boolean changed = true;
        for (int pass = 0; changed && pass < 3; pass++) {
            changed = false;
            for (int i = 1; i < proposed.size(); i++) {
                boolean before = words.get(i).start - words.get(i - 1).end >= HARD_GAP_MS;
                boolean after = proposed.get(i).start - proposed.get(i - 1).end >= HARD_GAP_MS;
                if (before == after) continue;
                if (proposed.get(i) != words.get(i)) {
                    proposed.set(i, words.get(i));
                    changed = true;
                }
                if (proposed.get(i - 1) != words.get(i - 1)) {
                    proposed.set(i - 1, words.get(i - 1));
                    changed = true;
                }
                restored++;
            }
        }
        return restored;
    }

    /**
     * Consumes segment/cue boundaries even when the reference has no word-level offsets. This is a
     * local, bounded affine remap: source shape between two observed boundaries is preserved,
     * native words and hard gaps are immutable constraints, and a tail without a right boundary is
     * never extrapolated.
     */
    private RebuildSource alignPartial(RebuildSource reference, Consumer<String> report) {
        if (reference == null || reference.anchors.isEmpty()) return this;
        Map<Integer, Integer> refToPrimary = uniqueWordMatches(words, reference.words);
        if (refToPrimary.size() < 6) {
            report(
                    report,
                    "partial_reason=insufficient_unique_context;matched_words="
                            + refToPrimary.size()
                            + ";anchors_adopted=0;actual_retimed=0");
            return this;
        }
        List<TimeAnchor> pairs = new ArrayList<>();
        for (TimeAnchor anchor : reference.anchors) {
            int primary =
                    mapBoundary(
                            anchor.boundary, refToPrimary, reference.words.size(), words.size());
            if (primary < 0 || primary > words.size()) continue;
            if (anchor.kind == AnchorKind.CUE_END_BOUND && anchor.boundary == 0) continue;
            pairs.add(new TimeAnchor(primary, anchor.timeMs, anchor.kind));
        }
        int mapped = pairs.size();
        pairs.sort(Comparator.comparingInt(p -> p.boundary));
        // One anchor per boundary: the most specific kind wins.
        List<TimeAnchor> unique = new ArrayList<>();
        for (TimeAnchor pair : pairs) {
            TimeAnchor prior = unique.isEmpty() ? null : unique.get(unique.size() - 1);
            if (prior == null || prior.boundary != pair.boundary) unique.add(pair);
            else if (anchorRank(pair.kind) > anchorRank(prior.kind))
                unique.set(unique.size() - 1, pair);
        }
        if (unique.size() < 2) {
            report(
                    report,
                    "partial_reason=unmapped_boundaries;matched_words="
                            + refToPrimary.size()
                            + ";anchors_seen="
                            + reference.anchors.size()
                            + ";anchors_mapped="
                            + mapped
                            + ";anchors_adopted=0;actual_retimed=0");
            return this;
        }
        List<Word> proposed = new ArrayList<>(words);
        int adopted = 0, retimed = 0, skipped = 0;
        StringBuilder components = new StringBuilder();
        for (int n = 0; n + 1 < unique.size(); n++) {
            TimeAnchor left = unique.get(n), right = unique.get(n + 1);
            if (right.boundary <= left.boundary || right.timeMs <= left.timeMs) continue;
            long oldLeft = boundaryTime(words, left.boundary);
            long oldRight = boundaryTime(words, right.boundary);
            if (oldRight <= oldLeft) continue;
            String reason = "";
            for (int i = left.boundary; i < right.boundary; i++) {
                if (i >= words.size()) {
                    reason = "boundary_out_of_range";
                    break;
                }
                if (RebuildPlanner.hardBreakBefore(this, i)) {
                    reason = "hard_source_gap";
                    break;
                }
            }
            List<Word> candidate = new ArrayList<>(proposed);
            int local = 0;
            if (reason.isEmpty())
                for (int i = left.boundary; i < right.boundary; i++) {
                    Word old = words.get(i);
                    if (old.precision != Precision.ESTIMATED) continue;
                    long a = mapTime(old.start, oldLeft, oldRight, left.timeMs, right.timeMs);
                    long b = mapTime(old.end, oldLeft, oldRight, left.timeMs, right.timeMs);
                    if (b <= a) {
                        reason = "non_monotone";
                        break;
                    }
                    candidate.set(i, new Word(old.text, a, b, old.cue, Precision.ESTIMATED));
                    local++;
                }
            if (reason.isEmpty()) {
                if (left.boundary > 0
                        && candidate.get(left.boundary - 1).end
                                > candidate.get(left.boundary).start) reason = "left_constraint";
                else if (right.boundary < words.size()
                        && candidate.get(right.boundary - 1).end
                                > candidate.get(right.boundary).start) reason = "right_constraint";
            }
            if (reason.isEmpty() && local > 0) {
                for (int i = left.boundary; i < right.boundary; i++)
                    if (candidate.get(i).precision == Precision.ESTIMATED
                            && (candidate.get(i).start != words.get(i).start
                                    || candidate.get(i).end != words.get(i).end)) retimed++;
                proposed = candidate;
                adopted++;
                if (components.length() > 0) components.append(',');
                components
                        .append(left.boundary)
                        .append('-')
                        .append(right.boundary)
                        .append('@')
                        .append(left.timeMs)
                        .append('-')
                        .append(right.timeMs)
                        .append(":adopted");
            } else if (!reason.isEmpty()) {
                skipped++;
                if (components.length() < 900) {
                    if (components.length() > 0) components.append(',');
                    components
                            .append(left.boundary)
                            .append('-')
                            .append(right.boundary)
                            .append(':')
                            .append(reason);
                }
            }
        }
        report(
                report,
                "partial_matched_words="
                        + refToPrimary.size()
                        + ";anchors_seen="
                        + reference.anchors.size()
                        + ";anchors_mapped="
                        + mapped
                        + ";anchors_adopted="
                        + adopted * 2
                        + ";partial_components="
                        + adopted
                        + ";partial_skipped="
                        + skipped
                        + ";actual_retimed="
                        + retimed
                        + ";partial_components_detail="
                        + components);
        return retimed == 0 ? this : retimed(proposed);
    }

    private static void report(Consumer<String> report, String detail) {
        if (report != null) report.accept(detail);
    }

    private static int anchorRank(AnchorKind kind) {
        switch (kind) {
            case WORD_ONSET:
                return 4;
            case SEGMENT_ONSET:
                return 3;
            case CUE_ONSET:
                return 2;
            default:
                return 1;
        }
    }

    private static int mapBoundary(
            int boundary, Map<Integer, Integer> matches, int refSize, int primarySize) {
        if (boundary <= 0) return 0;
        if (boundary >= refSize) return primarySize;
        Integer exact = matches.get(boundary);
        if (exact != null) return exact;
        Integer prior = matches.get(boundary - 1);
        return prior == null ? -1 : prior + 1;
    }

    private static long boundaryTime(List<Word> values, int boundary) {
        if (values.isEmpty()) return 0;
        if (boundary <= 0) return values.get(0).start;
        if (boundary >= values.size()) return values.get(values.size() - 1).end;
        return values.get(boundary).start;
    }

    private static long mapTime(long value, long oldA, long oldB, long newA, long newB) {
        return newA
                + Math.round((double) ((value - oldA) * (newB - newA)) / (double) (oldB - oldA));
    }

    /** Reference word index to primary word index for every word of a unique three-word run. */
    private static Map<Integer, Integer> uniqueWordMatches(
            List<Word> primary, List<Word> reference) {
        Map<String, Integer> left = ngrams(primary), right = ngrams(reference);
        Map<Integer, Integer> out = new TreeMap<>();
        int last = -1;
        for (int i = 0; i + 2 < primary.size(); i++) {
            String gram = gram(primary, i);
            Integer a = left.get(gram), j = right.get(gram);
            if (a == null || a != i || j == null || j < 0 || j <= last) continue;
            for (int n = 0; n < 3; n++) out.put(j + n, i + n);
            last = j + 2;
            i += 2;
        }
        return out;
    }

    private static String gram(List<Word> w, int i) {
        return key(w.get(i).text) + "|" + key(w.get(i + 1).text) + "|" + key(w.get(i + 2).text);
    }

    /** Index of each three-word run's first word, or -1 when the run occurs more than once. */
    private static Map<String, Integer> ngrams(List<Word> w) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i + 2 < w.size(); i++) {
            String k = gram(w, i);
            m.put(k, m.containsKey(k) ? -1 : i);
        }
        return m;
    }
}
