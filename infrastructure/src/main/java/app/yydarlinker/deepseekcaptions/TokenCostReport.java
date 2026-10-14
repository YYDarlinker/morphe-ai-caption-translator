package app.yydarlinker.deepseekcaptions;

import android.content.Context;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The text of the token cost audit: usage, cache hits, request purposes and cost per viewing
 * minute.
 *
 * <p>Every line is one authored template of the settings catalog. The panel shows them in the
 * interface language; the saved export uses the English templates, so that reports of different
 * users and builds can be compared.
 */
final class TokenCostReport {
    /** The purposes that get a line, with the catalog key of their label. */
    private static final String[][] PURPOSES = {
        {"priority", "audit_bucket_priority"},
        {"priority_current", "audit_bucket_priority_current"},
        {"priority_gap_rescue", "audit_bucket_priority_gap_rescue"},
        {"background", "audit_bucket_background"},
        {"background_page", "audit_bucket_background_page"},
        {"background_block", "audit_bucket_background_block"},
        {"background_alt", "audit_bucket_background_alt"},
        {"unit_realtime", "audit_bucket_unit_realtime"},
        {"unit_background", "audit_bucket_unit_background"},
        {"core_semantic_ledger_v2", "audit_core_semantic_ledger"},
        {"core_contextual_unit_v1", "audit_core_contextual_unit"},
        {"display", "audit_bucket_display"},
    };

    /** The page-merge purposes that also get an efficiency line. */
    private static final String[][] PAGE_PURPOSES = {
        {"background_page", "audit_bucket_background_page"},
        {"background_block", "audit_bucket_background_block"},
        {"background_alt", "audit_bucket_background_alt"},
    };

    private static final long MINIMUM_VIEWED_FOR_RATE_MS = 5_000L;

    private final Context context;
    private final boolean localized;
    private final StringBuilder out = new StringBuilder(1500);

    private final JSONObject state;
    private final JSONObject total;
    private final JSONObject totalMetrics;
    private final JSONObject session;
    private final JSONObject sessionTotal;
    private final JSONObject sessionMetrics;

    TokenCostReport(Context context, boolean localized, JSONObject state, JSONObject session) {
        this.context = context;
        this.localized = localized;
        this.state = state;
        this.session = session;
        total = TokenCostAudit.bucketLocked(state, "all");
        totalMetrics = TokenCostAudit.metricsLocked(state);
        sessionTotal = TokenCostAudit.bucketLocked(session, "all");
        sessionMetrics = TokenCostAudit.metricsLocked(session);
    }

    /** The label of the catalog in the language of the report. */
    private String text(String key) {
        return localized
                ? CaptionStrings.get(context, key)
                : CaptionTextResolver.string(context, Locale.ENGLISH, key);
    }

    private String line(String key, String... values) {
        return String.format(Locale.ROOT, text(key), (Object[]) values);
    }

    /** Starts a new line of the report with a template. */
    private void add(String key, String... values) {
        out.append('\n').append(line(key, values));
    }

    private static long get(JSONObject object, String key) {
        return TokenCostAudit.value(object, key);
    }

    private static String n(long value) {
        return TokenCostAudit.format(value);
    }

    private static String ratio(double value) {
        return String.format(Locale.US, "%.2fx", value);
    }

    String build() {
        long attempts = get(total, "attempts");
        long tokens = get(total, "total_tokens");
        if (attempts == 0L && tokens == 0L) return text("audit_no_usage");

        out.append(text("audit_title"));
        String model = session.optString("model", "").trim();
        if (!model.isEmpty()) add("audit_current_model", model);
        boolean rebuild = "event_rebuild_r2".equals(session.optString("active_core", ""));
        add(
                "audit_current_engine",
                rebuild
                        ? text("engine_event_rebuild") + " / " + CaptionEngineIdentity.ID
                        : text("audit_legacy_engine"));
        add(
                "audit_api",
                n(attempts),
                n(get(total, "http_ok")),
                n(get(total, "failures")),
                n(get(total, "internal_retries")));
        failures();

        long accepted = get(total, "accepted_caption_units");
        long rejected = get(total, "rejected_caption_units");
        if (accepted + rejected > 0) {
            add(
                    "audit_structural",
                    n(accepted),
                    n(rejected),
                    n(get(total, "quality_rejected_units")));
        }
        add(
                "audit_tokens",
                n(tokens),
                n(get(total, "prompt_tokens")),
                n(get(total, "completion_tokens")));
        inputCache();
        add("audit_estimate", cost(total));

        add("audit_by_purpose");
        for (String[] purpose : PURPOSES) purposeLine(state, purpose[0], text(purpose[1]));
        pageMerge();
        sunkRequests();
        displaySlicing();
        purposeLine(
                state,
                "core_event_rebuild_r2",
                line("audit_core_event_rebuild", CaptionEngineIdentity.ID));

        currentVideo();
        requestBlocks();
        for (String[] core :
                new String[][] {
                    {"semantic_ledger_v2", "legacy engine"},
                    {"contextual_unit_v1", "Legacy compatibility engine"},
                    {"event_rebuild_r2", null},
                }) {
            coreRate(core[0], core[1]);
        }
        long requestBytes = get(sessionTotal, "request_bytes");
        long sessionAttempts = get(sessionTotal, "attempts");
        if (sessionAttempts > 0L)
            add("audit_request_body", n(requestBytes), n(requestBytes / sessionAttempts));
        long noUsage = get(total, "responses_without_usage");
        if (noUsage > 0L) add("audit_no_usage_note", n(noUsage));
        return out.toString();
    }

