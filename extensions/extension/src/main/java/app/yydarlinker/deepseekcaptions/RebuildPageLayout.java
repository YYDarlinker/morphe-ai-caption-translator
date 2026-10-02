package app.yydarlinker.deepseekcaptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** Presentation-only pages inside one accepted event; never changes source ownership. */
final class RebuildPageLayout {
  static final long MIN_PAGE_MS = 1200;
  static final int MAX_CPS = 8;

  static final class Page {
    final String text;
    final long start, end;

    Page(String text, long start, long end) {
      this.text = text;
      this.start = start;
      this.end = end;
    }
  }

  /** Legacy callers have only two-line evidence; the one-line estimate is conservative. */
  static List<Page> plan(String text, long start, long end, Predicate<String> fitsTwo) {
    return plan(text, start, end, fitsTwo,
        value -> fitsTwo != null && fitsTwo.test(value)
            && value.codePointCount(0, value.length()) <= 18);
  }

  static List<Page> plan(String text, long start, long end,
      Predicate<String> fitsTwo, Predicate<String> fitsOne) {
    if (text == null || text.isEmpty() || fitsTwo == null || fitsOne == null || end <= start)
      return Collections.emptyList();
    long duration = end - start;
    int count = text.codePointCount(0, text.length());
    if (count * 1000L > duration * MAX_CPS) return Collections.emptyList();
    // Only the entire owned window may force a sub-1.2s translated page.
    if (duration < MIN_PAGE_MS)
      return fitsTwo.test(text) ? Collections.singletonList(new Page(text, start, end))
          : Collections.emptyList();

    int[] offset = new int[count + 1];
    for (int i = 0; i < count; i++) offset[i + 1] = text.offsetByCodePoints(offset[i], 1);
    List<Integer> seams = semanticSeams(text, offset);
    List<Page> semantic = choose(text, start, end, fitsTwo, fitsOne, offset, seams, false);
    if (!semantic.isEmpty()) return semantic;
    // Only a failed punctuation-only plan may use a measured non-punctuation seam.
    return choose(text, start, end, fitsTwo, fitsOne, offset,
        expandedSeams(text, offset), true);
  }

  /** Target-aware production entry. Chinese retains the exact N26 algorithm above. */
  static List<Page> plan(String text,long start,long end,CaptionOverlay.LayoutBudget budget,
      CaptionRenderSpec spec) {
    if(budget==null || spec==null) return Collections.emptyList();
    CaptionOverlay.LayoutBudget measured=budget.withSpec(spec);
    if(spec.legacy) return plan(text,start,end,measured::fitsPreferred,
        value -> spec.layout(value,measured.preferredPx,measured.width).getLineCount()<=1);
    return CaptionLanguagePager.plan(text,start,end,measured,spec);
  }

  private static List<Page> choose(String text, long start, long end,
      Predicate<String> fitsTwo, Predicate<String> fitsOne, int[] offset,
      List<Integer> seams, boolean fallback) {
    int count = offset.length - 1;
    long duration = end - start;
    boolean minimumApplies = displayHalfCells(text) >= 16;
    int last = seams.size() - 1;
    long nominal = duration / MIN_PAGE_MS + (duration % MIN_PAGE_MS == 0 ? 0 : 1);
    int pageCap = (int) Math.min(last, nominal);
    @SuppressWarnings("unchecked")
    List<State>[][] states = new List[last + 1][pageCap + 1];
    states[0][0] = new ArrayList<>();
    states[0][0].add(new State(null, 0, 0, 0, 0));
    for (int at = 0; at < last; at++) {
      for (int pages = 0; pages < pageCap; pages++) {
        if (states[at][pages] == null) continue;
        for (int next = at + 1; next <= last; next++) {
          int from = seams.get(at), to = seams.get(next);
          String part = text.substring(offset[from], offset[to]);
          if (minimumApplies && displayHalfCells(part) < 16) continue;
          if (!fitsTwo.test(part)) continue;
          long required = minimumMs(to - from);
          int lines = fitsOne.test(part) ? 1 : 2;
          long cost = styleCost(to - from, lines) + (next == last ? 0 :
              seamCost(text, offset[to])
                  + (fallback && !isPunctuationSeam(text, offset[to]) ? 5000 : 0));
          for (State previous : states[at][pages]) {
            long total = previous.required + required;
            // Nominal ceil is only an enumeration cap: actual pages still need full minimums.
            if (total > duration || (pages + 1L) * MIN_PAGE_MS > duration) continue;
            State candidate = new State(previous, next, pages + 1, total,
                previous.penalty + cost);
            if (states[next][pages + 1] == null) states[next][pages + 1] = new ArrayList<>();
            addUndominated(states[next][pages + 1], candidate);
          }
        }
      }
    }
    List<Page> best = Collections.emptyList();
    long bestCost = Long.MAX_VALUE;
    for (int pages = 1; pages <= pageCap; pages++) {
      if (states[last][pages] == null) continue;
      for (State state : states[last][pages]) {
        int[] cuts = new int[pages + 1];
        cuts[pages] = count;
        State cursor = state;
        for (int i = pages - 1; i >= 0; i--) {
          cursor = cursor.previous;
          cuts[i] = seams.get(cursor.at);
        }
        List<Page> proposed = allocate(text, offset, cuts, start, end);
        long score = state.penalty + pages * 100L + timingCost(proposed);
        if (score < bestCost) { bestCost = score; best = proposed; }
      }
    }
    return best;
  }

