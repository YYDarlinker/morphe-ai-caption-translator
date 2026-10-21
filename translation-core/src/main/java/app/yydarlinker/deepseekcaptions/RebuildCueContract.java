package app.yydarlinker.deepseekcaptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The authored-cue display contract.
 *
 * <p>A manually authored cue is the preferred display boundary. Translation quality is decided on
 * sentence/clause events by the same protocol and review as an automatic track; the cue contract
 * only decides whether the author's paging can be kept. {@link RebuildCueProjection} uses it as
 * tier 1 and falls back to event pages when the reply does not tile every cue, so an event that
 * leaves its cue is a presentation fact, never a reason to withhold text.
 *
 * <p>This is deliberately separate from {@link RebuildDisplayGroups}: automatic tracks may merge
 * and split events for readability, whereas an authored cue page never leaves its cue; an event
 * that does leave it is published as its own event page instead.
 */
final class RebuildCueContract {
    /**
     * v3: cue pages are the preferred tier and event pages guarantee display, so a cached authored
     * reply may now carry events that leave a cue; the request guidance also changed from "must"
     * to "prefer". The version participates in the cache identity.
     */
    static final String VERSION = "authored-cue-v3";

    /**
     * A cue shorter than this cannot be a page of its own: the author split a conjunction, a
     * particle, or a sentence fragment into a display event that carries no readable content.
     * Such a cue is merged with its continuation before the request is built, so the model is not
     * asked to honour a boundary no reader could follow.
     */
    static final long MIN_GROUP_MS = 1200;

    private RebuildCueContract() {}

