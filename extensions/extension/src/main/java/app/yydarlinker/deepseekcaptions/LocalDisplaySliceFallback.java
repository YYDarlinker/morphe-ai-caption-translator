package app.yydarlinker.deepseekcaptions;

import android.icu.text.BreakIterator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Deterministic zero-token display planner; canonical text is never rewritten. */
final class LocalDisplaySliceFallback {
    private static final long SOFT_MAX_SLICE_MS = 6_200L;
    private static final long HARD_MAX_SLICE_MS = 7_000L;
    private static final int SOFT_MAX_TEXT_CHARS = 48;
    private static final int HARD_MAX_TEXT_CHARS = 68;
    private static final int MIN_TEXT_CHARS = 5;
    // CJK glyphs occupy substantially more display width than Latin letters. The old
    // character-only limits allowed 30-40 Han characters to remain in one visual slice.
    private static final int CONTEXTUAL_SOFT_DISPLAY_WIDTH = 44;
    private static final int CONTEXTUAL_HARD_DISPLAY_WIDTH = 60;
    private static final int CONTEXTUAL_MIN_DISPLAY_WIDTH = 8;
    private static final double INF = 1.0e18;

    private static final int TERMINAL_BOUNDARY = 0;
    private static final int CLAUSE_BOUNDARY = 1;
    private static final int GRAMMAR_BOUNDARY = 2;
    private static final int SOFT_BOUNDARY = 3;
    private static final int TOKEN_BOUNDARY = 4;
    private static final int ICU_NON_CJK_BOUNDARY = 5;
    private static final int ICU_CJK_BOUNDARY = 6;

    // Kept only for the legacy Semantic Ledger compatibility entry point. Contextual strict mode
    // does not depend on this product-phrase list.
    private static final String[] LEGACY_PROTECTED_PHRASES = {
            "后置摄像头", "前置摄像头", "人工智能", "操作系统", "大语言模型",
            "中华人民共和国", "人脸识别", "其中之一", "主要原因之一", "除此之外"
    };
    private static final String[] CLAUSE_CONNECTORS = {
            "但是", "不过", "然而", "因此", "因而", "所以", "如果", "除非",
            "虽然", "尽管", "同时", "此外", "另外", "而且", "并且", "以及", "也就是说",
            "可是", "从而", "由于", "因为", "既然", "即使", "哪怕", "只要", "只有",
            "无论", "不但", "不仅", "随后", "接着", "最终", "后来", "与此同时", "并",
            "不過", "雖然", "儘管", "並且", "也就是說", "從而", "由於", "因為",
            "無論", "不僅", "隨後", "接著", "最終", "後來", "與此同時", "並"
    };

    private LocalDisplaySliceFallback() {}

    /** Legacy-compatible entry point used by Semantic Ledger. */
    static SemanticDisplaySliceApiClient.Result slice(
            List<SourceAtomTimeline.Atom> atoms,
            String canonicalTranslation
    ) {
        return sliceInternal(atoms, canonicalTranslation, false);
    }

    /** Stricter planner used only by the default Contextual Unit Core. */
    static SemanticDisplaySliceApiClient.Result sliceContextual(
            List<SourceAtomTimeline.Atom> atoms,
            String canonicalTranslation
    ) {
        return sliceInternal(atoms, canonicalTranslation, true);
    }

    static boolean exceedsContextualHardLimit(
            List<SourceAtomTimeline.Atom> atoms,
            String canonicalTranslation
    ) {
        if (atoms == null || atoms.isEmpty() || canonicalTranslation == null) return false;
        long durationMs = Math.max(1L,
                atoms.get(atoms.size() - 1).endMs - atoms.get(0).startMs);
        return durationMs > HARD_MAX_SLICE_MS ||
                displayWidth(canonicalTranslation) > CONTEXTUAL_HARD_DISPLAY_WIDTH;
    }

