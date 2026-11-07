package dev.syed.tvpilot.tv;

import android.content.Context;
import android.net.ConnectivityManager;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Settings → Diagnostics: checks each service the agent uses and says OK or exactly what's wrong. */
final class Diagnostics {
    private Diagnostics() {}

    interface Out { void line(String text, boolean last); }

    static final String OK = "\u2713 ", BAD = "\u2717 ", SKIP = "\u2013 ";

    /** Runs every check on the calling (background) thread, reporting each line as it finishes. */
    static void run(Context c, Out out) {
        String key = Prefs.geminiKey();

        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean net = cm == null || cm.getActiveNetwork() != null;
        out.line((net ? OK : BAD) + "Internet: " + (net ? "connected" : "the TV isn't connected"), false);

        boolean svc = Setup.serviceEnabled(c) && PilotService.instance != null;
        out.line((svc ? OK : BAD) + "Mic button service: " + (svc ? "on" : "off \u2014 select \u201CMic button service\u201D"), false);

        String adb = Tv.get(c).check();
        out.line((adb == null ? OK : BAD) + "TV debugging (ADB 127.0.0.1:5555): " + (adb == null ? "OK" : adb), false);
        // 1.2.0: can TV Pilot read the screen, and how fast is a step?
        if (svc) {
            long t0 = System.currentTimeMillis();
            Ui.Snapshot s = PilotService.instance.snapshot();
            out.line((s.empty() ? BAD : OK) + "Screen reading (accessibility tree): " + s.nodes.size() + " elements from " + (s.pkg.isEmpty() ? "?" : s.pkg)
                    + " in " + (System.currentTimeMillis() - t0) + " ms" + (s.usable() ? "" : " (this screen draws its own UI: vision is used)"), false);
        }
        if (adb == null) {
            try {
                long t0 = System.currentTimeMillis();
                Device.Frame f = Tv.get(c).capture(1280);
                out.line(OK + "Screenshot for the decision model: " + f.jpeg.length / 1024 + " KB JPEG (" + ((android.graphics.Bitmap) f.image).getWidth() + "x"
                        + ((android.graphics.Bitmap) f.image).getHeight() + ") in " + (System.currentTimeMillis() - t0) + " ms", false);
                if (!Prefs.liquidKey().isEmpty()) {
                    org.json.JSONObject q = new org.json.JSONObject().put("screen", Decider.choice("What is on this TV screen?", Driver.KINDS));
                    Decider.Answer a = Agent.decider().ask("A TV screen.", java.util.Collections.singletonList(f.jpeg), q, 15_000);
                    out.line(OK + Prefs.liquidModel() + " sees the screen: " + a.choice("screen") + String.format(java.util.Locale.US, " (%.0f%%) in %d ms", a.conf("screen") * 100, a.ms), false);
                }
            } catch (Exception e) {
                out.line(BAD + "Screenshot / vision check: " + Net.explain(e), false);
            }
        }
        out.line(SKIP + "Rulebook " + Rulebook.version + " \u00B7 learn log: " + Learn.stats(), false);
        String w = PilotService.lastWindowsAfterMic;
        if (!w.isEmpty()) out.line(SKIP + "Windows seen right after the last mic press (to find what draws a white circle): " + Net.shorten(w, 600), false);
        String ev = Prefs.lastEval();
        if (!ev.isEmpty()) out.line(SKIP + "Last accuracy test: " + ev.split("\n")[0], false);

        if (key.isEmpty()) {
            out.line(BAD + "Gemini: no key set (needed for Gemini Live, vision and chat)", false);
        } else {
            String g = Gemini.check(key, Prefs.visionModel());
            out.line((g == null ? OK : BAD) + "Gemini " + Prefs.visionModel() + ": " + (g == null ? "key OK" : g), false);
            if (!Prefs.chatModel().equals(Prefs.visionModel()) && g == null) {
                String gc = Gemini.check(key, Prefs.chatModel());
                out.line((gc == null ? OK : BAD) + "Gemini " + Prefs.chatModel() + " (chat): " + (gc == null ? "OK" : gc), false);
            }
            String lv = live(key);
            out.line((lv == null ? OK : BAD) + "Gemini Live " + Prefs.liveModel() + ": " + (lv == null ? "answers out loud (voice " + Prefs.liveVoice() + ")" : lv), false);
            String last = Prefs.lastLive();
            if (!last.isEmpty()) out.line((last.contains("  ok:") ? OK : BAD) + "Last Live session: " + last, false);
        }

        if (!Prefs.hasVm()) {
            out.line(SKIP + "Your server (d1 / speech / Phonon): no link set", false);
        } else {
            String up = Server.probe();
            out.line((up == null ? OK : BAD) + "Your server: " + (up == null ? "awake" : up), false);
            if (up == null) {
                String asr = asr();
                out.line((asr == null ? OK : BAD) + "Speech to text (:8081): " + (asr == null ? "OK" : asr), false);
                String d1 = Brain.checkD1();
                out.line((d1 == null ? OK : BAD) + "d1 (:8080): " + (d1 == null ? "OK" : d1), false);
                String ph;
                int n = 0;
                try { n = Server.voices().size(); ph = null; } catch (Exception e) { ph = Net.explain(e); }
                out.line((ph == null ? OK : BAD) + "Phonon voice (:8082): " + (ph == null ? "OK, " + n + " voices" : ph), false);
            }
        }

        if (Prefs.liquidKey().isEmpty()) out.line(SKIP + "Liquid d1-ultra: no key set" + (Prefs.hasVm() ? " (your d1 decides)" : ""), false);
        else {
            String lq = Brain.checkLiquid();
            out.line((lq == null ? OK : BAD) + "Liquid " + Prefs.liquidModel() + ": " + (lq == null ? "OK" : lq), false);
        }

        String rec = Prefs.lastTest();
        if (!rec.isEmpty()) out.line((rec.contains("\u2713") ? OK : BAD) + "Last recording: " + rec, false);

        String crash = Prefs.lastCrash();
        out.line(crash.isEmpty() ? OK + "No crashes recorded" : BAD + "Last crash: " + crash, true);
    }

