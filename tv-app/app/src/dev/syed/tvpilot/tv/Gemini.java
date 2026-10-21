package dev.syed.tvpilot.tv;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Speech to text with Gemini (generateContent with the recording inline), straight from the TV. */
final class Gemini {
    private Gemini() {}

    static String transcribe(short[] pcm, String key, String model) throws Exception {
        String audio = Base64.encodeToString(Recorder.wav(pcm), Base64.NO_WRAP);
        String prompt = "Transcribe this voice command spoken to a TV remote exactly as said. The speaker may use English, "
                + "Hindi or Hinglish: write Hindi words in Latin script. Keep app, movie, show and channel names as they are "
                + "normally spelled. Reply with only the transcript, nothing else. If there is no speech, reply with an empty string.";
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray()
                        .put(new JSONObject().put("text", prompt))
                        .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "audio/wav").put("data", audio))))))
                .put("generationConfig", new JSONObject().put("temperature", 0));
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model.replaceAll("[^A-Za-z0-9._-]", "") + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) {
            String msg = resp;
            try { msg = new JSONObject(resp).getJSONObject("error").optString("message", resp); } catch (Exception ignored) {}
            throw new Exception("Gemini " + code + ": " + (msg.length() > 140 ? msg.substring(0, 140) + "…" : msg));
        }
        JSONObject o = new JSONObject(resp);
        JSONArray cands = o.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return "";
        JSONArray parts = cands.getJSONObject(0).optJSONObject("content") == null ? null
                : cands.getJSONObject(0).getJSONObject("content").optJSONArray("parts");
        StringBuilder b = new StringBuilder();
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (p.optBoolean("thought")) continue;
            b.append(p.optString("text", ""));
        }
        return b.toString().trim().replaceAll("^\"|\"$", "");
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