    private static SemanticDisplaySliceApiClient.Result sliceInternal(
            List<SourceAtomTimeline.Atom> atoms,
            String canonicalTranslation,
            boolean contextualStrict
    ) {
        if (atoms == null || atoms.size() < 2) return SemanticDisplaySliceApiClient.Result.EMPTY;
        if (canonicalTranslation == null || canonicalTranslation.trim().isEmpty()) {
            return SemanticDisplaySliceApiClient.Result.EMPTY;
        }
        String canonical = canonicalTranslation;
        if (canonical.trim().length() < 8) return SemanticDisplaySliceApiClient.Result.EMPTY;

        int displayWidth = displayWidth(canonical);
        int softWidth = contextualStrict ? CONTEXTUAL_SOFT_DISPLAY_WIDTH : SOFT_MAX_TEXT_CHARS;
        int hardWidth = contextualStrict ? CONTEXTUAL_HARD_DISPLAY_WIDTH : HARD_MAX_TEXT_CHARS;

        long startMs = atoms.get(0).startMs;
        long endMs = atoms.get(atoms.size() - 1).endMs;
        long durationMs = Math.max(1L, endMs - startMs);
        if (durationMs <= SOFT_MAX_SLICE_MS && displayWidth <= softWidth) {
            return SemanticDisplaySliceApiClient.Result.EMPTY;
        }

        BoundaryMap boundaries = BoundaryMap.build(canonical, contextualStrict);
        boolean exceedsHardLimit = durationMs > HARD_MAX_SLICE_MS ||
                displayWidth > hardWidth;
        if (contextualStrict && !exceedsHardLimit &&
                isSingleTerminalSentence(canonical, boundaries)) {
            return new SemanticDisplaySliceApiClient.Result(
                    Collections.emptyList(), "whole_sentence", boundaries.rejectionSummary()
            );
        }

        int byTime = (int) Math.max(2L,
                (durationMs + SOFT_MAX_SLICE_MS - 1L) / SOFT_MAX_SLICE_MS);
        int byText = Math.max(2,
                (displayWidth + softWidth - 1) / softWidth);
        int desiredPieces = Math.max(byTime, byText);
        int maxPieces = Math.min(
                atoms.size(),
                Math.max(2, displayWidth / Math.max(
                        1, contextualStrict ? CONTEXTUAL_MIN_DISPLAY_WIDTH : MIN_TEXT_CHARS
                ))
        );
        desiredPieces = Math.min(desiredPieces, maxPieces);

        SemanticDisplaySliceApiClient.Result strict = plan(
                atoms, canonical, boundaries, desiredPieces, maxPieces,
                startMs, durationMs, contextualStrict, false, false
        );
        if (strict.slices.size() > 1) return strict;

        // A hard overlong caption may use token boundaries as a final readability fallback,
        // after punctuation and grammar-safe cuts have been exhausted.
        if (contextualStrict && exceedsHardLimit &&
                (hasTerminalEnding(canonical) || boundaries.hasPositiveEvidence())) {
            SemanticDisplaySliceApiClient.Result emergency = plan(
                    atoms, canonical, boundaries, desiredPieces, maxPieces,
                    startMs, durationMs, true, false, true
            );
            if (emergency.slices.size() > 1) return emergency;
        }

        // If there is no safe boundary evidence even after the hard-limit fallback, preserve the
        // canonical sentence rather than inventing a semantic cut.
        return new SemanticDisplaySliceApiClient.Result(
                Collections.emptyList(), "whole_sentence", boundaries.rejectionSummary()
        );
    }

    private static SemanticDisplaySliceApiClient.Result plan(
            List<SourceAtomTimeline.Atom> atoms,
            String canonical,
            BoundaryMap boundaries,
            int desiredPieces,
            int maxPieces,
            long startMs,
            long durationMs,
            boolean contextualStrict,
            boolean allowCjkFallback,
            boolean allowTokenFallback
    ) {
        for (int pieces = desiredPieces; pieces <= maxPieces; pieces++) {
            SemanticDisplaySliceApiClient.Result result = planForPieceCount(
                    atoms, canonical, boundaries, pieces, startMs, durationMs,
                    contextualStrict, allowCjkFallback, allowTokenFallback
            );
            if (result.slices.size() > 1) return result;
        }
        // A semantic plan with slightly fewer pieces is preferable to introducing a low-confidence
        // token cut when it still satisfies the hard text and timing limits.
        for (int pieces = desiredPieces - 1; pieces >= 2; pieces--) {
            SemanticDisplaySliceApiClient.Result result = planForPieceCount(
                    atoms, canonical, boundaries, pieces, startMs, durationMs,
                    contextualStrict, allowCjkFallback, allowTokenFallback
            );
            if (result.slices.size() > 1) return result;
        }
        return SemanticDisplaySliceApiClient.Result.EMPTY;
    }

    private static SemanticDisplaySliceApiClient.Result planForPieceCount(
            List<SourceAtomTimeline.Atom> atoms,
            String canonical,
            BoundaryMap boundaries,
            int pieces,
            long startMs,
            long durationMs,
            boolean contextualStrict,
            boolean allowCjkFallback,
            boolean allowTokenFallback
    ) {
        List<Integer> textCuts = planTextCuts(
                canonical, pieces, boundaries, contextualStrict,
                allowCjkFallback, allowTokenFallback
        );
        if (textCuts == null || !textLengthsSafe(canonical, textCuts, contextualStrict)) {
            return SemanticDisplaySliceApiClient.Result.EMPTY;
        }
        List<int[]> atomRanges = atomRangesForTextCuts(
                atoms, canonical.length(), textCuts, startMs, durationMs
        );
        if (atomRanges == null || atomRanges.size() != pieces ||
                !durationsSafe(atoms, atomRanges, canonical, textCuts)) {
            return SemanticDisplaySliceApiClient.Result.EMPTY;
        }
        return buildResult(canonical, atomRanges, textCuts, boundaries);
    }

