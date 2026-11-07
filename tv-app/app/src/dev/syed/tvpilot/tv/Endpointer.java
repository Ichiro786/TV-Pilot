package dev.syed.tvpilot.tv;

/**
 * Decides when you've stopped talking, from the loudness of each 100 ms of audio, against the room's own noise
 * (the remote's mic on some TVs never gives true silence, and the TV may be playing something). Pure Java, so it's
 * tested on a desktop JVM.
 *  - noise floor: learned from the quietest moments, drops fast, rises slowly;
 *  - speech starts at 0.2 s clearly above the floor (or one loud burst), even if you started before the mic opened;
 *  - speech ends after END_QUIET of audio back near the floor;
 *  - hard limits: MAX_SPEECH of talking, MAX_TOTAL overall, NO_SPEECH with nothing said.
 */
final class Endpointer {
    static final int CHUNK_MS = 100;
    static final int END_QUIET = 12;     // 1.2 s
    static final int MAX_SPEECH = 90;    // 9 s of talking
    static final int MAX_TOTAL = 100;    // 10 s in all
    static final int NO_SPEECH = 70;     // 7 s with nothing said

    enum Verdict { CONTINUE, END_SPEECH, END_MAX, END_NOTHING }

    float floor = Float.NaN;
    float speechLevel = Float.NaN;
    float maxDb = -120;
    boolean speech;
    /** Something clearly above the floor came through, even if it never counted as speech. */
    boolean weak;
    int chunks, loudRun, quiet, speechStart = -1;
    String why = "";

    /** One 100 ms chunk's RMS level in dBFS. */
    Verdict feed(float db) {
        chunks++;
        if (db > maxDb) maxDb = db;
        if (Float.isNaN(floor)) floor = db;
        else if (db < floor) floor = 0.6f * floor + 0.4f * db;                     // quieter: follow fast
        else if (!speech && db < floor + 6) floor += (db - floor) * 0.08f;         // a bit louder, no speech: follow slowly
        else if (speech && db < floor + 3) floor += (db - floor) * 0.03f;
        if (chunks <= 3 && db < floor) floor = db;

        float start = Math.max(floor + 9, -62);
        if (!speech) {
            if (db > start) loudRun++; else loudRun = 0;
            boolean burst = db > Math.max(floor + 16, -55);
            // Already talking when the mic opened: the floor only shows up once you pause.
            boolean before = chunks > 4 && maxDb > floor + 13 && db < floor + 4;
            if (loudRun >= 2 || burst || before) {
                speech = true;
                speechStart = before ? 0 : chunks - loudRun;
                speechLevel = before ? maxDb : db;
                quiet = before ? 1 : 0;
            } else if (maxDb > floor + 7) {
                weak = true;
            }
        } else {
            if (db > start) speechLevel = Float.isNaN(speechLevel) ? db : 0.85f * speechLevel + 0.15f * db;
            float end = floor + Math.max(5, 0.4f * (speechLevel - floor));
            if (db < end) quiet++;
            else if (db > end + 2) quiet = 0;
            if (quiet >= END_QUIET) { why = "stopped talking"; return Verdict.END_SPEECH; }
            if (chunks - speechStart >= MAX_SPEECH) { why = "talked for the longest allowed"; return Verdict.END_MAX; }
        }
        if (chunks >= MAX_TOTAL) { why = "time limit"; return speech ? Verdict.END_MAX : Verdict.END_NOTHING; }
        if (!speech && chunks >= NO_SPEECH) { why = "nothing said"; return Verdict.END_NOTHING; }
        return Verdict.CONTINUE;
    }

    /** Level for the cookie (0..1), relative to the room so noise doesn't make it dance. */
    float level(float db) {
        float f = Float.isNaN(floor) ? -60 : floor;
        return Math.max(0, Math.min(1, (db - f - 3) / 30f));
    }

    static float db(short[] pcm, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += (long) pcm[i] * pcm[i];
        double rms = Math.sqrt(sum / (double) Math.max(1, n));
        return (float) (20 * Math.log10(Math.max(1, rms) / 32768.0));
    }
}
