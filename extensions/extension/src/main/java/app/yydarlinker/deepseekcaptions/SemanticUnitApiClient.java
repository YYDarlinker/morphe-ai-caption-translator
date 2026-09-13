package app.yydarlinker.deepseekcaptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Centered semantic-unit planner.
 *
 * <p>Unlike the legacy prefix planner, the scheduling core is not a commit frontier. The model sees
 * generous source context around a target core and may return any complete sentence/clause that
 * overlaps the core, even when that unit starts before or ends after the core. A difficult boundary
 * therefore cannot block later complete units. Translation is still performed only after the model
 * has recovered a closed source-language unit, preserving the dev9 quality contract.</p>
 */
final class SemanticUnitApiClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_OUTPUT_TOKENS = 3_072;
    private static final int MAX_OUTPUT_TOKENS_PAGE = 7_168;
    private static final int CONNECT_TIMEOUT_MS = 3_200;
    private static final int READ_TIMEOUT_MS = 14_000;
    private static final long PRIORITY_TIMEOUT_MS = 10_000L;
    private static final long BACKGROUND_TIMEOUT_MS = 16_000L;
    private static final long BACKGROUND_PAGE_TIMEOUT_MS = 26_000L;

    static final String LANE_PRIORITY_CURRENT = "priority_current";
    static final String LANE_PRIORITY_GAP_RESCUE = "priority_gap_rescue";
    static final String LANE_BACKGROUND_PAGE = "background_page";
    static final String LANE_BACKGROUND_BLOCK = "background_block";
    static final String LANE_BACKGROUND_ALT = "background_alt";

    private SemanticUnitApiClient() {}

    static Result plan(
            List<SourceAtomTimeline.Atom> atoms,
            int coreFrom,
            int coreTo,
            int focusIndex,
            List<String> contextBefore,
            List<String> contextAfter,
            DeepSeekConfig.Snapshot config,
            TargetLanguage targetLanguage,
            DeepSeekApiClient.RequestControl control,
            boolean priority,
            String lane
    ) throws Exception {
        if (atoms == null || atoms.isEmpty()) return Result.EMPTY;
        if (!config.ready()) throw new IllegalStateException("AI 字幕翻译尚未启用或没有 API Key");
        String bucket = lane == null || lane.isEmpty()
                ? (priority ? LANE_PRIORITY_CURRENT : LANE_BACKGROUND_BLOCK)
                : lane;
        boolean pageMerge = LANE_BACKGROUND_PAGE.equals(bucket);

        // Cost-only gate: wait before the timeout budget and Token audit begin. Priority never enters
        // this path, and a paused background request stays cancellable without opening an HTTP call.
        if (!priority) BackgroundPauseGovernor.awaitBackgroundPermit(control);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                priority ? PRIORITY_TIMEOUT_MS : (pageMerge ? BACKGROUND_PAGE_TIMEOUT_MS : BACKGROUND_TIMEOUT_MS)
        );
        ensureActive(deadline, control);

        TargetLanguage target = targetLanguage == null
                ? TargetLanguage.SIMPLIFIED_CHINESE
                : targetLanguage;
        int count = atoms.size();
        int coreStart = Math.max(0, Math.min(count - 1, coreFrom));
        int coreEnd = Math.max(coreStart + 1, Math.min(count, coreTo));
        int focus = Math.max(0, Math.min(count - 1, focusIndex));
        JSONArray source = new JSONArray();
        long base = atoms.get(0).startMs;
        int sourceChars = 0;
        for (int i = 0; i < atoms.size(); i++) {
            SourceAtomTimeline.Atom atom = atoms.get(i);
            sourceChars += atom.text == null ? 0 : atom.text.length();
            long gapAfter = 0L;
            if (i + 1 < atoms.size()) gapAfter = Math.max(0L, atoms.get(i + 1).startMs - atom.endMs);
            // Lossless payload slimming: omit fields that carry their default value. A YouTube ASR
            // track reports 0% native word timing, so "timing":"estimated" used to repeat on every
            // atom (21 of ~98 bytes) while telling the model nothing it could act on. Likewise most
            // consecutive word atoms are contiguous, so gap_after_ms is 0 far more often than not.
            // The system prompt states both defaults, so an absent field is unambiguous.
            JSONObject entry = new JSONObject()
                    .put("i", i)
                    .put("text", atom.text == null ? "" : atom.text)
                    .put("start_ms", Math.max(0L, atom.startMs - base))
                    .put("end_ms", Math.max(1L, atom.endMs - base));
            if (gapAfter > 0L) entry.put("gap_after_ms", gapAfter);
            if (atom.precise) entry.put("timing", "native");
            source.put(entry);
        }

        String payloadText;
        if (priority) {
            // Keep the proven current-cue request shape byte-for-byte equivalent to dev9. Priority
            // latency and semantic behavior matter more than cache reuse on this rare lane.
            JSONObject payload = new JSONObject()
                    .put("target_language", target.code)
                    .put("core_from", coreStart)
                    .put("core_to_exclusive", coreEnd)
                    .put("focus_index", focus)
                    .put("mode", "current_priority")
                    .put("atoms", source);
            if (contextBefore != null && !contextBefore.isEmpty()) {
                payload.put("context_before", new JSONArray(contextBefore));
            }
            if (contextAfter != null && !contextAfter.isEmpty()) {
                payload.put("context_after", new JSONArray(contextAfter));
            }
            payloadText = payload.toString();
        } else {
            // Background cache layout: keep the large source page at the beginning and append only
            // the tiny scheduling task at the end. Adjacent fixed blocks now share an identical page,
            // so prefix-cache providers can reuse the expensive atom/context portion without changing
            // a single source token or weakening the semantic-quality contract.
            JSONObject stable = new JSONObject()
                    .put("target_language", target.code)
                    .put("atoms", source);
            if (contextBefore != null && !contextBefore.isEmpty()) {
                stable.put("context_before", new JSONArray(contextBefore));
            }
            if (contextAfter != null && !contextAfter.isEmpty()) {
                stable.put("context_after", new JSONArray(contextAfter));
            }
            String prefix = stable.toString();
            StringBuilder cacheAware = new StringBuilder(prefix.length() + 128);
            cacheAware.append(prefix, 0, Math.max(0, prefix.length() - 1));
            cacheAware.append(",\"core_from\":").append(coreStart)
                    .append(",\"core_to_exclusive\":").append(coreEnd)
                    .append(",\"focus_index\":").append(focus)
                    .append(",\"mode\":\"")
                    .append(pageMerge ? "background_inventory_page" : "background_inventory")
                    .append("\"}");
            payloadText = cacheAware.toString();
        }

        String systemPrompt =
                "你是专业的 YouTube 字幕翻译与源语言语义分段器。输入 atoms 是连续口语被拆成的细粒度时间原子，" +
                "atom 边界、YouTube cue 边界、estimated timing、core 边界都绝不等于句子边界。" +
                "为压缩传输，atom 省略了取默认值的字段：没有 gap_after_ms 表示与下一个 atom 紧邻（间隔 0 ms）；" +
                "没有 timing 表示该时间戳是 estimated（推算值，不可当作句子边界依据），timing=\"native\" 才是原生精确时间。" +
                "你的工作顺序必须是：第一，先在源语言中恢复连续话语并判断真正完整的句子/完整分句；" +
                "第二，只为源语言已经闭合、删除后文也不会显得语法没说完的单元建立 unit；" +
                "第三，再把该完整源语义单元整体翻译为自然、准确、连贯的" + target.promptLabel() + "。" +
                "core_from..core_to_exclusive 只是客户端当前希望补齐的调度区域，绝不是语言边界。" +
                "凡是与 core 有交集的完整语义单元都可以返回：unit 可以从 core 之前开始，也可以越过 core 之后结束。" +
                "绝对不要为了 core 边缘、目标语言字数、两行显示、窗口边缘、短暂停顿或原 cue 切换而提前断句。" +
                "如果某句话需要 atoms 窗口之外的内容才能闭合，就整句不要返回，不能截断；客户端会用更大的重叠窗口再次请求。" +
                "一个 unit 可以长；语言完整性永远高于显示长度。禁止产生需要下一 unit 才能补全的半句话。" +
                "尤其不能让 unit 结束在未完成的主谓结构、系词/助动词/情态动词之后、缺少宾语或补语的谓语之后、冠词/介词/连词之后、" +
                "未结束的定语/从句/比较结构之后，或类似 ‘this phone is …’ ‘it has …’ ‘I first felt …’ ‘although …’ ‘the whole …’ 这种仍依赖后文的结构。" +
                "每个 unit 必须覆盖连续 atom 索引 from..to，from/to 只能由源语言语义决定；客户端自己使用 atoms[from].start 到 atoms[to].end，" +
                "所以你绝对不要生成或修改时间戳。" +
                "units 按 from 升序，不得互相重叠、重复。与 core 无关的远处单元不要返回。" +
                "允许窗口边缘留空，也允许你对确实拿不准的边界不返回；但不能因为前面一个边界拿不准，就阻止后面已经完整闭合的 unit 返回。" +
                "priority 模式下，若 focus_index 所在句子能在本窗口内完整恢复，必须优先返回覆盖 focus_index 的完整 unit，仍然不得牺牲完整性。" +
                "每个 unit 的 boundary 只能是 sentence 或 clause：sentence 表示完整句，clause 表示无需下一页补语法成分、可独立阅读的完整分句。" +
                "在输出前逐个自检：如果删除相邻 unit 后当前 unit 会显得语法没说完，就不能提交它。" +
                "unit.text 必须忠实翻译对应 from..to 的全部源内容，不删减、不概括、不提前翻译窗口外内容，不增加源文没有的信息；" +
                "应恢复自然标点，最终 unit 不要以逗号、顿号或冒号作为人为截断符。" +
                "context_before/context_after 只用于理解上下文，不属于 atoms，也不能被翻译进 unit。" +
                "翻译要求：" + config.prompt +
                " 只返回合法 JSON，严格格式：{\"units\":[{\"from\":3,\"to\":17,\"text\":\"...\",\"boundary\":\"sentence\"}]}。" +
                "不要输出 Markdown、解释、思考过程、pending_from、时间戳或额外字段。";

        int coreAtomCount = coreEnd - coreStart;
        int outputCap = pageMerge ? MAX_OUTPUT_TOKENS_PAGE : MAX_OUTPUT_TOKENS;
        int outputTokens = Math.max(1_024, Math.min(
                outputCap,
                sourceChars * 2 + atoms.size() * 60 + coreAtomCount * 40 + 512
        ));
        JSONObject request = new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("temperature", 0.0)
                .put("response_format", new JSONObject().put("type", "json_object"))
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(new JSONObject().put("role", "user").put("content", payloadText)));

        boolean dashScopeQwen = isDashScope(config.baseUrl) && isQwenModel(config.model);
        if (isDeepSeekModel(config.model)) {
            request.put("thinking", new JSONObject().put("type", "disabled"));
        } else if (dashScopeQwen) {
            request.put("enable_thinking", false);
        }
        if (!dashScopeQwen) request.put("max_tokens", outputTokens);

        int outsideAtoms = (contextBefore == null ? 0 : contextBefore.size()) +
                (contextAfter == null ? 0 : contextAfter.size());
        TokenCostAudit.Request audit = TokenCostAudit.beginSemantic(
                config,
                priority,
                atoms.size(),
                coreAtomCount,
                outsideAtoms,
                sourceChars,
                bucket
        );

        boolean thinkingFallback = request.has("thinking") || request.has("enable_thinking");
        Exception last = null;
        int attempts = priority ? 1 : 2;
        for (int attempt = 0; attempt < attempts; attempt++) {
            ensureActive(deadline, control);
            try {
                String content = post(config, request, deadline, control, audit);
                Result parsed = parse(content, atoms, coreStart, coreEnd);
                boolean focusCovered = coversFocus(parsed, focus);
                TokenCostAudit.recordSemanticOutcome(audit, parsed.units.size(), focusCovered);
                return parsed;
            } catch (IllegalStateException rejected) {
                if (thinkingFallback && unsupportedThinking(rejected)) {
                    thinkingFallback = false;
                    request.remove("thinking");
                    request.remove("enable_thinking");
                    attempt--;
                    continue;
                }
                throw rejected;
            } catch (RetryableException | PlanFormatException retryable) {
                last = retryable;
                if (attempt + 1 < attempts && remainingMillis(deadline) > 700L) Thread.sleep(220L);
            }
        }
        throw last == null ? new IllegalStateException("AI 字幕语义规划失败") : last;
    }

    private static boolean coversFocus(Result result, int focus) {
        if (result == null || result.units == null) return false;
        for (Unit unit : result.units) {
            if (unit != null && unit.from <= focus && focus <= unit.to) return true;
        }
        return false;
    }

    private static Result parse(
            String content,
            List<SourceAtomTimeline.Atom> atoms,
            int coreFrom,
            int coreTo
    ) throws Exception {
        String json = content == null ? "" : content.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).trim();
            }
        }

        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Throwable malformed) {
            throw new PlanFormatException("API 返回的语义单元 JSON 无法解析", malformed);
        }
        JSONArray values = root.optJSONArray("units");
        if (values == null) throw new PlanFormatException("API 返回缺少 units");

        int count = atoms.size();
        List<Unit> candidates = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) continue;
            int from = value.optInt("from", -1);
            int to = value.optInt("to", -1);
            String text = value.optString("text", "").trim();
            String boundary = value.optString("boundary", "").trim().toLowerCase(Locale.ROOT);
            if (from < 0 || to < from || to >= count || text.isEmpty()) continue;
            if (to < coreFrom || from >= coreTo) continue;
            if (!("sentence".equals(boundary) || "clause".equals(boundary))) continue;
            if (unsafeSourceBoundary(atoms, from, to, count)) continue;
            if (endsWithSoftTargetPunctuation(text)) continue;
            candidates.add(new Unit(from, to, text, boundary));
        }

        candidates.sort(Comparator.comparingInt(unit -> unit.from));
        List<Unit> accepted = new ArrayList<>();
        int previousTo = -1;
        for (Unit unit : candidates) {
            if (unit.from <= previousTo) continue;
            accepted.add(unit);
            previousTo = unit.to;
        }
        return new Result(Collections.unmodifiableList(accepted));
    }

    /** Keep the dev9 source-boundary quality gate as a rejector, never as a local segmenter. */
    private static boolean unsafeSourceBoundary(
            List<SourceAtomTimeline.Atom> atoms,
            int from,
            int to,
            int available
    ) {
        if (to < from || to >= atoms.size()) return true;
        String source = SourceAtomTimeline.join(atoms, from, to).trim();
        if (source.isEmpty()) return true;
        if (endsStrong(source)) return false;

        String last = lastLexical(atoms.get(to).text);
        if (last.isEmpty()) return false;
        switch (last) {
            case "a": case "an": case "the": case "this": case "that": case "these": case "those":
            case "and": case "or": case "but": case "because": case "although": case "though": case "while":
            case "if": case "unless": case "whether": case "than": case "as":
            case "of": case "to": case "for": case "with": case "from": case "by": case "at": case "in":
            case "on": case "into": case "onto": case "about": case "through": case "between": case "without":
            case "is": case "am": case "are": case "was": case "were": case "be": case "been": case "being":
            case "has": case "have": case "had": case "do": case "does": case "did":
            case "can": case "could": case "will": case "would": case "shall": case "should": case "may":
            case "might": case "must": case "whose": case "which": case "who": case "whom":
                return true;
            default:
                break;
        }

        if (to + 1 < atoms.size() && to + 1 < available) {
            String next = firstLexical(atoms.get(to + 1).text);
            if (("feel".equals(last) || "felt".equals(last) || "seem".equals(last) || "seemed".equals(last) ||
                    "think".equals(last) || "thought".equals(last) || "say".equals(last) || "said".equals(last)) &&
                    ("that".equals(next) || "like".equals(next) || "as".equals(next) || "how".equals(next) ||
                            "what".equals(next))) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsStrong(String text) {
        if (text == null || text.isEmpty()) return false;
        int i = text.length() - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        if (i < 0) return false;
        char c = text.charAt(i);
        return c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '…';
    }

    private static boolean endsWithSoftTargetPunctuation(String text) {
        if (text == null || text.isEmpty()) return true;
        int i = text.length() - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        if (i < 0) return true;
        char c = text.charAt(i);
        return c == ',' || c == '，' || c == '、' || c == ':' || c == '：';
    }

    private static String lastLexical(String value) {
        if (value == null) return "";
        String clean = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}'’]+$", "").trim();
        int space = Math.max(clean.lastIndexOf(' '), clean.lastIndexOf('\t'));
        return (space >= 0 ? clean.substring(space + 1) : clean).replace('’', '\'');
    }

    private static String firstLexical(String value) {
        if (value == null) return "";
        String clean = value.toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}'’]+", "").trim();
        int space = clean.indexOf(' ');
        return (space >= 0 ? clean.substring(0, space) : clean).replace('’', '\'');
    }

    private static String post(
            DeepSeekConfig.Snapshot config,
            JSONObject request,
            long deadline,
            DeepSeekApiClient.RequestControl control,
            TokenCostAudit.Request audit
    ) throws Exception {
        HttpURLConnection connection = null;
        boolean consumed = false;
        int auditAttempt = 0;
        boolean auditRecorded = false;
        int sentBodyBytes = 0;
        try {
            ensureActive(deadline, control);
            connection = (HttpURLConnection) new URL(completionUrl(config.baseUrl)).openConnection();
            if (control != null) control.onConnection(connection);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(boundedTimeout(deadline, CONNECT_TIMEOUT_MS));
            connection.setReadTimeout(boundedTimeout(deadline, READ_TIMEOUT_MS));
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "keep-alive");

            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            auditAttempt = TokenCostAudit.beginAttempt(audit, body.length);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            // The provider now owns this prompt and will bill it. Tell the scheduler so it stops
            // preempting work whose cost is already sunk.
            sentBodyBytes = body.length;
            if (control != null) control.onRequestBodySent();

            ensureActive(deadline, control);
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = stream == null ? "" : new String(
                    readFully(stream, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8
            );
            consumed = true;
            if (status == 408 || status == 409 || status == 425 || status == 429 || status >= 500) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "http_" + status);
                auditRecorded = true;
                throw new RetryableException("API HTTP " + status + ": " + abbreviate(response));
            }
            if (status < 200 || status >= 300) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "http_" + status);
                auditRecorded = true;
                throw new IllegalStateException("API HTTP " + status + ": " + abbreviate(response));
            }

            JSONObject root = new JSONObject(response);
            TokenCostAudit.recordResponse(audit, auditAttempt, root);
            auditRecorded = true;
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new RetryableException("API 返回中没有 choices");
            JSONObject choice = choices.optJSONObject(0);
            String finish = choice == null ? "" : choice.optString("finish_reason", "");
            if ("length".equals(finish)) throw new PlanFormatException("API 输出达到 max_tokens，语义单元 JSON 被截断");
            if ("insufficient_system_resource".equals(finish)) throw new RetryableException("API 资源不足");
            if ("content_filter".equals(finish)) throw new IllegalStateException("API 内容过滤中止了字幕翻译");
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "").trim();
            if (content.isEmpty()) throw new RetryableException("API 返回了空 content");
            return content;
        } catch (SocketTimeoutException timeout) {
            if (auditAttempt > 0 && !auditRecorded) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "timeout");
                TokenCostAudit.recordSunkPrompt(audit, sentBodyBytes);
                auditRecorded = true;
            }
            ensureActive(deadline, control);
            throw new RetryableException("API 网络超时", timeout);
        } catch (IOException network) {
            if (auditAttempt > 0 && !auditRecorded) {
                // A disconnect triggered by our own cancellation surfaces here as an IOException.
                // Recording it as a network fault hid real preemption behaviour in dev15 reports.
                boolean cancelled = Thread.currentThread().isInterrupted() ||
                        (control != null && control.isCancelled());
                TokenCostAudit.recordFailure(audit, auditAttempt, cancelled ? "cancelled" : "network");
                TokenCostAudit.recordSunkPrompt(audit, sentBodyBytes);
                auditRecorded = true;
            }
            ensureActive(deadline, control);
            throw new RetryableException("API 网络错误: " + abbreviate(network.getMessage()), network);
        } finally {
            if (auditAttempt > 0 && !auditRecorded) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "cancelled_or_exception");
                TokenCostAudit.recordSunkPrompt(audit, sentBodyBytes);
            }
            if (connection != null && !consumed) connection.disconnect();
            if (control != null) control.onConnection(null);
        }
    }

    private static byte[] readFully(InputStream stream, int maxBytes) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (output.size() + read > maxBytes) throw new IllegalStateException("网络响应过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void ensureActive(long deadline, DeepSeekApiClient.RequestControl control) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("字幕翻译已取消");
        if (control != null && control.isCancelled()) throw new InterruptedException("字幕翻译已重新调度");
        if (remainingMillis(deadline) <= 0L) throw new RetryableException("AI 字幕请求超时");
    }

    private static int boundedTimeout(long deadline, int max) throws Exception {
        long left = remainingMillis(deadline);
        if (left <= 0L) throw new RetryableException("AI 字幕请求超时");
        return (int) Math.max(1L, Math.min((long) max, left));
    }

    private static long remainingMillis(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0L) return 0L;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static String completionUrl(String configured) {
        String value = configured == null ? "" : configured.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/models")) value = value.substring(0, value.length() - "/models".length());
        if (value.endsWith("/chat/completions")) return value;
        if (value.endsWith("/v1")) return value + "/chat/completions";
        return value + "/chat/completions";
    }

    private static boolean isDeepSeekModel(String model) {
        String value = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return value.contains("deepseek");
    }

    private static boolean isQwenModel(String model) {
        String value = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return value.contains("qwen") || value.contains("tongyi");
    }

    private static boolean isDashScope(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        return value.contains("dashscope.aliyuncs.com") || value.contains("maas.aliyuncs.com");
    }

    private static boolean unsupportedThinking(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return (message.contains("thinking") || message.contains("enable_thinking")) && (
                message.contains("unknown") || message.contains("unsupported") ||
                message.contains("unrecognized") || message.contains("not permitted") ||
                message.contains("invalid parameter") || message.contains("不支持") ||
                message.contains("未知")
        );
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        String one = value.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 280 ? one : one.substring(0, 280);
    }

    static final class Unit {
        final int from;
        final int to;
        final String text;
        final String boundary;

        Unit(int from, int to, String text, String boundary) {
            this.from = from;
            this.to = to;
            this.text = text;
            this.boundary = boundary;
        }
    }

    static final class Result {
        static final Result EMPTY = new Result(Collections.emptyList());
        final List<Unit> units;

        Result(List<Unit> units) {
            this.units = units;
        }
    }

    private static final class RetryableException extends Exception {
        private static final long serialVersionUID = 1L;
        RetryableException(String message) { super(message); }
        RetryableException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class PlanFormatException extends Exception {
        private static final long serialVersionUID = 1L;
        PlanFormatException(String message) { super(message); }
        PlanFormatException(String message, Throwable cause) { super(message, cause); }
    }
}
