package app.yydarlinker.caption.contracts;

import app.yydarlinker.caption.contracts.CaptionContracts.Source;
import app.yydarlinker.caption.contracts.CaptionContracts.SourceFragment;
import app.yydarlinker.caption.contracts.CaptionContracts.SpeakerMarker;
import app.yydarlinker.caption.contracts.CaptionContracts.Unit;
import app.yydarlinker.caption.contracts.CaptionContracts.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/** Derived reservations only. No target text and no modification of original source time. */
public final class DisplayTimeline {
    public static final class Slot {
        public final String unitId, issue;
        public final int from, to;
        public final List<Window> windows, originalWindows;

        Slot(
                String content,
                int from,
                int to,
                List<Window> windows,
                List<Window> original,
                String issue) {
            unitId = CaptionContracts.hash(content + ':' + from + ':' + to);
            this.from = from;
            this.to = to;
            this.windows = Collections.unmodifiableList(new ArrayList<>(windows));
            originalWindows = Collections.unmodifiableList(new ArrayList<>(original));
            this.issue = issue;
        }

        public boolean contains(long at) {
            for (Window w : windows) if (w.contains(at)) return true;
            return false;
        }
    }

    public final long revision;
    public final List<Slot> slots;
    public final String signature;

    private DisplayTimeline(List<Slot> slots, long revision) {
        this.slots = Collections.unmodifiableList(slots);
        this.revision = revision;
        StringBuilder key = new StringBuilder();
        for (Slot s : slots) {
            key.append(s.unitId).append(s.issue);
            for (Window w : s.windows)
                key.append('/').append(w.startMs).append('-').append(w.endMs);
        }
        signature = CaptionContracts.hash(key.toString());
    }

    /** One slot per distinct start token, in source order; the last unit at a start wins. */
    public static DisplayTimeline fromUnits(
            String content, Source source, List<Unit> units, long revision) {
        NavigableMap<Integer, Unit> byStart = new TreeMap<>();
        for (Unit u : units) byStart.put(u.from, u);
        List<Unit> ordered = new ArrayList<>(byStart.values());
        List<Slot> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Unit unit = ordered.get(i);
            List<Window> raw = windows(source, unit);
            String issue = "";
            long upper = Long.MAX_VALUE;
            if (raw.isEmpty()) issue = "unknown_source_time";
            if (source != null && unknown(source, unit)) issue = "partially_unknown_source_time";
            if (source != null && speakers(source, unit).size() > 1)
                issue = "multiple_speakers_not_linearizable";
            if (i + 1 < ordered.size()) {
                Unit next = ordered.get(i + 1);
                List<Window> nw = windows(source, next);
                if (!raw.isEmpty()
                        && !nw.isEmpty()
                        && nw.get(0).startMs < raw.get(raw.size() - 1).endMs) {
                    if (nw.get(0).startMs <= raw.get(0).startMs)
                        issue = "nonmonotonic_source_groups";
                    else if (source != null && speakersDiffer(source, unit, next))
                        issue = "overlapping_speakers";
                    else upper = nw.get(0).startMs;
                }
            }
            if (i > 0 && !raw.isEmpty()) {
                Unit before = ordered.get(i - 1);
                List<Window> prev = windows(source, before);
                if (!prev.isEmpty() && raw.get(0).startMs <= prev.get(0).startMs)
                    issue = "nonmonotonic_source_groups";
                else if (source != null
                        && !prev.isEmpty()
                        && raw.get(0).startMs < prev.get(prev.size() - 1).endMs
                        && speakersDiffer(source, unit, before)) issue = "overlapping_speakers";
            }
            List<Window> allocated = new ArrayList<>();
            if (issue.isEmpty())
                for (Window w : raw) {
                    long end = Math.min(upper, w.endMs);
                    if (end > w.startMs) allocated.add(new Window(w.startMs, end));
                }
            if (allocated.isEmpty() && issue.isEmpty()) issue = "unallocatable_source_window";
            result.add(new Slot(content, unit.from, unit.to, allocated, raw, issue));
        }
        // Reject any remaining ambiguous display time instead of selecting last-audible.
        Set<String> ambiguous = new HashSet<>();
        for (int i = 0; i < result.size(); i++)
            for (int j = i + 1; j < result.size(); j++)
                for (Window a : result.get(i).windows)
                    for (Window b : result.get(j).windows)
                        if (a.startMs < b.endMs && b.startMs < a.endMs) {
                            ambiguous.add(result.get(i).unitId);
                            ambiguous.add(result.get(j).unitId);
                        }
        for (int i = 0; i < result.size(); i++) {
            Slot s = result.get(i);
            if (ambiguous.contains(s.unitId))
                result.set(
                        i,
                        new Slot(
                                content,
                                s.from,
                                s.to,
                                Collections.emptyList(),
                                s.originalWindows,
                                "ambiguous_schedule"));
        }
        return new DisplayTimeline(result, revision);
    }

    private static boolean unknown(Source source, Unit unit) {
        for (int i = unit.from; i < unit.to; i++)
            if (source.tokens.get(i).captionedSpeech && !source.tokens.get(i).timed()) return true;
        return false;
    }

    /** Both ranges name speakers and the sets disagree. */
    private static boolean speakersDiffer(Source source, Unit a, Unit b) {
        Set<String> first = speakers(source, a), second = speakers(source, b);
        return !first.isEmpty() && !second.isEmpty() && !first.equals(second);
    }

    private static Set<String> speakers(Source source, Unit unit) {
        Set<String> result = new HashSet<>();
        Set<Integer> fragments = new HashSet<>();
        for (int i = unit.from; i < unit.to; i++) fragments.add(source.tokens.get(i).fragment);
        for (SourceFragment f : source.fragments)
            if (fragments.contains(f.id))
                for (SpeakerMarker m : f.speakerMarkers)
                    if (!m.label.isEmpty()) result.add(m.label);
        return result;
    }

    private static List<Window> windows(Source source, Unit unit) {
        return source != null ? source.windows(unit.from, unit.to) : unit.windows;
    }

    public Slot forUnit(String id) {
        for (Slot slot : slots) if (slot.unitId.equals(id)) return slot;
        return null;
    }

    public Slot at(long at) {
        for (Slot slot : slots) if (slot.contains(at)) return slot;
        return null;
    }

    public boolean uncertainAt(long at) {
        for (Slot slot : slots)
            if (!slot.issue.isEmpty())
                for (Window w : slot.originalWindows) if (w.contains(at)) return true;
        return false;
    }
}
