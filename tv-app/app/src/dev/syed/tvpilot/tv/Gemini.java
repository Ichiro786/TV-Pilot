package dev.syed.tvpilot.tv;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/** Speech to text with Gemini (generateContent with the recording inline), straight from the TV. */
final class Gemini {
    private Gemini() {}

    static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    static final String NO_KEY = "No Gemini key set \u2014 add it in TV Pilot TV's Settings";

    static String clean(String model) { return model.replaceAll("[^A-Za-z0-9._-]", ""); }

    static String transcribe(short[] pcm, String key, String model) throws Exception {
        if (key == null || key.isEmpty()) throw new Net.Fail(NO_KEY, 0, false, "no key");
        String audio = Base64.encodeToString(Recorder.wav(pcm), Base64.NO_WRAP);
        String prompt = "Transcribe this voice command spoken to a TV remote exactly as said. The speaker may use English, "
                + "Hindi or Hinglish: write Hindi words in Latin script. Keep app, movie, show and channel names as they are "
                + "normally spelled. Reply with only the transcript, nothing else. If there is no speech, reply with an empty string.";
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray()
                        .put(new JSONObject().put("text", prompt))
                        .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "audio/wav").put("data", audio))))))
                .put("generationConfig", new JSONObject().put("temperature", 0));
        Net.Response r = Net.postJson(BASE + clean(model) + ":generateContent", body.toString(),
                new String[][] {{"x-goog-api-key", key}}, 6000, 25_000, "Gemini");
        if (r.code >= 400) throw Net.http(r.code, r.body, "Gemini");
        JSONObject o = new JSONObject(r.body);
        JSONArray cands = o.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return "";
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder b = new StringBuilder();
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (p.optBoolean("thought")) continue;
            b.append(p.optString("text", ""));
        }
        return b.toString().trim().replaceAll("^\"|\"$", "");
    }

    /** Diagnostics: is the key good and does this model exist? Costs nothing (a model lookup). Null when fine. */
    static String check(String key, String model) {
        if (key == null || key.isEmpty()) return NO_KEY;
        try {
            Net.Response r = Net.request(BASE + clean(model), "GET", null, null, new String[][] {{"x-goog-api-key", key}}, 6000, 10_000, "Gemini");
            if (r.code == 200) return null;
            if (r.code == 400 && Net.errorMessage(r.body).toLowerCase(java.util.Locale.US).contains("api key"))
                return "Gemini rejected the key \u2014 check it in Settings";
            return Net.http(r.code, r.body, "Gemini (" + model + ")").user;
        } catch (IOException e) {
            return Net.explain(e);
        }
    }
}
