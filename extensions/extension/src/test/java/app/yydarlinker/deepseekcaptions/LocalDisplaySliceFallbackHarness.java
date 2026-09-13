package app.yydarlinker.deepseekcaptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Blind-corpus and invariant harness for the Contextual-only deterministic display planner. */
public final class LocalDisplaySliceFallbackHarness {
    private static final List<String> BLIND_COMPOUNDS = Arrays.asList(
            "潜望式长焦镜头", "可变光圈系统", "图像信号处理器", "自适应刷新率",
            "固态电池技术", "多模态视觉模型", "无线通信模块", "光学防抖功能"
    );
    private static final List<String> DEPENDENT_TAILS = Arrays.asList(
            "中的一种", "的一部分", "所造成的", "所需要的"
    );

    public static void main(String[] args) {
        List<SourceAtomTimeline.Atom> longAtoms = atoms(20, 700L);

        assertPlan(
                longAtoms,
                "这是最重要的原因之一，同时它还包含后置摄像头功能。",
                true
        );

        String blind = "这套系统采用潜望式长焦镜头，并配备可变光圈系统；" +
                "图像信号处理器同时支持自适应刷新率和光学防抖功能。";
        SemanticDisplaySliceApiClient.Result blindResult = assertPlan(longAtoms, blind, true);
        assertNoCutsInside(blind, blindResult, BLIND_COMPOUNDS);

        String blindTwo = "多模态视觉模型通过无线通信模块同步数据，同时固态电池技术提供更长续航。";
        SemanticDisplaySliceApiClient.Result blindTwoResult = assertPlan(longAtoms, blindTwo, true);
        assertNoCutsInside(blindTwo, blindTwoResult, BLIND_COMPOUNDS);

        String deviceRegression = "委员会在篇幅很长的报告中再次强调一国两制具有完整含义，" +
                "同时说明这项覆盖680万人和6,800,000名参与者的计划必须按阶段稳步推进。";
        SemanticDisplaySliceApiClient.Result deviceResult = assertPlan(
                longAtoms, deviceRegression, true
        );
        assertNoCutsInside(deviceRegression, deviceResult,
                Arrays.asList("一国两制", "680万人", "6,800,000名"));

        String reportingVerb = "某某宣称，要推行一套覆盖范围更广而且执行周期更长的方案，" +
                "但是委员会要求先完成公开评估并充分说明所有潜在风险。";
        SemanticDisplaySliceApiClient.Result reportingResult = assertPlan(
                longAtoms, reportingVerb, true
        );
        assertNoCutsInside(reportingVerb, reportingResult, Arrays.asList("要推行"));

        String noPunctuation = "这套系统采用多模态视觉模型同时支持无线通信模块" +
                "并且提供固态电池技术所以整体续航表现更加稳定可靠";
        SemanticDisplaySliceApiClient.Result asrResult = assertPlan(longAtoms, noPunctuation, true);
        assertNoCutsInside(noPunctuation, asrResult, BLIND_COMPOUNDS);

        String unpunctuatedPredicateShift = "这项持续数月的第一阶段工作终于结束了" +
                "他们随后决定继续推进覆盖多个地区的第二阶段实施计划";
        SemanticDisplaySliceApiClient.Result predicateShiftResult = assertPlan(
                longAtoms, unpunctuatedPredicateShift, true
        );
        if (!predicateShiftResult.boundaryReason.contains("grammar_boundary")) {
            throw new AssertionError("unpunctuated predicate shift should use grammar boundary: " +
                    predicateShiftResult.boundaryReason);
        }

        String englishPreposition =
                "steel obelisks erected in central during the latter colonial years.";
        SemanticDisplaySliceApiClient.Result englishResult = assertPlan(
                longAtoms, englishPreposition, true
        );
        assertNoSliceEndsWith(englishResult, "during");
        assertNoCutBetween(englishPreposition, englishResult, "during", "the");

        String longTraditionalSentence =
                "最可辨識的城市變成了一個如何在只開四槍的情況下控制住自由城市的案例。";
        SemanticDisplaySliceApiClient.Result longTraditionalResult = assertPlan(
                longAtoms, longTraditionalSentence, true
        );
        assertNoStandaloneDependentTail(longTraditionalResult);

        String causalClause = "对于塔利班重新夺回国家后会发生什么的恐惧显而易见，" +
                "并导致机场出现混乱局面。";
        SemanticDisplaySliceApiClient.Result causalResult = assertPlan(
                longAtoms, causalClause, true
        );
        assertNoSliceEndsWith(causalResult, "并导致");
        assertNoCutBetween(causalClause, causalResult, "并导致", "机场");
        if (!causalResult.boundaryReason.contains("clause_punctuation")) {
            throw new AssertionError("connector-led clause should use clause punctuation: " +
                    causalResult.boundaryReason);
        }

        String traditionalCausal = "對於塔利班重新奪回國家後會發生什麼的恐懼顯而易見，" +
                "並導致機場出現混亂局面。";
        SemanticDisplaySliceApiClient.Result traditionalCausalResult = assertPlan(
                longAtoms, traditionalCausal, true
        );
        assertNoSliceEndsWith(traditionalCausalResult, "並導致");
        assertNoCutBetween(traditionalCausal, traditionalCausalResult, "並導致", "機場");

        String modifierClause = "各国政府陷入恐慌，" +
                "竭尽所能将人民撤离这个正在四分五裂的国家。";
        SemanticDisplaySliceApiClient.Result modifierResult = assertPlan(
                longAtoms, modifierClause, true
        );
        assertNoCutBetween(modifierClause, modifierResult, "这个国家", "正在四分五裂");
        assertNoCutBetween(modifierClause, modifierResult, "正在四分五裂", "的国家");

        String traditionalModifier = "各國政府陷入恐慌，" +
                "竭盡所能將人民撤離這個正在四分五裂的國家。";
        SemanticDisplaySliceApiClient.Result traditionalModifierResult = assertPlan(
                longAtoms, traditionalModifier, true
        );
        assertNoCutBetween(traditionalModifier, traditionalModifierResult,
                "這個", "正在四分五裂");
        assertNoCutBetween(traditionalModifier, traditionalModifierResult,
                "正在四分五裂", "的國家");

        String reportingAttachment = "公司宣布，将从明年开始全面调整价格。";
        SemanticDisplaySliceApiClient.Result reportingAttachmentResult =
                LocalDisplaySliceFallback.sliceContextual(longAtoms, reportingAttachment);
        assertExactAndRanges(reportingAttachment, reportingAttachmentResult, longAtoms.size());
        assertNoCutBetween(reportingAttachment, reportingAttachmentResult, "宣布，", "将");

        String traditionalReporting = "公司宣布，將從明年開始全面調整價格。";
        SemanticDisplaySliceApiClient.Result traditionalReportingResult =
                LocalDisplaySliceFallback.sliceContextual(longAtoms, traditionalReporting);
        assertExactAndRanges(traditionalReporting, traditionalReportingResult, longAtoms.size());
        assertNoCutBetween(traditionalReporting, traditionalReportingResult, "宣布，", "將");

        String subjectPredicate = "这个国家正在四分五裂。";
        SemanticDisplaySliceApiClient.Result subjectPredicateResult =
                LocalDisplaySliceFallback.sliceContextual(longAtoms, subjectPredicate);
        assertExactAndRanges(subjectPredicate, subjectPredicateResult, longAtoms.size());
        assertNoCutBetween(subjectPredicate, subjectPredicateResult, "这个国家", "正在四分五裂");

        List<String> semanticBlindCorpus = Arrays.asList(
                "会议将在三天后举行。",
                "新系统支持超过125万人同时使用。",
                "公司宣布，将从明年开始全面调整价格。",
                "这一决定，引发了市场剧烈波动。",
                "政府正竭尽全力将仍滞留当地的居民撤出这个不断恶化的地区。",
                "尽管天气已经明显转差，但救援人员仍决定继续前进。",
                "预计未来24小时内还会有进一步降雨。",
                "他表示，要在年底前完成整个项目。"
        );
        for (String sentence : semanticBlindCorpus) {
            SemanticDisplaySliceApiClient.Result result =
                    LocalDisplaySliceFallback.sliceContextual(longAtoms, sentence);
            assertExactAndRanges(sentence, result, longAtoms.size());
            assertNoStandaloneDependentTail(result);
            assertNoCutBetween(sentence, result, "三", "天");
            assertNoCutBetween(sentence, result, "125万", "人");
            assertNoCutBetween(sentence, result, "24", "小时");
            assertNoCutBetween(sentence, result, "宣布，", "将");
            assertNoCutBetween(sentence, result, "国家", "正在");
            assertNoCutBetween(sentence, result, "正在", "四分五裂");
        }

        String noEvidence = "这是一段长度明显超过普通字幕限制却完全缺少标点与可靠语义边界证据的" +
                "连续中文字幕文本宁可暂时保持完整显示也不能在普通汉字之间随意切开造成词组破坏";
        if (!LocalDisplaySliceFallback.sliceContextual(longAtoms, noEvidence).slices.isEmpty()) {
            throw new AssertionError("unsafe CJK-only fallback should keep canonical whole");
        }

        List<String> dependentSentences = Arrays.asList(
                "这只是解决方案中的一种，而不是全部。",
                "我们讨论的是系统的一部分，不是最终产品。",
                "这才是关键，因为它决定了后续结果。",
                "这些问题是网络延迟所造成的。",
                "具体安排以后再说。",
                "也就是说，系统需要先完成初始化。",
                "这是完成任务所需要的条件。"
        );
        for (String sentence : dependentSentences) {
            SemanticDisplaySliceApiClient.Result result =
                    LocalDisplaySliceFallback.sliceContextual(longAtoms, sentence);
            assertExactAndRanges(sentence, result, longAtoms.size());
            assertNoStandaloneDependentTail(result);
            assertNoCutsInside(sentence, result, DEPENDENT_TAILS);
        }

        List<SourceAtomTimeline.Atom> nearHardAtoms = atoms(8, 850L);
        for (String sentence : Arrays.asList(
                "这句话真的很好吗？", "我不知道为什么？", "这不是一个简单的问题。"
        )) {
            if (!LocalDisplaySliceFallback.sliceContextual(nearHardAtoms, sentence).slices.isEmpty()) {
                throw new AssertionError("single terminal sentence should remain whole: " + sentence);
            }
        }

        String spaced = "  这套系统采用人工智能，同时保留完整输入文本。  ";
        SemanticDisplaySliceApiClient.Result spacedResult =
                LocalDisplaySliceFallback.sliceContextual(longAtoms, spaced);
        assertExactAndRanges(spaced, spacedResult, longAtoms.size());

        if (!LocalDisplaySliceFallback.sliceContextual(longAtoms, "为什么？").slices.isEmpty()) {
            throw new AssertionError("independent short sentence should not be sliced");
        }
        if (!LocalDisplaySliceFallback.sliceContextual(longAtoms, "没有。").slices.isEmpty()) {
            throw new AssertionError("independent short answer should not be sliced");
        }

        // The legacy entry point remains available to Semantic Ledger; Phase 4 strict behavior is
        // intentionally selected only through sliceContextual().
        LocalDisplaySliceFallback.slice(longAtoms, blind);

        System.out.println("LocalDisplaySliceFallbackHarness: OK");
    }

