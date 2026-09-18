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
        String host=ProviderEndpoint.host(config.baseUrl);
        if("api.openai.com".equals(host)){
            r.put("max_completion_tokens",r.getInt("max_tokens"));r.remove("max_tokens");
        }
        if("api.minimax.io".equals(host)||"api.minimaxi.com".equals(host))r.put("reasoning_split",true);
        if("api.deepseek.com".equals(host)) r.put("thinking",new JSONObject().put("type","disabled"));
        if(ProviderEndpoint.bailian(host)||"api.siliconflow.cn".equals(host)||"api.siliconflow.com".equals(host)) r.put("enable_thinking",false);
        if("api.anthropic.com".equals(host))r.remove("response_format"); // Compatibility API ignores it; prompt still requires JSON.
        if("open.bigmodel.cn".equals(host)||ProviderEndpoint.ark(host))
            r.put("thinking",new JSONObject().put("type","disabled"));
        // Unknown gateways keep the portable request. Never infer vendor fields from model IDs.
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
    static boolean removeOptional(JSONObject request,String category) {
        boolean changed=false;
        if(category!=null&&category.contains("response_format")){changed=request.has("response_format");request.remove("response_format");return changed;}
        if(category!=null&&category.contains("thinking")){changed=request.has("thinking")||request.has("enable_thinking");request.remove("thinking");request.remove("enable_thinking");return changed;}
        return removeOptional(request);
    }
    static boolean removeOptional(JSONObject request) {
        boolean changed=request.has("thinking") || request.has("enable_thinking") || request.has("response_format");
        request.remove("thinking");request.remove("enable_thinking");request.remove("response_format");
        return changed; // Output budget is NEVER removed.
    }
}
