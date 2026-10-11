package app.yydarlinker.deepseekcaptions;

import java.util.*;
import java.util.function.Predicate;

/**
 * Display-only partition of one accepted plan into caption units. Every accepted event belongs to
 * exactly one unit, so a later page never repeats text an earlier page already showed. Source
 * ownership, event times, cache entries and translation requests are unchanged.
 *
 * <p>Replaces the upstream pairwise join (previous lead + current event), which re-showed the lead
 * as part of the next page and slid through runs of short events. Pages are chosen per block with
 * a small cost search: a page should not end in the middle of a clause, flash for under a second,
 * or hold only a short lead, and joined pages stay within the reading limits.
 */
final class RebuildDisplayGroups {
  static final long SHORT_EVENT_MS = 1000, MAX_UNIT_MS = 5000;
  /** Below the planner's hard source break, a pause does not split a joined unit. */
  static final long MAX_BRIDGE_MS = 650;
  /** A caption stays up through a shorter pause until the next speech, so the box does not blink. */
  static final long HOLD_MS = 1000;
  private static final int MAX_GROUP = 6;
  private static final String TERMINAL = "。！？!?；;．.؟।", CLOSING = "\"'”’」』）)]》";

  static final class Unit {
    final int block, from, to;
    final long start, end;
    final String text;
    final boolean blocked;
    final List<RebuildProtocol.Event> events;
    /** The separator written before each event's text; the first is always empty. */
    final List<String> separators;

    Unit(int block, List<RebuildProtocol.Event> events, List<String> separators, long end,
        boolean blocked) {
      this.block = block;
      this.events = Collections.unmodifiableList(new ArrayList<>(events));
      this.separators = Collections.unmodifiableList(new ArrayList<>(separators));
      from = events.get(0).from;
      to = events.get(events.size() - 1).to;
      start = events.get(0).start;
      this.end = end;
      this.text = text(events, separators);
      this.blocked = blocked;
    }

    String id() { return block + ":" + from + "-" + to; }

    boolean covers(long time) { return start <= time && time < end; }
  }

  private RebuildDisplayGroups() {}

  /** Units in source order. {@code fits} is the measured two-line check; null when not laid out yet. */
  static List<Unit> partition(int block, List<RebuildProtocol.Event> events, RebuildSource source,
      CaptionLanguageContext context, Predicate<String> fits, Predicate<RebuildProtocol.Event> blocked) {
    int n = events.size();
    if (n == 0) return Collections.emptyList();
    CaptionRenderSpec spec = context.renderSpec;
    boolean punctuated = punctuated(source, events.get(0).from, events.get(n - 1).to);
    // Boundary j sits between events j-1 and j: its separator and the cost of ending a page there.
    String[] separator = new String[n];
    double[] breakCost = new double[n];
    boolean[] linked = new boolean[n];
    for (int j = 1; j < n; j++) {
      RebuildProtocol.Event left = events.get(j - 1), right = events.get(j);
      linked[j] = joinable(left, blocked) && joinable(right, blocked) && adjacent(source, left, right);
      boolean clause = clauseEnd(left.text);
      boolean continuous = !clause && continuous(source, context, left.to, punctuated);
      separator[j] = separator(left.text.trim(), right.text.trim(), spec, continuous);
      if (!linked[j]) continue;
      breakCost[j] = clause ? 0 : continuous ? 4 : sourceBreakCost(source, context, left.to, punctuated);
      // A short lead without a sentence end belongs with what follows (the former lead join), and
      // a short tail that closes the sentence belongs with the open text before it.
      if (lead(left, spec)) breakCost[j] += 1;
      if (!clause && tail(right, j == n - 1, spec)) breakCost[j] += 1;
    }
    double[] best = new double[n + 1];
    int[] cut = new int[n + 1];
    Arrays.fill(best, Double.MAX_VALUE);
    best[0] = 0;
    for (int i = 1; i <= n; i++) {
      for (int j = i - 1; j >= 0 && i - j <= MAX_GROUP; j--) {
        if (j < i - 1 && !linked[j + 1]) break;
        List<RebuildProtocol.Event> group = events.subList(j, i);
        List<String> separators = separators(separator, j, i);
        if (group.size() > 1 && !readable(group, separators, spec, fits)) {
          if (group.get(group.size() - 1).end - group.get(0).start > MAX_UNIT_MS) break;
          continue;
        }
        double cost = best[j] + pageCost(group) + (i < n ? breakCost[i] : 0);
        if (cost < best[i]) { best[i] = cost; cut[i] = j; }
      }
    }
    List<int[]> ranges = new ArrayList<>();
    for (int i = n; i > 0; i = cut[i]) ranges.add(0, new int[] {cut[i], i});
    List<Unit> units = new ArrayList<>(ranges.size());
    for (int g = 0; g < ranges.size(); g++) {
      int from = ranges.get(g)[0], to = ranges.get(g)[1];
      List<RebuildProtocol.Event> group = events.subList(from, to);
      RebuildProtocol.Event last = group.get(group.size() - 1);
      long end = last.end, next = -1;
      if (to < n) next = events.get(to).start;
      else if (last.to + 1 < source.words.size()) next = source.words.get(last.to + 1).start;
      if (next > end && next - end < HOLD_MS) end = next;
      units.add(new Unit(block, group, separators(separator, from, to), end,
          group.size() == 1 && blocked != null && blocked.test(group.get(0))));
    }
    return Collections.unmodifiableList(units);
  }

