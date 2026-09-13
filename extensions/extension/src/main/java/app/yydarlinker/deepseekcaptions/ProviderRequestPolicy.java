package app.yydarlinker.deepseekcaptions;
import org.json.*;
import java.net.URI;
import java.util.Locale;
/** Portable default request: vendor-specific thinking parameters are never inferred from model names. */
final class ProviderRequestPolicy {
    static JSONObject request(DeepSeekConfig.Snapshot config, String prompt, JSONObject payload, int limit) throws Exception {
        JSONObject r=new JSONObject().put("model",config.model).put("stream",false)
            .put("max_tokens",Math.max(256,Math.min(3072,limit)))
            .put("messages",new JSONArray()
                .put(new JSONObject().put("role","system").put("content","Return valid JSON only. "+prompt))
                .put(new JSONObject().put("role","user").put("content",payload.toString())))
            .put("response_format",new JSONObject().put("type","json_object"));
        String host="";
        try { host=URI.create(config.baseUrl).getHost(); } catch(Exception ignored) {}
        if("api.deepseek.com".equalsIgnoreCase(host)) r.put("thinking",new JSONObject().put("type","disabled"));
        if(host!=null && (host.equals("dashscope.aliyuncs.com") || host.endsWith(".dashscope.aliyuncs.com")))
            r.put("enable_thinking",false);
        return r;
    }
    static String reason(String body) {
        String text=body==null ? "" : body.toLowerCase(Locale.ROOT);
        if(text.contains("json") && (text.contains("messages") || text.contains("prompt"))) return "json_prompt_required";
        if(text.contains("response_format") || text.contains("json_object")) return "response_format_unsupported";
        if(text.contains("thinking")) return "thinking_unsupported";
        if(text.contains("model")) return "model_rejected";
        if(text.contains("token")) return "output_budget_rejected";
        return "invalid_request"; // Never expose raw provider text or credentials.
    }
    static boolean removeOptional(JSONObject request) {
        boolean changed=request.has("thinking") || request.has("enable_thinking") || request.has("response_format");
        request.remove("thinking");request.remove("enable_thinking");request.remove("response_format");
        return changed; // Output budget is NEVER removed.
    }
}
