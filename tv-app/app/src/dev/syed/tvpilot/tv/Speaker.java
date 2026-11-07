package dev.syed.tvpilot.tv;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TV Pilot's voice. Phonon on your server first (24 kHz audio streams back and plays as it arrives); when there is no
 * server, it's asleep, or it fails before any sound came out, the TV's own text-to-speech says it instead, so a reply
 * (or an error) is never silently lost. Reports loudness so the cookie moves with the voice.
 */
final class Speaker {
    interface Listener {
        void level(float l);   // 0..1, from the speaking thread
        void done(String error);
    }

    private static volatile int generation;
    private static volatile Player current;
    private static Context app;

    static void init(Context c) { if (app == null) app = c.getApplicationContext(); }

    /** Speaks text in the chosen Phonon voice (or the TV's voice as a fallback). Stops whatever was speaking. */
    static void say(final String text, final String voice, final Listener l) {
        final int me = ++generation;
        stopPlayback();
        SysTts.stop();
        new Thread(() -> {
            String err = null;
            boolean phonon = Prefs.hasVm() && !Boolean.FALSE.equals(Server.knownUp());
            boolean heard = false;
            if (phonon) {
                try {
                    heard = phonon(text, voice, l, me);
                } catch (Exception e) {
                    err = Net.explain(e);
                    Log.w("voice: Phonon failed: " + e);
                    if (e instanceof java.io.IOException) Server.note((java.io.IOException) e);
                }
            }
            if (!heard && me == generation) {
                // The TV's own voice: always there, even with the VM asleep.
                String e2 = SysTts.say(app, text, l, () -> me == generation);
                err = e2 == null ? null : (err != null ? err + "; " : "") + e2;
            }
            if (l != null) {
                l.level(0);
                l.done(me == generation ? err : null);
            }
        }, "tvpilot-voice").start();
    }

    /** Returns true once sound actually played. Throws when Phonon failed before any sound. */
    private static boolean phonon(String text, String voice, Listener l, int me) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(Server.tts() + "/v1/audio/speech").openConnection();
        java.io.Closeable closer = c::disconnect;
        Net.track(closer);
        TimerTask dog = Net.watch(c, 45_000);
        Player p = new Player(l == null ? null : l::level);
        current = p;
        boolean heard = false;
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(12_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + Server.key());
            byte[] body = new JSONObject().put("model", "tts-1").put("input", text)
                    .put("voice", voice == null || voice.isEmpty() ? "default" : voice).put("response_format", "pcm")
                    .toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(body); }
            int code = c.getResponseCode();
            if (code != 200) throw Net.http(code, Net.read(c.getErrorStream(), 4096), "The voice server");
            Server.markUp();
            byte[] buf = new byte[4800];
            try (InputStream in = c.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0 && me == generation) {
                    p.write(buf, 0, n);
                    heard = true;
                }
            } catch (java.io.IOException e) {
                if (!heard) throw e;   // nothing came out: let the TV's voice say it
                Log.w("voice: stream cut short: " + e);
            }
            long limit = System.currentTimeMillis() + p.queuedMs() + 3000;   // never wait forever on the speaker
            while (me == generation && !p.drained() && System.currentTimeMillis() < limit) Thread.sleep(40);   // let the tail play out
            return heard;
        } catch (java.io.IOException e) {
            throw Net.classify(e, "The voice server");
        } finally {
            dog.cancel();
            Net.untrack(closer);
            c.disconnect();
            p.stop();
            if (current == p) current = null;
        }
    }

    private static void stopPlayback() {
        Player p = current;
        current = null;
        if (p != null) p.stop();
    }

    static void stop() {
        generation++;
        stopPlayback();
        SysTts.stop();
    }

    /** The TV's built-in text-to-speech (Google / the maker's engine), Indian English when it has it. */
    static final class SysTts {
        interface Alive { boolean alive(); }

        private static TextToSpeech tts;
        private static volatile boolean ready;
        private static volatile String initError;

        static String say(Context c, String text, Listener l, Alive alive) {
            if (c == null) return "no voice engine";
            try {
                if (!ensure(c)) return initError != null ? initError : "the TV has no text-to-speech voice";
                final CountDownLatch done = new CountDownLatch(1);
                final String[] err = {null};
                final String id = "tvp" + System.nanoTime();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String u) {}
                    @Override public void onDone(String u) { if (id.equals(u)) done.countDown(); }
                    @Override public void onStop(String u, boolean interrupted) { if (id.equals(u)) done.countDown(); }
                    @Override @SuppressWarnings("deprecation") public void onError(String u) { if (id.equals(u)) { err[0] = "the TV's voice failed"; done.countDown(); } }
                    @Override public void onError(String u, int code) { if (id.equals(u)) { err[0] = "the TV's voice failed (" + code + ")"; done.countDown(); } }
                });
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id) != TextToSpeech.SUCCESS) return "the TV's voice refused";
                long until = System.currentTimeMillis() + 8000 + text.length() * 120L;
                float t = 0;
                while (!done.await(60, TimeUnit.MILLISECONDS)) {
                    if (!alive.alive()) { tts.stop(); return null; }
                    if (System.currentTimeMillis() > until) { tts.stop(); return "the TV's voice took too long"; }
                    t += 0.06f;
                    if (l != null) l.level(0.35f + 0.25f * (float) Math.abs(Math.sin(t * 7)));   // no real levels from TTS: a gentle pulse
                }
                return err[0];
            } catch (InterruptedException e) {
                return null;
            } catch (RuntimeException e) {
                return "the TV's voice failed: " + e.getMessage();
            }
        }

        private static synchronized boolean ensure(Context c) throws InterruptedException {
            if (tts != null && ready) return true;
            if (tts != null && initError != null) { tts.shutdown(); tts = null; }   // try again (an engine may have been installed)
            final CountDownLatch init = new CountDownLatch(1);
            initError = null;
            ready = false;
            final TextToSpeech[] holder = new TextToSpeech[1];
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> holder[0] = tts = new TextToSpeech(c.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    TextToSpeech t = holder[0];
                    int r = t.setLanguage(new Locale("en", "IN"));
                    if (r < 0) t.setLanguage(Locale.US);
                    ready = true;
                } else {
                    initError = "the TV has no text-to-speech voice";
                }
                init.countDown();
            }));
            if (!init.await(5, TimeUnit.SECONDS)) { initError = "the TV's voice didn't start"; return false; }
            return ready;
        }

        static void stop() {
            TextToSpeech t = tts;
            if (t != null && ready) try { t.stop(); } catch (RuntimeException ignored) {}
        }
    }
}