    private static SemanticDisplaySliceApiClient.Result assertPlan(
            List<SourceAtomTimeline.Atom> atoms,
            String canonical,
            boolean requireMultiple
    ) {
        SemanticDisplaySliceApiClient.Result result =
                LocalDisplaySliceFallback.sliceContextual(atoms, canonical);
        if (requireMultiple && result.slices.size() < 2) {
            throw new AssertionError("long canonical text was not segmented: " + canonical +
                    "; boundary=" + result.boundaryReason +
                    "; rejected=" + result.rejectionSummary);
        }
        assertExactAndRanges(canonical, result, atoms.size());
        assertNoStandaloneDependentTail(result);
        return result;
    }

    private static void assertExactAndRanges(
            String canonical,
            SemanticDisplaySliceApiClient.Result result,
            int atomCount
    ) {
        if (result.slices.isEmpty()) return;
        StringBuilder joined = new StringBuilder();
        int expectedFrom = 0;
        for (SemanticDisplaySliceApiClient.Slice slice : result.slices) {
            if (slice.text.isEmpty() || slice.text.trim().isEmpty()) {
                throw new AssertionError("empty slice");
            }
            if (slice.from != expectedFrom || slice.to < slice.from) {
                throw new AssertionError("atom ranges are not continuous");
            }
            expectedFrom = slice.to + 1;
            joined.append(slice.text);
        }
        if (expectedFrom != atomCount) {
            throw new AssertionError("atom ranges did not cover the complete unit");
        }
        if (!canonical.equals(joined.toString())) {
            throw new AssertionError(
                    "slice text did not preserve exact canonical: expected=<" + canonical +
                            "> actual=<" + joined + ">"
            );
        }
    }