  static Unit at(List<Unit> units, long time) {
    for (Unit unit : units) if (unit.covers(time)) return unit;
    return null;
  }

  /** The same unit without the events whose source tokens were already shown; null if none remain. */
  static Unit after(Unit unit, int shownTo) {
    int first = 0;
    while (first < unit.events.size() && unit.events.get(first).from <= shownTo) first++;
    if (first == unit.events.size()) return null;
    if (first == 0) return unit;
    List<String> separators = new ArrayList<>(unit.separators.subList(first, unit.events.size()));
    separators.set(0, "");
    return new Unit(unit.block, unit.events.subList(first, unit.events.size()), separators, unit.end,
        unit.blocked);
  }

  /** A blocked, empty or non-speech event is shown (or withheld) on its own. */
  private static boolean joinable(RebuildProtocol.Event event, Predicate<RebuildProtocol.Event> blocked) {
    return !event.text.trim().isEmpty() && (blocked == null || !blocked.test(event));
  }

  static boolean adjacent(RebuildSource source, RebuildProtocol.Event left, RebuildProtocol.Event right) {
    return left.to + 1 == right.from && left.start < left.end && right.start < right.end
        && left.end <= right.start && right.start - left.end < MAX_BRIDGE_MS
        && !RebuildPlanner.hardBreakBefore(source, right.from);
  }

  /** A page that flashes for under a second; each join costs a little, so ties stay separate. */
  private static double pageCost(List<RebuildProtocol.Event> group) {
    double cost = 0.1 * (group.size() - 1);
    if (group.get(group.size() - 1).end - group.get(0).start < SHORT_EVENT_MS) cost += 3;
    return cost;
  }

  /** A short event without a sentence end; the threshold is three quarters of one reference line. */
  static boolean lead(RebuildProtocol.Event event, CaptionRenderSpec spec) {
    String text = event.text.trim();
    return !text.isEmpty() && !sentenceEnd(text)
        && spec.readingUnits(text) <= Math.max(1, spec.profile.referenceCpl * 3 / 4);
  }

  private static boolean tail(RebuildProtocol.Event event, boolean last, CaptionRenderSpec spec) {
    String text = event.text.trim();
    return !text.isEmpty() && (last || sentenceEnd(text))
        && spec.readingUnits(text) <= Math.max(1, spec.profile.referenceCpl * 3 / 4);
  }

  /** Ending a page where the target carries no punctuation, judged from the source boundary. */
  private static double sourceBreakCost(RebuildSource source, CaptionLanguageContext context, int token,
      boolean punctuated) {
    String word = source.words.get(token).text;
    if (punctuated) return RebuildSource.terminal(word) ? 0.5 : 1;
    int score = RebuildPlanner.resourceScore(source, token, context);
    return score >= 50 ? 0.5 : score >= 30 ? 1 : 2;
  }

  /**
   * The source runs on through this boundary: no punctuation in punctuated captions, or a
   * dependent ending (article, preposition, protected pair) in unpunctuated ones.
   */
  private static boolean continuous(RebuildSource source, CaptionLanguageContext context, int token,
      boolean punctuated) {
    if (token < 0 || token + 1 >= source.words.size()) return false;
    if (punctuated) return !punctuationEnd(source.words.get(token).text);
    return RebuildPlanner.dependentEnding(source, token, context)
        || RebuildPlanner.protectedCut(source, token, context);
  }

  private static boolean punctuated(RebuildSource source, int from, int to) {
    for (int i = Math.max(0, from); i <= to && i < source.words.size(); i++)
      if (punctuationEnd(source.words.get(i).text)) return true;
    return false;
  }

  private static boolean punctuationEnd(String word) {
    String text = word.trim();
    int end = text.length();
    while (end > 0 && CLOSING.indexOf(text.charAt(end - 1)) >= 0) end--;
    return end > 0 && ".,?!;:。，、？！；：…".indexOf(text.charAt(end - 1)) >= 0;
  }

  private static boolean sentenceEnd(String value) {
    String text = value.trim();
    int end = text.length();
    while (end > 0 && CLOSING.indexOf(text.charAt(end - 1)) >= 0) end--;
    return end > 0 && TERMINAL.indexOf(text.charAt(end - 1)) >= 0;
  }

  private static boolean clauseEnd(String value) {
    String text = value.trim();
    return !text.isEmpty() && punctuation(text.codePointBefore(text.length()));
  }

  static boolean shortEvent(RebuildProtocol.Event event) {
    return event.end - event.start < SHORT_EVENT_MS;
  }