    /** Half a second of silence through your speech server: proves the link, the key and the model. */
    private static String asr() {
        try {
            Server.transcribe(new short[8000]);
            return null;
        } catch (Exception e) {
            return Net.explain(e);
        }
    }

    /**
     * A real Gemini Live round trip with the app's own setup (the same functions, turn-taking and voice): asks it to
     * say "OK" and waits for the spoken reply. You'll hear it on the TV.
     */
    static String live(String key) {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] err = {null};
        final Live.Tools tools = new Live.Tools() {
            @Override public org.json.JSONArray declarations() throws Exception { return LiveDecl.declarations(); }
            @Override public JSONObject run(String name, JSONObject args) throws Exception { return new JSONObject().put("result", "skipped in Diagnostics"); }
            @Override public byte[] screen() { return null; }
        };
        Live l = new Live(key, Prefs.liveModel(), Prefs.liveVoice(),
                "This is a connection test. Reply with just the word OK, out loud, then call finish. Do not call any other function.",
                tools, new Live.Events() {
            @Override public void ready() {}
            @Override public void heard(String text) {}
            @Override public void said(String text) {}
            @Override public void level(float lv) {}
            @Override public void step(String what) {}
            @Override public void finished(String heard, String said) {
                if (said.isEmpty()) err[0] = "the session opened but the reply had no words";
                done.countDown();
            }
            @Override public void failed(String reason, boolean beforeOutput) { err[0] = reason; done.countDown(); }
        });
        l.start();
        l.text("Say OK.");
        try {
            if (!done.await(25, TimeUnit.SECONDS)) { l.cancel(); return "no spoken reply in 25 s"; }
        } catch (InterruptedException e) {
            l.cancel();
            return "stopped";
        }
        return err[0];
    }
}
