package app.yydarlinker.deepseekcaptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fetches model IDs from an OpenAI-compatible GET /models endpoint. */
final class ProviderModelCatalog {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private ProviderModelCatalog() {}

    interface CredentialPort { String resolve(); }
    static List<String> fetch(String baseUrl, CredentialPort credentials) throws Exception {
        String key = credentials.resolve().trim();
        if (key.isEmpty()) throw new IllegalArgumentException("请先填写 API Key");

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(modelsUrl(baseUrl)).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            ProviderEndpoint.authenticate(connection,baseUrl,key);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "YYDarlinker-AICaptions/2");

            int status = connection.getResponseCode();
            InputStream stream = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String response = stream == null ? "" : new String(
                    readBody(stream),
                    StandardCharsets.UTF_8
            );
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(
                        "模型列表 HTTP " + status +
                                ""
                );
            }

            List<String> models = parse(response);
            if (models.isEmpty()) throw new IllegalStateException("接口没有返回可选择的模型 ID");
            return models;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String modelsUrl(String configured) { return ProviderEndpoint.models(configured); }

    private static byte[] readBody(InputStream stream) throws Exception {
        try (InputStream in=stream;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(out.size()+count>MAX_RESPONSE_BYTES)throw new java.io.IOException("response_too_large");
                out.write(buffer,0,count);
            }
            return out.toByteArray();
        }
    }

    private static List<String> parse(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray data = root.optJSONArray("data");
        if (data == null) data = root.optJSONArray("models");
        if (data == null) {
            JSONObject result = root.optJSONObject("result");
            if (result != null) {
                data = result.optJSONArray("data");
                if (data == null) data = result.optJSONArray("models");
            }
        }
        if (data == null) return Collections.emptyList();

        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < data.length(); i++) {
            Object item = data.opt(i);
            String id = "";
            if (item instanceof JSONObject) {
                id = ((JSONObject) item).optString("id", "").trim();
                if (id.isEmpty()) id = ((JSONObject) item).optString("name", "").trim();
            } else if (item instanceof String) {
                id = ((String) item).trim();
            }
            if (!id.isEmpty() && id.length() <= 300 &&
                    id.indexOf('\r') < 0 && id.indexOf('\n') < 0) {
                unique.add(id);
            }
        }
        List<String> models = new ArrayList<>(unique);
        models.sort(String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    private static String abbreviate(String value) {
        String oneLine = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 260 ? oneLine : oneLine.substring(0, 260);
    }
}
