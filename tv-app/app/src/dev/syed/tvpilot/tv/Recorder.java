package dev.syed.tvpilot.tv;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/**
 * Records one utterance at 16 kHz mono through whatever mic the TV routes (normally the remote's, once the mic button
 * opened it). Stops by itself 1.2 s after you stop talking (judged against the room's own noise, see Endpointer),
 * after 9 s of talking or 10 s in all (12 s hard), or when stop() is called. Reports the level as it
 * goes, and at the end what it heard: the samples plus enough to tell "the remote's mic never streamed" apart from
 * "you were quiet".
 */
final class Recorder {
    static final int RATE = 16000;

    interface Listener {
        void level(float level);
        void routed(String device);
        void done(Result r);
        /** Each 100 ms of audio as it's recorded (for streaming to Gemini Live). The array is reused: copy it. */
        default void chunk(short[] pcm, int n) {}
    }

    static final class Result {
        short[] pcm = new short[0];
        float seconds, peak;      // peak as 0..1 level
        int peakRaw;
        boolean speech, silentStream, failed;
        /** Only faint sound: it may still be words, so speech-to-text gets a go. */
        boolean weak;
        String device = "", error, source = "", ended = "";
    }

    /** Hard wall-clock limit for one recording once the mic is open, whatever the audio does. */
    static final long HARD_MS = 12_000;

    private volatile boolean stop, abandoned;
    private final java.util.concurrent.atomic.AtomicBoolean reported = new java.util.concurrent.atomic.AtomicBoolean();
    private Thread thread;
    private final ByteArrayOutputStream all = new ByteArrayOutputStream();
    private final Result live = new Result();
    private volatile Endpointer ep;

