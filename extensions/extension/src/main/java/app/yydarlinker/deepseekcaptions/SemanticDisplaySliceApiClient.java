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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * AI-assisted presentation slicer for an already-approved canonical translation.
 *
 * <p>This class never retranslates or changes semantic content. The canonical translation comes
 * from {@link SemanticUnitApiClient} after the full source-language unit has passed the dev9 quality
 * gate. The model may only choose target-text cut positions and matching source-atom boundaries.
 * Concatenating every returned slice without a separator must reproduce the canonical translation
 * byte-for-byte; otherwise the result is rejected and the original whole-unit display is retained.</p>
 *
 * <p>Display slicing is strictly lower priority than semantic translation. Before opening a network
 * request it gives the semantic scheduler a short beat to claim the API, and every slice connection
 * is registered with the transport recovery shim so a new video or urgent current-cue request can
 * disconnect it immediately. Since dev15 the caller always tries the zero-token
 * {@link LocalDisplaySliceFallback} first and only calls this class when the local planner cannot
 * safely split the unit, making the AI path a minority-case fallback rather than the default.
 * Translation content is never changed by this policy.</p>
 */
final class SemanticDisplaySliceApiClient {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_OUTPUT_TOKENS = 1_536;
    private static final int CONNECT_TIMEOUT_MS = 3_200;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final long REQUEST_TIMEOUT_MS = 11_000L;
    private static final long SEMANTIC_PRIORITY_GRACE_MS = 140L;

    private SemanticDisplaySliceApiClient() {}

    static Result slice(
            List<SourceAtomTimeline.Atom> atoms,
            String canonicalTranslation,
            DeepSeekConfig.Snapshot config,
            TargetLanguage targetLanguage,
            DeepSeekApiClient.RequestControl control
    ) throws Exception {
        if (atoms == null || atoms.isEmpty()) return Result.EMPTY;
        String canonical = canonicalTranslation == null ? "" : canonicalTranslation.trim();
        if (canonical.isEmpty()) return Result.EMPTY;
        if (!config.ready()) throw new IllegalStateException("AI 字幕翻译尚未启用或没有 API Key");

        // finishPlan queues display refinement just before it schedules the next semantic request.
        // A tiny grace period lets that higher-priority scheduler claim the lane first without any
        // user-visible delay; if it does, keep the already-approved whole canonical unit instead.
        if (control != null && control.isCancelled()) throw new InterruptedException("字幕显示切片已重新调度");
        Thread.sleep(SEMANTIC_PRIORITY_GRACE_MS);
        if (control != null && control.isCancelled()) throw new InterruptedException("字幕显示切片已重新调度");
        if (SemanticLedgerRequestRecovery.semanticRequestBusy()) return Result.EMPTY;

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_TIMEOUT_MS);
        ensureActive(deadline, control);
        TargetLanguage target = targetLanguage == null
                ? TargetLanguage.SIMPLIFIED_CHINESE
                : targetLanguage;

        JSONArray source = new JSONArray();
        long base = atoms.get(0).startMs;
        for (int i = 0; i < atoms.size(); i++) {
            SourceAtomTimeline.Atom atom = atoms.get(i);
            source.put(new JSONObject()
                    .put("i", i)
                    .put("text", atom.text == null ? "" : atom.text)
                    .put("start_ms", Math.max(0L, atom.startMs - base))
                    .put("end_ms", Math.max(1L, atom.endMs - base)));
        }

        JSONObject payload = new JSONObject()
                .put("target_language", target.code)
                .put("canonical_translation", canonical)
                .put("atoms", source);