    private static List<Integer> planTextCuts(
            String text,
            int pieces,
            BoundaryMap boundaries,
            boolean contextualStrict,
            boolean allowCjkFallback,
            boolean allowTokenFallback
    ) {
        if (pieces < 2 || text == null || text.length() < pieces) return null;
        List<Integer> candidates = boundaries.candidates(
                allowCjkFallback || !contextualStrict,
                allowTokenFallback || !contextualStrict
        );
        int n = text.length();
        double[][] cost = new double[pieces + 1][n + 1];
        int[][] previous = new int[pieces + 1][n + 1];
        for (int part = 0; part <= pieces; part++) {
            java.util.Arrays.fill(cost[part], INF);
            java.util.Arrays.fill(previous[part], -1);
        }
        double targetWidth = displayWidth(text) / (double) pieces;

        for (int end : candidates) {
            double value = segmentCost(
                    text, 0, end, targetWidth, boundaries.kind(end), end, end == n,
                    contextualStrict, allowCjkFallback, allowTokenFallback
            );
            if (value < INF) {
                cost[1][end] = value;
                previous[1][end] = 0;
            }
        }

        for (int part = 2; part <= pieces; part++) {
            for (int end : candidates) {
                if (end <= 0 || end >= n && part < pieces) continue;
                double best = INF;
                int bestStart = -1;
                for (int start : candidates) {
                    if (start >= end || cost[part - 1][start] >= INF) continue;
                    int remainingPieces = pieces - part;
                    if (n - end < remainingPieces) continue;
                    double value = segmentCost(
                            text, start, end, targetWidth, boundaries.kind(end), end, end == n,
                            contextualStrict, allowCjkFallback, allowTokenFallback
                    );
                    if (value >= INF) continue;
                    value += cost[part - 1][start];
                    if (value < best) {
                        best = value;
                        bestStart = start;
                    }
                }
                if (bestStart >= 0) {
                    cost[part][end] = best;
                    previous[part][end] = bestStart;
                }
            }
        }

        if (cost[pieces][n] >= INF) return null;
        List<Integer> cuts = new ArrayList<>(pieces - 1);
        int end = n;
        for (int part = pieces; part > 1; part--) {
            int start = previous[part][end];
            if (start <= 0 || start >= end) return null;
            cuts.add(start);
            end = start;
        }
        Collections.reverse(cuts);
        return cuts;
    }

    private static double segmentCost(
            String text,
            int start,
            int end,
            double targetWidth,
            int boundaryKind,
            int boundaryIndex,
            boolean finalSegment,
            boolean contextualStrict,
            boolean allowCjkFallback,
            boolean allowTokenFallback
    ) {
        if (end <= start || end > text.length()) return INF;
        int width = displayWidth(text.substring(start, end));
        int hardWidth = contextualStrict ? CONTEXTUAL_HARD_DISPLAY_WIDTH : HARD_MAX_TEXT_CHARS;
        int softWidth = contextualStrict ? CONTEXTUAL_SOFT_DISPLAY_WIDTH : SOFT_MAX_TEXT_CHARS;
        if (width > hardWidth) return INF;
        String value = text.substring(start, end);
        if (value.trim().isEmpty()) return INF;
        if (contextualStrict
                ? isUnsafeContextualSegment(value, finalSegment)
                : isLegacyOrphan(value, finalSegment)) return INF;
        double grammarPenalty = contextualStrict && !finalSegment
                ? grammarBoundaryPenalty(text, boundaryIndex) : 0d;
        if (grammarPenalty >= INF) return INF;

        double cost = Math.abs(width - targetWidth) * 1.4d + grammarPenalty;
        if (width > softWidth) cost += (width - softWidth) * 4.0d;
        if (!finalSegment) {
            if (!contextualStrict) {
                if (boundaryKind == TERMINAL_BOUNDARY) cost += 0d;
                else if (boundaryKind == CLAUSE_BOUNDARY || boundaryKind == SOFT_BOUNDARY) cost += 2d;
                else if (boundaryKind == TOKEN_BOUNDARY) cost += 10d;
                else if (boundaryKind == ICU_NON_CJK_BOUNDARY || boundaryKind == ICU_CJK_BOUNDARY) {
                    cost += 24d;
                } else return INF;
            } else {
                if (boundaryKind == TERMINAL_BOUNDARY) cost += 0d;
                else if (boundaryKind == CLAUSE_BOUNDARY) cost += 2d;
                else if (boundaryKind == GRAMMAR_BOUNDARY) cost += 6d;
                else if (boundaryKind == SOFT_BOUNDARY) cost += 9d;
                else if (boundaryKind == TOKEN_BOUNDARY && allowTokenFallback) cost += 80d;
                else if (boundaryKind == ICU_NON_CJK_BOUNDARY) cost += 36d;
                else if (boundaryKind == ICU_CJK_BOUNDARY && allowCjkFallback) cost += 190d;
                else return INF;
            }
        }
        return cost;
    }

    private static boolean isUnsafeContextualSegment(String value, boolean finalSegment) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return true;
        String core = stripTrailingPunctuation(clean);
        if (core.isEmpty()) return true;
        boolean terminal = hasTerminalEnding(clean);

        if (startsDependentFragment(core)) return true;
        if (isDependentFragment(core)) return true;
        if (!finalSegment && endsIncomplete(core)) return true;
        if (isDependentTerminalFragment(core)) return true;
        if (terminal) return false;

