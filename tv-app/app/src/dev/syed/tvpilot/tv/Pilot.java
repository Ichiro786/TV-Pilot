package dev.syed.tvpilot.tv;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * One voice turn: mic button -> the bar listens -> words -> the TV does it / TV Pilot answers -> the reply is spoken.
 * Two brains: Classic (speech-to-text, d1 decides, Gemini Flash Lite looks and talks, Phonon speaks) and Gemini Live
 * (speech in, speech out, actions as function calls). Rules this class keeps:
 *  - config is checked before listening, so a missing key or link is said up front;
 *  - every stage has a short timeout and a watchdog backs them up, so the bar never sits on "Thinking";
 *  - every failure ends with a sentence on the bar and out loud (the TV's own voice when the server is down);
 *  - if Gemini Live fails before it said or did anything, Classic takes the same request and says so.
 * The bar lives in whichever host is in front: the accessibility overlay (over any app) or an activity.
 */
final class Pilot {
    interface Host {
        /** Adds the bar; false if this host can't show it right now. */
        boolean attach(MicBar bar);
        void detach(MicBar bar);
    }

    private static Pilot instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MicBar bar;
    private Host host;
    private Recorder rec;
    private volatile int turn;
    private volatile short[] lastPcm;
    private final Runnable autoHide = this::hide;
    private volatile Thread agent;
    private volatile Live live;
    /** What the current run is waiting on (named in the timeout message). */
    private volatile String stage = "";
    private String quote = "";
    /** Set when Gemini Live failed before saying anything while the mic was still open: Classic takes over. */
    private String liveFallback;
    private boolean recording;
    /** The typed command being run (null for a spoken one). */
    private String typedCommand;
    /** Gemini Live already finished (or failed for good) this turn, even before the mic closed. */
    private boolean liveHandled;

    /** A question TV Pilot asked (which app?) and the command it belongs to. */
    private volatile String pendingCmd;
    private volatile java.util.List<String> pendingOptions = new java.util.ArrayList<>();
    private volatile long pendingAt;

    /** Driving the TV or in a Live session (its own Back presses and app launches must not cancel it). */
    boolean acting() { return agent != null || live != null; }

    boolean hostedBy(Host h) { return host == h && bar != null; }

    static synchronized Pilot get(Context c) {
        if (instance == null) instance = new Pilot(c.getApplicationContext());
        return instance;
    }

    private Pilot(Context app) {
        this.app = app;
        Prefs.init(app);
        loadRulebook(app);
        Speaker.init(app);
        installCrashLog();
        Tv.approval = () -> main.post(() -> { if (bar != null && bar.showing()) bar.setLiveText("Choose \u201CAlways allow\u201D on the TV's debugging prompt"); });
    }

    /** The built-in rulebook (assets/rulebook) plus what was learned on this TV (files/learned.json). */
    static synchronized void loadRulebook(Context c) {
        if (rulebookLoaded) return;
        java.util.Map<String, String> files = new java.util.TreeMap<>();
        try {
            android.content.res.AssetManager am = c.getAssets();
            String[] names = am.list("rulebook");
            if (names != null) for (String f : names) {
                if (!f.endsWith(".json") || f.equals("evals.json")) continue;
                try (java.io.InputStream in = am.open("rulebook/" + f)) { files.put(f, Net.read(in, 2_000_000)); }
            }
        } catch (Exception e) {
            Log.w("rulebook: " + e);
        }
        Rulebook.load(files, new java.io.File(c.getFilesDir(), "learned.json"));
        Learn.init(c.getFilesDir());
        Learn.on = Prefs.learnLog();
        rulebookLoaded = true;
    }
    private static boolean rulebookLoaded;

    /** A crash anywhere is written down first, so Diagnostics can show it instead of the app just vanishing. */
    private static void installCrashLog() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev != null && prev.getClass().getName().startsWith(Pilot.class.getName())) return;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringBuilder b = new StringBuilder(new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date()))
                            .append("  ").append(t.getName()).append(": ").append(e);
                    StackTraceElement[] st = e.getStackTrace();
                    for (int i = 0; i < Math.min(4, st.length); i++) b.append("\n   at ").append(st[i]);
                    Prefs.lastCrash(b.toString());
                    android.util.Log.e("TVPilotTV", "crash", e);
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    boolean showing() { return bar != null && bar.showing(); }

    // MARK: - Starting a turn

    /** The mic button: starts listening, or (while listening) stops and uses what it heard. */
    void trigger(Host h) {
        main.removeCallbacks(autoHide);
        if (bar != null && bar.showing()) {
            if (bar.state() == MicBar.LISTENING && recording) {
                if (rec != null) rec.stop();
                return;
            }
            if (bar.state() == MicBar.THINKING) {
                cancel();   // mic while it's thinking or driving the TV: stop
                return;
            }
            stopTalking();   // talking: cut it and listen again
        }
        if (!ensureBar(h)) {
            Log.w("couldn't show the bar here");
            if (h instanceof PilotService) openActivity();
            return;
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone is off", "Open TV Pilot TV once and allow the microphone.", 7000);
            return;
        }
        final boolean useLive = Prefs.live() && !Prefs.geminiKey().isEmpty();
        String block = preflight(useLive);
        if (block != null) {
            fail(block, "Open TV Pilot TV \u2192 Settings", 9000);
            return;
        }
        final int myTurn = ++turn;
        liveFallback = null;
        liveHandled = false;
        typedCommand = null;
        quote = "";
        bar.show(MicBar.LISTENING, "Listening\u2026", "", useLive ? "Gemini Live \u00B7 Back to cancel" : "Back to cancel");
        Log.w("listening" + (useLive ? " (Gemini Live)" : ""));
        if (useLive) startLive(myTurn, null);
        else if (Prefs.hasVm() && !"gemini".equals(Prefs.asr())) new Thread(Server::probe, "tvpilot-probe").start();   // is the VM awake? (ready by the time you stop talking)
        recording = true;
        synchronized (early) { early.clear(); earlyOn = useLive; }
        final Recorder r0 = new Recorder();
        rec = r0;
        r0.start(app, new Recorder.Listener() {
            @Override public void level(final float l) {
                main.post(() -> { if (myTurn == turn && bar != null && recording) bar.setLevel(l); });
            }
            @Override public void routed(String device) { Log.w("mic routed to " + device); }
            @Override public void chunk(short[] pcm, int n) {
                if (myTurn != turn) return;
                // Gemini Live is still being set up: keep the audio so the first words aren't lost.
                Live l;
                synchronized (early) {
                    l = live;
                    if (l == null) { if (earlyOn && early.size() < 120) early.add(java.util.Arrays.copyOf(pcm, n)); return; }
                }
                l.audio(pcm, n);
            }
            @Override public void done(final Recorder.Result r) { main.post(() -> finished(myTurn, r)); }
        });
        // Listening can never hang: if the mic thread hasn't reported a little after its own hard limit, stop it,
        // and if it still doesn't answer, carry on with what it recorded so far.
        main.postDelayed(() -> {
            if (myTurn != turn || rec != r0 || !recording || !r0.running()) return;
            Log.w("mic didn't stop by itself, stopping it");
            r0.stop();
            main.postDelayed(() -> {
                if (myTurn != turn || rec != r0 || !recording) return;
                Recorder.Result snap = r0.abandon("the TV's microphone stopped responding");
                if (snap == null) return;
                Log.w("mic thread stuck: going on with " + snap.seconds + " s of audio");
                finished(myTurn, snap);
            }, 1500);
        }, Recorder.HARD_MS + 1800);
    }

    /** Mic audio that came in before the Live session object existed (sent to it as soon as it does). */
    private final java.util.List<short[]> early = new java.util.ArrayList<>();
    private boolean earlyOn;

    /** What's missing before anything can work, as one sentence (null when it can run). */
    private String preflight(boolean useLive) {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (cm != null && cm.getActiveNetwork() == null) return "The TV isn't connected to the internet";
        } catch (RuntimeException ignored) {}
        if (useLive) return null;
        if (Prefs.live() && Prefs.geminiKey().isEmpty() && !Prefs.hasVm()) return Gemini.NO_KEY;
        if (!Prefs.hasVm() && Prefs.geminiKey().isEmpty())
            return "Nothing is set up yet \u2014 add your Gemini key or your server link in Settings";
        return null;
    }

    /** A typed command from the settings screen. Returns an error to show, or null. */
    String command(String text) {
        if (PilotService.instance == null) return "Turn on the mic button service first";
        if (rec != null) rec.stop();
        stopTalking();
        main.removeCallbacks(autoHide);
        final int myTurn = ++turn;
        final boolean useLive = Prefs.live() && !Prefs.geminiKey().isEmpty();
        String block = preflight(useLive);
        if (block != null) return block;
        main.postDelayed(() -> {   // let the settings screen leave first
            if (myTurn != turn) return;
            quote = "\u201C" + text + "\u201D";
            typedCommand = text;
            lastPcm = null;
            if (useLive) {
                if (!moveToOverlay()) return;
                bar.show(MicBar.THINKING, quote, "Connecting to Gemini Live\u2026", "Back to stop");
                startLive(myTurn, text);
            } else {
                act(text, myTurn, null);
            }
        }, 600);
        return null;
    }

    // MARK: - Stopping

    /** Back: stops everything and slides the bar away. */
    void cancel() {
        turn++;
        main.removeCallbacks(watchdog);
        stopTalking();
        Thread a = agent;
        agent = null;
        if (a != null) a.interrupt();
        Live l = live;
        live = null;
        if (l != null) l.cancel();
        Net.abortAll();
        Tv.get(app).abort();
        if (rec != null) rec.stop();
        recording = false;
        hide();
    }

    private void stopTalking() {
        Speaker.stop();
        endLive();
    }

    private void hide() {
        main.removeCallbacks(autoHide);
        if (bar != null) bar.hide();
    }

    /** The host is going away (its activity stopped, the service unbound). A running task carries on without a bar. */
    void hostGone(Host h) {
        if (host != h || bar == null) return;
        MicBar b = bar;
        bar = null;
        host = null;
        try { h.detach(b); } catch (RuntimeException ignored) {}
        if (!acting() && !recording) return;
        Log.w("the bar's screen closed; carrying on " + (acting() ? "with the task" : "listening"));
        PilotService svc = PilotService.instance;
        if (svc != null && svc != h) {   // bring the bar back over whatever app is in front
            MicBar.seamless = true;
            if (ensureBar(svc)) bar.show(recording ? MicBar.LISTENING : MicBar.THINKING, recording ? "Listening\u2026" : quote, "", "Back to stop");
        }
    }

    // MARK: - The bar

    private boolean ensureBar(Host h) {
        if (bar != null && host == h) return true;
        final MicBar b = new MicBar(app);
        b.setOnGone(() -> {
            if (bar == b) {
                bar = null;
                Host was = host;
                host = null;
                if (was != null) was.detach(b);
            }
        });
        boolean ok;
        try {
            ok = h.attach(b);
        } catch (RuntimeException e) {
            Log.w("bar attach failed: " + e);
            ok = false;
        }
        if (!ok) {
            MicBar.seamless = false;
            return bar != null;   // keep the bar where it was
        }
        MicBar old = bar;
        Host oldHost = host;
        bar = b;
        host = h;
        if (old != null && oldHost != null) try { oldHost.detach(old); } catch (RuntimeException ignored) {}
        PilotService.handedOff();
        return true;
    }

    /** Before keys and taps go to apps, the bar moves to the see-through overlay (an activity in front would eat them). */
    private boolean moveToOverlay() {
        PilotService svc = PilotService.instance;
        if (svc == null) {
            if (bar == null) {
                Log.w("no overlay and no bar: the mic button service is off");
                return false;
            }
            return true;
        }
        if (host == svc && bar != null) return true;
        MicBar.seamless = bar != null && bar.showing();
        return ensureBar(svc);
    }

    private void openActivity() {
        try {
            app.startActivity(new Intent(app, AssistActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (RuntimeException e) {
            Log.w("couldn't open the voice screen: " + e);
        }
    }

    /** Shows an error and says it. */
    private void fail(String title, String text, long hideAfter) {
        if (bar == null) {
            Log.w("error (no bar): " + title);
            if (Prefs.speak()) Speaker.say(title, Prefs.voice(), quiet);
            return;
        }
        bar.show(MicBar.ERROR, title, text, "");
        Log.w("error: " + title + (text == null || text.isEmpty() ? "" : " \u2014 " + text));
        if (Prefs.speak()) speak(title, turn, hideAfter);
        else main.postDelayed(autoHide, hideAfter);
    }

    // MARK: - After listening

    private void finished(int myTurn, Recorder.Result r) {
        if (myTurn != turn) return;
        recording = false;
        synchronized (early) { earlyOn = false; early.clear(); }
        lastPcm = r.pcm;
        if (liveHandled) return;   // Gemini Live already answered while the mic was still open
        if (bar == null && !moveToOverlay()) {   // the bar's screen went away: still answer, out loud
            Log.w("no bar after listening; carrying on without one");
        }
        String stats = String.format(Locale.US, "%.1f s, loudest %d%%, %s source, from %s, ended: %s", r.seconds, Math.round(r.peak * 100), r.source, r.device, r.ended);
        Log.w("recorded " + stats + (r.failed ? " FAILED: " + r.error : "") + (r.silentStream ? " (digital silence)" : "") + (r.weak ? " (faint)" : ""));
        Live l = live;
        boolean liveUsable = l != null && liveFallback == null;
        if (r.failed && r.pcm.length < Recorder.RATE / 2 || r.silentStream || (!r.speech && !(liveUsable && l.heardAnything()))) {
            endLive();
            micProblem(myTurn, r, stats);
            return;
        }
        verdict("Remote mic works \u2713 (" + stats + ")");
        if (liveUsable) {
            l.audioEnd();
            if (bar != null) bar.show(MicBar.THINKING, quote.isEmpty() ? "Thinking\u2026" : quote, "", "Back to stop");
            stage = "Gemini Live";
            watch(myTurn, Live.MAX_MS + 8000);
            return;
        }
        String note = liveFallback != null ? "Gemini Live isn't available (" + liveFallback + ") \u2014 using Classic" : null;
        transcribe(myTurn, r.pcm, note);
    }

    private void micProblem(int myTurn, Recorder.Result r, String stats) {
        if (r.failed) {
            verdict("Mic failed: " + r.error);
            final boolean busy = r.error != null && r.error.contains("busy");
            fail(busy ? "Another app is holding the mic" : "The mic didn't open", busy ? "Checking which one\u2026" : r.error + ".", 9000);
            if (busy) {
                final MicBar b = bar;
                new Thread(() -> {
                    String err = Setup.findMicHolders(app, null);
                    final List<String> h = Setup.holders;
                    main.post(() -> {
                        if (bar != b || myTurn != turn) return;
                        if (err != null) b.setLiveText("Couldn't check (TV debugging: " + err + "). Open TV Pilot \u2192 Who has the mic?");
                        else if (h.isEmpty()) b.setLiveText("It's hidden from apps. Open TV Pilot \u2192 Who has the mic? for details.");
                        else b.setLiveText("Held by " + android.text.TextUtils.join(", ", h) + ". Open TV Pilot \u2192 Free the mic.");
                    });
                }, "tvpilot-who").start();
            }
        } else if (r.silentStream) {
            verdict("Silence from " + r.device + ": the remote's mic didn't stream to TV Pilot");
            fail("No sound from the mic", "The TV gave pure silence from " + r.device + ". The remote's mic may only open for Google.", 8000);
        } else {
            verdict("Sound but no speech (" + stats + ")");
            fail("Didn't catch that", "Only quiet sound came through. Try speaking closer to the remote.", 7000);
        }
    }

    /** Classic: speech to words (your server, or Gemini), with the other one as a fallback. */
    private void transcribe(final int myTurn, final short[] pcm, final String note) {
        final String key = Prefs.geminiKey();
        boolean server = Prefs.hasVm() && !("gemini".equals(Prefs.asr()) && !key.isEmpty());
        String why = note;
        if (server && Boolean.FALSE.equals(Server.knownUp())) {
            if (key.isEmpty()) {
                fail(Server.OFFLINE, "It pauses after 30 idle minutes. Or add a Gemini key in Settings so Gemini can listen.", 10_000);
                return;
            }
            server = false;
            why = (note == null ? "" : note + ". ") + "Your server is offline \u2014 Gemini is listening instead";
        }
        if (!server && key.isEmpty()) {
            fail(Gemini.NO_KEY, "Or add your server link for speech-to-text.", 9000);
            return;
        }
        final boolean viaServer = server;
        final String shownNote = why;
        if (bar != null) bar.show(MicBar.THINKING, "Thinking\u2026", shownNote == null ? "" : shownNote, "Back to stop");
        stage = viaServer ? "the speech server" : "Gemini (speech to text)";
        watch(myTurn, 40_000);
        Thread t = new Thread(() -> {
            String text = null, err = null, extra = shownNote;
            try {
                try {
                    text = viaServer ? Server.transcribe(pcm) : Gemini.transcribe(pcm, key, Prefs.model());
                } catch (Net.Fail f) {
                    if (viaServer && !key.isEmpty() && myTurn == turn) {
                        Log.w("speech server failed (" + f.getMessage() + "), trying Gemini");
                        stage = "Gemini (speech to text)";
                        extra = f.user + " \u2014 Gemini is listening instead";
                        final String e3 = extra;
                        main.post(() -> { if (myTurn == turn && bar != null) bar.setLiveText(e3); });
                        text = Gemini.transcribe(pcm, key, Prefs.model());
                    } else throw f;
                }
            } catch (Throwable e) {
                err = Net.explain(e);
                Log.w("speech to text failed: " + e);
            }
            final String tx = text, e2 = err, ex = extra;
            main.post(() -> {
                if (myTurn != turn) return;
                main.removeCallbacks(watchdog);
                if (tx == null) fail("Couldn't get your words", e2, 9000);
                else if (tx.isEmpty()) fail("Didn't catch that", "No words came through.", 7000);
                else {
                    Log.w("heard: " + tx);
                    act(tx, myTurn, ex);
                }
            });
        }, "tvpilot-asr");
        t.start();
    }

    // MARK: - Classic: doing it

    /** Does the command on the TV. The bar moves to the see-through overlay so keys and taps reach the apps. */
    private void act(final String command, final int myTurn, final String note) {
        moveToOverlay();
        if (bar == null) {   // no bar anywhere (the mic button service is off): say why instead of going quiet
            Log.w("no bar to show the task on");
            if (Prefs.speak()) Speaker.say("I can't show myself on screen \u2014 turn on TV Pilot's mic button service", Prefs.voice(), quiet);
            return;
        }
        final MicBar b = bar;
        quote = "\u201C" + command + "\u201D";
        b.show(MicBar.THINKING, quote, note != null ? note : "Thinking\u2026", "Mic or Back to stop");
        stage = "Gemini";
        Thread t = new Thread(() -> {
            String say;
            boolean ok;
            boolean talk = !Prefs.geminiKey().isEmpty();
            // "Decision model only": the decider goes first with no Gemini round trip before it; Gemini is only asked
            // afterwards if the decider says it's a question (r.chat), so answers still work.
            boolean deciderOnly = "d1".equals(Prefs.brain());
            Vision.Steps steps = s -> main.post(() -> { if (bar == b && myTurn == turn) b.setLiveText(s); });
            try {
                boolean quick = Brain.quick(command) != null;
                boolean routedTv = false;
                if (!quick && talk && !deciderOnly) {
                    stage = "Gemini";
                    Chat.Route route = Chat.route(command, null);
                    if (route == null && Chat.lastUnreadable && !Thread.currentThread().isInterrupted()) route = Chat.route(command, null);   // one retry, only for a garbled reply
                    if (route == null && Chat.lastError != null) steps.step(Chat.lastError);
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
                            main.removeCallbacks(watchdog);
                            b.show(MicBar.RESULT, answer, quote, "");
                            if (Prefs.speak()) speak(answer, myTurn, 2200); else main.postDelayed(autoHide, 8000);
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
                stage = "TV debugging";
                String cmd = command;
                // The answer to TV Pilot's own question ("Netflix or Prime Video?"): finish the original command with it.
                if (pendingCmd != null && System.currentTimeMillis() - pendingAt < 90_000) {
                    String pick = null;
                    for (String o : pendingOptions) if (Ui.match(command, o) >= 0.6 || Ui.norm(command).contains(Ui.norm(o))) pick = o;
                    if (pick != null) { cmd = pick.startsWith("Search ") ? pendingCmd : pendingCmd.replaceAll("(?i)\\s+(on|in)\\s+\\S+(\\s+\\S+)?$", "") + " on " + pick; routedTv = true; Log.w("answer to my question: " + cmd); }
                }
                pendingCmd = null;
                Agent.Result r = new Agent(app).run(cmd, s -> { stageFrom(s); steps.step(s); }, routedTv);
                say = r.say;
                ok = r.success;
                if (r.needsUser) { pendingCmd = cmd; pendingOptions = r.options; pendingAt = System.currentTimeMillis(); ok = true; }
                if (r.chat) {
                    // Not a TV action after all: talk instead of failing.
                    stage = "Gemini";
                    String a = talk ? Chat.answer(command) : null;
                    if (a != null) { say = a; ok = true; }
                } else if (talk && !quick && !deciderOnly && say != null && !say.isEmpty()) {
                    stage = "Gemini";
                    say = Chat.finish(command, say, ok);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                say = Net.explain(e);
                ok = false;
                Log.w("agent failed: " + e);
            }
            if (Thread.currentThread().isInterrupted() || myTurn != turn) return;
            if (say == null || say.isEmpty()) say = ok ? "Done." : "I couldn't do that.";
            Chat.remember(command, say);
            final String fs = say;
            final boolean fok = ok;
            Log.w("agent: " + (ok ? "done" : "stopped") + " - " + say);
            main.post(() -> {
                if (myTurn != turn) return;
                agent = null;
                main.removeCallbacks(watchdog);
                if (bar != b) {   // the bar went away mid-task: still say how it went
                    if (Prefs.speak()) Speaker.say(fs, Prefs.voice(), quiet);
                    return;
                }
                b.show(fok ? MicBar.RESULT : MicBar.ERROR, fs, quote, "");
                if (Prefs.speak()) speak(fs, myTurn, 2200);
                else main.postDelayed(autoHide, 6000);
            });
        }, "tvpilot-agent");
        agent = t;
        watch(myTurn, 100_000);
        t.start();
    }

    /** Names the stage from a step message (for the timeout sentence). */
    private void stageFrom(String s) {
        String l = s.toLowerCase(Locale.US);
        if (l.startsWith("deciding")) stage = "the decision model";
        else if (l.contains("gemini") || l.startsWith("looking at the screen")) stage = "Gemini";
        else if (l.startsWith("connecting to the tv") || l.startsWith("looking at your apps") || l.startsWith("opening")) stage = "TV debugging";
    }

    // MARK: - Gemini Live

    private void startLive(final int myTurn, final String typed) {
        final String key = Prefs.geminiKey();
        final Tv tv = Tv.get(app);
        stage = "Gemini Live";
        new Thread(() -> {
            // The app list for the instructions (cached after the first time); never wait long for it.
            List<Tv.App> apps = null;
            String unavailable = null;
            FutureTask<List<Tv.App>> f = new FutureTask<>(tv::apps);
            Thread ft = new Thread(f, "tvpilot-apps");
            ft.setDaemon(true);
            ft.start();
            try {
                apps = f.get(3500, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                unavailable = Net.explain(e.getCause());
            } catch (Exception e) {
                Log.w("live: apps not ready yet");
            }
            if (myTurn != turn) return;
            final LiveTv tools = new LiveTv(app, tv, apps, unavailable);
            tools.steps = st -> main.post(() -> { if (myTurn == turn && bar != null && bar.state() == MicBar.THINKING) bar.setLiveText(st); });
            final Live l = new Live(key, Prefs.liveModel(), Prefs.liveVoice(), liveInstructions(tools), tools, liveEvents(myTurn));
            main.post(() -> {
                if (myTurn != turn) return;
                synchronized (early) {
                    live = l;
                    for (short[] c : early) l.audio(c, c.length);
                    if (!early.isEmpty()) Log.w("live: " + early.size() * 100 + " ms of audio from before the session was made");
                    early.clear();
                    earlyOn = false;
                }
                l.start();
                if (typed != null) {
                    l.text(typed);
                    watch(myTurn, Live.MAX_MS + 8000);
                }
            });
        }, "tvpilot-live-start").start();
    }

    private String liveInstructions(LiveTv tools) {
        return LiveDecl.instructions(Prefs.rules(), Chat.transcript(), tools.unavailable, tools.appList(), tools.rulebookSummary());
    }

    private Live.Events liveEvents(final int myTurn) {
        return new Live.Events() {
            private boolean speaking;

            @Override public void ready() { Log.w("live: ready"); }

            @Override public void heard(String text) {
                main.post(() -> {
                    if (myTurn != turn || bar == null) return;
                    quote = "\u201C" + text + "\u201D";
                    if (recording) bar.setLiveText(text);
                    else if (bar.state() == MicBar.THINKING) bar.show(MicBar.THINKING, quote, "", "Back to stop");
                });
            }

            @Override public void said(String text) {
                main.post(() -> {
                    if (myTurn != turn || bar == null || recording) return;
                    String tail = text.length() > 140 ? "\u2026" + text.substring(text.length() - 140) : text;
                    if (bar.state() != MicBar.RESULT) bar.show(MicBar.RESULT, quote.isEmpty() ? "TV Pilot" : quote, tail, "Back to stop");
                    else bar.setLiveText(tail);
                    bar.setSpeaking(true);
                });
            }

            @Override public void level(float lv) {
                main.post(() -> { if (myTurn == turn && bar != null && !recording) bar.setLevel(lv); });
            }

            @Override public void step(String what) {
                main.post(() -> {
                    if (myTurn != turn) return;
                    if (rec != null && recording) rec.stop();   // it's acting: no more listening
                    if (moveToOverlay() && bar != null) {
                        if (bar.state() != MicBar.THINKING) bar.show(MicBar.THINKING, quote.isEmpty() ? "TV Pilot" : quote, what, "Back to stop");
                        else bar.setLiveText(what);
                    }
                });
            }

            @Override public void finished(String heard, String said) {
                final Live l = live;
                String sum = l != null && l.summary() != null && !l.summary().isEmpty() ? l.summary() : null;
                final String reply = !said.isEmpty() ? said : sum != null ? sum : "";
                if (!heard.isEmpty() && !reply.isEmpty()) Chat.remember(heard, reply);
                Log.w("live: finished. heard \"" + heard + "\" said \"" + Net.shorten(reply, 120) + "\"");
                main.post(() -> {
                    if (myTurn != turn) return;
                    live = null;
                    liveHandled = true;
                    if (rec != null && recording) rec.stop();
                    main.removeCallbacks(watchdog);
                    if (bar == null) return;
                    if (reply.isEmpty() && heard.isEmpty()) {
                        fail("Gemini Live didn't say anything", "Try again, or switch Brain to Classic in Settings.", 7000);
                        return;
                    }
                    String tail = reply.length() > 140 ? "\u2026" + reply.substring(reply.length() - 140) : reply;
                    bar.show(MicBar.RESULT, quote.isEmpty() ? "TV Pilot" : quote, tail, "");
                    bar.setSpeaking(true);
                    final MicBar b = bar;
                    new Thread(() -> {
                        if (l != null) l.awaitQuiet(15_000);
                        main.post(() -> {
                            if (myTurn != turn || bar != b) return;
                            b.setSpeaking(false);
                            main.postDelayed(autoHide, 1800);
                        });
                    }, "tvpilot-live-tail").start();
                });
            }

            @Override public void failed(String reason, boolean beforeOutput) {
                Log.w("live failed: " + reason + (beforeOutput ? " (nothing said yet)" : ""));
                main.post(() -> {
                    if (myTurn != turn) return;
                    Live l = live;
                    live = null;
                    String heardText = l == null ? "" : l.heard();
                    boolean classicOk = Prefs.hasVm() || !Prefs.geminiKey().isEmpty();
                    if (beforeOutput && classicOk) {
                        if (recording) {   // still listening: Classic gets the recording when you stop
                            liveFallback = reason;
                            return;
                        }
                        String note = "Gemini Live isn't available (" + reason + ") \u2014 using Classic";
                        main.removeCallbacks(watchdog);
                        if (typedCommand != null) act(typedCommand, myTurn, note);
                        else if (!heardText.isEmpty()) act(heardText, myTurn, note);
                        else if (lastPcm != null && lastPcm.length > 0) transcribe(myTurn, lastPcm, note);
                        else fail(reason, "", 8000);
                        return;
                    }
                    main.removeCallbacks(watchdog);
                    liveHandled = true;
                    if (rec != null && recording) rec.stop();
                    if (bar == null) { if (Prefs.speak()) Speaker.say(reason, Prefs.voice(), quiet); return; }
                    fail(reason, beforeOutput ? "Add your server link or check the Gemini key in Settings." : "", 8000);
                });
            }
        };
    }

    private void endLive() {
        Live l = live;
        live = null;
        if (l != null) l.cancel();
    }

    // MARK: - The watchdog: nothing waits forever

    private int watchTurn;
    private final Runnable watchdog = () -> {
        if (watchTurn != turn) return;
        String what = stage;
        Log.w("timeout while waiting on " + what);
        Thread a = agent;
        agent = null;
        Live l = live;
        live = null;
        final int t2 = ++turn;   // anything still running reports into a turn that's gone
        if (a != null) a.interrupt();
        if (l != null) l.cancel();
        Net.abortAll();
        Tv.get(Pilot.this.app).abort();
        String msg;
        if (what.startsWith("the speech server") || what.startsWith("the decision model") && Prefs.hasVm())
            msg = what.startsWith("the speech") ? "The speech server didn't answer \u2014 your VM may be paused. Turn it on, or set Speech to words to Gemini"
                    : "The decision model didn't answer \u2014 your VM may be paused";
        else if (what.equals("TV debugging")) msg = "TV debugging stopped answering \u2014 check Developer options";
        else msg = "That took too long \u2014 " + (what.isEmpty() ? "something" : what) + " didn't answer";
        if (bar == null) { if (Prefs.speak()) Speaker.say(msg, Prefs.voice(), quiet); return; }
        bar.show(MicBar.ERROR, msg, quote, "");
        if (Prefs.speak()) speak(msg, t2, 8000);
        else main.postDelayed(autoHide, 8000);
    };

    private void watch(int myTurn, long ms) {
        main.removeCallbacks(watchdog);
        watchTurn = myTurn;
        main.postDelayed(watchdog, ms);
    }

    // MARK: - Speaking

    /** For the short acknowledgement: nothing to animate or hide afterwards. */
    private static final Speaker.Listener quiet = new Speaker.Listener() {
        @Override public void level(float l) {}
        @Override public void done(String error) { if (error != null) Log.w("voice: " + error); }
    };

    /** Says a reply; the cookie pulses with it, and the bar leaves a moment after. */
    private void speak(String text, int myTurn, long hideAfter) {
        final MicBar b = bar;
        if (b == null) return;
        b.setSpeaking(true);
        Speaker.say(text, Prefs.voice(), new Speaker.Listener() {
            @Override public void level(float l) { main.post(() -> { if (bar == b) b.setLevel(l); }); }
            @Override public void done(String error) {
                main.post(() -> {
                    if (bar != b || myTurn != turn) return;
                    b.setSpeaking(false);
                    if (error != null) Log.w("couldn't speak: " + error);
                    main.postDelayed(autoHide, error != null ? Math.max(hideAfter, 7000) : hideAfter);
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
            AudioTrack t = null;
            try {
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(Recorder.RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                t.write(pcm, 0, pcm.length);
                t.play();
                Thread.sleep(pcm.length * 1000L / Recorder.RATE + 300);
            } catch (Exception e) {
                Log.w("playback: " + e);
            } finally {
                if (t != null) t.release();
            }
        }, "tvpilot-play").start();
        return true;
    }
}