    private static void assertNoCutsInside(
            String canonical,
            SemanticDisplaySliceApiClient.Result result,
            List<String> protectedValues
    ) {
        List<Integer> cuts = new ArrayList<>();
        int position = 0;
        for (int i = 0; i + 1 < result.slices.size(); i++) {
            position += result.slices.get(i).text.length();
            cuts.add(position);
        }
        for (String value : protectedValues) {
            int from = 0;
            while (from < canonical.length()) {
                int start = canonical.indexOf(value, from);
                if (start < 0) break;
                int end = start + value.length();
                for (int cut : cuts) {
                    if (cut > start && cut < end) {
                        throw new AssertionError("cut inside phrase: " + value + " at " + cut);
                    }
                }
                from = end;
            }
        }
    }

    private static void assertNoSliceEndsWith(
            SemanticDisplaySliceApiClient.Result result,
            String suffix
    ) {
        for (int i = 0; i + 1 < result.slices.size(); i++) {
            String clean = result.slices.get(i).text.trim();
            if (clean.endsWith(suffix)) {
                throw new AssertionError("incomplete predicate at slice end: " + suffix);
            }
        }
    }

    private static void assertNoCutBetween(
            String canonical,
            SemanticDisplaySliceApiClient.Result result,
            String left,
            String right
    ) {
        String pair = left + right;
        int start = canonical.indexOf(pair);
        if (start < 0) return;
        int forbidden = start + left.length();
        int position = 0;
        for (int i = 0; i + 1 < result.slices.size(); i++) {
            position += result.slices.get(i).text.length();
            if (position == forbidden) {
                throw new AssertionError("unsafe boundary in <" + canonical + ">: " +
                        left + " | " + right);
            }
        }
    }

    private static void assertNoStandaloneDependentTail(
            SemanticDisplaySliceApiClient.Result result
    ) {
        for (SemanticDisplaySliceApiClient.Slice slice : result.slices) {
            String clean = slice.text.trim();
            while (!clean.isEmpty() && "，。！？；,.!?;：:".indexOf(clean.charAt(clean.length() - 1)) >= 0) {
                clean = clean.substring(0, clean.length() - 1).trim();
            }
            if (DEPENDENT_TAILS.contains(clean) || "之一".equals(clean) || "其一".equals(clean)) {
                throw new AssertionError("standalone dependent fragment: " + slice.text);
            }
        }
    }

    private static List<SourceAtomTimeline.Atom> atoms(int count, long stepMs) {
        List<SourceAtomTimeline.Atom> atoms = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            atoms.add(new SourceAtomTimeline.Atom(
                    i * stepMs,
                    (i + 1L) * stepMs,
                    "a" + i,
                    i,
                    false
            ));
        }
        return atoms;
    }
}