        String systemPrompt =
                "你是字幕显示节奏规划器，不是翻译器。canonical_translation 已经由完整源语义单元整体翻译并通过质量检查，" +
                "它是不可修改的最终译文。你的唯一任务是为了视频观看节奏，把这条最终译文切成若干显示片段，并为每个片段选择连续的源 atom 时间范围。" +
                "绝对禁止重译、改写、润色、增删、交换或重复 canonical_translation 中任何字符。" +
                "最重要的硬规则：按顺序把所有 slices[].text 直接拼接，中间不添加任何字符，结果必须与 canonical_translation 完全逐字相同。" +
                "slices 必须从 atom 0 开始连续覆盖到最后一个 atom，不得跳号、重叠或遗漏；每个 slice 的 from..to 必须连续。" +
                "优先让单个显示片段约 1.8～4.2 秒；超过 5.2 秒时应尽量再拆，除非找不到自然且与源语义对应的显示切点。" +
                "对于 6～10 秒的长句，通常优先规划为 2～3 个显示片段；不要为了追求时长而切断目标语言中的词、专有名词、数字单位或明显不可分短语。" +
                "显示切点不等于新的翻译语义边界：整句翻译质量已经由 canonical_translation 保证，片段只负责阅读节奏。" +
                "可以利用标点、自然短语、从句内部的可读停顿以及源 atom timing 来选择切点，但不能改变任何译文字词。" +
                "如果无法可靠拆分，就返回一个覆盖全部 atoms 的单一 slice，text 必须等于 canonical_translation。" +
                "只返回合法 JSON：{\"slices\":[{\"from\":0,\"to\":8,\"text\":\"原译文的第一段原样字符\"},{\"from\":9,\"to\":17,\"text\":\"剩余原样字符\"}]}。" +
                "不要输出 Markdown、解释、时间戳或额外字段。";