  private static final class State {
    final State previous;
    final int at, pages;
    final long required, penalty;
    State(State prior, int index, int n, long min, long cost) {
      previous = prior; at = index; pages = n; required = min; penalty = cost;
    }
  }

  private static void addUndominated(List<State> choices, State candidate) {
    for (State existing : choices)
      if (existing.required <= candidate.required && existing.penalty <= candidate.penalty) return;
    choices.removeIf(existing -> candidate.required <= existing.required
        && candidate.penalty <= existing.penalty);
    choices.add(candidate);
  }

  private static List<Integer> semanticSeams(String text, int[] offset) {
    List<Integer> seams = new ArrayList<>();
    seams.add(0);
    int count = offset.length - 1;
    for (int cut = 1; cut < count; cut++) {
      int before = text.codePointBefore(offset[cut]);
      if ("，。！？；：,!?;:".indexOf(before) < 0) continue;
      int after = text.codePointAt(offset[cut]);
      if (cut > 1 && Character.isDigit(text.codePointBefore(offset[cut - 1]))
          && Character.isDigit(after) && (before == ',' || before == ':')) continue;
      // Closing quotation marks belong to the clause just ended; retain exact text.
      int adjusted = cut;
      while (adjusted < count - 1 && "”’\"'）)]".indexOf(text.codePointAt(offset[adjusted])) >= 0)
        adjusted++;
      while (adjusted < count - 1 && Character.isWhitespace(text.codePointAt(offset[adjusted])))
        adjusted++;
      if (adjusted < count && adjusted > seams.get(seams.size() - 1)) seams.add(adjusted);
    }
    seams.add(count);
    return seams;
  }

  private static List<Integer> expandedSeams(String text, int[] offset) {
    List<Integer> seams = new ArrayList<>();
    seams.add(0);
    int count = offset.length - 1;
    for (int cut = 1; cut < count; cut++) {
      int before = text.codePointBefore(offset[cut]);
      int after = text.codePointAt(offset[cut]);
      // Never divide an ASCII number or word merely to satisfy a page preference.
      if (before < 128 && after < 128
          && Character.isLetterOrDigit(before) && Character.isLetterOrDigit(after)) continue;
      seams.add(cut);
    }
    seams.add(count);
    return seams;
  }

  private static boolean isPunctuationSeam(String text, int offset) {
    return "，。！？；：,!?;:".indexOf(text.codePointBefore(offset)) >= 0;
  }

  /** Approximate display width in half-CJK cells, without rounding up short pages. */
  static int displayHalfCells(String text) {
    int whole = 0, half = 0;
    for (int at = 0; at < text.length();) {
      int cp = text.codePointAt(at);
      if (cp >= 0x20 && cp <= 0x7e) half++;
      else whole++;
      at += Character.charCount(cp);
    }
    return whole * 2 + half;
  }

  private static long styleCost(int length, int lines) {
    long outside = length < 12 ? 650L * (12 - length)
        : length > 18 ? 850L * (length - 18) : 0;
    // Avoid making a tiny introductory fragment its own page just to save a line.
    return outside + (length < 8 ? 30000 : 0)
        + (lines == 1 ? 0 : 18000) + 8L * Math.abs(length - 15);
  }

  private static long seamCost(String text, int offset) {
    int prior = text.codePointBefore(offset);
    return "。！？；!?;".indexOf(prior) >= 0 ? 0 : 20;
  }

  private static long timingCost(List<Page> pages) {
    long cost = 0;
    for (Page page : pages) {
      long ms = page.end - page.start;
      if (ms < 2000) cost += (2000 - ms) / 10;
      else if (ms > 3500) cost += (ms - 3500) / 10;
    }
    return cost;
  }

  private static long minimumMs(int codePoints) {
    return Math.max(MIN_PAGE_MS, (codePoints * 1000L + MAX_CPS - 1) / MAX_CPS);
  }

  private static List<Page> allocate(String text, int[] offset, int[] cuts, long start, long end) {
    int pages = cuts.length - 1;
    long[] spans = new long[pages];
    long minimum = 0;
    for (int i = 0; i < pages; i++) {
      spans[i] = minimumMs(cuts[i + 1] - cuts[i]);
      minimum += spans[i];
    }
    long extra = end - start - minimum;
    int total = offset.length - 1;
    long distributed = 0;
    for (int i = 0; i < pages - 1; i++) {
      long share = extra * (cuts[i + 1] - cuts[i]) / total;
      spans[i] += share;
      distributed += share;
    }
    spans[pages - 1] += extra - distributed;
    List<Page> result = new ArrayList<>(pages);
    long at = start;
    for (int i = 0; i < pages; i++) {
      long next = at + spans[i];
      result.add(new Page(text.substring(offset[cuts[i]], offset[cuts[i + 1]]), at, next));
      at = next;
    }
    return Collections.unmodifiableList(result);
  }

  static int indexAt(List<Page> pages, long position) {
    if (pages == null || pages.isEmpty()) return -1;
    if (position < pages.get(0).start) return 0;
    for (int i = 0; i < pages.size(); i++) if (position < pages.get(i).end) return i;
    return pages.size() - 1;
  }
}
