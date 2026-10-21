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
 * opened it). Stops by itself after you stop talking, or after 12 s, or when stop() is called. Reports the level as it
 * goes, and at the end what it heard: the samples plus enough to tell "the remote's mic never streamed" apart from
 * "you were quiet".
 */
final class Recorder {
    static final int RATE = 16000;

    interface Listener {
        void level(float level);
        void routed(String device);
        void done(Result r);
    }

    static final class Result {
        short[] pcm = new short[0];
        float seconds, peak;      // peak as 0..1 level
        int peakRaw;
        boolean speech, silentStream, failed;
        String device = "", error, source = "";
    }

    private volatile boolean stop;
    private Thread thread;

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

    void stop() {
        stop = true;
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
        Result res = new Result();
        AudioRecord rec = null;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try {
            rec = open(context, res);
            short[] buf = new short[RATE / 10];   // 100 ms
            int filled = 0, chunks = 0, loud = 0, quietAfter = 0, restarts = 0;
            boolean routedSent = false, gotSound = false;
            long started = System.currentTimeMillis(), lastData = started;
            while (!stop) {
                // Non-blocking: right after Google lets go, the remote's mic can open but send nothing.
                int n = rec.read(buf, filled, buf.length - filled, AudioRecord.READ_NON_BLOCKING);
                if (n < 0) throw new IllegalStateException("microphone read failed (" + n + ")");
                long now = System.currentTimeMillis();
                if (n > 0) lastData = now;
                filled += n;
                if (filled < buf.length) {
                    if (now - lastData > 1500 || (!gotSound && now - started > 1500 && restarts == 0 && chunks == 0)) {
                        if (restarts >= 2) throw new IllegalStateException("the mic opened but sent nothing");
                        restarts++;
                        Log.w("mic stuck, reopening it");
                        try { rec.stop(); } catch (RuntimeException ignored) {}
                        rec.release();
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
                long sum = 0;
                int peak = 0;
                for (int i = 0; i < buf.length; i++) {
                    int v = buf[i];
                    sum += (long) v * v;
                    int av = Math.abs(v);
                    if (av > peak) peak = av;
                    all.write(v & 0xff);
                    all.write((v >> 8) & 0xff);
                }
                if (peak > 40) gotSound = true;
                if (peak > res.peakRaw) res.peakRaw = peak;
                double rms = Math.sqrt(sum / (double) buf.length);
                double db = 20 * Math.log10(Math.max(1, rms) / 32768.0);
                float lv = (float) Math.max(0, Math.min(1, (db + 52) / 38));
                res.peak = Math.max(res.peak, lv);
                l.level(lv);
                if (!routedSent && chunks >= 3) {
                    routedSent = true;
                    res.device = describe(rec.getRoutedDevice());
                    l.routed(res.device);
                }
                // Silence for the first 2 s of a fresh mic: reopen once (it sometimes streams zeros at first).
                if (!gotSound && chunks == 20 && restarts == 0) {
                    restarts++;
                    Log.w("only silence so far, reopening the mic");
                    try { rec.stop(); } catch (RuntimeException ignored) {}
                    rec.release();
                    rec = null;
                    rec = open(context, res);
                    lastData = System.currentTimeMillis();
                    continue;
                }
                // End of speech: talking (level > 0.38 for 0.2 s), then 1.1 s under 0.22.
                if (lv > 0.38f) {
                    loud++;
                    quietAfter = 0;
                    if (loud >= 2) res.speech = true;
                } else if (res.speech && lv < 0.22f) {
                    if (++quietAfter >= 11) break;
                }
                if (!res.speech && chunks >= 70) break;   // 7 s and nothing said
                if (chunks >= 120) break;                 // 12 s max
            }
            if (!routedSent && rec != null) res.device = describe(rec.getRoutedDevice());
        } catch (SecurityException e) {
            res.failed = true;
            res.error = "microphone permission is off";
        } catch (InterruptedException e) {
            res.failed = true;
            res.error = "stopped";
        } catch (RuntimeException e) {
            res.failed = true;
            res.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (RuntimeException ignored) {}
                rec.release();
            }
        }
        byte[] b = all.toByteArray();
        res.pcm = new short[b.length / 2];
        for (int i = 0; i < res.pcm.length; i++) res.pcm[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        res.seconds = res.pcm.length / (float) RATE;
        res.silentStream = !res.failed && res.peakRaw < 40;   // digital silence: the mic never really opened
        l.done(res);
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