        JSONObject request = new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("temperature", 0.0)
                .put("response_format", new JSONObject().put("type", "json_object"))
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(new JSONObject().put("role", "user").put("content", payload.toString())));

        boolean dashScopeQwen = isDashScope(config.baseUrl) && isQwenModel(config.model);
        if (isDeepSeekModel(config.model)) {
            request.put("thinking", new JSONObject().put("type", "disabled"));
        } else if (dashScopeQwen) {
            request.put("enable_thinking", false);
        }
        if (!dashScopeQwen) request.put("max_tokens", MAX_OUTPUT_TOKENS);

        TokenCostAudit.Request audit = TokenCostAudit.beginDisplay(config, atoms.size(), canonical.length());
        boolean thinkingFallback = request.has("thinking") || request.has("enable_thinking");
        ensureActive(deadline, control);
        if (SemanticLedgerRequestRecovery.semanticRequestBusy()) return Result.EMPTY;
        try {
            String content = post(config, request, deadline, control, audit);
            return parse(content, atoms.size(), canonical);
        } catch (IllegalStateException rejected) {
            if (thinkingFallback && unsupportedThinking(rejected)) {
                request.remove("thinking");
                request.remove("enable_thinking");
                ensureActive(deadline, control);
                if (SemanticLedgerRequestRecovery.semanticRequestBusy()) return Result.EMPTY;
                String content = post(config, request, deadline, control, audit);
                return parse(content, atoms.size(), canonical);
            }
            throw rejected;
        }
    }

    private static Result parse(String content, int atomCount, String canonical) throws Exception {
        String json = content == null ? "" : content.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).trim();
            }
        }

        final JSONArray values;
        try {
            values = new JSONObject(json).getJSONArray("slices");
        } catch (Throwable malformed) {
            throw new SliceFormatException("API 返回的显示切片 JSON 无法解析", malformed);
        }
        if (values.length() == 0) throw new SliceFormatException("API 返回空显示切片");

        List<Slice> out = new ArrayList<>();
        StringBuilder joined = new StringBuilder(canonical.length() + 16);
        int expectedFrom = 0;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) throw new SliceFormatException("显示切片不是对象: " + i);
            int from = value.optInt("from", -1);
            int to = value.optInt("to", -1);
            String text = value.has("text") ? value.optString("text", null) : null;
            if (from != expectedFrom || to < from || to >= atomCount || text == null || text.trim().isEmpty()) {
                throw new SliceFormatException("显示切片范围或文本无效: " + i);
            }
            out.add(new Slice(from, to, text));
            joined.append(text);
            expectedFrom = to + 1;
        }
        if (expectedFrom != atomCount) {
            throw new SliceFormatException("显示切片没有完整覆盖源 atom: " + expectedFrom + "/" + atomCount);
        }
        if (!canonical.equals(joined.toString())) {
            throw new SliceFormatException("显示切片改变了 canonical translation，已拒绝应用");
        }
        return new Result(Collections.unmodifiableList(out));
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
        try {
            ensureActive(deadline, control);
            connection = (HttpURLConnection) new URL(completionUrl(config.baseUrl)).openConnection();
            SemanticLedgerRequestRecovery.registerAuxiliary(connection);
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
            if ("length".equals(finish)) throw new SliceFormatException("显示切片 JSON 达到 max_tokens 被截断");
            if ("insufficient_system_resource".equals(finish)) throw new RetryableException("API 资源不足");
            if ("content_filter".equals(finish)) throw new IllegalStateException("API 内容过滤中止了显示切片");
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "");
            if (content == null || content.trim().isEmpty()) throw new RetryableException("API 返回了空 content");
            return content;
        } catch (SocketTimeoutException timeout) {
            if (auditAttempt > 0 && !auditRecorded) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "timeout");
                auditRecorded = true;
            }
            ensureActive(deadline, control);
            throw new RetryableException("显示切片请求超时", timeout);
        } catch (IOException network) {
            if (auditAttempt > 0 && !auditRecorded) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "network");
                auditRecorded = true;
            }
            ensureActive(deadline, control);
            throw new RetryableException("显示切片网络错误: " + abbreviate(network.getMessage()), network);
        } finally {
            if (auditAttempt > 0 && !auditRecorded) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "cancelled_or_exception");
            }
            SemanticLedgerRequestRecovery.unregisterAuxiliary(connection);
            if (connection != null && !consumed) connection.disconnect();
            if (control != null) control.onConnection(null);
        }
    }

    private static byte[] readFully(InputStream stream, int maxBytes) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (output.size() + read > maxBytes) throw new IllegalStateException("显示切片响应过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void ensureActive(long deadline, DeepSeekApiClient.RequestControl control) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("字幕显示切片已取消");
        if (control != null && control.isCancelled()) throw new InterruptedException("字幕显示切片已重新调度");
        if (remainingMillis(deadline) <= 0L) throw new RetryableException("显示切片请求超时");
    }

    private static int boundedTimeout(long deadline, int max) throws Exception {
        long left = remainingMillis(deadline);
        if (left <= 0L) throw new RetryableException("显示切片请求超时");
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
        return one.length() <= 220 ? one : one.substring(0, 220);
    }

    static final class Slice {
        final int from;
        final int to;
        final String text;

        Slice(int from, int to, String text) {
            this.from = from;
            this.to = to;
            this.text = text == null ? "" : text;
        }
    }

    static final class Result {
        static final Result EMPTY = new Result(Collections.emptyList(), "whole_sentence", "");
        final List<Slice> slices;
        final String boundaryReason;
        final String rejectionSummary;

        Result(List<Slice> slices) {
            this(slices, "unspecified", "");
        }

        Result(List<Slice> slices, String boundaryReason, String rejectionSummary) {
            this.slices = slices == null ? Collections.emptyList() : slices;
            this.boundaryReason = boundaryReason == null ? "unspecified" : boundaryReason;
            this.rejectionSummary = rejectionSummary == null ? "" : rejectionSummary;
        }
    }

    private static final class RetryableException extends Exception {
        private static final long serialVersionUID = 1L;
        RetryableException(String message) { super(message); }
        RetryableException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class SliceFormatException extends Exception {
        private static final long serialVersionUID = 1L;
        SliceFormatException(String message) { super(message); }
        SliceFormatException(String message, Throwable cause) { super(message, cause); }
    }
}
