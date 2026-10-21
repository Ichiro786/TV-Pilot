package dev.syed.tvpilot.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * TV Pilot's voice: Phonon on the VM. The words go up, 24 kHz audio streams back and plays as it arrives, so speech
 * starts in a fraction of a second. Reports how loud each moment is, so the cookie can move with the voice.
 */
final class Speaker {
    interface Listener {
        void level(float l);   // 0..1, from the speaking thread
        void done(String error);
    }

    private static final int RATE = 24000;
    private static volatile int generation;

    /** Speaks text in the chosen voice (or the given one). Stops whatever was speaking. */
    static void say(String text, String voice, Listener l) {
        final int me = ++generation;
        new Thread(() -> {
            String err = null;
            AudioTrack track = null;
            try {
                HttpURLConnection c = Server.open(Server.tts() + "/v1/audio/speech", "POST", 30000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                JSONObject body = new JSONObject().put("model", "tts-1").put("input", text)
                        .put("voice", voice == null || voice.isEmpty() ? "default" : voice).put("response_format", "pcm");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code != 200) throw new IllegalStateException("voice server said " + code + ": " + Server.read(c.getErrorStream()));
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min, RATE)).build();
                track.play();
                byte[] buf = new byte[RATE / 10 * 2];   // ~100 ms
                int written = 0, odd = -1;
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buf, odd >= 0 ? 1 : 0, buf.length - (odd >= 0 ? 1 : 0))) > 0 && me == generation) {
                        if (odd >= 0) { buf[0] = (byte) odd; n += 1; odd = -1; }
                        if ((n & 1) == 1) { odd = buf[n - 1] & 0xff; n--; }
                        // loudness of this piece, for the cookie
                        long sum = 0;
                        for (int i = 0; i + 1 < n; i += 2) {
                            int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                            sum += (long) v * v;
                        }
                        double rms = Math.sqrt(sum / Math.max(1.0, n / 2.0));
                        double db = 20 * Math.log10(Math.max(1, rms) / 32768.0);
                        if (l != null) l.level((float) Math.max(0, Math.min(1, (db + 46) / 34)));
                        track.write(buf, 0, n);
                        written += n / 2;
                    }
                }
                if (me == generation) {
                    // let the tail play out
                    while (track.getPlaybackHeadPosition() < written && me == generation) Thread.sleep(30);
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.w("voice: " + err);
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (RuntimeException ignored) {}
                    track.release();
                }
            }
            if (l != null) {
                l.level(0);
                l.done(me == generation ? err : null);
            }
        }, "tvpilot-voice").start();
    }

    static void stop() { generation++; }
}
