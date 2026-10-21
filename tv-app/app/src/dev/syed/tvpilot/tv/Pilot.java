package dev.syed.tvpilot.tv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/**
 * One voice turn: mic button -> bar slides up and listens -> Gemini turns it into words -> the words show -> the bar
 * slides away. The bar lives in whichever host is in front: the accessibility overlay (over any app) or an activity.
 */
final class Pilot {
    interface Host {
        void attach(MicBar bar);
        void detach(MicBar bar);
    }

    private static Pilot instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MicBar bar;
    private Host host;
    private Recorder rec;
    private int turn;
    private volatile short[] lastPcm;
    private final Runnable autoHide = this::hide;
    private volatile Thread agent;

    /** The agent is driving the TV (its own Back presses must not cancel it). */
    boolean acting() { return agent != null; }

    boolean hostedBy(Host h) { return host == h && bar != null; }

    static synchronized Pilot get(Context c) {
        if (instance == null) instance = new Pilot(c.getApplicationContext());
        return instance;
    }

    private Pilot(Context app) {
        this.app = app;
        Prefs.init(app);
    }

    boolean showing() {
        return bar != null && bar.showing();
    }

    /** The mic button: starts listening, or (while listening) stops and uses what it heard. */
    void trigger(Host h) {
        main.removeCallbacks(autoHide);
        if (bar != null && bar.showing()) {
            if (bar.state() == MicBar.LISTENING) {
                if (rec != null) rec.stop();
                return;
            }
            if (bar.state() == MicBar.THINKING) {
                if (acting()) cancel();   // mic while it's driving the TV: stop
                return;
            }
            if (bar.state() == MicBar.RESULT || bar.state() == MicBar.ERROR) Speaker.stop();   // talking: cut it and listen again
        }
        ensureBar(h);
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bar.show(MicBar.ERROR, "Microphone is off", "Open TV Pilot TV once and allow the microphone.", "");
            main.postDelayed(autoHide, 6000);
            return;
        }
        final int myTurn = ++turn;
        bar.show(MicBar.LISTENING, "Listening…", "", "Back to cancel");
        Log.w("listening");
        rec = new Recorder();
        rec.start(app, new Recorder.Listener() {
            @Override public void level(final float l) {
                main.post(() -> { if (myTurn == turn && bar != null) bar.setLevel(l); });
            }
            @Override public void routed(String device) {
                Log.w("mic routed to " + device);
            }
            @Override public void done(final Recorder.Result r) {
                main.post(() -> finished(myTurn, r));
            }
        });
    }

    /** Back: stops everything and slides the bar away. */
    void cancel() {
        turn++;
        Speaker.stop();
        Thread a = agent;
        agent = null;
        if (a != null) a.interrupt();
        if (rec != null) rec.stop();
        hide();
    }

    private void hide() {
        main.removeCallbacks(autoHide);
        if (bar != null) bar.hide();
    }

    private void ensureBar(Host h) {
        if (bar != null && host != h) {
            MicBar old = bar;
            Host oldHost = host;
            bar = null;
            if (oldHost != null) oldHost.detach(old);
        }
        if (bar == null) {
            bar = new MicBar(app);
            final MicBar b = bar;
            host = h;
            b.setOnGone(() -> {
                if (bar == b) {
                    bar = null;
                    Host was = host;
                    host = null;
                    if (was != null) was.detach(b);
                }
            });
            h.attach(b);
            PilotService.handedOff();
        }
    }

    private void finished(int myTurn, Recorder.Result r) {
        if (myTurn != turn || bar == null) return;
        lastPcm = r.pcm;
        String stats = String.format(Locale.US, "%.1f s, loudest %d%%, %s source, from %s", r.seconds, Math.round(r.peak * 100), r.source, r.device);
        Log.w("recorded " + stats + (r.failed ? " FAILED: " + r.error : "") + (r.silentStream ? " (digital silence)" : ""));
        if (r.failed) {
            verdict("Mic failed: " + r.error);
            final boolean busy = r.error != null && r.error.contains("busy");
            bar.show(MicBar.ERROR, busy ? "Another app is holding the mic" : "The mic didn't open",
                    busy ? "Checking which one\u2026" : r.error + ".", "");
            main.postDelayed(autoHide, 9000);
            if (busy) {
                final MicBar b = bar;
                new Thread(() -> {
                    String err = Setup.findMicHolders(app, null);
                    final java.util.List<String> h = Setup.holders;
                    main.post(() -> {
                        if (bar != b || myTurn != turn) return;
                        if (err != null) b.setLiveText("Couldn't check (TV debugging: " + err + "). Open TV Pilot \u2192 Who has the mic?");
                        else if (h.isEmpty()) b.setLiveText("It's hidden from apps. Open TV Pilot \u2192 Who has the mic? for details.");
                        else b.setLiveText("Held by " + android.text.TextUtils.join(", ", h) + ". Open TV Pilot \u2192 Free the mic.");
                    });
                }, "tvpilot-who").start();
            }
            return;
        }
        if (r.silentStream) {
            verdict("Silence from " + r.device + ": the remote's mic didn't stream to TV Pilot");
            bar.show(MicBar.ERROR, "No sound from the mic",
                    "The TV gave pure silence from " + r.device + ". The remote's mic may only open for Google.", "");
            main.postDelayed(autoHide, 8000);
            return;
        }
        if (!r.speech) {
            verdict("Sound but no speech (" + stats + ")");
            bar.show(MicBar.ERROR, "Didn't catch that", "Only quiet sound came through (" + stats + "). Try speaking closer.", "");
            main.postDelayed(autoHide, 7000);
            return;
        }
        verdict("Remote mic works ✓ (" + stats + ")");
        String key = Prefs.geminiKey();
        final boolean gemini = ("gemini".equals(Prefs.asr()) || !Prefs.hasVm()) && !key.isEmpty();
        bar.show(MicBar.THINKING, "Thinking…", "", "");
        final short[] pcm = r.pcm;
        new Thread(() -> {
            String text, err = null;
            try {
                text = gemini ? Gemini.transcribe(pcm, key, Prefs.model()) : Server.transcribe(pcm);
            } catch (Exception e) {
                text = null;
                err = e.getMessage();
            }
            final String t = text, e2 = err;
            main.post(() -> {
                if (myTurn != turn || bar == null) return;
                if (t == null) {
                    Log.w((gemini ? "gemini" : "parakeet") + " failed: " + e2);
                    bar.show(MicBar.ERROR, "Couldn't get your words", e2, "");
                } else if (t.isEmpty()) {
                    bar.show(MicBar.ERROR, "Didn't catch that", "No words came through.", "");
                } else {
                    Log.w("heard: " + t);
                    act(t, myTurn);
                    return;
                }
                main.postDelayed(autoHide, 7000);
            });
        }, "tvpilot-gemini").start();
    }

    /** A typed command from the settings screen. Returns an error to show, or null. */
    String command(String text) {
        if (PilotService.instance == null) return "Turn on the mic button service first";
        if (rec != null) rec.stop();
        Speaker.stop();
        main.removeCallbacks(autoHide);
        final int myTurn = ++turn;
        main.postDelayed(() -> { if (myTurn == turn) act(text, myTurn); }, 600);   // let the settings screen leave first
        return null;
    }

    /** Does the command on the TV. The bar moves to the see-through overlay so keys and taps reach the apps. */
    private void act(final String command, final int myTurn) {
        PilotService svc = PilotService.instance;
        if (svc != null && host != svc) {
            MicBar.seamless = bar != null && bar.showing();
            ensureBar(svc);
        }
        if (bar == null) return;
        final MicBar b = bar;
        final String quote = "\u201C" + command + "\u201D";
        b.show(MicBar.THINKING, quote, "Thinking\u2026", "Mic or Back to stop");
        Thread t = new Thread(() -> {
            String say;
            boolean ok;
            boolean talk = !Prefs.geminiKey().isEmpty();
            try {
                boolean quick = Brain.quick(command) != null;
                boolean routedTv = false;
                if (!quick) {
                    Chat.Route route = talk ? Chat.route(command, null) : null;
                    if (route == null && talk && !Thread.currentThread().isInterrupted()) route = Chat.route(command, null);   // one retry
                    routedTv = route != null && route.tv;
                    if (Thread.currentThread().isInterrupted() || myTurn != turn) return;
                    if (route != null && !route.tv) {
                        // Just a question: answer it, nothing to do on the TV.
                        final String answer = route.say;
                        Chat.remember(command, answer);
                        Log.w("chat: " + answer);
                        main.post(() -> {
                            if (myTurn != turn || bar != b) return;
                            agent = null;
                            b.show(MicBar.RESULT, answer, quote, "");
                            if (Prefs.speak()) speak(answer, myTurn); else main.postDelayed(autoHide, 8000);
                        });
                        return;
                    }
                    // Only acknowledge when we know it's a TV action; an unknown route may still turn out to be a question.
                    final String ack = route != null && !route.say.isEmpty() ? route.say : route != null ? "On it." : null;
                    if (ack != null) main.post(() -> {
                        if (myTurn != turn || bar != b) return;
                        b.setLiveText(ack);
                        if (Prefs.speak()) Speaker.say(ack, Prefs.voice(), quiet);
                    });
                }
                Agent.Result r = new Agent(app).run(command, s -> main.post(() -> { if (bar == b && myTurn == turn) b.setLiveText(s); }), routedTv);
                say = r.say;
                ok = r.success;
                if (r.chat) {
                    // Not a TV action after all: talk instead of failing.
                    String a = talk ? Chat.answer(command) : null;
                    if (a != null) { say = a; ok = true; }
                } else if (talk && !quick && say != null && !say.isEmpty()) say = Chat.finish(command, say, ok);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                if (m.contains("5555") || m.contains("debugging")) m = "TV debugging isn't reachable: " + m;
                say = m;
                ok = false;
                Log.w("agent failed: " + m);
            }
            if (Thread.currentThread().isInterrupted()) return;
            Chat.remember(command, say);
            final String fs = say;
            final boolean fok = ok;
            Log.w("agent: " + (ok ? "done" : "stopped") + " - " + say);
            main.post(() -> {
                if (myTurn != turn || bar != b) return;
                agent = null;
                b.show(fok ? MicBar.RESULT : MicBar.ERROR, fs, quote, "");
                if (Prefs.speak() && fs != null && !fs.isEmpty()) speak(fs, myTurn);
                else main.postDelayed(autoHide, 5000);
            });
        }, "tvpilot-agent");
        agent = t;
        t.start();
    }

    /** For the short acknowledgement: nothing to animate or hide afterwards. */
    private static final Speaker.Listener quiet = new Speaker.Listener() {
        @Override public void level(float l) {}
        @Override public void done(String error) { if (error != null) Log.w("ack voice: " + error); }
    };

    /** Says a reply in the chosen voice; the cookie pulses with it, and the bar leaves a moment after. */
    private void speak(String text, int myTurn) {
        final MicBar b = bar;
        if (b == null) return;
        b.setSpeaking(true);
        Speaker.say(text, Prefs.voice(), new Speaker.Listener() {
            @Override public void level(float l) { main.post(() -> { if (bar == b) b.setLevel(l); }); }
            @Override public void done(String error) {
                main.post(() -> {
                    if (bar != b || myTurn != turn) return;
                    b.setSpeaking(false);
                    if (error != null) b.setLiveText("Couldn't speak: " + error);
                    main.postDelayed(autoHide, error != null ? 6000 : 2200);
                });
            }
        });
    }

    /** A voice preview from the settings screen. */
    static void preview(String voice, Speaker.Listener l) {
        Speaker.say("Hi, I'm " + voice + ". What should we watch?", voice, l);
    }

    private void verdict(String v) {
        Prefs.lastTest(new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date()) + "  " + v);
    }

    /** Plays the last recording on the TV's speakers. */
    boolean playLast() {
        final short[] pcm = lastPcm;
        if (pcm == null || pcm.length == 0) return false;
        new Thread(() -> {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(Recorder.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
            t.write(pcm, 0, pcm.length);
            t.play();
            try { Thread.sleep(pcm.length * 1000L / Recorder.RATE + 300); } catch (InterruptedException ignored) {}
            t.release();
        }, "tvpilot-play").start();
        return true;
    }
}
