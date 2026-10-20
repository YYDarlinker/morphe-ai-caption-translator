package app.yydarlinker.deepseekcaptions;

/**
 * Extra request guidance for event sizing, sentence completeness and the authored display
 * contract. Kept out of the pinned protocol literal so the upstream prompt/protocol audit stays
 * byte-identical; it is appended to every request instead.
 */
final class RebuildGuidance {
    private RebuildGuidance() {}

    /**
     * Appended only for a manually authored track. It does not replace {@link #SENTENCE}: the
     * translation quality instructions stay the same as an automatic track. It only states that the
     * author's cue ranges are fixed display boundaries, so the model must keep every event inside
     * one cue while remaining free to use several natural events for a cue.
     */
    static String displayCueRule(RebuildPlanner.Block block) {
        return " Authored display groups are fixed page boundaries. The display_cues field lists"
                + " each group's first-last owned token ids. Every event must lie inside exactly"
                + " one group; several natural events per group are allowed; never merge two"
                + " groups into one event and never move text across a group boundary. Cover every"
                + " group completely. A group may contain several short author cues that only read"
                + " together; keep the group as one display page.";
    }

    /** Cue-sized requests need the sentence around them; the cue is the unit, not the context. */
    private static final int CONTEXT_TOKENS = 600;

    /**
     * A manually authored cue is a line of a sentence, so translating it alone loses the references
     * that make it accurate. The reply is still limited to the block's own tokens, but the model
     * gets the surrounding cues as read-only context, which is what a whole-block request used to
     * give.
     */
    static void widenContext(
            org.json.JSONObject payload, RebuildSource source, RebuildPlanner.Block block)
            throws org.json.JSONException {
        int last = source.words.size() - 1;
        payload.put(
                "context_before",
                RebuildPlanner.context(
                        source, Math.max(0, block.from - CONTEXT_TOKENS), block.from - 1));
        payload.put(
                "context_after",
                RebuildPlanner.context(
                        source, block.to + 1, Math.min(last, block.to + CONTEXT_TOKENS)));
    }

    static final String SENTENCE =
            " Event sizing: one event is one complete clause or sentence that reads naturally on its"
                + " own, ideally one line and at most two. Punctuate every event's text for its own"
                + " range: end with a full stop when that range ends a sentence, a comma when the"
                + " clause continues into the next event, and never drop the closing punctuation."
                + " A long source sentence may stay in one event, or be split only where each side"
                + " keeps its own subject and predicate. Never leave a prepositional phrase, a verb"
                + " and its object, a comparison such as 'more A than B', or a correlative pair on"
                + " opposite sides of an event boundary, and never shorten an event merely to make it"
                + " fit: shorter is only better when the meaning is still complete.";
}
