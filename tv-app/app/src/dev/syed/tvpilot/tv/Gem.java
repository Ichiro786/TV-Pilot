package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * The stronger eye, for one step only: Gemini (Flash / Flash Lite, the user's key) looks at the marked screenshot when
 * the decision model is unsure (low confidence) or the same step failed twice. Pure Java, OpenAI-style endpoint.
 */
final class Gem {
    static final String URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private final String key, model;
    volatile long lastMs;

    Gem(String key, String model) { this.key = key; this.model = model; }

    boolean available() { return key != null && !key.isEmpty(); }

    /** Returns the parsed JSON object from the reply ({} when unreadable). */
    JSONObject ask(String system, String user, List<byte[]> images, int timeoutMs) throws Exception {
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", user));
        if (images != null) for (byte[] b : images) if (b != null)
            content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", Decider.dataUrl(b))));
        JSONObject body = new JSONObject().put("model", model).put("temperature", 0).put("max_tokens", 600)
                .put("reasoning_effort", "low")
                .put("response_format", new JSONObject().put("type", "json_object"))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", system))
                        .put(new JSONObject().put("role", "user").put("content", content)));
        long t0 = System.currentTimeMillis();
        Net.Response r = Net.postJson(URL, body.toString(), new String[][] {{"Authorization", "Bearer " + key}}, 6000, timeoutMs, "Gemini");
        if (r.code == 400) {   // models without reasoning_effort / json mode: plainest request
            body.remove("reasoning_effort");
            body.remove("response_format");
            r = Net.postJson(URL, body.toString(), new String[][] {{"Authorization", "Bearer " + key}}, 6000, timeoutMs, "Gemini");
        }
        if (r.code != 200) throw Net.http(r.code, r.body, "Gemini");
        lastMs = System.currentTimeMillis() - t0;
        JSONArray ch = new JSONObject(r.body).optJSONArray("choices");
        String text = ch == null || ch.optJSONObject(0) == null ? "" : ch.optJSONObject(0).optJSONObject("message").optString("content", "");
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return new JSONObject();
        try { return new JSONObject(text.substring(s, e + 1)); } catch (Exception ex) { return new JSONObject(); }
    }
}