    private void failures() {
        if (get(total, "failures") <= 0L) return;
        add(
                "audit_failure_breakdown",
                n(get(total, "http_429_failures")),
                n(get(total, "http_5xx_failures")),
                n(get(total, "http_4xx_failures")),
                n(get(total, "timeout_failures")),
                n(get(total, "network_failures")),
                n(get(total, "cancelled_failures")),
                n(get(total, "other_failures") + get(total, "http_other_failures")));
    }

    private void inputCache() {
        long hit = get(total, "cache_hit_tokens");
        long miss = get(total, "cache_miss_tokens");
        if (hit + miss > 0L) {
            add("audit_cache_known", n(hit), n(miss), TokenCostAudit.percent(hit, hit + miss));
        } else {
            add("audit_cache_unknown");
        }
    }

    /** The cost of a bucket, or "-" when no response of it was priced. */
    private String cost(JSONObject bucket) {
        if (get(bucket, "priced_responses") <= 0L) return "-";
        return "¥"
                + TokenCostAudit.money(get(bucket, "cost_nano_cny") / 1_000_000_000d)
                + pricedSubset(bucket);
    }

    /** " (only responses priced by ...)" when some responses of the bucket had no known price. */
    private String pricedSubset(JSONObject bucket) {
        return get(bucket, "priced_responses") < get(bucket, "usage_responses")
                ? " " + text("audit_priced_subset")
                : "";
    }

    /** One line about the requests of one purpose: how many, their tokens, deferrals and cost. */
    private void purposeLine(JSONObject parent, String key, String label) {
        JSONObject bucket = TokenCostAudit.bucketLocked(parent, key);
        long requests = get(bucket, "logical_requests");
        long calls = get(bucket, "attempts");
        out.append("\n  ")
                .append(label)
                .append(": ")
                .append(
                        line(
                                requests == 1
                                        ? "audit_logical_request_one"
                                        : "audit_logical_request_many",
                                n(requests)))
                .append(line(calls == 1 ? "audit_api_call_one" : "audit_api_call_many", n(calls)))
                .append(line("audit_tok", n(get(bucket, "total_tokens"))));
        long focusMiss = get(bucket, "focus_miss_results");
        long deferred = get(bucket, "local_deferrals");
        if (focusMiss > 0L) out.append(" · ").append(line("audit_focus_misses", n(focusMiss)));
        if (deferred > 0L) out.append(" · ").append(line("audit_circuit_breakers", n(deferred)));
        if (get(bucket, "priced_responses") > 0L) {
            out.append(" · ¥")
                    .append(TokenCostAudit.money(get(bucket, "cost_nano_cny") / 1_000_000_000d))
                    .append(pricedSubset(bucket));
        }
    }

    /** What the page merge added per atom, how far it got, and when it fell back to blocks. */
    private void pageMerge() {
        for (String[] purpose : PAGE_PURPOSES) {
            JSONObject bucket = TokenCostAudit.bucketLocked(state, purpose[0]);
            long committed = get(bucket, "committed_atoms");
            if (committed > 0L) {
                out.append("\n  ")
                        .append(
                                line(
                                        "audit_efficiency",
                                        text(purpose[1]),
                                        n(committed),
                                        oneDecimal(
                                                get(bucket, "prompt_tokens") / (double) committed),
                                        oneDecimal(
                                                get(bucket, "cache_miss_tokens")
                                                        / (double) committed)));
            }
        }
        JSONObject page = TokenCostAudit.bucketLocked(state, "background_page");
        long pageRequests = get(page, "logical_requests");
        long blocksCompleted = get(page, "blocks_completed");
        if (pageRequests > 0L) {
            add(
                    "audit_bucket_progress",
                    n(blocksCompleted),
                    n(pageRequests),
                    oneDecimal(blocksCompleted / (double) pageRequests));
        }
        long armed = get(totalMetrics, "background_alt_armed");
        if (armed > 0L) {
            JSONObject recheck = TokenCostAudit.bucketLocked(state, "background_alt");
            add(
                    "audit_boundary",
                    n(armed),
                    n(get(recheck, "logical_requests")),
                    n(get(recheck, "committed_atoms")));
        }
        long fallbacks = get(totalMetrics, "background_page_fallback_events");
        if (fallbacks > 0L) add("audit_page_fallback", n(fallbacks));
    }

