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
 * Source-first semantic page planner.
 *
 * <p>The model receives lexical source atoms rather than YouTube cue-sized chunks. It first decides
 * closed source-language units and only then translates them. A protected look-ahead tail is never
 * committable in the current request. Model boundary mistakes are treated as a recoverable tail:
 * Java commits the longest safe prefix and leaves the rest pending instead of discarding the whole
 * window and creating a visible subtitle hole.</p>
 */
final class SemanticPageApiClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_OUTPUT_TOKENS = 3_072;
    private static final int CONNECT_TIMEOUT_MS = 3_200;
    private static final int READ_TIMEOUT_MS = 14_000;
    private static final long PRIORITY_TIMEOUT_MS = 10_000L;
    private static final long BACKGROUND_TIMEOUT_MS = 16_000L;
    private static final int PRIORITY_LOOKAHEAD_ATOMS = 10;
    private static final int BACKGROUND_LOOKAHEAD_ATOMS = 14;

    private SemanticPageApiClient() {}

    static Result plan(
            List<SourceAtomTimeline.Atom> atoms,
            int focusIndex,
            List<String> contextBefore,
            List<String> contextAfter,
            DeepSeekConfig.Snapshot config,
            TargetLanguage targetLanguage,
            DeepSeekApiClient.RequestControl control,
            boolean priority
    ) throws Exception {
        if (atoms == null || atoms.isEmpty()) return Result.EMPTY;
        if (!config.ready()) throw new IllegalStateException("AI 字幕翻译尚未启用或没有 API Key");

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                priority ? PRIORITY_TIMEOUT_MS : BACKGROUND_TIMEOUT_MS
        );
        ensureActive(deadline, control);

        TargetLanguage target = targetLanguage == null
                ? TargetLanguage.SIMPLIFIED_CHINESE
                : targetLanguage;
        int count = atoms.size();
        int focus = Math.max(0, Math.min(count - 1, focusIndex));
        boolean endOfInput = contextAfter == null || contextAfter.isEmpty();
        int reserve = priority ? PRIORITY_LOOKAHEAD_ATOMS : BACKGROUND_LOOKAHEAD_ATOMS;
        int commitLimit = endOfInput
                ? count
                : Math.max(focus + 1, count - Math.min(reserve, Math.max(1, count / 3)));
        commitLimit = Math.max(1, Math.min(count, commitLimit));

        JSONArray source = new JSONArray();
        long base = atoms.get(0).startMs;
        int sourceChars = 0;
        for (int i = 0; i < atoms.size(); i++) {
            SourceAtomTimeline.Atom atom = atoms.get(i);
            sourceChars += atom.text == null ? 0 : atom.text.length();
            long gapAfter = 0L;
            if (i + 1 < atoms.size()) gapAfter = Math.max(0L, atoms.get(i + 1).startMs - atom.endMs);
            source.put(new JSONObject()
                    .put("i", i)
                    .put("text", atom.text == null ? "" : atom.text)
                    .put("start_ms", Math.max(0L, atom.startMs - base))
                    .put("end_ms", Math.max(1L, atom.endMs - base))
                    .put("gap_after_ms", gapAfter)
                    .put("timing", atom.precise ? "native" : "estimated"));
        }

        JSONObject payload = new JSONObject()
                .put("target_language", target.code)
                .put("focus_index", focus)
                .put("commit_limit", commitLimit)
                .put("atoms", source);
        if (contextBefore != null && !contextBefore.isEmpty()) {
            payload.put("context_before", new JSONArray(contextBefore));
        }
        if (contextAfter != null && !contextAfter.isEmpty()) {
            payload.put("context_after", new JSONArray(contextAfter));
        }

        String systemPrompt =
                "你是专业的 YouTube 字幕翻译与源语言语义分段器。输入 atoms 是连续口语被拆成的细粒度时间原子，" +
                "atom 边界、YouTube cue 边界、estimated timing 都绝不等于句子边界。" +
                "你的工作顺序必须是：第一，先在源语言中恢复连续话语并判断完整句子/完整分句；第二，只在源语言已经闭合的边界建立 page；" +
                "第三，再把该完整源语义单元整体翻译为自然、准确、连贯的" + target.promptLabel() + "。" +
                "绝对不要为了目标语言字数、两行显示、窗口边缘、短暂停顿或原 cue 切换而提前断句。" +
                "一个 page 可以长；语言完整性永远高于显示长度。禁止产生需要下一页才能补全的半句话。" +
                "尤其不能让 page 结束在未完成的主谓结构、系词/助动词/情态动词之后、缺少宾语或补语的谓语之后、冠词/介词/连词之后、" +
                "未结束的定语/从句/比较结构之后，或类似 ‘this phone is …’ ‘it has …’ ‘I first felt …’ ‘although …’ ‘the whole …’ 这种仍依赖后文的结构。" +
                "每个 page 必须覆盖连续 atom 索引 from..to，from/to 只能由源语言语义决定；客户端自己取 atoms[from].start 到 atoms[to].end，" +
                "所以你绝对不要生成或修改时间戳。" +
                "commit_limit 是本次可提交区间的右开边界。索引 >= commit_limit 的 atoms 是强制观察区，只能帮助你理解前文，绝不能被翻译进 pages。" +
                "如果一个完整句子跨过 commit_limit，就必须从该句第一个尚未提交的 atom 开始 pending，而不是截断句子。" +
                "pages 必须从 atom 0 开始连续覆盖到 pending_from-1，不得跳号、重叠、重复或遗漏；pending_from 必须小于等于 commit_limit，绝不能返回观察区中的索引。" +
                "如果不能在 commit_limit 前找到安全边界，就少提交一些 page，把未闭合部分全部留给 pending，而不是强行越过 commit_limit。" +
                "每个 page 额外返回 boundary，值只能是 sentence 或 clause：sentence 表示完整句，clause 表示无需下一页补语法成分、可独立阅读的完整分句。" +
                "在输出前请逐个自检：如果删除下一页后当前 page 会显得语法没说完，就不能提交它。" +
                "page.text 必须忠实翻译对应 from..to 的源内容，不删减、不概括、不提前翻译观察区内容，不增加源文没有的信息；" +
                "应恢复自然标点。最终 page 不要以逗号、顿号或冒号作为人为截断符。" +
                "context_before/context_after 只用于理解上下文，不属于 atoms，也不能被翻译到 page 中。" +
                "focus_index 只是告诉你当前播放头位置，不能降低语义完整性的要求。" +
                "翻译要求：" + config.prompt +
                " 只返回合法 JSON，格式严格为：{\"pages\":[{\"from\":0,\"to\":5,\"text\":\"...\",\"boundary\":\"sentence\"}],\"pending_from\":6}。" +
                "不要输出 Markdown、解释、思考过程、换行指令或额外字段。";

        int outputTokens = Math.max(1_024, Math.min(
                MAX_OUTPUT_TOKENS,
                sourceChars * 2 + atoms.size() * 72 + 448
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
        if (!dashScopeQwen) request.put("max_tokens", outputTokens);

        boolean thinkingFallback = request.has("thinking") || request.has("enable_thinking");
        Exception last = null;
        int attempts = priority ? 1 : 2;
        for (int attempt = 0; attempt < attempts; attempt++) {
            ensureActive(deadline, control);
            try {
                String content = post(config, request, deadline, control);
                return parse(content, atoms, commitLimit, endOfInput);
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

    /**
     * Parse a model plan conservatively. Structural JSON corruption still fails the request, but a
     * semantically unsafe tail does not poison an already-good prefix. This is critical for realtime
     * playback: a single bad pending_from or one over-eager final page must not erase 1-2 seconds of
     * already usable captions and trigger several network round trips.
     */
    private static Result parse(
            String content,
            List<SourceAtomTimeline.Atom> atoms,
            int commitLimit,
            boolean endOfInput
    ) throws Exception {
        int count = atoms.size();
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
            throw new PlanFormatException("API 返回的语义分页 JSON 无法解析", malformed);
        }
        JSONArray values = root.optJSONArray("pages");
        if (values == null) throw new PlanFormatException("API 返回缺少 pages");

        int hardLimit = Math.max(0, Math.min(count, commitLimit));
        int modelPending = root.has("pending_from") ? root.optInt("pending_from", hardLimit) : hardLimit;
        int advisoryLimit;
        if (modelPending < 0 || modelPending > count) {
            advisoryLimit = hardLimit;
        } else {
            advisoryLimit = Math.min(hardLimit, modelPending);
        }

        List<Page> pages = new ArrayList<>();
        int expected = 0;
        for (int i = 0; i < values.length() && expected < advisoryLimit; i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) break;
            int from = value.optInt("from", -1);
            int to = value.optInt("to", -1);
            String text = value.optString("text", "").trim();
            String boundary = value.optString("boundary", "").trim().toLowerCase(Locale.ROOT);

            if (from != expected || to < from || to >= count || text.isEmpty()) break;
            if (to >= advisoryLimit || to >= hardLimit) break;
            if (!("sentence".equals(boundary) || "clause".equals(boundary))) break;
            if (unsafeSourceBoundary(atoms, from, to, hardLimit)) break;
            // Keep dev6's useful quality gate, but turn it into a tail deferral rather than an
            // exception that discards every valid page before it.
            if (endsWithSoftTargetPunctuation(text)) break;

            pages.add(new Page(from, to, text, boundary));
            expected = to + 1;
        }

        // The client, not the model, owns the final commit frontier. If the model returned
        // pending_from in the protected observation region, simply keep the safe prefix.
        int pending = expected;

        if (endOfInput && pending < count) {
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                int from = value.optInt("from", -1);
                int to = value.optInt("to", -1);
                if (from != pending || to != count - 1) continue;
                String text = trimSoftTerminal(value.optString("text", "").trim());
                String boundary = value.optString("boundary", "").trim().toLowerCase(Locale.ROOT);
                if (text.isEmpty() || !("sentence".equals(boundary) || "clause".equals(boundary))) break;
                if (unsafeSourceBoundary(atoms, from, to, count)) break;
                pages.add(new Page(from, to, text, boundary));
                pending = count;
                break;
            }
        }

        return new Result(Collections.unmodifiableList(pages), pending);
    }

    private static boolean unsafeSourceBoundary(
            List<SourceAtomTimeline.Atom> atoms,
            int from,
            int to,
            int pending
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

        if (to + 1 < atoms.size() && to + 1 < pending) {
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

    private static String trimSoftTerminal(String text) {
        if (text == null) return "";
        String value = text.trim();
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == ',' || c == '，' || c == '、' || c == ':' || c == '：') end--;
            else break;
        }
        return value.substring(0, end).trim();
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
            DeepSeekApiClient.RequestControl control
    ) throws Exception {
        HttpURLConnection connection = null;
        boolean consumed = false;
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
                throw new RetryableException("API HTTP " + status + ": " + abbreviate(response));
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("API HTTP " + status + ": " + abbreviate(response));
            }

            JSONObject root = new JSONObject(response);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new RetryableException("API 返回中没有 choices");
            JSONObject choice = choices.optJSONObject(0);
            String finish = choice == null ? "" : choice.optString("finish_reason", "");
            if ("length".equals(finish)) throw new PlanFormatException("API 输出达到 max_tokens，语义分页 JSON 被截断");
            if ("insufficient_system_resource".equals(finish)) throw new RetryableException("API 资源不足");
            if ("content_filter".equals(finish)) throw new IllegalStateException("API 内容过滤中止了字幕翻译");
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "").trim();
            if (content.isEmpty()) throw new RetryableException("API 返回了空 content");
            return content;
        } catch (SocketTimeoutException timeout) {
            ensureActive(deadline, control);
            throw new RetryableException("API 网络超时", timeout);
        } catch (IOException network) {
            ensureActive(deadline, control);
            throw new RetryableException("API 网络错误: " + abbreviate(network.getMessage()), network);
        } finally {
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

    static final class Page {
        final int from;
        final int to;
        final String text;
        final String boundary;

        Page(int from, int to, String text, String boundary) {
            this.from = from;
            this.to = to;
            this.text = text;
            this.boundary = boundary;
        }
    }

    static final class Result {
        static final Result EMPTY = new Result(Collections.emptyList(), 0);
        final List<Page> pages;
        final int pendingFrom;

        Result(List<Page> pages, int pendingFrom) {
            this.pages = pages;
            this.pendingFrom = pendingFrom;
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
