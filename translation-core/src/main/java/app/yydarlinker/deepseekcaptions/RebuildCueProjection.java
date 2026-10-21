package app.yydarlinker.deepseekcaptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Display projection for a manually authored track: turn a validated reply into pages.
 *
 * <p>A page is what the viewer sees at one instant. The reply's events are only the transport of
 * translated text over source tokens; their boundaries are decided by the model and are not an
 * identity of the author's cue. This class therefore has two tiers:
 *
 * <ol>
 *   <li><b>Cue pages</b> — for every authored cue group that is exactly tiled by consecutive
 *       events, one page whose window is the cue window. This is the preferred presentation and
 *       keeps the author's paging.
 *   <li><b>Event pages</b> — every event not used by a cue page becomes its own page, with the
 *       event's own source window. Text is never split, duplicated or dropped. This tier is what
 *       makes a complete translation displayable even when the model merged or split a sentence
 *       across the author's cue boundaries.
 * </ol>
 *
 * <p>The two tiers are disjoint by construction: a group that is exactly tiled cannot overlap an
 * event that leaves the group, because events never overlap. Pages are returned in source order,
 * never overlap, and a gap shorter than {@link RebuildDisplayGroups#HOLD_MS} is held by extending
 * the previous page instead of blanking the box.
 */
final class RebuildCueProjection {
    /** The projection result plus the tier counts used by diagnostics. */
    static final class Result {
        final List<RebuildDisplayGroups.Unit> units;
        final int groups;
        final int cuePages;
        final int eventPages;

        Result(List<RebuildDisplayGroups.Unit> units, int groups, int cuePages, int eventPages) {
            this.units = Collections.unmodifiableList(new ArrayList<>(units));
            this.groups = groups;
            this.cuePages = cuePages;
            this.eventPages = eventPages;
        }
    }

    private RebuildCueProjection() {}

    /**
     * Projects a validated reply onto display pages. Never rejects and never returns an empty page
     * for a non-empty event, so a translation that arrived is always displayable.
     */
    static Result project(
            int blockIndex,
            RebuildSource source,
            RebuildPlanner.Block block,
            List<RebuildProtocol.Event> events,
            CaptionLanguageContext context) {
        List<RebuildPlanner.Block.CueRange> groups = RebuildCueContract.groups(source, block);
        if (events.isEmpty()) return new Result(Collections.emptyList(), groups.size(), 0, 0);

        boolean[] used = new boolean[events.size()];
        List<RebuildDisplayGroups.Unit> units = new ArrayList<>();
        int cuePages = 0;
        for (RebuildPlanner.Block.CueRange group : groups) {
            List<Integer> owned = tile(events, group);
            if (owned == null) continue;
            List<RebuildProtocol.Event> page = new ArrayList<>(owned.size());
            for (int index : owned) {
                used[index] = true;
                page.add(events.get(index));
            }
            units.add(RebuildCueContract.unit(blockIndex, group, page, context));
            cuePages++;
        }

        int eventPages = 0;
        for (int i = 0; i < events.size(); i++) {
            if (used[i]) continue;
            RebuildProtocol.Event event = events.get(i);
            units.add(
                    new RebuildDisplayGroups.Unit(
                            blockIndex,
                            Collections.singletonList(event),
                            Collections.singletonList(""),
                            event.end,
                            false));
            eventPages++;
        }

        units.sort(
                Comparator.comparingLong((RebuildDisplayGroups.Unit unit) -> unit.start)
                        .thenComparingInt(unit -> unit.from));
        return new Result(normalize(units), groups.size(), cuePages, eventPages);
    }

    /**
     * The indexes of the consecutive events that exactly tile the group, or null. "Exactly" means
     * the first starts at the group start, every event continues the previous one, and the last
     * ends at the group end. A group covered only partially or contained in a larger event is not
     * a cue page; its text is published by the event pages instead.
     */
    private static List<Integer> tile(
            List<RebuildProtocol.Event> events, RebuildPlanner.Block.CueRange group) {
        List<Integer> owned = new ArrayList<>();
        int next = group.from;
        for (int i = 0; i < events.size(); i++) {
            RebuildProtocol.Event event = events.get(i);
            if (event.from < group.from || event.to > group.to) continue;
            if (event.from != next) return null;
            owned.add(i);
            next = event.to + 1;
        }
        if (owned.isEmpty() || next != group.to + 1) return null;
        return owned;
    }

    /**
     * Source-ordered, non-overlapping pages. A short gap between two pages is filled by extending
     * the earlier page to the next start, so a cue boundary or a fragment join does not blank the
     * box for a few hundred milliseconds.
     */
    private static List<RebuildDisplayGroups.Unit> normalize(
            List<RebuildDisplayGroups.Unit> units) {
        if (units.isEmpty()) return units;
        List<RebuildDisplayGroups.Unit> out = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            RebuildDisplayGroups.Unit unit = units.get(i);
            long end = unit.end;
            if (i + 1 < units.size()) {
                long next = units.get(i + 1).start;
                if (end > next) end = next;
                else if (next - end < RebuildDisplayGroups.HOLD_MS) end = next;
            }
            out.add(
                    end == unit.end
                            ? unit
                            : new RebuildDisplayGroups.Unit(
                                    unit.block, unit.events, unit.separators, end, unit.blocked));
        }
        return out;
    }
}
