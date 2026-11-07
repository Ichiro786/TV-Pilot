package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Your own server (blank by default, set in Settings): d1 on 8080, speech-to-text on 8081 (Parakeet / Bark, the
 * OpenAI-style /v1/audio/transcriptions), Phonon speaking the replies on 8082. All take the server key as a Bearer token.
 */
final class Server {
    private Server() {}

    static final String OFFLINE = "Your server is offline \u2014 turn on the VM";

    /** "https://8080-x.host" style links swap the leading port; others get ":port". */
    static String url(int port) {
        String l = Prefs.vmLink();
        if (l.isEmpty()) throw new IllegalStateException("No server link set \u2014 add it in TV Pilot TV's Settings");
        return url(l, port);
    }

    static String url(String l, int port) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https?://)\\d+-(.*)$").matcher(l);
        if (m.matches()) return m.group(1) + port + "-" + m.group(2).replaceAll("/.*$", "");
        String base = l.replaceAll("^(https?://[^/:]+).*$", "$1");
        if (!base.startsWith("http")) base = "http://" + base.replaceAll("[/:].*$", "");
        return base + ":" + port;
    }

    static String asr() { return url(8081); }
    static String tts() { return url(8082); }
    static String key() { return Prefs.vmKey(); }
    static String[][] auth() { return new String[][] {{"Authorization", "Bearer " + key()}}; }

    // MARK: - Is it up? (the VM pauses itself after 30 idle minutes)

    private static volatile long checkedAt;
    private static volatile Boolean up;
    private static volatile String why = "";

    /** Last known state, if checked in the last 2 minutes: TRUE up, FALSE down, null unknown. */
    static Boolean knownUp() {
        return System.currentTimeMillis() - checkedAt < 120_000 ? up : null;
    }

    static String downReason() { return why; }

    static void markDown(String reason) { up = false; why = reason; checkedAt = System.currentTimeMillis(); }
    static void markUp() { up = true; why = ""; checkedAt = System.currentTimeMillis(); }

    /**
     * Quick check of Phonon's open /health (8082, the same VM): reachable means the VM is awake. Takes at most ~6 s.
     * Returns null when up, or what's wrong.
     */
    static String probe() {
        if (!Prefs.hasVm()) return "No server link set";
        try {
            // A paused VM wakes on the first request and takes ~8-10 s to answer (measured Oct 22, 2026): allow for it.
            Net.Response r = Net.request(url(8082) + "/health", "GET", null, null, null, 6000, 15_000, "Your server");
            if (r.code >= 500) { markDown(OFFLINE); return OFFLINE + " (it answered " + r.code + ")"; }
            markUp();
            return null;
        } catch (IOException e) {
            String m = e instanceof Net.Fail && ((Net.Fail) e).offline ? OFFLINE : Net.explain(e);
            // Slow is not down (it may still be waking up): only a refused / unreachable server counts as offline.
            String d = String.valueOf(e.getMessage()) + " " + e;
            boolean slow = e instanceof java.net.SocketTimeoutException || d.contains("SocketTimeout") || m.contains("didn't answer");
            if (!slow) markDown(m);
            return slow ? "Your server is slow to answer (it may be waking up)" : m;
        }
    }

    /** Server failures that mean "the VM is off" are remembered so the next steps skip it at once. */
    static IOException note(IOException e) {
        if (e instanceof Net.Fail && ((Net.Fail) e).offline) markDown(OFFLINE);
        return e;
    }

    // MARK: - Speech to text

    /** 16 kHz mono PCM in, words out. */
    static String transcribe(short[] pcm) throws IOException {
        byte[] wav = Recorder.wav(pcm);
        Net.Response r = postAudio(wav, true);
        if (r.code == 400 || r.code == 422) r = postAudio(wav, false);   // newer servers may not know the "parakeet" name
        if (r.code != 200) throw note(Net.http(r.code, r.body, "The speech server"));
        markUp();
        try {
            return new JSONObject(r.body).optString("text").trim();
        } catch (Exception e) {
            throw new Net.Fail("The speech server sent something unreadable", 200, false, Net.shorten(r.body, 120));
        }
    }

    private static Net.Response postAudio(byte[] wav, boolean withModel) throws IOException {
        String b = "----tvpilot" + System.nanoTime();
        ByteArrayOutputStream o = new ByteArrayOutputStream(wav.length + 400);
        try {
            if (withModel) o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nparakeet\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(wav);
            o.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
        try {
            return Net.request(asr() + "/v1/audio/transcriptions", "POST", "multipart/form-data; boundary=" + b, o.toByteArray(),
                    auth(), 6000, 25_000, "The speech server");
        } catch (IOException e) {
            throw note(e);
        }
    }

    /** Phonon's voice names. */
    static List<String> voices() throws IOException {
        Net.Response r;
        try {
            r = Net.request(tts() + "/v1/audio/voices", "GET", null, null, auth(), 6000, 12_000, "The voice server");
        } catch (IOException e) {
            throw note(e);
        }
        if (r.code != 200) throw note(Net.http(r.code, r.body, "The voice server"));
        try {
            JSONArray a = new JSONObject(r.body).getJSONArray("voices");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
            return out;
        } catch (Exception e) {
            throw new Net.Fail("The voice server sent an unreadable voice list", 200, false, Net.shorten(r.body, 120));
        }
    }

    static String read(InputStream in) throws IOException { return Net.read(in, 4 * 1024 * 1024); }
}
