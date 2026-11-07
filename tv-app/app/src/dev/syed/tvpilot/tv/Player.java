package dev.syed.tvpilot.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streams 16-bit mono PCM (24 kHz: Phonon and Gemini Live both speak at that rate) to the speakers as it arrives.
 * Pieces are queued and written by its own thread, so a network reader never blocks on audio. Reports loudness for the
 * cookie, and can tell when everything queued has actually been heard.
 */
final class Player {
    interface Level { void level(float l); }

    static final int RATE = 24000;
    private static final byte[] END = new byte[0];

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final Level level;
    private volatile boolean stopped;
    private volatile long queuedFrames, writtenFrames;
    private volatile AudioTrack track;
    private volatile String error;
    private Thread thread;
    private int odd = -1;

    Player(Level level) { this.level = level; }

    synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::loop, "tvpilot-player");
        thread.start();
    }

    /** Queues raw little-endian PCM bytes (any length; an odd trailing byte is kept for the next piece). */
    synchronized void write(byte[] pcm, int off, int len) {
        if (stopped || len <= 0) return;
        start();
        byte[] b;
        if (odd >= 0) {
            b = new byte[len + 1];
            b[0] = (byte) odd;
            System.arraycopy(pcm, off, b, 1, len);
            odd = -1;
        } else {
            b = new byte[len];
            System.arraycopy(pcm, off, b, 0, len);
        }
        if ((b.length & 1) == 1) {
            odd = b[b.length - 1] & 0xff;
            byte[] e = new byte[b.length - 1];
            System.arraycopy(b, 0, e, 0, e.length);
            b = e;
        }
        queuedFrames += b.length / 2;
        queue.offer(b);
    }

    /** Drops what hasn't been played yet (the model was interrupted). */
    void flush() {
        queue.clear();
        AudioTrack t = track;
        if (t != null) try { t.pause(); t.flush(); t.play(); } catch (RuntimeException ignored) {}
        writtenFrames = queuedFrames = 0;
        if (t != null) try { basePosition = t.getPlaybackHeadPosition(); } catch (RuntimeException ignored) {}
    }

    private volatile int basePosition;

    /** True once everything queued so far has come out of the speaker (or nothing was ever queued). */
    boolean drained() {
        if (stopped) return true;
        if (!queue.isEmpty()) return false;
        AudioTrack t = track;
        if (t == null) return queuedFrames == 0;
        try {
            return (t.getPlaybackHeadPosition() - basePosition) >= writtenFrames - 240;
        } catch (RuntimeException e) {
            return true;
        }
    }

    boolean playedAnything() { return queuedFrames > 0 || writtenFrames > 0; }

    /** How long everything queued so far takes to play. */
    long queuedMs() { return queuedFrames * 1000L / RATE; }

    String error() { return error; }

    void stop() {
        stopped = true;
        queue.clear();
        queue.offer(END);
    }

    private void loop() {
        AudioTrack t = null;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min, RATE)).build();
            if (t.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("the TV wouldn't open its speaker");
            t.play();
            track = t;
            basePosition = 0;
            int bufFrames = Math.max(min, RATE) / 2;
            boolean padded = true;
            while (!stopped) {
                byte[] b = queue.poll(250, TimeUnit.MILLISECONDS);
                if (b == null) {
                    // Nothing more for now: push a buffer of silence through, so a short reply that never filled the
                    // track's start threshold still plays (otherwise it sits in the buffer and drained() never comes).
                    if (!padded && writtenFrames > 0) {
                        padded = true;
                        byte[] z = new byte[bufFrames * 2];
                        int off = 0;
                        while (off < z.length && !stopped && queue.isEmpty()) {
                            int n = t.write(z, off, z.length - off);
                            if (n <= 0) break;
                            off += n;
                        }
                        writtenFrames += off / 2;   // counted, so drained() still means "everything written has played"
                    }
                    if (level != null && drained()) level.level(0);
                    continue;
                }
                if (b == END) break;
                padded = false;
                long sum = 0;
                for (int i = 0; i + 1 < b.length; i += 2) {
                    int v = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
                    sum += (long) v * v;
                }
                double rms = Math.sqrt(sum / Math.max(1.0, b.length / 2.0));
                double db = 20 * Math.log10(Math.max(1, rms) / 32768.0);
                if (level != null) level.level((float) Math.max(0, Math.min(1, (db + 46) / 34)));
                int off = 0;
                while (off < b.length && !stopped) {
                    int n = t.write(b, off, b.length - off);
                    if (n < 0) throw new IllegalStateException("speaker write failed (" + n + ")");
                    off += n;
                }
                writtenFrames += b.length / 2;
            }
        } catch (InterruptedException ignored) {
        } catch (RuntimeException e) {
            error = Net.explain(e);
            Log.w("player: " + e);
        } finally {
            stopped = true;   // nothing more will play: never let drained() wait on a speaker that's gone
            track = null;
            if (t != null) {
                try { t.stop(); } catch (RuntimeException ignored) {}
                t.release();
            }
            if (level != null) level.level(0);
        }
    }
}
