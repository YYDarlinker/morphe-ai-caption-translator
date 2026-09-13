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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Fixed-target contextual translator used by the experimental unit core. */
final class ContextualBatchApiClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_OUTPUT_TOKENS = 3_072;
    private static final int CONNECT_TIMEOUT_MS = 3_200;
    private static final int READ_TIMEOUT_MS = 14_000;
    private static final long PRIORITY_TIMEOUT_MS = 10_000L;
    private static final long BACKGROUND_TIMEOUT_MS = 16_000L;

    static final String LANE_REALTIME = "unit_realtime";
    static final String LANE_BACKGROUND = "unit_background";

    private ContextualBatchApiClient() {}

    static Result translate(
            List<TranslationUnitTimeline.Unit> targets,
            List<SourceAtomTimeline.Atom> atoms,
            List<String> contextBefore,
            List<String> contextAfter,
            DeepSeekConfig.Snapshot config,
            TargetLanguage targetLanguage,
            DeepSeekApiClient.RequestControl control,
            boolean priority
    ) throws Exception {
        if (targets == null || targets.isEmpty()) return Result.EMPTY;
        validateTargetIds(targets);
        if (!config.ready()) {
            throw new PermanentException("configuration", "AI 字幕翻译尚未启用或没有 API Key", "");
        }
        if (!priority) BackgroundPauseGovernor.awaitBackgroundPermit(control);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                priority ? PRIORITY_TIMEOUT_MS : BACKGROUND_TIMEOUT_MS
        );
        ensureActive(deadline, control);
        TargetLanguage target = targetLanguage == null
                ? TargetLanguage.SIMPLIFIED_CHINESE
                : targetLanguage;

        JSONArray targetValues = new JSONArray();
        int sourceChars = 0;
        for (TranslationUnitTimeline.Unit unit : targets) {
            String text = ContextualCaptionTextPolicy.sourceForTranslation(
                    unit == null ? "" : unit.sourceText
            );
            sourceChars += text.length();
            targetValues.put(new JSONObject()
                    .put("id", unit == null ? "" : unit.id)
                    .put("tokens", AnchoredCaptionPlan.tokens(atoms, unit)));
        }
        JSONObject payload = new JSONObject()
                .put("target_language", target.code)
                .put("targets", targetValues);
        if (contextBefore != null && !contextBefore.isEmpty()) {
            payload.put("context_before", sanitizedContext(contextBefore));
        }
        if (contextAfter != null && !contextAfter.isEmpty()) {
            payload.put("context_after", sanitizedContext(contextAfter));
        }

        String systemPrompt = AnchoredCaptionPlan.PROMPT
                + " Target language: " + target.promptLabel() + ". User translation preferences: " + config.prompt;

        int outputTokens = Math.max(768, Math.min(
                MAX_OUTPUT_TOKENS,
                sourceChars * 2 + targets.size() * 96 + 320
        ));
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
        request.put("max_tokens", outputTokens);

        int contextCount = (contextBefore == null ? 0 : contextBefore.size()) +
                (contextAfter == null ? 0 : contextAfter.size());
        int contextChars = 0;
        if (contextBefore != null) {
            for (String value : contextBefore) contextChars += value == null ? 0 : value.length();
        }
        if (contextAfter != null) {
            for (String value : contextAfter) contextChars += value == null ? 0 : value.length();
        }
        TokenCostAudit.Request audit = TokenCostAudit.beginUnitBatch(
                config,
                priority,
                targets.size(),
                contextCount,
                sourceChars,
                contextChars,
                priority ? LANE_REALTIME : LANE_BACKGROUND
        );

        boolean thinkingFallback = request.has("thinking") || request.has("enable_thinking");
        boolean responseFormatFallback = request.has("response_format");
        boolean maxTokensFallback = request.has("max_tokens");
        Exception last = null;
        for (int attempt = 0; attempt < 1; attempt++) {
            ensureActive(deadline, control);
            try {
                String content = post(config, request, deadline, control, audit);
                Result result = parseAnchored(content, targets, atoms);
                TokenCostAudit.recordUnitBatchOutcome(audit, result.validCount());
                return result;
            } catch (IllegalStateException rejected) {
                if (thinkingFallback && unsupportedThinking(rejected)) {
                    thinkingFallback = false;
                    request.remove("thinking");
                    request.remove("enable_thinking");
                    attempt--;
                    continue;
                }
                if (responseFormatFallback && unsupportedResponseFormat(rejected)) {
                    responseFormatFallback = false;
                    request.remove("response_format");
                    attempt--;
                    continue;
                }
                if (maxTokensFallback && providerRejectsParameter(rejected, "max_tokens")) {
                    maxTokensFallback = false;
                    throw new PermanentException("output_limit_unsupported",
                            "供应商不支持输出预算，请选择支持 max_tokens 的兼容端点", "");
                }
                throw rejected;
            } catch (RetryableException | BatchFormatException retryable) {
                last = retryable;
                if (attempt + 1 < 1 && remainingMillis(deadline) > 700L) Thread.sleep(220L);
            }
        }
        throw last == null ? new IllegalStateException("AI 字幕固定单元翻译失败") : last;
    }

    static Result parseAnchored(String content, List<TranslationUnitTimeline.Unit> targets,
                                List<SourceAtomTimeline.Atom> atoms) throws Exception {
        validateTargetIds(targets);
        JSONObject root;
        try { root = new JSONObject(content.trim()); }
        catch (Exception e) { throw new BatchFormatException("invalid anchored JSON", e); }
        JSONArray rows = root.optJSONArray("translations");
        if (rows == null) throw new BatchFormatException("missing translations");
        Map<String, AnchoredCaptionPlan> plans = new HashMap<>();
        Map<String, String> texts = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<String> invalid = new ArrayList<>(), unknown = new ArrayList<>(), missing = new ArrayList<>();
        for (int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);
            if(row==null || !(row.opt("id") instanceof String))
                throw new BatchFormatException("invalid response id");
            String id=row.getString("id");
            TranslationUnitTimeline.Unit unit=targetById(targets,id);
            if(unit==null) { unknown.add(id); continue; }
            if(!seen.add(id)) { plans.remove(id); texts.remove(id); invalid.add(id); continue; }
            try {
                AnchoredCaptionPlan plan=AnchoredCaptionPlan.parse(row.optJSONArray("segments"),atoms,unit);
                plans.put(id,plan); texts.put(id,plan.canonical);
            } catch(Exception rejected) { invalid.add(id); }
        }
        for(TranslationUnitTimeline.Unit unit:targets) if(!plans.containsKey(unit.id)) missing.add(unit.id);
        Result result=new Result(texts,missing,invalid,unknown);
        result.plansById.putAll(plans);
        return result;
    }

    private static Result parse(
            String content,
            List<TranslationUnitTimeline.Unit> targets
    ) throws Exception {
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
            JSONObject root = new JSONObject(json);
            Object raw = root.opt("translations");
            if (!(raw instanceof JSONArray)) {
                throw new BatchFormatException("API 返回缺少 translations 数组");
            }
            values = (JSONArray) raw;
        } catch (BatchFormatException failure) {
            throw failure;
        } catch (Throwable malformed) {
            throw new BatchFormatException("API 返回的固定单元 JSON 无法解析", malformed);
        }

        Set<String> expected = validateTargetIds(targets);

        Map<String, String> byId = new HashMap<>();
        Set<String> invalid = new HashSet<>();
        Set<String> unknown = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) {
                throw new BatchFormatException("翻译项不是对象: " + i);
            }
            Object rawId = value.opt("id");
            if (!(rawId instanceof String) || ((String) rawId).trim().isEmpty()) {
                // Without a reliable id we cannot safely associate any item with a target.
                throw new BatchFormatException("翻译项 id 类型无效: " + i);
            }
            String id = ((String) rawId).trim();
            if (!expected.contains(id)) {
                // Unknown ids never become READY and do not prevent known ids from being used.
                unknown.add(id);
                continue;
            }
            if (!seen.add(id)) {
                byId.remove(id);
                invalid.add(id);
                continue;
            }
            Object rawText = value.opt("text");
            if (!(rawText instanceof String)) {
                invalid.add(id);
                continue;
            }
            String text = ContextualCaptionTextPolicy.translationForDisplay((String) rawText);
            TranslationUnitTimeline.Unit target = targetById(targets, id);
            if (text.isEmpty() || target == null ||
                    !ContextualCaptionTextPolicy.adequateTranslation(target.sourceText, text)) {
                invalid.add(id);
                continue;
            }
            byId.put(id, text);
        }

        List<String> missing = new ArrayList<>();
        for (TranslationUnitTimeline.Unit unit : targets) {
            String id = unit.id;
            if (!byId.containsKey(id)) missing.add(id);
        }
        return new Result(byId, missing, new ArrayList<>(invalid), new ArrayList<>(unknown));
    }

    private static JSONArray sanitizedContext(List<String> values) {
        JSONArray result = new JSONArray();
        for (String value : values) {
            String clean = ContextualCaptionTextPolicy.sourceForTranslation(value);
            if (!clean.isEmpty()) result.put(clean);
        }
        return result;
    }

    private static TranslationUnitTimeline.Unit targetById(
            List<TranslationUnitTimeline.Unit> targets,
            String id
    ) {
        if (targets == null || id == null) return null;
        for (TranslationUnitTimeline.Unit target : targets) {
            if (target != null && id.equals(target.id)) return target;
        }
        return null;
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
        boolean auditRecorded = false;
        int auditAttempt = 0;
        int sentBodyBytes = 0;
        try {
            ensureActive(deadline, control);
            connection = (HttpURLConnection) new URL(completionUrl(config.baseUrl)).openConnection();
            if (control != null) control.onConnection(connection);
            connection.setInstanceFollowRedirects(false);
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
                throw new RetryableException("http_" + status, "API HTTP " + status);
            }
            if (status == 400 || status == 413 || status == 422) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "http_" + status);
                auditRecorded = true;
                throw new ProviderRequestException(
                        safeProviderCategory(status, response),
                        "API HTTP " + status,
                        response
                );
            }
            if (status < 200 || status >= 300) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "http_" + status);
                auditRecorded = true;
                throw new PermanentException("http_" + status, "API HTTP " + status, response);
            }

            JSONObject root = new JSONObject(response);
            TokenCostAudit.recordResponse(audit, auditAttempt, root);
            auditRecorded = true;
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new RetryableException("missing_choices", "API 返回中没有 choices");
            }
            JSONObject choice = choices.optJSONObject(0);
            String finish = choice == null ? "" : choice.optString("finish_reason", "");
            if ("length".equals(finish)) {
                throw new BatchFormatException("finish_length", "API 输出达到 max_tokens，固定单元 JSON 被截断");
            }
            if ("insufficient_system_resource".equals(finish)) {
                throw new RetryableException("provider_resource", "API 资源不足");
            }
            if ("content_filter".equals(finish)) {
                throw new PermanentException("content_filter", "API 内容过滤中止了字幕翻译", "");
            }
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String result = message == null ? "" : message.optString("content", "").trim();
            if (result.isEmpty()) throw new RetryableException("empty_content", "API 返回了空 content");
            return result;
        } catch (SocketTimeoutException timeout) {
            if (auditAttempt > 0 && !auditRecorded) {
                TokenCostAudit.recordFailure(audit, auditAttempt, "timeout");
                TokenCostAudit.recordSunkPrompt(audit, sentBodyBytes);
                auditRecorded = true;
            }
            ensureActive(deadline, control);
            throw new RetryableException("timeout", "API 网络超时", timeout);
        } catch (IOException network) {
            if (auditAttempt > 0 && !auditRecorded) {
                boolean cancelled = Thread.currentThread().isInterrupted() ||
                        (control != null && control.isCancelled());
                TokenCostAudit.recordFailure(audit, auditAttempt, cancelled ? "cancelled" : "network");
                TokenCostAudit.recordSunkPrompt(audit, sentBodyBytes);
                auditRecorded = true;
            }
            ensureActive(deadline, control);
            throw new RetryableException("network", "API 网络错误", network);
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
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > maxBytes) throw new IllegalStateException("网络响应过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void ensureActive(
            long deadline,
            DeepSeekApiClient.RequestControl control
    ) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("字幕翻译已取消");
        if (control != null && control.isCancelled()) throw new InterruptedException("字幕翻译已重新调度");
        if (remainingMillis(deadline) <= 0L) {
            throw new RetryableException("deadline", "AI 字幕请求超时");
        }
    }

    private static int boundedTimeout(long deadline, int maximum) throws Exception {
        long left = remainingMillis(deadline);
        if (left <= 0L) throw new RetryableException("deadline", "AI 字幕请求超时");
        return (int) Math.max(1L, Math.min((long) maximum, left));
    }

    private static long remainingMillis(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0L) return 0L;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static Set<String> validateTargetIds(
            List<TranslationUnitTimeline.Unit> targets
    ) throws BatchFormatException {
        Set<String> expected = new HashSet<>();
        if (targets == null) throw new BatchFormatException("target_ids", "targets 为空");
        for (TranslationUnitTimeline.Unit unit : targets) {
            if (unit == null || unit.id == null || unit.id.trim().isEmpty()) {
                throw new BatchFormatException("target_ids", "targets 含有无法识别的 unit id");
            }
            String id = unit.id.trim();
            if (!id.equals(unit.id) || !expected.add(id)) {
                throw new BatchFormatException("duplicate_target_id", "targets 含有重复或非规范 unit id");
            }
        }
        return expected;
    }

    static ContextualUnitCorePolicy.FailureKind failureKind(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ProviderRequestException) {
                return ContextualUnitCorePolicy.FailureKind.RETRYABLE_PROTOCOL;
            }
            if (current instanceof PermanentException) {
                return ContextualUnitCorePolicy.FailureKind.PERMANENT;
            }
            if (current instanceof BatchFormatException) {
                return ContextualUnitCorePolicy.FailureKind.RETRYABLE_PROTOCOL;
            }
            if (current instanceof RetryableException) {
                return ContextualUnitCorePolicy.FailureKind.TRANSIENT;
            }
            current = current.getCause();
        }
        return ContextualUnitCorePolicy.FailureKind.TRANSIENT;
    }

    static boolean requiresBatchIsolation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ProviderRequestException) return true;
            current = current.getCause();
        }
        return false;
    }

    static String failureCategory(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CategorizedFailure) {
                return ((CategorizedFailure) current).category();
            }
            if (current instanceof InterruptedException) return "cancelled";
            current = current.getCause();
        }
        return error == null ? "unknown" : error.getClass().getSimpleName();
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
        String raw = providerDetail(error);
        String message = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return (message.contains("thinking") || message.contains("enable_thinking")) && (
                message.contains("unknown") || message.contains("unsupported") ||
                        message.contains("unrecognized") || message.contains("not permitted") ||
                        message.contains("invalid parameter") || message.contains("不支持") ||
                        message.contains("未知")
        );
    }

    private static boolean unsupportedResponseFormat(Throwable error) {
        return providerRejectsParameter(error, "response_format") ||
                providerRejectsParameter(error, "json_object");
    }

    static boolean providerRejectsParameter(Throwable error, String parameter) {
        String raw = providerDetail(error);
        String message = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        String key = parameter == null ? "" : parameter.toLowerCase(Locale.ROOT).trim();
        if (key.isEmpty() || !message.contains(key)) return false;
        return message.contains("unknown") || message.contains("unsupported") ||
                message.contains("unrecognized") || message.contains("not permitted") ||
                message.contains("invalid parameter") || message.contains("invalid value") ||
                message.contains("out of range") || message.contains("too large") ||
                message.contains("maximum") || message.contains("不支持") ||
                message.contains("未知") || message.contains("无效") ||
                message.contains("超出范围");
    }

    private static String providerDetail(Throwable error) {
        if (error instanceof ProviderRequestException) {
            return ((ProviderRequestException) error).providerDetail;
        }
        if (error instanceof PermanentException) {
            return ((PermanentException) error).providerDetail;
        }
        return error == null ? "" : error.getMessage();
    }

    static String safeProviderCategory(int status, String response) {
        StringBuilder category = new StringBuilder("http_").append(status);
        try {
            JSONObject root = new JSONObject(response == null ? "" : response);
            JSONObject error = root.optJSONObject("error");
            if (error == null) error = root;
            appendSafeCategoryPart(category, error.optString("code", ""));
            appendSafeCategoryPart(category, error.optString("param", ""));
        } catch (Throwable ignored) {
            // HTTP status remains sufficient for recovery; never persist provider response text.
        }
        return category.toString();
    }

    private static void appendSafeCategoryPart(StringBuilder category, String raw) {
        if (category == null || raw == null || raw.trim().isEmpty()) return;
        String safe = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        while (safe.contains("__")) safe = safe.replace("__", "_");
        if (safe.length() > 48) safe = safe.substring(0, 48);
        if (!safe.isEmpty()) category.append('_').append(safe);
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        String one = value.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 280 ? one : one.substring(0, 280);
    }

    static final class Result {
        static final Result EMPTY = new Result(
                new HashMap<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
        );
        final Map<String, AnchoredCaptionPlan> plansById = new HashMap<>();
        final Map<String, String> translationsById;
        final List<String> missingIds;
        final List<String> invalidIds;
        final List<String> unknownIds;

        Result(
                Map<String, String> translationsById,
                List<String> missingIds,
                List<String> invalidIds,
                List<String> unknownIds
        ) {
            this.translationsById = translationsById == null
                    ? new HashMap<>() : translationsById;
            this.missingIds = missingIds == null ? new ArrayList<>() : missingIds;
            this.invalidIds = invalidIds == null ? new ArrayList<>() : invalidIds;
            this.unknownIds = unknownIds == null ? new ArrayList<>() : unknownIds;
        }

        int validCount() {
            return translationsById.size();
        }

        boolean isPartial() {
            return !translationsById.isEmpty() && !missingIds.isEmpty();
        }
    }

    private interface CategorizedFailure {
        String category();
    }

    static final class RetryableException extends Exception implements CategorizedFailure {
        private static final long serialVersionUID = 1L;
        private final String category;
        RetryableException(String message) { this("transient", message); }
        RetryableException(String category, String message) {
            super(message);
            this.category = category == null ? "transient" : category;
        }
        RetryableException(String message, Throwable cause) { this("transient", message, cause); }
        RetryableException(String category, String message, Throwable cause) {
            super(message, cause);
            this.category = category == null ? "transient" : category;
        }
        @Override public String category() { return category; }
    }

    static final class BatchFormatException extends Exception implements CategorizedFailure {
        private static final long serialVersionUID = 1L;
        private final String category;
        BatchFormatException(String message) { this("protocol_format", message); }
        BatchFormatException(String category, String message) {
            super(message);
            this.category = category == null ? "protocol_format" : category;
        }
        BatchFormatException(String message, Throwable cause) {
            this("protocol_format", message, cause);
        }
        BatchFormatException(String category, String message, Throwable cause) {
            super(message, cause);
            this.category = category == null ? "protocol_format" : category;
        }
        @Override public String category() { return category; }
    }

    static final class PermanentException extends IllegalStateException implements CategorizedFailure {
        private static final long serialVersionUID = 1L;
        private final String category;
        private final String providerDetail;
        PermanentException(String category, String message, String providerDetail) {
            super(message);
            this.category = category == null ? "permanent" : category;
            this.providerDetail = providerDetail == null ? "" : providerDetail;
        }
        @Override public String category() { return category; }
    }

    static final class ProviderRequestException extends IllegalStateException
            implements CategorizedFailure {
        private static final long serialVersionUID = 1L;
        private final String category;
        private final String providerDetail;

        ProviderRequestException(String category, String message, String providerDetail) {
            super(message);
            this.category = category == null ? "provider_request" : category;
            this.providerDetail = providerDetail == null ? "" : providerDetail;
        }

        @Override public String category() { return category; }
    }
}