        int cjk = countCjk(core);
        return cjk > 0 && cjk <= 3 && (finalSegment || core.length() <= 3);
    }

    private static boolean startsDependentFragment(String text) {
        if (text.startsWith("之一") || text.startsWith("其一") ||
                text.startsWith("中的一种") || text.startsWith("的一部分")) return true;
        if (text.length() <= 3 && "的地得了着过著過".indexOf(text.charAt(0)) >= 0) return true;
        return false;
    }

    private static boolean isDependentFragment(String text) {
        if (text.equals("之一") || text.equals("其一") || text.equals("其中之一") ||
                text.equals("中的一种") || text.equals("的一部分")) return true;
        if (text.equals("也就是说") || text.equals("因为") || text.equals("所以") ||
                text.equals("如果") || text.equals("虽然") || text.equals("但是") ||
                text.equals("也就是說") || text.equals("因為") || text.equals("雖然")) return true;
        return text.length() <= 8 && text.startsWith("所") && text.endsWith("的");
    }

    private static boolean endsIncomplete(String text) {
        if (text.endsWith("其中") ||
                text.endsWith("因为") || text.endsWith("如果") || text.endsWith("虽然") ||
                text.endsWith("但是") || text.endsWith("也就是说") || text.endsWith("因為") ||
                text.endsWith("雖然") || text.endsWith("也就是說")) return true;
        if (endsWithAny(text,
                "导致", "引发", "造成", "使得", "促使", "带来", "宣布", "表示", "认为",
                "预计", "要求", "决定", "计划", "试图", "准备", "希望",
                "導致", "引發", "帶來", "認為", "預計", "計劃", "試圖", "準備")) return true;
        char last = text.charAt(text.length() - 1);
        return "的地得和与與或及而但并並".indexOf(last) >= 0 ||
                isEnglishDependentWord(trailingLatinToken(text));
    }

    private static boolean isDependentTerminalFragment(String text) {
        if (text == null || text.length() > 4) return false;
        String clean = text.replaceAll("[，。！？；,:;!?\\s]", "");
        return clean.matches("[0-9.,]+") ||
                "天年月日时時分秒万萬亿億千百十元块塊角公里公斤克米厘毫岁歲倍度号號层層项項人次台臺个個套件%％℃℉"
                        .contains(clean);
    }

    private static boolean isLegacyOrphan(String value, boolean finalSegment) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return true;
        if (hasTerminalEnding(clean)) return false;
        if (clean.startsWith("之一") || clean.startsWith("其一") || clean.startsWith("的") ||
                clean.startsWith("了") || clean.startsWith("着")) return true;
        if (clean.endsWith("之一") || clean.endsWith("其一") || clean.endsWith("其中") ||
                clean.endsWith("而且") || clean.endsWith("因此") || clean.endsWith("因为") ||
                clean.endsWith("如果") || clean.endsWith("但是") || clean.endsWith("的话") ||
                clean.endsWith("的") || clean.endsWith("了") || clean.endsWith("着") ||
                clean.endsWith("和") || clean.endsWith("与") || clean.endsWith("或")) return true;
        int cjk = countCjk(clean);
        return cjk > 0 && cjk <= 3 && (finalSegment || clean.length() <= 3);
    }

    private static boolean isSingleTerminalSentence(String text, BoundaryMap boundaries) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty() || !hasTerminalEnding(clean)) return false;
        int terminal = terminalIndex(clean);
        if (terminal < 0) return false;
        for (int i = 0; i < terminal; i++) {
            if (isTerminalSentenceEnding(clean.charAt(i))) return false;
        }
        for (int i = 1; i < terminal; i++) {
            int kind = boundaries.kindInOriginalText(text, clean, i);
            if (kind == TERMINAL_BOUNDARY || kind == CLAUSE_BOUNDARY) return false;
        }
        return true;
    }

    private static List<int[]> atomRangesForTextCuts(
            List<SourceAtomTimeline.Atom> atoms,
            int textLength,
            List<Integer> textCuts,
            long startMs,
            long durationMs
    ) {
        int pieces = textCuts.size() + 1;
        if (pieces < 2 || pieces > atoms.size() || textLength <= 0) return null;
        List<int[]> ranges = new ArrayList<>(pieces);
        int from = 0;
        for (int part = 0; part < pieces - 1; part++) {
            int remainingSlices = pieces - part - 1;
            int minTo = from;
            int maxTo = atoms.size() - remainingSlices - 1;
            if (minTo > maxTo) return null;
            double fraction = textCuts.get(part) / (double) textLength;
            long targetTime = startMs + Math.round(durationMs * fraction);
            int best = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int i = minTo; i <= maxTo; i++) {
                long segmentDuration = Math.max(1L, atoms.get(i).endMs - atoms.get(from).startMs);
                if (segmentDuration > HARD_MAX_SLICE_MS && i > minTo) break;
                if (segmentDuration > HARD_MAX_SLICE_MS) continue;
                long remainingDuration = Math.max(0L,
                        atoms.get(atoms.size() - 1).endMs - atoms.get(i + 1).startMs);
                if (remainingDuration > remainingSlices * HARD_MAX_SLICE_MS) continue;
                long distance = Math.abs(atoms.get(i).endMs - targetTime);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            if (best < minTo) return null;
            ranges.add(new int[]{from, best});
            from = best + 1;
        }
        if (from >= atoms.size()) return null;
        ranges.add(new int[]{from, atoms.size() - 1});
        return ranges;
    }

    private static boolean durationsSafe(
            List<SourceAtomTimeline.Atom> atoms,
            List<int[]> ranges,
            String canonical,
            List<Integer> textCuts
    ) {
        int fromChar = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int[] range = ranges.get(i);
            long duration = Math.max(1L,
                    atoms.get(range[1]).endMs - atoms.get(range[0]).startMs);
            if (duration > HARD_MAX_SLICE_MS) return false;
            int toChar = i < textCuts.size() ? textCuts.get(i) : canonical.length();
            int readableChars = countReadableCharacters(canonical.substring(fromChar, toChar));
            long minimumReadable = Math.min(900L, 260L + readableChars * 55L);
            if (duration < minimumReadable) return false;
            fromChar = toChar;
        }
        return fromChar == canonical.length();
    }

    private static boolean textLengthsSafe(
            String text, List<Integer> cuts, boolean contextualStrict
    ) {
        int hardWidth = contextualStrict ? CONTEXTUAL_HARD_DISPLAY_WIDTH : HARD_MAX_TEXT_CHARS;
        int previous = 0;
        for (int i = 0; i <= cuts.size(); i++) {
            int end = i < cuts.size() ? cuts.get(i) : text.length();
            if (end <= previous || end > text.length()) return false;
            if (text.substring(previous, end).trim().isEmpty()) return false;
            if (displayWidth(text.substring(previous, end)) > hardWidth) return false;
            previous = end;
        }
        return previous == text.length();
    }

    private static SemanticDisplaySliceApiClient.Result buildResult(
            String canonical,
            List<int[]> atomRanges,
            List<Integer> textCuts,
            BoundaryMap boundaries
    ) {
        int pieces = atomRanges.size();
        if (textCuts.size() != pieces - 1) return SemanticDisplaySliceApiClient.Result.EMPTY;
        List<SemanticDisplaySliceApiClient.Slice> slices = new ArrayList<>(pieces);
        int fromChar = 0;
        for (int part = 0; part < pieces; part++) {
            int toChar = part < textCuts.size() ? textCuts.get(part) : canonical.length();
            if (toChar <= fromChar || toChar > canonical.length()) {
                return SemanticDisplaySliceApiClient.Result.EMPTY;
            }
            String sliceText = canonical.substring(fromChar, toChar);
            if (sliceText.trim().isEmpty()) return SemanticDisplaySliceApiClient.Result.EMPTY;
            int[] range = atomRanges.get(part);
            slices.add(new SemanticDisplaySliceApiClient.Slice(range[0], range[1], sliceText));
            fromChar = toChar;
        }
        if (fromChar != canonical.length()) return SemanticDisplaySliceApiClient.Result.EMPTY;
        return new SemanticDisplaySliceApiClient.Result(
                Collections.unmodifiableList(slices),
                boundaries.chosenReasons(textCuts),
                boundaries.rejectionSummary()
        );
    }

    private static final class BoundaryMap {
        private final String text;
        private final int[] kinds;
        private final boolean[] blocked;

        private BoundaryMap(String text, int[] kinds, boolean[] blocked) {
            this.text = text;
            this.kinds = kinds;
            this.blocked = blocked;
        }

        static BoundaryMap build(String text, boolean contextualStrict) {
            int[] kinds = new int[text.length() + 1];
            boolean[] blocked = new boolean[text.length() + 1];
            java.util.Arrays.fill(kinds, -1);
            for (int i = 1; i < text.length(); i++) {
                if (!unicodeSafeBoundary(text, i)) continue;
                char left = text.charAt(i - 1);
                char right = text.charAt(i);
                if (isTerminalSentenceEnding(left)) kinds[i] = TERMINAL_BOUNDARY;
                else if (left == '；' || left == ';') kinds[i] = CLAUSE_BOUNDARY;
                else if (isSoftPunctuation(left)) {
                    kinds[i] = startsWithConnector(text, i) ? CLAUSE_BOUNDARY : SOFT_BOUNDARY;
                } else if (Character.isWhitespace(left) || Character.isWhitespace(right)) {
                    kinds[i] = TOKEN_BOUNDARY;
                } else if (contextualStrict && startsWithConnector(text, i)) {
                    kinds[i] = CLAUSE_BOUNDARY;
                } else if (contextualStrict && isGrammarBoundary(text, i)) {
                    kinds[i] = GRAMMAR_BOUNDARY;
                }
            }
            try {
                BreakIterator iterator = BreakIterator.getWordInstance(localeFor(text));
                iterator.setText(text);
                int boundary = iterator.first();
                while (boundary != BreakIterator.DONE) {
                    if (boundary > 0 && boundary < text.length() &&
                            unicodeSafeBoundary(text, boundary) && kinds[boundary] < 0) {
                        kinds[boundary] = isCjk(text.charAt(boundary - 1)) &&
                                isCjk(text.charAt(boundary))
                                ? ICU_CJK_BOUNDARY : ICU_NON_CJK_BOUNDARY;
                    }
                    boundary = iterator.next();
                }
            } catch (Throwable ignored) {
                // High-confidence punctuation and connector boundaries remain available.
            }
            markProtectedSpans(text, blocked, contextualStrict);
            if (contextualStrict) markUnsafeGrammarCuts(text, blocked);
            return new BoundaryMap(text, kinds, blocked);
        }

        int kind(int index) {
            if (index <= 0 || index >= kinds.length - 1 || blocked[index]) return -1;
            return kinds[index];
        }

        int kindInOriginalText(String original, String trimmed, int trimmedIndex) {
            int offset = original.indexOf(trimmed);
            if (offset < 0) return -1;
            return kind(offset + trimmedIndex);
        }

        List<Integer> candidates(boolean allowCjkFallback, boolean allowTokenFallback) {
            List<Integer> result = new ArrayList<>();
            result.add(0);
            for (int i = 1; i < kinds.length - 1; i++) {
                int kind = kind(i);
                if (kind < 0 || kind == ICU_CJK_BOUNDARY && !allowCjkFallback ||
                        kind == TOKEN_BOUNDARY && !allowTokenFallback) continue;
                result.add(i);
            }
            result.add(kinds.length - 1);
            return result;
        }

        String chosenReasons(List<Integer> cuts) {
            if (cuts == null || cuts.isEmpty()) return "whole_sentence";
            StringBuilder result = new StringBuilder();
            for (int cut : cuts) {
                if (result.length() > 0) result.append(',');
                result.append(boundaryName(kind(cut)));
            }
            return result.toString();
        }

        String rejectionSummary() {
            int numeric = 0;
            int dependency = 0;
            int modifier = 0;
            int protectedEntity = 0;
            for (int i = 1; i < blocked.length - 1; i++) {
                if (!blocked[i]) continue;
                String grammar = grammarCutRejection(text, i);
                if ("dependent_connector".equals(grammar) ||
                        "predicate_requires_complement".equals(grammar)) dependency++;
                else if ("modifier_attachment".equals(grammar)) modifier++;
                else if (numericBoundary(text, i)) numeric++;
                else protectedEntity++;
            }
            StringBuilder result = new StringBuilder();
            appendCount(result, "numeric_span", numeric);
            appendCount(result, "dependent_connector", dependency);
            appendCount(result, "modifier_attachment", modifier);
            appendCount(result, "protected_entity", protectedEntity);
            return result.toString();
        }

        boolean hasPositiveEvidence() {
            for (int i = 1; i < kinds.length - 1; i++) {
                int value = kind(i);
                if (value >= 0 && value != ICU_CJK_BOUNDARY) return true;
            }
            return false;
        }
    }

    private static void markProtectedSpans(
            String text,
            boolean[] blocked,
            boolean contextualStrict
    ) {
        if (!contextualStrict) {
            for (String phrase : LEGACY_PROTECTED_PHRASES) blockOccurrences(text, phrase, blocked, true);
        }
        blockOccurrences(text, "之一", blocked, true);
        blockOccurrences(text, "其一", blocked, true);
        protectNumericSpans(text, blocked);
        protectChineseNumericSpans(text, blocked);

        // Protect compact 所…的 attributive structures without maintaining a product-name lexicon.
        for (int start = 0; start < text.length(); start++) {
            if (text.charAt(start) != '所') continue;
            int limit = Math.min(text.length(), start + 8);
            for (int end = start + 2; end < limit; end++) {
                if (text.charAt(end) == '的') {
                    blockRange(blocked, start, end + 1);
                    break;
                }
            }
        }
        for (int i = 1; i < text.length(); i++) {
            char left = text.charAt(i - 1);
            char right = text.charAt(i);
            if (Character.isDigit(left) && (Character.isDigit(right) || isCjk(right))) blocked[i] = true;
            if (isCjk(left) && Character.isDigit(right)) blocked[i] = true;
        }
        int tokenStart = 0;
        while (tokenStart < text.length()) {
            while (tokenStart < text.length() && Character.isWhitespace(text.charAt(tokenStart))) tokenStart++;
            int tokenEnd = tokenStart;
            while (tokenEnd < text.length() && !Character.isWhitespace(text.charAt(tokenEnd))) tokenEnd++;
            if (tokenEnd > tokenStart) {
                String token = text.substring(tokenStart, tokenEnd);
                if (token.contains("://") || token.startsWith("www.") || token.contains("@")) {
                    blockRange(blocked, tokenStart, tokenEnd);
                }
            }
            tokenStart = tokenEnd + 1;
        }
        protectBracketSpans(text, blocked);
    }

    private static void protectNumericSpans(String text, boolean[] blocked) {
        for (int start = 0; start < text.length(); start++) {
            if (!Character.isDigit(text.charAt(start))) continue;
            int end = start + 1;
            boolean consumedUnit = false;
            while (end < text.length()) {
                char value = text.charAt(end);
                if (Character.isDigit(value)) {
                    end++;
                    continue;
                }
                if (isNumericSeparator(value) && end + 1 < text.length() &&
                        Character.isDigit(text.charAt(end + 1))) {
                    end++;
                    continue;
                }
                if (isNumericUnit(value)) {
                    consumedUnit = true;
                    end++;
                    continue;
                }
                break;
            }
            int protectedStart = start;
            if (start > 0 && "第¥￥$€£".indexOf(text.charAt(start - 1)) >= 0) {
                protectedStart = start - 1;
            }
            if (end - protectedStart > 1 || consumedUnit) {
                blockRange(blocked, protectedStart, end);
            }
            start = Math.max(start, end - 1);
        }
    }

    private static boolean isNumericSeparator(char value) {
        return value == ',' || value == '，' || value == '.' || value == '．' ||
                value == ':' || value == '：' || value == '/' || value == '-';
    }

    private static boolean isNumericUnit(char value) {
        return "万萬亿億千百十天年月日时時分秒元块塊角公里公斤克米厘毫岁歲倍度号號层層项項人次台臺个個套件%％℃℉".indexOf(value) >= 0;
    }

    private static void protectChineseNumericSpans(String text, boolean[] blocked) {
        for (int start = 0; start < text.length(); start++) {
            if (!isChineseNumberChar(text.charAt(start))) continue;
            int end = start;
            while (end < text.length() && isChineseNumberChar(text.charAt(end))) end++;
            if (end < text.length() && isChineseQuantityUnit(text.charAt(end))) {
                end++;
                while (end < text.length() && isChineseQuantityUnit(text.charAt(end))) end++;
                blockRange(blocked, start, end);
            }
        }
    }

    private static boolean isChineseNumberChar(char value) {
        return "零〇一二三四五六七八九十百千万萬亿億两兩半".indexOf(value) >= 0;
    }

    private static boolean isChineseQuantityUnit(char value) {
        return "天年月日时時分秒元块塊角公里公斤克米厘毫岁歲倍度号號层層项項人次台臺个個套件".indexOf(value) >= 0;
    }

    private static void markUnsafeGrammarCuts(String text, boolean[] blocked) {
        for (int i = 1; i < text.length(); i++) {
            if (!grammarCutRejection(text, i).isEmpty()) blocked[i] = true;
        }
    }

    private static void blockOccurrences(
            String text,
            String phrase,
            boolean[] blocked,
            boolean attachLeft
    ) {
        int from = 0;
        while (from < text.length()) {
            int start = text.indexOf(phrase, from);
            if (start < 0) break;
            if (attachLeft && start > 0 && isCjk(text.charAt(start - 1))) blocked[start] = true;
            blockRange(blocked, start, start + phrase.length());
            from = start + phrase.length();
        }
    }

    private static void blockRange(boolean[] blocked, int start, int endExclusive) {
        int from = Math.max(1, start + 1);
        int to = Math.min(blocked.length - 1, endExclusive);
        for (int i = from; i < to; i++) blocked[i] = true;
    }

    private static void protectBracketSpans(String text, boolean[] blocked) {
        String opens = "([（【《「『\"“'‘";
        String closes = ")]）】》」』\"”'’";
        for (int i = 0; i < text.length(); i++) {
            int kind = opens.indexOf(text.charAt(i));
            if (kind < 0) continue;
            char close = closes.charAt(kind);
            int end = text.indexOf(close, i + 1);
            if (end > i && end - i <= 40) blockRange(blocked, i, end + 1);
        }
    }

    private static boolean startsWithConnector(String text, int index) {
        for (String connector : CLAUSE_CONNECTORS) {
            if (index + connector.length() <= text.length() && text.startsWith(connector, index)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGrammarBoundary(String text, int index) {
        if (index < 8 || text.length() - index < 6) return false;
        char left = text.charAt(index - 1);
        if ("了过着過著".indexOf(left) < 0) return false;
        return startsWithAny(text, index,
                "他", "她", "它", "他们", "她们", "我们", "你们", "这", "那", "其",
                "他們", "她們", "我們", "你們", "這");
    }

    private static double grammarBoundaryPenalty(String text, int index) {
        String rejection = grammarCutRejection(text, index);
        if (!rejection.isEmpty()) return INF;
        if (startsWithConnector(text, index)) return -8d;
        return 0d;
    }

    private static String grammarCutRejection(String text, int index) {
        if (text == null || index <= 0 || index >= text.length()) return "";
        char left = text.charAt(index - 1);
        char right = text.charAt(index);
        if (isCjk(left) && "的地得".indexOf(right) >= 0) return "modifier_attachment";
        if (isCjk(right) && "和与與或及而但并並".indexOf(left) >= 0) {
            return "dependent_connector";
        }

        String leftText = text.substring(Math.max(0, index - 12), index)
                .replaceAll("[，。！？；,:;!?\\s]+$", "");
        String rightText = text.substring(index).trim();
        String trailingWord = trailingLatinToken(leftText);
        if (isEnglishDependentWord(trailingWord)) return "dependent_connector";
        boolean punctuationBefore = isTerminalSentenceEnding(left) || left == '；' || left == ';' ||
                isSoftPunctuation(left);

        if (!punctuationBefore && startsWithAny(rightText, 0,
                "正在", "正", "将", "要", "已经", "曾经", "可能", "必须", "仍然", "仍",
                "还在", "被", "把", "对于", "关于", "將", "已經", "曾經", "必須",
                "還在", "對於", "關於")) {
            return "modifier_attachment";
        }
        if (!punctuationBefore && endsWithAny(leftText,
                "他", "她", "它", "他们", "她们", "我们", "你们",
                "他們", "她們", "我們", "你們") &&
                startsWithAny(rightText, 0,
                        "随后", "接着", "最终", "后来", "同时", "此外", "仍然",
                        "隨後", "接著", "最終", "後來", "同時", "此外", "仍然")) {
            return "modifier_attachment";
        }
        if (isSoftPunctuation(left) && leftText.length() <= 12 &&
                startsWithAny(rightText, 0, "引发", "导致", "造成", "使得", "带来", "促使",
                        "引發", "導致", "帶來")) {
            return "modifier_attachment";
        }
        if (endsWithAny(leftText,
                "导致", "引发", "造成", "使得", "促使", "带来", "宣布", "表示", "认为",
                "预计", "要求", "决定", "计划", "试图", "准备", "希望",
                "導致", "引發", "帶來", "認為", "預計", "計劃", "試圖", "準備")) {
            return "predicate_requires_complement";
        }
        return "";
    }

    private static boolean startsWithAny(String text, int index, String... values) {
        if (text == null || index < 0 || index >= text.length()) return false;
        for (String value : values) {
            if (text.startsWith(value, index)) return true;
        }
        return false;
    }

    private static boolean endsWithAny(String text, String... values) {
        if (text == null) return false;
        for (String value : values) if (text.endsWith(value)) return true;
        return false;
    }

    private static String trailingLatinToken(String text) {
        if (text == null || text.isEmpty()) return "";
        int end = text.length();
        while (end > 0 && !isLatinWordChar(text.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && isLatinWordChar(text.charAt(start - 1))) start--;
        return text.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static boolean isLatinWordChar(char value) {
        return value < 128 && (Character.isLetter(value) || value == '\'' || value == '-');
    }

    private static boolean isEnglishDependentWord(String word) {
        if (word == null || word.isEmpty()) return false;
        switch (word.toLowerCase(Locale.ROOT)) {
            case "a": case "an": case "the":
            case "and": case "or": case "but": case "nor":
            case "because": case "although": case "though": case "while":
            case "if": case "unless": case "whether": case "than": case "as":
            case "of": case "to": case "for": case "with": case "from": case "by":
            case "at": case "in": case "on": case "into": case "onto": case "about":
            case "through": case "between": case "without": case "during":
            case "is": case "am": case "are": case "was": case "were": case "be":
            case "been": case "being": case "has": case "have": case "had":
            case "do": case "does": case "did": case "can": case "could":
            case "will": case "would": case "shall": case "should": case "may":
            case "might": case "must": case "who": case "whom": case "whose":
            case "which": case "that":
                return true;
            default:
                return false;
        }
    }

    private static boolean numericBoundary(String text, int index) {
        if (text == null || index <= 0 || index >= text.length()) return false;
        char left = text.charAt(index - 1);
        char right = text.charAt(index);
        return Character.isDigit(left) || Character.isDigit(right) ||
                isNumericUnit(left) || isNumericUnit(right) || isNumericSeparator(left);
    }

    private static String boundaryName(int kind) {
        switch (kind) {
            case TERMINAL_BOUNDARY: return "strong_punctuation";
            case CLAUSE_BOUNDARY: return "clause_punctuation";
            case GRAMMAR_BOUNDARY: return "grammar_boundary";
            case SOFT_BOUNDARY: return "soft_punctuation";
            case TOKEN_BOUNDARY: return "token_boundary";
            case ICU_NON_CJK_BOUNDARY: return "non_cjk_word_boundary";
            case ICU_CJK_BOUNDARY: return "cjk_fallback";
            default: return "unknown";
        }
    }

    private static void appendCount(StringBuilder output, String name, int count) {
        if (output == null || count <= 0) return;
        if (output.length() > 0) output.append(',');
        output.append(name).append('=').append(count);
    }

    private static boolean unicodeSafeBoundary(String text, int index) {
        if (index <= 0 || index >= text.length()) return false;
        char left = text.charAt(index - 1);
        char right = text.charAt(index);
        if (Character.isHighSurrogate(left) && Character.isLowSurrogate(right)) return false;
        return !(Character.isLetterOrDigit(left) && Character.isLetterOrDigit(right) &&
                !isCjk(left) && !isCjk(right));
    }

    private static Locale localeFor(String text) {
        boolean cjk = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
            if (block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA) return Locale.JAPANESE;
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                    block == Character.UnicodeBlock.HANGUL_JAMO ||
                    block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) return Locale.KOREAN;
            if (isCjk(value)) cjk = true;
        }
        return cjk ? Locale.TRADITIONAL_CHINESE : Locale.ROOT;
    }

    private static boolean hasTerminalEnding(String text) {
        return terminalIndex(text) >= 0;
    }

    private static int terminalIndex(String text) {
        int index = text.length() - 1;
        while (index >= 0 && (Character.isWhitespace(text.charAt(index)) ||
                isClosingPunctuation(text.charAt(index)))) index--;
        return index >= 0 && isTerminalSentenceEnding(text.charAt(index)) ? index : -1;
    }

    private static String stripTrailingPunctuation(String text) {
        int end = text.length();
        while (end > 0) {
            char value = text.charAt(end - 1);
            if (!Character.isWhitespace(value) && !isClosingPunctuation(value) &&
                    !isTerminalSentenceEnding(value) && !isSoftPunctuation(value) &&
                    value != '；' && value != ';') break;
            end--;
        }
        return text.substring(0, end).trim();
    }

    private static int countCjk(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (isCjk(text.charAt(i))) count++;
        return count;
    }

    private static int countReadableCharacters(String text) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (!Character.isWhitespace(value) && !isClosingPunctuation(value) &&
                    !isTerminalSentenceEnding(value) && !isSoftPunctuation(value) &&
                    value != '；' && value != ';') count++;
        }
        return count;
    }

    /** Conservative monospace-equivalent width used to keep CJK and Latin captions balanced. */
    private static int displayWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isWhitespace(value)) width += 1;
            else if (isCjk(value)) width += 2;
            else width += 1;
        }
        return width;
    }

    private static boolean isTerminalSentenceEnding(char value) {
        return value == '。' || value == '！' || value == '？' || value == '!' ||
                value == '?' || value == '…';
    }

    private static boolean isClosingPunctuation(char value) {
        return value == '"' || value == '\'' || value == '”' || value == '’' ||
                value == ')' || value == '）' || value == ']' || value == '】' ||
                value == '》' || value == '」' || value == '』';
    }

    private static boolean isSoftPunctuation(char value) {
        return value == '，' || value == ',' || value == '、' || value == '：' || value == ':';
    }

    private static boolean isCjk(char value) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
