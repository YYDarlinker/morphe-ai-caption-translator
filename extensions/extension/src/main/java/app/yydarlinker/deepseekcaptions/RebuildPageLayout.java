package app.yydarlinker.deepseekcaptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** Bounded presentation pages inside one accepted event; source ownership never changes. */
final class RebuildPageLayout {
  static final int MAX_PAGES = 3;
  static final long MIN_PAGE_MS = 1000;
  static final int MAX_CPS = 12; // Existing readability diagnostic threshold.

  static final class Page {
    final String text;
    final long start, end;

    Page(String text, long start, long end) {
      this.text = text;
      this.start = start;
      this.end = end;
    }
  }

  static List<Page> plan(String text, long start, long end, Predicate<String> fits) {
    if (text == null || text.isEmpty() || fits == null || end <= start) return Collections.emptyList();
    int count = text.codePointCount(0, text.length());
    long duration = end - start;
    int[] offset = new int[count + 1];
    for (int i = 0; i < count; i++) offset[i + 1] = text.offsetByCodePoints(offset[i], 1);
    // Existing one-page events retain their current timing; the floor governs added pages.
    if (fits.test(text))
      return Collections.singletonList(new Page(text, start, end));
    if (count * 1000L > duration * MAX_CPS) return Collections.emptyList();
    for (int pages = 2; pages <= MAX_PAGES; pages++) {
      if (duration < pages * MIN_PAGE_MS) break;
      int[] cuts = new int[pages + 1];
      cuts[0] = 0;
      cuts[pages] = count;
      Choice choice = new Choice();
      search(text, offset, fits, cuts, 1, pages, duration, choice);
      if (choice.cuts != null) return allocate(text, offset, choice.cuts, start, end);
    }
    return Collections.emptyList();
  }

  private static long minimumMs(int codePoints) {
    return Math.max(MIN_PAGE_MS, (codePoints * 1000L + MAX_CPS - 1) / MAX_CPS);
  }

  private static final class Choice {
    int[] cuts;
    long penalty = Long.MAX_VALUE;
  }

  private static void search(String text, int[] offset, Predicate<String> fits, int[] cuts,
      int depth, int pages, long duration, Choice best) {
    int count = offset.length - 1;
    if (depth == pages) {
      int from = cuts[depth - 1];
      if (!fits.test(text.substring(offset[from], offset[count]))) return;
      long required = 0, penalty = 0;
      for (int i = 0; i < pages; i++) {
        int length = cuts[i + 1] - cuts[i];
        required += minimumMs(length);
        long difference = (long) length * pages - count;
        penalty += difference * difference;
      }
      if (required > duration) return;
      // At equal balance, prefer a page break after sentence or clause punctuation.
      for (int i = 1; i < pages; i++) {
        int prior = text.codePointBefore(offset[cuts[i]]);
        if ("。！？!?；;，,".indexOf(prior) >= 0) penalty -= 2;
      }
      if (penalty < best.penalty) {
        best.penalty = penalty;
        best.cuts = cuts.clone();
      }
      return;
    }
    int from = cuts[depth - 1];
    for (int cut = from + 1; cut <= count - (pages - depth); cut++) {
      if (!fits.test(text.substring(offset[from], offset[cut]))) break;
      cuts[depth] = cut;
      search(text, offset, fits, cuts, depth + 1, pages, duration, best);
    }
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