  private static boolean readable(List<RebuildProtocol.Event> group, List<String> separators,
      CaptionRenderSpec spec, Predicate<String> fits) {
    long duration = group.get(group.size() - 1).end - group.get(0).start;
    String text = text(group, separators);
    int units = spec.readingUnits(text);
    // Chinese reference: 16 per line, 9/s, so 48 units and 12/s, the former pairwise join limits.
    return duration > 0 && duration <= MAX_UNIT_MS && units <= spec.profile.referenceCpl * 3
        && units * 3000L <= duration * spec.profile.referenceCps * 4
        && (fits == null || fits.test(text));
  }

  private static List<String> separators(String[] separator, int from, int to) {
    List<String> out = new ArrayList<>(to - from);
    out.add("");
    for (int j = from + 1; j < to; j++) out.add(separator[j]);
    return out;
  }

  static String text(List<RebuildProtocol.Event> group, List<String> separators) {
    if (group.size() == 1) return group.get(0).text;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < group.size(); i++) {
      String next = group.get(i).text.trim();
      if (next.isEmpty()) continue;
      if (out.length() > 0) out.append(separators.get(i));
      out.append(next);
    }
    return out.toString();
  }

  /**
   * Chinese and Japanese get a pause separator only where neither side carries punctuation and the
   * source does not run on, so "排在第二位" + "见鬼，" does not read as one phrase while
   * "俄罗斯拥有" + "世界第二强大的军队" stays one sentence. Spaced scripts keep their word space.
   */
  private static String separator(String left, String right, CaptionRenderSpec spec, boolean continuous) {
    if (left.isEmpty() || right.isEmpty()) return "";
    boolean closingRight = punctuation(right.codePointAt(0)) && !opening(right.codePointAt(0));
    boolean japanese = spec.profile.id.equals("ja");
    if (spec.compactScript || japanese) {
      if (punctuation(left.codePointBefore(left.length())) || closingRight) return "";
      // A Latin word or number next to Chinese or Japanese text keeps its usual space.
      if (latin(left.codePointBefore(left.length())) || latin(right.codePointAt(0))) return " ";
      if (continuous) return "";
      return japanese ? "　" : " ";
    }
    return closingRight ? "" : " ";
  }

  private static boolean latin(int c) {
    return c < 128 && Character.isLetterOrDigit(c);
  }

  private static boolean opening(int c) {
    int type = Character.getType(c);
    return type == Character.START_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION;
  }

  private static boolean punctuation(int c) {
    switch (Character.getType(c)) {
      case Character.CONNECTOR_PUNCTUATION: case Character.DASH_PUNCTUATION:
      case Character.START_PUNCTUATION: case Character.END_PUNCTUATION:
      case Character.INITIAL_QUOTE_PUNCTUATION: case Character.FINAL_QUOTE_PUNCTUATION:
      case Character.OTHER_PUNCTUATION:
        return true;
      default:
        return Character.isWhitespace(c);
    }
  }

  /**
   * Per-session display memory for one generation. It never re-shows source tokens of a block that
   * already reached the screen, and only lets a replacement plan take over where it continues
   * exactly after the shown text.
   */
  static final class Cursor {
    private Unit shown;
    private int block = -1, shownTo = -1;

    void reset() { shown = null; block = -1; shownTo = -1; }

    Unit select(int index, List<RebuildProtocol.Event> accepted, List<Unit> units, long time) {
      if (shown != null && shown.block == index && shown.covers(time) && contains(accepted, shown))
        return shown;
      Unit unit = at(units, time);
      if (unit == null || block != index || unit.from > shownTo) return unit;
      Unit rest = after(unit, shownTo);
      return rest == null ? unit : rest;
    }

    /** Records a unit that actually reached the screen. Withheld units are not recorded. */
    void shown(Unit unit) {
      shown = unit;
      if (block != unit.block) { block = unit.block; shownTo = -1; }
      shownTo = Math.max(shownTo, unit.to);
    }

    boolean shownIn(int index) { return block == index && shownTo >= 0; }

    boolean onScreen(int index, long time) {
      return shown != null && shown.block == index && shown.covers(time);
    }

    /** A replacement may start only at a shared boundary after everything already shown. */
    boolean continues(int index, RebuildProtocol.Plan candidate) {
      if (!shownIn(index) || candidate.events.isEmpty()) return true;
      if (candidate.events.get(candidate.events.size() - 1).to <= shownTo) return true;
      for (RebuildProtocol.Event event : candidate.events) if (event.from == shownTo + 1) return true;
      return false;
    }

    boolean keepsShown(int index, RebuildProtocol.Plan candidate) {
      return shown != null && shown.block == index && contains(candidate.events, shown);
    }

    private static boolean contains(List<RebuildProtocol.Event> accepted, Unit unit) {
      for (RebuildProtocol.Event wanted : unit.events) {
        boolean found = false;
        for (RebuildProtocol.Event event : accepted)
          if (event.from == wanted.from && event.to == wanted.to && event.text.equals(wanted.text)) {
            found = true;
            break;
          }
        if (!found) return false;
      }
      return true;
    }
  }
}