    /**
     * The display groups of one authored block: each is either one complete cue or a run of
     * fragment cues that only read together (Japanese 「が」 + 「しかし」, a 425 ms 「另一方面，」,
     * a word split across two upload cues). The author's substantive cues stay one group each.
     */
    static List<RebuildPlanner.Block.CueRange> groups(
            RebuildSource source, RebuildPlanner.Block block) {
        List<RebuildPlanner.Block.CueRange> out = new ArrayList<>();
        for (RebuildPlanner.Block.CueRange cue : block.cues) {
            if (out.isEmpty()) {
                out.add(cue);
                continue;
            }
            RebuildPlanner.Block.CueRange previous = out.get(out.size() - 1);
            if (mergeable(source, previous, cue)) {
                out.set(out.size() - 1, merge(previous, cue));
            } else {
                out.add(cue);
            }
        }
        if (out.size() > 1) {
            RebuildPlanner.Block.CueRange last = out.get(out.size() - 1);
            if (fragment(source, last)) {
                RebuildPlanner.Block.CueRange previous = out.get(out.size() - 2);
                out.set(out.size() - 2, merge(previous, last));
                out.remove(out.size() - 1);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean mergeable(
            RebuildSource source,
            RebuildPlanner.Block.CueRange previous,
            RebuildPlanner.Block.CueRange next) {
        if (!fragment(source, previous)) return false;
        if (RebuildPlanner.hardBreakBefore(source, next.from)) return false;
        long start = previous.start, end = next.end;
        if (end - start > 20_000) return false;
        if (source.text(previous.from, next.to).length() > 600) return false;
        return true;
    }

    /**
     * A fragment group has no sentence end and is either too short in time or too short in visible
     * characters to stand as a page. It merges forward into its continuation; the last group of a
     * block merges backward.
     */
    private static boolean fragment(
            RebuildSource source, RebuildPlanner.Block.CueRange group) {
        String text = source.text(group.from, group.to);
        if (terminal(text)) return false;
        int visible = visible(text);
        if (visible <= 2) return true;
        if (allFunctionWords(text)) return true;
        return group.end - group.start < MIN_GROUP_MS / 2;
    }

    /** True when every token is a particle/connective, e.g. 「がしかし」. */
    private static boolean allFunctionWords(String text) {
        if (RebuildPlanner.functionWordsOnly(text)) return true;
        java.util.List<String> tokens = RebuildSource.tokens(text);
        if (tokens.isEmpty()) return false;
        for (String token : tokens) if (!RebuildPlanner.functionWord(token)) return false;
        return true;
    }

    private static boolean terminal(String text) {
        String value = text == null ? "" : text.trim();
        int end = value.length();
        while (end > 0 && "”’\"'」』）)]》】".indexOf(value.charAt(end - 1)) >= 0) end--;
        if (end == 0) return false;
        char last = value.charAt(end - 1);
        return "。！？!?；;…".indexOf(last) >= 0
                || RebuildSource.terminal(value.substring(0, end));
    }

    private static int visible(String text) {
        int count = 0;
        for (int at = 0; at < text.length(); ) {
            int cp = text.codePointAt(at);
            at += Character.charCount(cp);
            if (Character.isWhitespace(cp) || cp == 0x200b || cp == 0xfeff) continue;
            if (Character.getType(cp) == Character.FORMAT) continue;
            count++;
        }
        return count;
    }

    private static RebuildPlanner.Block.CueRange merge(
            RebuildPlanner.Block.CueRange previous, RebuildPlanner.Block.CueRange next) {
        return new RebuildPlanner.Block.CueRange(
                previous.index, previous.from, next.to, previous.start, next.end);
    }

    /**
     * The authored part of a translation request. The prompt names the fixed display groups; this
     * data gives their exact token ranges and source text so the model can keep every event inside
     * one group.
     */
    static void addPayload(
            JSONObject payload, RebuildSource source, RebuildPlanner.Block block)
            throws org.json.JSONException {
        JSONArray cues = new JSONArray();
        for (RebuildPlanner.Block.CueRange cue : groups(source, block))
            cues.put(
                    new JSONObject()
                            .put("cue", cue.index)
                            .put("from", cue.from)
                            .put("to", cue.to)
                            .put("start_ms", cue.start)
                            .put("end_ms", cue.end)
                            .put("source", source.text(cue.from, cue.to)));
        payload.put("authored", true).put("display_cues", cues);
        RebuildGuidance.widenContext(payload, source, block);
    }

    /**
     * Fail closed when a plan cannot be projected one-to-one onto the authored cues.
     *
     * <p>This is the strict tier-1 check. Production display does not call it for acceptance:
     * {@link RebuildCueProjection} keeps the cue pages it can build and publishes the remaining
     * events as their own pages. Multiple events per cue are allowed; those events are
     * concatenated into the one page for that cue.
     */
    static void validate(
            RebuildSource source,
            RebuildPlanner.Block block,
            List<RebuildProtocol.Event> events)
            throws RebuildProtocol.Invalid {
        if (!block.authored) return;
        if (events.isEmpty()) throw new RebuildProtocol.Invalid("authored_cue_coverage", "no events");
        List<RebuildPlanner.Block.CueRange> groups = groups(source, block);
        Map<Integer, List<RebuildProtocol.Event>> byGroup = new LinkedHashMap<>();
        int next = block.from;
        for (RebuildProtocol.Event event : events) {
            if (event.from != next)
                throw new RebuildProtocol.Invalid(
                        "authored_cue_hole",
                        "expected from="
                                + next
                                + "; got "
                                + event.from
                                + "-"
                                + event.to
                                + "; holes are not allowed on an authored track");
            if (event.to < event.from || event.to > block.to)
                throw new RebuildProtocol.Invalid(
                        "authored_cue_boundary",
                        "range=" + event.from + "-" + event.to + "; outside block");
            RebuildPlanner.Block.CueRange group = groupAt(groups, event.from);
            if (group == null)
                throw new RebuildProtocol.Invalid(
                        "authored_cue_boundary",
                        "range=" + event.from + "-" + event.to + "; outside display groups");
            if (event.to > group.to)
                throw new RebuildProtocol.Invalid(
                        "authored_cue_boundary",
                        "range="
                                + event.from
                                + "-"
                                + event.to
                                + "; crosses display group "
                                + group.index
                                + " ("
                                + group.from
                                + "-"
                                + group.to
                                + ")");
            byGroup.computeIfAbsent(group.index, key -> new ArrayList<>()).add(event);
            next = event.to + 1;
        }
        if (next != block.to + 1)
            throw new RebuildProtocol.Invalid(
                    "authored_cue_coverage",
                    "block=" + block.from + "-" + block.to + "; uncovered tail from " + next);

        for (RebuildPlanner.Block.CueRange group : groups) {
            List<RebuildProtocol.Event> owned = byGroup.get(group.index);
            if (owned == null || owned.isEmpty())
                throw new RebuildProtocol.Invalid(
                        "authored_cue_coverage",
                        "group=" + group.index + "; no event for tokens " + group.from + "-" + group.to);
            if (owned.get(0).from != group.from || owned.get(owned.size() - 1).to != group.to)
                throw new RebuildProtocol.Invalid(
                        "authored_cue_coverage",
                        "group="
                                + group.index
                                + "; events cover "
                                + owned.get(0).from
                                + "-"
                                + owned.get(owned.size() - 1).to
                                + " but group is "
                                + group.from
                                + "-"
                                + group.to);
            for (int i = 1; i < owned.size(); i++)
                if (owned.get(i).from != owned.get(i - 1).to + 1)
                    throw new RebuildProtocol.Invalid(
                            "authored_cue_hole",
                            "group=" + group.index + "; gap between events");
        }
        for (int group : byGroup.keySet())
            if (groupByIndex(groups, group) == null)
                throw new RebuildProtocol.Invalid(
                        "authored_cue_boundary",
                        "event for group " + group + " outside its block groups");
    }

    private static RebuildPlanner.Block.CueRange groupAt(
            List<RebuildPlanner.Block.CueRange> groups, int token) {
        for (RebuildPlanner.Block.CueRange group : groups)
            if (token >= group.from && token <= group.to) return group;
        return null;
    }

    private static RebuildPlanner.Block.CueRange groupByIndex(
            List<RebuildPlanner.Block.CueRange> groups, int index) {
        for (RebuildPlanner.Block.CueRange group : groups)
            if (group.index == index) return group;
        return null;
    }

    /**
     * One display unit per authored cue. The unit text is the concatenation of all events owned by
     * that cue; its window is exactly the cue window. No source character is redistributed and no
     * synthetic sub-cue time is invented.
     */
    static List<RebuildDisplayGroups.Unit> units(
            int blockIndex,
            RebuildSource source,
            RebuildPlanner.Block block,
            List<RebuildProtocol.Event> events,
            CaptionLanguageContext context)
            throws RebuildProtocol.Invalid {
        validate(source, block, events);
        List<RebuildPlanner.Block.CueRange> groups = groups(source, block);
        Map<Integer, List<RebuildProtocol.Event>> byGroup = new LinkedHashMap<>();
        for (RebuildProtocol.Event event : events)
            byGroup.computeIfAbsent(groupAt(groups, event.from).index, key -> new ArrayList<>())
                    .add(event);
        List<RebuildDisplayGroups.Unit> units = new ArrayList<>(groups.size());
        for (RebuildPlanner.Block.CueRange group : groups)
            units.add(unit(blockIndex, group, byGroup.get(group.index), context));
        return Collections.unmodifiableList(units);
    }

    /**
     * The salvage counterpart of {@link #units}: complete cue groups are displayed and missing or
     * partially covered groups are skipped (their token ranges stay holes). A group is complete
     * only when its events tile it exactly; a half-covered cue is not shown as a truncated page.
     */
    static List<RebuildDisplayGroups.Unit> unitsPartial(
            int blockIndex,
            RebuildSource source,
            RebuildPlanner.Block block,
            List<RebuildProtocol.Event> events,
            CaptionLanguageContext context) {
        if (!block.authored) return Collections.emptyList();
        List<RebuildPlanner.Block.CueRange> groups = groups(source, block);
        Map<Integer, List<RebuildProtocol.Event>> byGroup = new LinkedHashMap<>();
        for (RebuildProtocol.Event event : events) {
            RebuildPlanner.Block.CueRange group = groupAt(groups, event.from);
            if (group == null || event.to > group.to) continue;
            byGroup.computeIfAbsent(group.index, key -> new ArrayList<>()).add(event);
        }
        List<RebuildDisplayGroups.Unit> units = new ArrayList<>(groups.size());
        for (RebuildPlanner.Block.CueRange group : groups) {
            List<RebuildProtocol.Event> owned = byGroup.get(group.index);
            if (owned == null || owned.isEmpty()) continue;
            owned.sort((left, right) -> Integer.compare(left.from, right.from));
            if (owned.get(0).from != group.from
                    || owned.get(owned.size() - 1).to != group.to) continue;
            boolean contiguous = true;
            for (int i = 1; i < owned.size(); i++)
                if (owned.get(i).from != owned.get(i - 1).to + 1) {
                    contiguous = false;
                    break;
                }
            if (!contiguous) continue;
            units.add(unit(blockIndex, group, owned, context));
        }
        return Collections.unmodifiableList(units);
    }

    /**
     * Leading complete groups of a streamed authored reply. A prefix may stop inside a cue; that
     * cue and everything after it wait for the complete plan, while the already complete cues can
     * be shown immediately. This is the authored counterpart of automatic streaming preview.
     */
    static List<RebuildDisplayGroups.Unit> partialUnits(
            int blockIndex,
            RebuildSource source,
            RebuildPlanner.Block block,
            List<RebuildProtocol.Event> events,
            CaptionLanguageContext context) {
        List<RebuildPlanner.Block.CueRange> groups = groups(source, block);
        Map<Integer, List<RebuildProtocol.Event>> byGroup = new LinkedHashMap<>();
        int next = block.from;
        for (RebuildProtocol.Event event : events) {
            if (event.from != next) break;
            RebuildPlanner.Block.CueRange group = groupAt(groups, event.from);
            if (group == null || event.to > group.to) break;
            byGroup.computeIfAbsent(group.index, key -> new ArrayList<>()).add(event);
            next = event.to + 1;
        }
        List<RebuildDisplayGroups.Unit> units = new ArrayList<>();
        for (RebuildPlanner.Block.CueRange group : groups) {
            List<RebuildProtocol.Event> owned = byGroup.get(group.index);
            if (owned == null || owned.isEmpty()) break;
            if (owned.get(0).from != group.from || owned.get(owned.size() - 1).to != group.to)
                break;
            boolean contiguous = true;
            for (int i = 1; i < owned.size(); i++)
                if (owned.get(i).from != owned.get(i - 1).to + 1) {
                    contiguous = false;
                    break;
                }
            if (!contiguous) break;
            units.add(unit(blockIndex, group, owned, context));
        }
        return Collections.unmodifiableList(units);
    }

    /** One cue group page: several events concatenated, window = the group's own window. */
    static RebuildDisplayGroups.Unit unit(
            int blockIndex,
            RebuildPlanner.Block.CueRange group,
            List<RebuildProtocol.Event> owned,
            CaptionLanguageContext context) {
        List<String> separators = new ArrayList<>(owned.size());
        separators.add("");
        for (int i = 1; i < owned.size(); i++)
            separators.add(
                    separator(owned.get(i - 1).text, owned.get(i).text, context.renderSpec));
        return new RebuildDisplayGroups.Unit(blockIndex, owned, separators, group.end, false);
    }

    static String summary(
            RebuildSource source, RebuildPlanner.Block block, List<RebuildProtocol.Event> events) {
        if (!block.authored) return "cues=0";
        List<RebuildPlanner.Block.CueRange> groups = groups(source, block);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (RebuildProtocol.Event event : events) {
            RebuildPlanner.Block.CueRange group = groupAt(groups, event.from);
            if (group != null) counts.put(group.index, counts.getOrDefault(group.index, 0) + 1);
        }
        StringBuilder out =
                new StringBuilder("cues=")
                        .append(block.cues.size())
                        .append(";groups=")
                        .append(groups.size())
                        .append(";events_per_group=");
        boolean first = true;
        for (RebuildPlanner.Block.CueRange group : groups) {
            if (!first) out.append(',');
            first = false;
            out.append(group.index).append(':').append(counts.getOrDefault(group.index, 0));
        }
        return out.toString();
    }

    private static String separator(
            String left, String right, CaptionRenderSpec spec) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        if (a.isEmpty() || b.isEmpty()) return "";
        if (CaptionScript.requiresSpace(a.codePointBefore(a.length()), b.codePointAt(0)))
            return " ";
        // An invisible event join is still a legal page cut for an overflowing cue.
        return spec.compactScript ? "\u200b" : "";
    }
}