    private void sunkRequests() {
        long sunk = get(total, "sunk_prompts");
        if (sunk > 0L) add("audit_sunk", n(sunk), n(get(total, "sunk_prompt_bytes")));
    }

    /**
     * How often the display was sliced locally, without tokens, and how often the AI had to be
     * asked.
     */
    private void displaySlicing() {
        long success = get(totalMetrics, "display_local_success");
        long inconclusive = get(totalMetrics, "display_local_inconclusive");
        long whole = get(totalMetrics, "display_local_whole_sentence");
        if (success + inconclusive + whole <= 0L) return;
        JSONObject display = TokenCostAudit.bucketLocked(state, "display");
        out.append('\n').append(line("audit_display_local", n(success)));
        if (whole > 0L) out.append(" · ").append(line("audit_display_whole", n(whole)));
        if (inconclusive > 0L) {
            out.append(" · ")
                    .append(
                            line(
                                    "audit_display_handoff",
                                    n(inconclusive),
                                    n(get(display, "attempts")),
                                    n(get(display, "usage_responses")),
                                    n(get(display, "failures"))));
        }
    }

    private void currentVideo() {
        long viewed = get(session, "viewed_ms");
        long tokens = get(sessionTotal, "total_tokens");
        add("audit_video", TokenCostAudit.seconds1(viewed / 1000d), n(tokens));
        if (viewed >= MINIMUM_VIEWED_FOR_RATE_MS) {
            double minutes = viewed / 60_000d;
            out.append(" · ")
                    .append(
                            line(
                                    "audit_per_minute",
                                    n(Math.round(tokens / Math.max(0.001d, minutes)))));
            long cost = get(sessionTotal, "cost_nano_cny");
            if (cost > 0L)
                out.append(" · ¥")
                        .append(TokenCostAudit.money(cost / 1_000_000_000d / minutes))
                        .append("/minute");
        }
    }

    /**
     * How much text around the requested blocks the requests carried, and how the disk cache did.
     */
    private void requestBlocks() {
        long coreAtoms = get(sessionMetrics, "semantic_core_atoms");
        if (coreAtoms > 0L) {
            long exposed =
                    get(sessionMetrics, "semantic_window_atoms")
                            + get(sessionMetrics, "semantic_outside_atoms");
            add(
                    "audit_semantic_window",
                    n(coreAtoms),
                    n(exposed),
                    ratio(exposed / (double) coreAtoms));
        }
        long targetUnits = get(sessionMetrics, "unit_target_units");
        if (targetUnits > 0L) {
            long contextUnits = get(sessionMetrics, "unit_context_units");
            add(
                    "audit_request_blocks",
                    n(targetUnits),
                    n(contextUnits),
                    ratio(contextUnits / (double) targetUnits));
            long targetChars = get(sessionMetrics, "unit_target_chars");
            if (targetChars > 0L) {
                out.append(" · ")
                        .append(
                                line(
                                        "audit_char_ratio",
                                        ratio(
                                                get(sessionMetrics, "unit_context_chars")
                                                        / (double) targetChars)));
            }
        }
        long lookups = get(sessionMetrics, "unit_cache_lookups");
        if (lookups > 0L) {
            add(
                    "audit_disk_cache",
                    n(lookups),
                    n(get(sessionMetrics, "unit_cache_hit_units")),
                    n(get(sessionMetrics, "unit_cache_miss_units")));
        }
    }

    /** The viewing time and tokens of one engine of this session, if it was used. */
    private void coreRate(String core, String legacyLabel) {
        long viewed = get(sessionMetrics, "viewed_ms_" + core);
        JSONObject bucket = TokenCostAudit.bucketLocked(session, "core_" + core);
        long tokens = get(bucket, "total_tokens");
        if (viewed <= 0L && tokens <= 0L) return;
        out.append("\n  ")
                .append(
                        legacyLabel == null
                                ? line("audit_core_event_rebuild", CaptionEngineIdentity.ID)
                                : line("audit_current_engine", legacyLabel))
                .append(": ")
                .append(line("audit_viewed", TokenCostAudit.seconds1(viewed / 1000d), n(tokens)));
        if (viewed >= MINIMUM_VIEWED_FOR_RATE_MS) {
            double minutes = viewed / 60_000d;
            out.append(" · ")
                    .append(
                            line(
                                    "audit_tok_per_minute",
                                    n(Math.round(tokens / Math.max(0.001d, minutes)))));
            long cost = get(bucket, "cost_nano_cny");
            if (cost > 0L)
                out.append(" · ¥")
                        .append(TokenCostAudit.money(cost / 1_000_000_000d / minutes))
                        .append("/minute");
        }
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.US, "%.1f", value);
    }
}