    static String describe(AudioDeviceInfo d) {
        if (d == null) return "unknown";
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: type = "built-in mic"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = "Bluetooth SCO"; break;
            case AudioDeviceInfo.TYPE_USB_DEVICE: case AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: type = "wired headset"; break;
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX: type = "remote submix"; break;
            case AudioDeviceInfo.TYPE_IP: type = "IP"; break;
            case AudioDeviceInfo.TYPE_BUS: type = "bus"; break;
            case 26: type = "BLE headset"; break;   // TYPE_BLE_HEADSET (Android 12)
            default: type = "type " + d.getType();
        }
        CharSequence name = d.getProductName();
        String n = name == null ? "" : name.toString().trim();
        String addr = "";
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try { addr = d.getAddress(); } catch (Throwable ignored) {}
        }
        return (n.isEmpty() ? type : n + " (" + type + ")") + (addr == null || addr.isEmpty() ? "" : " " + addr);
    }

    void start(final Context context, final Listener l) {
        stop = false;
        thread = new Thread(() -> run(context, l), "tvpilot-mic");
        thread.start();
    }

    /** Ends the recording soon (the loop checks every 15 ms) and reports what was heard. */
    void stop() {
        stop = true;
    }

    /** True while the thread hasn't reported yet. */
    boolean running() { return !reported.get(); }

    /**
     * The mic thread is stuck (a TV audio driver call that never returns): give up on it and report what was
     * recorded so far, right now. Its own report, if it ever comes, is dropped. Returns null if it already reported.
     */
    Result abandon(String why) {
        stop = true;
        abandoned = true;
        if (!reported.compareAndSet(false, true)) return null;
        Thread t = thread;
        if (t != null) t.interrupt();
        Result r = finish(live);
        r.error = why;
        return r;
    }

    private Result finish(Result res) {
        byte[] b;
        synchronized (all) { b = all.toByteArray(); }
        res.pcm = new short[b.length / 2];
        for (int i = 0; i < res.pcm.length; i++) res.pcm[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        res.seconds = res.pcm.length / (float) RATE;
        Endpointer e = ep;
        if (e != null && !res.speech && e.weak && !res.failed) { res.speech = true; res.weak = true; }   // let speech-to-text decide
        res.silentStream = !res.failed && res.peakRaw < 40;   // digital silence: the mic never really opened
        return res;
    }

    /** How many recordings other apps have open right now (Android hides who, but not how many). */
    static int othersRecording(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            return am.getActiveRecordingConfigurations().size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Opens the mic, retrying while Google (or anyone) is still letting go of it. */
    private AudioRecord open(Context context, Result res) throws InterruptedException {
        // Wait up to 2.5 s for other recordings to close.
        long until = System.currentTimeMillis() + 2500;
        int others = othersRecording(context);
        if (others > 0) Log.w(others + " other recording(s) open, waiting for them to finish");
        while (others > 0 && System.currentTimeMillis() < until && !stop) {
            Thread.sleep(100);
            others = othersRecording(context);
        }
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_RECOGNITION};
        String last = "the TV wouldn't open a microphone";
        for (int attempt = 0; attempt < sources.length && !stop; attempt++) {
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(sources[attempt], RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, RATE));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("the TV wouldn't open a microphone");
                int want = Prefs.device();
                if (want != 0 && attempt % 2 == 0) {   // every other try lets the TV pick the input
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                        if (d.getId() == want) { rec.setPreferredDevice(d); break; }
                    }
                }
                rec.startRecording();
                if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("the mic is busy (" + othersRecording(context) + " visible recordings open)");
                }
                if (attempt > 0) Log.w("mic opened on try " + (attempt + 1) + (sources[attempt] == MediaRecorder.AudioSource.MIC ? " (MIC source)" : ""));
                res.source = sources[attempt] == MediaRecorder.AudioSource.VOICE_RECOGNITION ? "voice" : sources[attempt] == MediaRecorder.AudioSource.MIC ? "mic" : "default";
                return rec;
            } catch (RuntimeException e) {
                last = e.getMessage() == null ? e.toString() : e.getMessage();
                if (rec != null) rec.release();
                Thread.sleep(350);
            }
        }
        throw new IllegalStateException(last);
    }

    private void run(Context context, Listener l) {
        Result res = live;
        AudioRecord rec = null;
        Endpointer e = new Endpointer();
        ep = e;
        try {
            rec = open(context, res);
            short[] buf = new short[RATE / 10];   // 100 ms
            int filled = 0, chunks = 0, restarts = 0;
            boolean routedSent = false, gotSound = false;
            long started = System.currentTimeMillis(), lastData = started, opened = started;
            while (!stop) {
                long now = System.currentTimeMillis();
                if (now - opened > HARD_MS) { res.ended = "hard time limit"; Log.w("mic: hard time limit reached"); break; }
                // Non-blocking: right after Google lets go, the remote's mic can open but send nothing.
                int n = rec.read(buf, filled, buf.length - filled, AudioRecord.READ_NON_BLOCKING);
                if (n < 0) throw new IllegalStateException("microphone read failed (" + n + ")");
                if (n > 0) lastData = now;
                filled += n;
                if (filled < buf.length) {
                    if (now - lastData > 1500 || (!gotSound && now - started > 1500 && restarts == 0 && chunks == 0)) {
                        if (restarts >= 2) throw new IllegalStateException("the mic opened but sent nothing");
                        restarts++;
                        Log.w("mic stuck, reopening it");
                        release(rec);
                        rec = null;
                        rec = open(context, res);
                        started = lastData = System.currentTimeMillis();
                        filled = 0;
                        continue;
                    }
                    Thread.sleep(15);
                    continue;
                }
                filled = 0;
                chunks++;
                int peak = 0;
                synchronized (all) {
                    for (int i = 0; i < buf.length; i++) {
                        int v = buf[i];
                        int av = Math.abs(v);
                        if (av > peak) peak = av;
                        all.write(v & 0xff);
                        all.write((v >> 8) & 0xff);
                    }
                }
                if (peak > 40) gotSound = true;
                if (peak > res.peakRaw) res.peakRaw = peak;
                float db = Endpointer.db(buf, buf.length);
                Endpointer.Verdict v = e.feed(db);
                float lv = e.level(db);
                res.peak = Math.max(res.peak, lv);
                res.speech = e.speech;
                try { l.level(lv); l.chunk(buf, buf.length); } catch (RuntimeException ex) { Log.w("mic listener: " + ex); }
                if (!routedSent && chunks >= 3) {
                    routedSent = true;
                    res.device = describe(rec.getRoutedDevice());
                    try { l.routed(res.device); } catch (RuntimeException ignored) {}
                }
                // Silence for the first 2 s of a fresh mic: reopen once (it sometimes streams zeros at first).
                if (!gotSound && chunks == 20 && restarts == 0) {
                    restarts++;
                    Log.w("only silence so far, reopening the mic");
                    release(rec);
                    rec = null;
                    rec = open(context, res);
                    lastData = System.currentTimeMillis();
                    continue;
                }
                if (v != Endpointer.Verdict.CONTINUE) {
                    res.ended = e.why;
                    if (chunks == 1 || v != Endpointer.Verdict.END_SPEECH) Log.w("mic: " + e.why);
                    break;
                }
            }
            if (stop && res.ended.isEmpty()) res.ended = "stopped";
            Log.w(String.format(java.util.Locale.US, "mic: %s after %d chunks, floor %.0f dB, speech %s%s", res.ended, chunks,
                    Float.isNaN(e.floor) ? -99f : e.floor, e.speech, e.weak && !e.speech ? " (faint sound only)" : ""));
            if (!routedSent && rec != null) res.device = describe(rec.getRoutedDevice());
        } catch (SecurityException ex) {
            res.failed = true;
            res.error = "microphone permission is off";
        } catch (InterruptedException ex) {
            if (!stop) { res.failed = true; res.error = "stopped"; }
        } catch (Throwable ex) {   // never leave the bar stuck on "Listening" (an Error would skip done())
            res.failed = true;
            res.error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        } finally {
            release(rec);
        }
        if (!reported.compareAndSet(false, true)) return;   // abandoned: someone else already reported
        try { l.done(finish(res)); } catch (RuntimeException ex) { Log.w("mic done handler: " + ex); }
    }

    /** Stops and frees the mic, never throwing (and never left open). */
    private static void release(AudioRecord rec) {
        if (rec == null) return;
        try { rec.stop(); } catch (Throwable ignored) {}
        try { rec.release(); } catch (Throwable ignored) {}
    }

    /** 16-bit mono WAV of the samples. */
    static byte[] wav(short[] pcm) {
        int dataLen = pcm.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes()).put("fmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(dataLen);
        for (short s : pcm) b.putShort(s);
        return b.array();
    }
}
