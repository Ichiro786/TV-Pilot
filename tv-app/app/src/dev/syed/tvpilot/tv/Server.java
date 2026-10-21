package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Your own server (blank by default, set in Settings): Parakeet turns speech into words (port 8081), Phonon speaks the
 * replies (port 8082). Both take the VM key as a Bearer token.
 */
final class Server {
    /** d1 on 8080, Parakeet on 8081, Phonon on 8082. "https://8080-x.host" style links swap the leading port; others get ":port". */
    static String url(int port) {
        String l = Prefs.vmLink();
        if (l.isEmpty()) throw new IllegalStateException("No server set. Add your VM link in TV Pilot TV's settings.");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https?://)\\d+-(.*)$").matcher(l);
        if (m.matches()) return m.group(1) + port + "-" + m.group(2).replaceAll("/.*$", "");
        String base = l.replaceAll("^(https?://[^/:]+).*$", "$1");
        if (!base.startsWith("http")) base = "http://" + base.replaceAll("[/:].*$", "");
        return base + ":" + port;
    }
    static String asr() { return url(8081); }
    static String tts() { return url(8082); }

    static String key() { return Prefs.vmKey(); }

    static HttpURLConnection open(String url, String method, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("Authorization", "Bearer " + key());
        return c;
    }

    /** Parakeet: 16 kHz mono PCM in, words out. */
    static String transcribe(short[] pcm) throws Exception {
        byte[] wav = Recorder.wav(pcm);
        String b = "----tvpilot" + System.nanoTime();
        HttpURLConnection c = open(asr() + "/v1/audio/transcriptions", "POST", 30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
        try (OutputStream o = c.getOutputStream()) {
            o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nparakeet\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(wav);
            o.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code != 200) throw new IllegalStateException("speech server said " + code + (code == 502 || code == 503 ? " (the VM may be waking up)" : ""));
        return new JSONObject(body).optString("text").trim();
    }

    /** Phonon's voice names. */
    static List<String> voices() throws Exception {
        HttpURLConnection c = open(tts() + "/v1/audio/voices", "GET", 15000);
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code != 200) throw new IllegalStateException("voice server said " + code);
        JSONArray a = new JSONObject(body).getJSONArray("voices");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
