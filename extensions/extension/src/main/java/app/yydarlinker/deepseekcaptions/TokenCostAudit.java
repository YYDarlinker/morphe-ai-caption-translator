package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Exact on-device token/cost accounting for AI caption traffic.
 *
 * <p>The audit consumes provider-reported usage whenever it is present. It recognizes both native
 * DeepSeek cache counters and OpenAI-compatible prompt_tokens_details.cached_tokens used by Alibaba
 * Cloud Model Studio, while also recording semantic zero-yield/deferred work and failure classes.</p>
 */
final class TokenCostAudit {
    private static final String PREFS = "deepseek_caption_token_cost_audit";
    private static final String STATE = "state_v1";
    private static final int VERSION = 1;
    private static final long WATCH_SAMPLE_MAX_DELTA_MS = 5_000L;
    private static final long WATCH_PERSIST_INTERVAL_MS = 5_000L;

    private static final Object LOCK = new Object();
    private static volatile Context appContext;
    private static JSONObject memoryState;
    private static long generation;
    private static String activeVideoId = "";
    private static String activeCore = "event_rebuild_r2";
    private static long lastVideoTimeMs = -1L;
    private static long lastWatchPersistRealtimeMs;

    private TokenCostAudit() {}

    static void install(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            stateLocked();
        }
    }

    static void onCoreSelected(Context context, String rawCore) {
        if (context != null) install(context);
        String core = "event_rebuild_r2";
        synchronized (LOCK) {
            stateLocked();
            boolean changed = !core.equals(activeCore);
            activeCore = core;
            putQuiet(sessionLocked(), "active_core", core);
            if (changed) lastVideoTimeMs = -1L;
            persistLocked();
        }
    }
    static void onVideoId(Context context, String rawVideoId) {
        if (context != null) install(context);
        String videoId = rawVideoId == null ? "" : rawVideoId.trim();
        if (videoId.isEmpty()) return;
        synchronized (LOCK) {
            stateLocked();
            if (videoId.equals(activeVideoId)) return;
            activeVideoId = videoId;
            generation++;
            putQuiet(memoryState, "session", freshSession(videoId, generation));
            lastVideoTimeMs = -1L;
            lastWatchPersistRealtimeMs = android.os.SystemClock.elapsedRealtime();
            persistLocked();
        }
    }

    static void onVideoTime(Context context, long timeMs, boolean aiVisible) {
        if (context != null) install(context);
        synchronized (LOCK) {
            stateLocked();
            if (!aiVisible) {
                lastVideoTimeMs = -1L;
                return;
            }
            long clean = Math.max(0L, timeMs);
            if (lastVideoTimeMs >= 0L) {
                long delta = clean - lastVideoTimeMs;
                if (delta > 0L && delta <= WATCH_SAMPLE_MAX_DELTA_MS) {
                    JSONObject session = sessionLocked();
                    add(session, "viewed_ms", delta);
                    add(memoryState, "viewed_ms", delta);
                    String viewedKey = "viewed_ms_" + activeCore;
                    add(metricsLocked(session), viewedKey, delta);
                    add(metricsLocked(memoryState), viewedKey, delta);
                }
            }
            lastVideoTimeMs = clean;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastWatchPersistRealtimeMs >= WATCH_PERSIST_INTERVAL_MS) {
                lastWatchPersistRealtimeMs = now;
                persistLocked();
            }
        }
    }

    static Request beginSemantic(
            DeepSeekConfig.Snapshot config,
            boolean priority,
            int windowAtoms,
            int coreAtoms,
            int outsideContextAtoms,
            int sourceChars,
            String detailBucket
    ) {
        synchronized (LOCK) {
            stateLocked();
            String purpose = priority ? "priority" : "background";
            Request request = new Request(
                    generation,
                    purpose,
                    config == null ? "" : config.model,
                    config == null ? "" : config.baseUrl,
                    detailBucket,
                    "semantic_ledger_v2"
            );
            noteModelLocked(request.model);
            incrementLogicalRequestLocked(request);
            JSONObject totalsMetrics = metricsLocked(memoryState);
            JSONObject sessionMetrics = metricsLocked(sessionLocked());
            add(totalsMetrics, "semantic_requests", 1L);
            add(sessionMetrics, "semantic_requests", 1L);
            add(totalsMetrics, "semantic_window_atoms", Math.max(0, windowAtoms));
            add(sessionMetrics, "semantic_window_atoms", Math.max(0, windowAtoms));
            add(totalsMetrics, "semantic_core_atoms", Math.max(0, coreAtoms));
            add(sessionMetrics, "semantic_core_atoms", Math.max(0, coreAtoms));
            add(totalsMetrics, "semantic_outside_atoms", Math.max(0, outsideContextAtoms));
            add(sessionMetrics, "semantic_outside_atoms", Math.max(0, outsideContextAtoms));
            add(totalsMetrics, "semantic_source_chars", Math.max(0, sourceChars));
            add(sessionMetrics, "semantic_source_chars", Math.max(0, sourceChars));
            return request;
        }
    }

    static Request beginUnitBatch(
            DeepSeekConfig.Snapshot config,
            boolean priority,
            int targetUnits,
            int contextUnits,
            int targetChars,
            int contextChars,
            String detailBucket
    ) {
        synchronized (LOCK) {
            stateLocked();
            String purpose = priority ? "priority" : "background";
            Request request = new Request(
                    generation,
                    purpose,
                    config == null ? "" : config.model,
                    config == null ? "" : config.baseUrl,
                    detailBucket,
                    detailBucket != null && detailBucket.startsWith("event-rebuild-") ? "event_rebuild_r2" : "contextual_unit_v1"
            );
            noteModelLocked(request.model);
            incrementLogicalRequestLocked(request);
            JSONObject totalsMetrics = metricsLocked(memoryState);
            JSONObject sessionMetrics = metricsLocked(sessionLocked());
            add(totalsMetrics, "unit_batch_requests", 1L);
            add(sessionMetrics, "unit_batch_requests", 1L);
            add(totalsMetrics, "unit_target_units", Math.max(0, targetUnits));
            add(sessionMetrics, "unit_target_units", Math.max(0, targetUnits));
            add(totalsMetrics, "unit_context_units", Math.max(0, contextUnits));
            add(sessionMetrics, "unit_context_units", Math.max(0, contextUnits));
            add(totalsMetrics, "unit_target_chars", Math.max(0, targetChars));
            add(sessionMetrics, "unit_target_chars", Math.max(0, targetChars));
            add(totalsMetrics, "unit_context_chars", Math.max(0, contextChars));
            add(sessionMetrics, "unit_context_chars", Math.max(0, contextChars));
            return request;
        }
    }

    static void recordUnitCacheOutcome(int totalUnits, int restoredUnits) {
        synchronized (LOCK) {
            stateLocked();
            int total = Math.max(0, totalUnits);
            int restored = Math.max(0, Math.min(total, restoredUnits));
            JSONObject totalsMetrics = metricsLocked(memoryState);
            JSONObject sessionMetrics = metricsLocked(sessionLocked());
            add(totalsMetrics, "unit_cache_lookups", 1L);
            add(sessionMetrics, "unit_cache_lookups", 1L);
            add(totalsMetrics, "unit_cache_hit_units", restored);
            add(sessionMetrics, "unit_cache_hit_units", restored);
            add(totalsMetrics, "unit_cache_miss_units", total - restored);
            add(sessionMetrics, "unit_cache_miss_units", total - restored);
            persistLocked();
        }
    }
    static void recordUnitBatchOutcome(Request request, int translatedUnits) {
        if (request == null) return;
        synchronized (LOCK) {
            stateLocked();
            updateBucketsLocked(request, bucket -> {
                add(bucket, "unit_batch_results", 1L);
                add(bucket, "unit_translations", Math.max(0, translatedUnits));
            });
            persistLocked();
        }
    }
    static void recordUnitQualityOutcome(Request request,int accepted,int rejected,int qualityRejected) {
        if(request==null)return;
        synchronized(LOCK){stateLocked();updateBucketsLocked(request,b->{
            add(b,"accepted_caption_units",Math.max(0,accepted));
            add(b,"rejected_caption_units",Math.max(0,rejected));
            add(b,"quality_rejected_units",Math.max(0,qualityRejected));
        });persistLocked();}
    }
    static void recordCommittedAtoms(String detailBucket, long committedAtoms) {
        if (detailBucket == null || detailBucket.isEmpty() || committedAtoms <= 0L) return;
        synchronized (LOCK) {
            stateLocked();
            add(bucketLocked(memoryState, detailBucket), "committed_atoms", committedAtoms);
            add(bucketLocked(sessionLocked(), detailBucket), "committed_atoms", committedAtoms);
            persistLocked();
        }
    }

    /**
     * Counts only fixed blocks that actually transitioned unresolved to done on this response.
     * dev15 counted the whole merged span at request time, so a page that was re-requested inflated
     * the "avoided duplicate sends" estimate instead of exposing the spin that caused it.
     */
    static void recordBlocksCompleted(String detailBucket, long blocks) {
        if (detailBucket == null || detailBucket.isEmpty() || blocks <= 0L) return;
        synchronized (LOCK) {
            stateLocked();
            add(bucketLocked(memoryState, detailBucket), "blocks_completed", blocks);
            add(bucketLocked(sessionLocked(), detailBucket), "blocks_completed", blocks);
            persistLocked();
        }
    }

    /**
     * A prompt that reached the provider but whose response was never read. The tokens are absent
     * from every usage-based total below, so they are tracked as request bytes instead of invented
     * token counts.
     */
    static void recordSunkPrompt(Request request, long requestBytes) {
        if (request == null || requestBytes <= 0L) return;
        synchronized (LOCK) {
            stateLocked();
            updateBucketsLocked(request, bucket -> {
                add(bucket, "sunk_prompts", 1L);
                add(bucket, "sunk_prompt_bytes", requestBytes);
            });
            persistLocked();
        }
    }

    static void recordAltFramingArmed() {
        synchronized (LOCK) {
            stateLocked();
            add(metricsLocked(memoryState), "background_alt_armed", 1L);
            add(metricsLocked(sessionLocked()), "background_alt_armed", 1L);
            persistLocked();
        }
    }

    static void recordPageFallback() {
        synchronized (LOCK) {
            stateLocked();
            add(metricsLocked(memoryState), "background_page_fallback_events", 1L);
            add(metricsLocked(sessionLocked()), "background_page_fallback_events", 1L);
            persistLocked();
        }
    }

    static void recordDisplayLocalOutcome(boolean succeeded) {
        synchronized (LOCK) {
            stateLocked();
            String key = succeeded ? "display_local_success" : "display_local_inconclusive";
            add(metricsLocked(memoryState), key, 1L);
            add(metricsLocked(sessionLocked()), key, 1L);
            persistLocked();
        }
    }

    static void recordDisplayLocalWholeSentence() {
        synchronized (LOCK) {
            stateLocked();
            add(metricsLocked(memoryState), "display_local_whole_sentence", 1L);
            add(metricsLocked(sessionLocked()), "display_local_whole_sentence", 1L);
            persistLocked();
        }
    }

    static Request beginDisplay(
            DeepSeekConfig.Snapshot config,
            int atoms,
            int canonicalChars
    ) {
        synchronized (LOCK) {
            stateLocked();
            Request request = new Request(
                    generation,
                    "display",
                    config == null ? "" : config.model,
                    config == null ? "" : config.baseUrl,
                    "",
                    "semantic_ledger_v2"
            );
            noteModelLocked(request.model);
            incrementLogicalRequestLocked(request);
            JSONObject totalsMetrics = metricsLocked(memoryState);
            JSONObject sessionMetrics = metricsLocked(sessionLocked());
            add(totalsMetrics, "display_requests", 1L);
            add(sessionMetrics, "display_requests", 1L);
            add(totalsMetrics, "display_atoms", Math.max(0, atoms));
            add(sessionMetrics, "display_atoms", Math.max(0, atoms));
            add(totalsMetrics, "display_canonical_chars", Math.max(0, canonicalChars));
            add(sessionMetrics, "display_canonical_chars", Math.max(0, canonicalChars));
            return request;
        }
    }

    static int beginAttempt(Request request, int requestBytes) {
        if (request == null) return 0;
        synchronized (LOCK) {
            stateLocked();
            int attempt = ++request.attempts;
            updateBucketsLocked(request, bucket -> {
                add(bucket, "attempts", 1L);
                add(bucket, "request_bytes", Math.max(0, requestBytes));
                if (attempt > 1) add(bucket, "internal_retries", 1L);
            });
            return attempt;
        }
    }

    static void recordResponse(Request request, int attempt, JSONObject root) {
        if (request == null || attempt <= 0) return;
        synchronized (LOCK) {
            stateLocked();
            JSONObject usage = root == null ? null : root.optJSONObject("usage");
            final long prompt = usage == null ? 0L : nonNegative(usage.optLong("prompt_tokens", 0L));
            final long completion = usage == null ? 0L : nonNegative(usage.optLong("completion_tokens", 0L));
            final long total = usage == null ? prompt + completion :
                    nonNegative(usage.optLong("total_tokens", prompt + completion));

            boolean nativeCache = usage != null &&
                    (usage.has("prompt_cache_hit_tokens") || usage.has("prompt_cache_miss_tokens"));
            JSONObject promptDetails = usage == null ? null : usage.optJSONObject("prompt_tokens_details");
            boolean compatibleCache = promptDetails != null && promptDetails.has("cached_tokens");
            final boolean cacheKnown = nativeCache || compatibleCache;

            long hitValue = 0L;
            long missValue = 0L;
            if (nativeCache) {
                hitValue = nonNegative(usage.optLong("prompt_cache_hit_tokens", 0L));
                if (usage.has("prompt_cache_miss_tokens")) {
                    missValue = nonNegative(usage.optLong("prompt_cache_miss_tokens", 0L));
                } else {
                    missValue = Math.max(0L, prompt - hitValue);
                }
            } else if (compatibleCache) {
                hitValue = nonNegative(promptDetails.optLong("cached_tokens", 0L));
                hitValue = Math.min(hitValue, prompt);
                missValue = Math.max(0L, prompt - hitValue);
            }
            final long hit = hitValue;
            final long miss = missValue;
            final long costNano = priceNanoCny(request.model, request.baseUrl, hit, miss, completion, cacheKnown);

            updateBucketsLocked(request, bucket -> {
                add(bucket, "http_ok", 1L);
                if (usage == null) {
                    add(bucket, "responses_without_usage", 1L);
                    return;
                }
                add(bucket, "usage_responses", 1L);
                add(bucket, "prompt_tokens", prompt);
                add(bucket, "completion_tokens", completion);
                add(bucket, "total_tokens", total);
                add(bucket, "cache_hit_tokens", hit);
                add(bucket, "cache_miss_tokens", miss);
                if (cacheKnown) add(bucket, "cache_known_responses", 1L);
                if (costNano >= 0L) {
                    add(bucket, "priced_responses", 1L);
                    add(bucket, "cost_nano_cny", costNano);
                }
            });
            persistLocked();
        }
    }

    static void recordSemanticOutcome(Request request, int acceptedUnits, boolean focusCovered) {
        if (request == null) return;
        synchronized (LOCK) {
            stateLocked();
            updateBucketsLocked(request, bucket -> {
                add(bucket, "semantic_results", 1L);
                add(bucket, "semantic_units", Math.max(0, acceptedUnits));
                if (!focusCovered) add(bucket, "focus_miss_results", 1L);
            });
            persistLocked();
        }
    }

    static void recordSemanticDeferred(Request request) {
        if (request == null) return;
        synchronized (LOCK) {
            stateLocked();
            updateBucketsLocked(request, bucket -> add(bucket, "local_deferrals", 1L));
            persistLocked();
        }
    }

    static void recordFailure(Request request, int attempt, String kind) {
        if (request == null || attempt <= 0) return;
        synchronized (LOCK) {
            stateLocked();
            final String clean = kind == null ? "unknown" : kind.toLowerCase(Locale.ROOT);
            updateBucketsLocked(request, bucket -> {
                add(bucket, "failures", 1L);
                if (clean.startsWith("http")) {
                    add(bucket, "http_failures", 1L);
                    if (clean.contains("429")) add(bucket, "http_429_failures", 1L);
                    else if (isHttp5xx(clean)) add(bucket, "http_5xx_failures", 1L);
                    else if (isHttp4xx(clean)) add(bucket, "http_4xx_failures", 1L);
                    else add(bucket, "http_other_failures", 1L);
                } else if (clean.contains("timeout")) {
                    add(bucket, "timeout_failures", 1L);
                } else if (clean.contains("network")) {
                    add(bucket, "network_failures", 1L);
                } else if (clean.contains("cancel") || clean.contains("interrupt")) {
                    add(bucket, "cancelled_failures", 1L);
                } else {
                    add(bucket, "other_failures", 1L);
                }
            });
            persistLocked();
        }
    }

    static void clear(Context context) {
        if (context != null) install(context);
        synchronized (LOCK) {
            memoryState = freshState();
            generation++;
            putQuiet(memoryState, "session", freshSession(activeVideoId, generation));
            lastVideoTimeMs = -1L;
            persistLocked();
        }
    }

    static String uiText(Context context) {
        if (context != null) install(context);
        synchronized (LOCK) {
            JSONObject state = stateLocked();
            JSONObject totalAll = bucketLocked(state, "all");
            JSONObject session = sessionLocked();
            JSONObject sessionAll = bucketLocked(session, "all");
            JSONObject metrics = metricsLocked(session);

            long attempts = value(totalAll, "attempts");
            long totalTokens = value(totalAll, "total_tokens");
            if (attempts == 0L && totalTokens == 0L) {
                return "Token cost audit: no API usage yet. Play AI captions to see usage, cache hits, request purposes, and cost per viewing minute.";
            }

            StringBuilder out = new StringBuilder(1500);
            out.append("Token cost audit (since last reset)");
            String model = session.optString("model", "").trim();
            if (!model.isEmpty()) out.append("\nCurrent model: ").append(model);
            out.append("\nCurrent engine: ").append(
                    "event_rebuild_r2".equals(session.optString("active_core", ""))
                            ? "Event rebuild / " + RebuildProtocol.VERSION
                            : "legacy engine"
            );
            out.append("\nAPI: ").append(format(value(totalAll, "attempts"))).append(" attempts")
                    .append(" · ").append(format(value(totalAll, "http_ok"))).append(" successful (2xx)")
                    .append(" · ").append(format(value(totalAll, "failures"))).append(" failures")
                    .append(" · internal retries ").append(format(value(totalAll, "internal_retries")));
            appendFailureBreakdown(out, totalAll);
            long accepted=value(totalAll,"accepted_caption_units"),rejected=value(totalAll,"rejected_caption_units");
            if(accepted+rejected>0)out.append("\n").append("Structural and deterministic checks: accepted / rejected (not semantic acceptance)")
                    .append(": ").append(accepted).append(" / ").append(rejected)
                    .append(" (").append(value(totalAll,"quality_rejected_units")).append(")");
            out.append("\nTokens: ").append(format(totalTokens))
                    .append(" = input ").append(format(value(totalAll, "prompt_tokens")))
                    .append(" + output ").append(format(value(totalAll, "completion_tokens")));

            long hit = value(totalAll, "cache_hit_tokens");
            long miss = value(totalAll, "cache_miss_tokens");
            long knownInput = hit + miss;
            if (knownInput > 0L) {
                out.append("\nInput cache: hit ").append(format(hit))
                        .append(" / miss ").append(format(miss))
                        .append(" · hit rate ").append(percent(hit, knownInput));
            } else {
                out.append("\nInput cache: provider did not report recognizable hit/miss details");
            }

            appendCost(out, totalAll, "\nV4 Flash current provider estimate: ");
            out.append("\nRequests by purpose:");
            appendBucketLine(out, state, "priority", "Current priority");
            appendBucketLine(out, state, "priority_current", "  Current anchor");
            appendBucketLine(out, state, "priority_gap_rescue", "  Gap rescue");
            appendBucketLine(out, state, "background", "Background prefetch");
            appendBucketLine(out, state, "background_page", "  Page merge");
            appendBucketLine(out, state, "background_block", "  Block (fallback)");
            appendBucketLine(out, state, "background_alt", "  Boundary recheck");
            appendBucketLine(out, state, "unit_realtime", "Time anchor / current batch");
            appendBucketLine(out, state, "unit_background", "Time anchor / background batch");
            appendBucketLine(out, state, "core_semantic_ledger_v2", "Core: Semantic Ledger v2");
            appendBucketLine(out, state, "core_contextual_unit_v1", "Core: Contextual Unit v1");
            appendBucketLine(out, state, "display", "Display slicing");

            JSONObject totalsMetrics = metricsLocked(state);
            appendPageEfficiencyLine(out, state, "background_page", "Page merge");
            appendPageEfficiencyLine(out, state, "background_block", "Block (fallback)");
            appendPageEfficiencyLine(out, state, "background_alt", "Boundary recheck");

            JSONObject pageBucket = bucketLocked(state, "background_page");
            long pageRequests = value(pageBucket, "logical_requests");
            long pageBlocksCompleted = value(pageBucket, "blocks_completed");
            if (pageRequests > 0L) {
                out.append("\nPage merge progress: ").append(format(pageBlocksCompleted))
                        .append(" fixed blocks completed by ").append(format(pageRequests))
                        .append(" page requests (")
                        .append(oneDecimal(pageBlocksCompleted / (double) pageRequests))
                        .append(" blocks/request; <1.0 means repeated requests without progress)");
            }
            long altArmed = value(totalsMetrics, "background_alt_armed");
            if (altArmed > 0L) {
                JSONObject altBucket = bucketLocked(state, "background_alt");
                out.append("\nBoundary recheck: triggered ").append(format(altArmed))
                        .append(" times (at most once/page) · sent ")
                        .append(format(value(altBucket, "logical_requests")))
                        .append(" requests · recovered ").append(format(value(altBucket, "committed_atoms")))
                        .append(" atoms");
            }
            long pageFallback = value(totalsMetrics, "background_page_fallback_events");
            if (pageFallback > 0L) {
                out.append("\nPage fallback: ").append(format(pageFallback))
                        .append(" cached pages exceeded failure threshold; falling back to block requests");
            }
            long sunkPrompts = value(totalAll, "sunk_prompts");
            if (sunkPrompts > 0L) {
                out.append("\nSent but unread requests: ").append(format(sunkPrompts))
                        .append(" requests · ").append(format(value(totalAll, "sunk_prompt_bytes")))
                        .append(" bytes. Provider processing/billing is unknown; token and cost totals below exclude this usage,")
                        .append(" so actual spend cannot be determined.");
            }

            long localSuccess = value(totalsMetrics, "display_local_success");
            long localInconclusive = value(totalsMetrics, "display_local_inconclusive");
            long localWholeSentence = value(totalsMetrics, "display_local_whole_sentence");
            if (localSuccess + localInconclusive + localWholeSentence > 0L) {
                JSONObject displayBucket = bucketLocked(state, "display");
                out.append("\nDisplay slicing: local zero-token successes ").append(format(localSuccess)).append(" times");
                if (localWholeSentence > 0L) {
                    out.append(" · kept whole sentences ").append(format(localWholeSentence)).append(" times");
                }
                if (localInconclusive > 0L) {
                    out.append(" · handed off to AI ").append(format(localInconclusive)).append(" times")
                            .append(" (AI calls ").append(format(value(displayBucket, "attempts")))
                            .append(" · with usage ").append(format(value(displayBucket, "usage_responses")))
                            .append(" · failures ").append(format(value(displayBucket, "failures"))).append(")");
                }
            }

            appendBucketLine(out, state, "core_event_rebuild_r2", "Core: Event rebuild / " + RebuildProtocol.VERSION);
            long viewed = value(session, "viewed_ms");
            long sessionTokens = value(sessionAll, "total_tokens");
            out.append("\nCurrent video: viewed ").append(seconds1(viewed / 1000d)).append(" s")
                    .append(" · ").append(format(sessionTokens)).append(" tokens");
            if (viewed >= 5_000L) {
                double minutes = viewed / 60_000d;
                long perMinute = Math.round(sessionTokens / Math.max(0.001d, minutes));
                out.append(" · ").append(format(perMinute)).append(" tokens/viewing minute");
                long sessionCost = value(sessionAll, "cost_nano_cny");
                if (sessionCost > 0L) {
                    out.append(" · ¥").append(money(sessionCost / 1_000_000_000d / minutes)).append("/minute");
                }
            }

            long coreAtoms = value(metrics, "semantic_core_atoms");
            long windowAtoms = value(metrics, "semantic_window_atoms");
            long outsideAtoms = value(metrics, "semantic_outside_atoms");
            if (coreAtoms > 0L) {
                double exposure = (windowAtoms + outsideAtoms) / (double) coreAtoms;
                out.append("\nSemantic window: core ").append(format(coreAtoms))
                        .append(" atoms · exposed ").append(format(windowAtoms + outsideAtoms))
                        .append(" atoms · context exposure ratio ")
                        .append(String.format(Locale.US, "%.2fx", exposure));
            }
            long targetUnits = value(metrics, "unit_target_units");
            long contextUnits = value(metrics, "unit_context_units");
            long targetChars = value(metrics, "unit_target_chars");
            long contextChars = value(metrics, "unit_context_chars");
            if (targetUnits > 0L) {
                out.append("\nRequest blocks: target ").append(format(targetUnits))
                        .append(" blocks · read-only context ").append(format(contextUnits))
                        .append(" sections · context sections/block ")
                        .append(String.format(Locale.US, "%.2fx", contextUnits / (double) targetUnits));
                if (targetChars > 0L) {
                    out.append(" · character exposure ratio ")
                            .append(String.format(Locale.US, "%.2fx", contextChars / (double) targetChars));
                }
            }
            long cacheLookups = value(metrics, "unit_cache_lookups");
            if (cacheLookups > 0L) {
                out.append("\nRequest-block disk cache: lookups ").append(format(cacheLookups))
                        .append(" · hit blocks ").append(format(value(metrics, "unit_cache_hit_units")))
                        .append(" · missed units ").append(format(value(metrics, "unit_cache_miss_units")));
            }
            appendCoreRate(out, session, metrics, "semantic_ledger_v2", "legacy engine");
            appendCoreRate(out, session, metrics, "contextual_unit_v1", "Legacy compatibility engine");
            appendCoreRate(out, session, metrics, "event_rebuild_r2", "Event rebuild / " + RebuildProtocol.VERSION);
            long requestBytes = value(sessionAll, "request_bytes");
            long sessionAttempts = value(sessionAll, "attempts");
            if (sessionAttempts > 0L) {
                out.append("\nRequest body: total ").append(format(requestBytes)).append(" bytes")
                        .append(" · average ").append(format(requestBytes / sessionAttempts)).append(" bytes/API attempt");
            }
            long noUsage = value(totalAll, "responses_without_usage");
            if (noUsage > 0L) {
                out.append("\nNote: ").append(format(noUsage))
                        .append(" 2xx responses lacked usage; only request counts, not exact tokens, are known for these.");
            }
            return out.toString();
        }
    }

    private static void appendCoreRate(
            StringBuilder out,
            JSONObject session,
            JSONObject metrics,
            String core,
            String label
    ) {
        long viewed = value(metrics, "viewed_ms_" + core);
        JSONObject bucket = bucketLocked(session, "core_" + core);
        long tokens = value(bucket, "total_tokens");
        if (viewed <= 0L && tokens <= 0L) return;
        out.append("\n  ").append(label).append(": viewed ")
                .append(seconds1(viewed / 1000d)).append(" s · ")
                .append(format(tokens)).append(" tok");
        if (viewed >= 5_000L) {
            double minutes = viewed / 60_000d;
            out.append(" · ").append(format(Math.round(tokens / Math.max(0.001d, minutes))))
                    .append(" tok/viewing minute");
            long cost = value(bucket, "cost_nano_cny");
            if (cost > 0L) {
                out.append(" · ¥").append(money(cost / 1_000_000_000d / minutes)).append("/minute");
            }
        }
    }
    private static void appendFailureBreakdown(StringBuilder out, JSONObject bucket) {
        if (value(bucket, "failures") <= 0L) return;
        out.append("\nFailure breakdown: 429 ").append(format(value(bucket, "http_429_failures")))
                .append(" · 5xx ").append(format(value(bucket, "http_5xx_failures")))
                .append(" · other 4xx ").append(format(value(bucket, "http_4xx_failures")))
                .append(" · timeout ").append(format(value(bucket, "timeout_failures")))
                .append(" · network ").append(format(value(bucket, "network_failures")))
                .append(" · cancelled/interrupted ").append(format(value(bucket, "cancelled_failures")))
                .append(" · other ").append(format(
                        value(bucket, "other_failures") + value(bucket, "http_other_failures")
                ));
    }

    private static void appendBucketLine(StringBuilder out, JSONObject parent, String key, String label) {
        JSONObject bucket = bucketLocked(parent, key);
        long requests = value(bucket, "logical_requests"), attempts = value(bucket, "attempts");
        out.append("\n  ").append(label).append(": ")
                .append(format(requests)).append(requests == 1 ? " logical request / " : " logical requests / ")
                .append(format(attempts)).append(attempts == 1 ? " API call · " : " API calls · ")
                .append(format(value(bucket, "total_tokens"))).append(" tok");
        long focusMiss = value(bucket, "focus_miss_results");
        long deferred = value(bucket, "local_deferrals");
        if (focusMiss > 0L) out.append(" · focus misses ").append(format(focusMiss));
        if (deferred > 0L) out.append(" · local circuit breakers ").append(format(deferred));
        appendCost(out, bucket, " · ");
    }

    private static void appendPageEfficiencyLine(
            StringBuilder out, JSONObject parent, String key, String label
    ) {
        JSONObject bucket = bucketLocked(parent, key);
        long committed = value(bucket, "committed_atoms");
        if (committed <= 0L) return;
        long input = value(bucket, "prompt_tokens");
        long miss = value(bucket, "cache_miss_tokens");
        out.append("\n  ").append(label).append(" efficiency: added ").append(format(committed))
                .append(" core atoms · input per atom ").append(oneDecimal(input / (double) committed))
                .append(" tok · cache-miss input per atom ").append(oneDecimal(miss / (double) committed))
                .append(" tok");
    }

    private static void appendCost(StringBuilder out, JSONObject bucket, String prefix) {
        long nano = value(bucket, "cost_nano_cny");
        long priced = value(bucket, "priced_responses");
        if (priced <= 0L) return;
        out.append(prefix).append("¥").append(money(nano / 1_000_000_000d));
        long usage = value(bucket, "usage_responses");
        if (priced < usage) out.append(" (only responses priced by built-in V4 Flash rates)");
    }

    private static void incrementLogicalRequestLocked(Request request) {
        updateBucketsLocked(request, bucket -> add(bucket, "logical_requests", 1L));
    }

    private interface BucketMutation {
        void apply(JSONObject bucket);
    }

    private static void updateBucketsLocked(Request request, BucketMutation mutation) {
        if (request == null || mutation == null) return;
        JSONObject state = stateLocked();
        mutation.apply(bucketLocked(state, "all"));
        mutation.apply(bucketLocked(state, request.purpose));
        boolean hasDetail = request.detailBucket != null && !request.detailBucket.isEmpty();
        if (hasDetail) mutation.apply(bucketLocked(state, request.detailBucket));
        if (!request.coreBucket.isEmpty()) {
            mutation.apply(bucketLocked(state, request.coreBucket));
        }
        if (request.generation == generation) {
            JSONObject session = sessionLocked();
            mutation.apply(bucketLocked(session, "all"));
            mutation.apply(bucketLocked(session, request.purpose));
            if (hasDetail) mutation.apply(bucketLocked(session, request.detailBucket));
            if (!request.coreBucket.isEmpty()) {
                mutation.apply(bucketLocked(session, request.coreBucket));
            }
        }
    }

    private static JSONObject stateLocked() {
        if (memoryState != null) return memoryState;
        Context context = appContext;
        JSONObject loaded = null;
        if (context != null) {
            try {
                String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STATE, "");
                if (raw != null && !raw.trim().isEmpty()) loaded = new JSONObject(raw);
            } catch (Throwable ignored) {
            }
        }
        if (loaded == null || loaded.optInt("version", 0) != VERSION) loaded = freshState();
        memoryState = loaded;
        JSONObject session = loaded.optJSONObject("session");
        if (session == null) {
            generation++;
            session = freshSession(activeVideoId, generation);
            putQuiet(loaded, "session", session);
        } else {
            generation = Math.max(generation, session.optLong("generation", 0L));
            String restoredVideo = session.optString("video_id", "").trim();
            if (activeVideoId.isEmpty()) activeVideoId = restoredVideo;
            activeCore = normalizeCore(session.optString("active_core", activeCore));
        }
        return memoryState;
    }

    private static JSONObject freshState() {
        JSONObject state = new JSONObject();
        putQuiet(state, "version", VERSION);
        putQuiet(state, "cleared_at", System.currentTimeMillis());
        putQuiet(state, "viewed_ms", 0L);
        putQuiet(state, "metrics", new JSONObject());
        putQuiet(state, "buckets", freshBuckets());
        return state;
    }

    private static JSONObject freshSession(String videoId, long gen) {
        JSONObject session = new JSONObject();
        putQuiet(session, "generation", gen);
        putQuiet(session, "video_id", videoId == null ? "" : videoId);
        putQuiet(session, "model", "");
        putQuiet(session, "active_core", activeCore);
        putQuiet(session, "started_at", System.currentTimeMillis());
        putQuiet(session, "viewed_ms", 0L);
        putQuiet(session, "metrics", new JSONObject());
        putQuiet(session, "buckets", freshBuckets());
        return session;
    }

    private static JSONObject freshBuckets() {
        JSONObject buckets = new JSONObject();
        putQuiet(buckets, "all", new JSONObject());
        putQuiet(buckets, "priority", new JSONObject());
        putQuiet(buckets, "background", new JSONObject());
        putQuiet(buckets, "display", new JSONObject());
        return buckets;
    }

    private static JSONObject sessionLocked() {
        JSONObject state = stateLocked();
        JSONObject session = state.optJSONObject("session");
        if (session == null) {
            generation++;
            session = freshSession(activeVideoId, generation);
            putQuiet(state, "session", session);
        }
        return session;
    }

    private static JSONObject metricsLocked(JSONObject parent) {
        JSONObject metrics = parent.optJSONObject("metrics");
        if (metrics == null) {
            metrics = new JSONObject();
            putQuiet(parent, "metrics", metrics);
        }
        return metrics;
    }

    private static JSONObject bucketLocked(JSONObject parent, String key) {
        JSONObject buckets = parent.optJSONObject("buckets");
        if (buckets == null) {
            buckets = freshBuckets();
            putQuiet(parent, "buckets", buckets);
        }
        JSONObject bucket = buckets.optJSONObject(key);
        if (bucket == null) {
            bucket = new JSONObject();
            putQuiet(buckets, key, bucket);
        }
        return bucket;
    }

    private static void noteModelLocked(String model) {
        if (model == null || model.trim().isEmpty()) return;
        putQuiet(sessionLocked(), "model", model.trim());
    }

    private static long priceNanoCny(
            String model,
            String baseUrl,
            long hit,
            long miss,
            long completion,
            boolean cacheKnown
    ) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        String base = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        if (!m.contains("deepseek-v4-flash") || !cacheKnown) return -1L;

        long hitNanoPerToken;
        if (base.contains("api.deepseek.com")) {
            // DeepSeek official: cached input ¥0.02/M, uncached input ¥1/M, output ¥2/M.
            hitNanoPerToken = 20L;
        } else if (isAlibabaBase(base)) {
            // Alibaba Cloud Model Studio DeepSeek V4 Flash: cached input ¥0.20/M,
            // uncached input ¥1/M, output ¥2/M for the supported compatible endpoint.
            hitNanoPerToken = 200L;
        } else {
            return -1L;
        }
        return safeMultiply(hit, hitNanoPerToken) +
                safeMultiply(miss, 1_000L) +
                safeMultiply(completion, 2_000L);
    }

    private static boolean isAlibabaBase(String base) {
        if (base == null) return false;
        String value = base.toLowerCase(Locale.ROOT);
        return value.contains("dashscope.aliyuncs.com") || value.contains("maas.aliyuncs.com");
    }

    private static String normalizeCore(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return "event_rebuild_r2".equals(value) || "contextual_unit_v1".equals(value) ? value : "semantic_ledger_v2";
    }

    private static String coreBucket(String core) {
        String value = normalizeCore(core);
        return "core_" + value;
    }
    private static boolean isHttp5xx(String clean) {
        int code = httpCode(clean);
        return code >= 500 && code <= 599;
    }

    private static boolean isHttp4xx(String clean) {
        int code = httpCode(clean);
        return code >= 400 && code <= 499 && code != 429;
    }

    private static int httpCode(String clean) {
        if (clean == null) return -1;
        int underscore = clean.lastIndexOf('_');
        if (underscore < 0 || underscore + 1 >= clean.length()) return -1;
        try {
            return Integer.parseInt(clean.substring(underscore + 1));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static long safeMultiply(long value, long multiplier) {
        if (value <= 0L || multiplier <= 0L) return 0L;
        if (value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
        return value * multiplier;
    }

    private static void persistLocked() {
        Context context = appContext;
        if (context == null || memoryState == null) return;
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putString(STATE, memoryState.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void add(JSONObject object, String key, long delta) {
        if (object == null || delta == 0L) return;
        long old = object.optLong(key, 0L);
        long next;
        if (delta > 0L && old > Long.MAX_VALUE - delta) next = Long.MAX_VALUE;
        else if (delta < 0L && old < Long.MIN_VALUE - delta) next = Long.MIN_VALUE;
        else next = old + delta;
        putQuiet(object, key, next);
    }

    private static void putQuiet(JSONObject object, String key, Object value) {
        if (object == null || key == null) return;
        try {
            object.put(key, value);
        } catch (Throwable ignored) {
        }
    }

    private static long value(JSONObject object, String key) {
        return object == null ? 0L : nonNegative(object.optLong(key, 0L));
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static String format(long value) {
        return String.format(Locale.US, "%,d", Math.max(0L, value));
    }

    private static String percent(long numerator, long denominator) {
        if (denominator <= 0L) return "0.0%";
        return String.format(Locale.US, "%.1f%%", numerator * 100d / denominator);
    }

    private static String money(double value) {
        if (value < 0.01d) return String.format(Locale.US, "%.5f", value);
        if (value < 1d) return String.format(Locale.US, "%.4f", value);
        return String.format(Locale.US, "%.3f", value);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String seconds1(double seconds) {
        if (seconds < 10d) return String.format(Locale.US, "%.1f", seconds);
        return String.format(Locale.US, "%.0f", seconds);
    }

    static final class Request {
        final long generation;
        final String purpose;
        final String model;
        final String baseUrl;
        final String detailBucket;
        final String coreBucket;
        int attempts;

        Request(
                long generation,
                String purpose,
                String model,
                String baseUrl,
                String detailBucket,
                String core
        ) {
            this.generation = generation;
            this.purpose = purpose == null || purpose.isEmpty() ? "background" : purpose;
            this.model = model == null ? "" : model;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.detailBucket = detailBucket == null ? "" : detailBucket;
            this.coreBucket = coreBucket(core);
        }
    }
}
