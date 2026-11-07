package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One short Gemini Live session (BidiGenerateContent over a WebSocket): the mic streams in as 16 kHz PCM, the TV's
 * screen goes in as JPEG frames (at the start and after every action), the model talks back in 24 kHz audio that
 * plays straight away, and it drives the TV through function calls (declared BLOCKING so actions run one after
 * another). The session ends itself once the answer is spoken, after an idle spell, or at a hard time limit, and every
 * way it can fail ends in failed(reason) with a sentence for the user.
 * Turn-taking is ours (automatic activity detection off): the mic button press is activityStart, TV Pilot's own
 * end-of-speech is activityEnd. Tested against the real API (Oct 22, 2026): with the server's own detection, a stream
 * that stops right after the last word (audioStreamEnd, no trailing silence) is never answered at all.
 * Everything is written to the socket by one sender thread, so the mic thread can never block on the network.
 * Docs: https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket and https://ai.google.dev/api/live
 */
final class Live {
    interface Events {
        void ready();
        void heard(String text);          // what you said so far (input transcription)
        void said(String text);           // what the model has said so far (output transcription)
        void level(float l);              // loudness of the model's voice
        void step(String what);           // an action is being done on the TV
        void finished(String heard, String said);
        /** beforeOutput: nothing was said or done yet, so the request can safely be retried another way. */
        void failed(String reason, boolean beforeOutput);
    }

    /** Executes the model's function calls on the TV. */
    interface Tools {
        JSONArray declarations() throws Exception;
        /** Runs one call; returns the response object. May take seconds. */
        JSONObject run(String name, JSONObject args) throws Exception;
        /** A fresh JPEG of the screen, or null when the TV can't be seen. */
        byte[] screen() throws Exception;
    }

    static final String HOST = "generativelanguage.googleapis.com";
    static final String PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    static final int MAX_MS = 110_000;           // audio + video sessions are capped at 2 minutes by the API

    static final String[] VOICES = {"Puck", "Charon", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr",
            "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib",
            "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
            "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"};
    static final String[] VOICE_STYLES = {"Upbeat", "Informative", "Firm", "Excitable", "Breezy", "Youthful", "Firm", "Bright",
            "Easy-going", "Bright", "Breathy", "Clear", "Easy-going", "Smooth", "Smooth", "Clear", "Gravelly",
            "Informative", "Upbeat", "Soft", "Firm", "Even", "Mature", "Forward", "Friendly",
            "Casual", "Gentle", "Lively", "Knowledgeable", "Warm"};

    private final String key, model, voice, system;
    private final Events ev;
    private final Tools tools;          // null: plain talk (voice preview)
    private final Player player;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "tvpilot-live-tools"));
    private final List<String> pendingAudio = new ArrayList<>();
    private final java.util.concurrent.LinkedBlockingQueue<String> outbox = new java.util.concurrent.LinkedBlockingQueue<>();
    private static final String STOP = new String("stop");
    private volatile boolean activityOpen, activityClosed;
    private volatile int audioChunks;
    private volatile String lastClose;
    private volatile Ws ws;
    private volatile boolean ready, ended, cancelled, outputStarted, audioEnded, finishCalled, turnDone, idle, lean;
    private final java.util.concurrent.atomic.AtomicInteger toolsRunning = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long startedAt, audioEndAt, lastActivity, turnDoneAt, finishAt, lastFrameAt;
    private final StringBuilder heard = new StringBuilder(), said = new StringBuilder();
    private String pendingText;
    private volatile String finishSummary;

    Live(String key, String model, String voice, String system, Tools tools, Events ev) {
        this.key = key; this.model = model; this.voice = voice; this.system = system; this.tools = tools; this.ev = ev;
        player = new Player(ev::level);
    }

    // MARK: - Starting

    void start() {
        startedAt = lastActivity = System.currentTimeMillis();
        Thread t = new Thread(this::run, "tvpilot-live");
        t.start();
    }

    private void run() {
        try {
            connect();
        } catch (Exception e) {
            Log.w("live: connect failed: " + e);
            lastClose = "connect failed: " + Net.shorten(String.valueOf(e), 160);
            fail(explainConnect(e), true);
            return;
        }
        // The watchdog: decides when the session is done, and never lets it hang.
        while (!ended) {
            try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            long now = System.currentTimeMillis();
            if (!ready && now - startedAt > 12_000) { fail("Gemini Live didn't start in time", true); return; }
            if (now - startedAt > MAX_MS) { fail(outputStarted ? "That took too long, so I stopped" : "Gemini Live took too long", !outputStarted); return; }
            if (audioEnded && !outputStarted && toolsRunning.get() == 0 && now - audioEndAt > 12_000) {
                fail(heardAnything() ? "Gemini Live heard you but didn't answer" : ready ? "Gemini Live didn't answer" : "Gemini Live never got ready", true);
                return;
            }
            if (toolsRunning.get() > 0) continue;
            boolean drained = player.drained();
            if (finishCalled && drained && (turnDone || now - finishAt > 6000)) { end(); return; }
            if (turnDone && drained && now - Math.max(turnDoneAt, lastActivity) > (idle ? 900 : 1800)) { end(); return; }
            if (outputStarted && drained && now - lastActivity > 25_000) { end(); return; }   // went quiet
        }
    }

    /** Tests only: a local mock server instead of Google. */
    static volatile String testUrl;

    private Thread sender;

    private void connect() throws Exception {
        String url = testUrl != null ? testUrl : "wss://" + HOST + PATH + "?key=" + java.net.URLEncoder.encode(key, "UTF-8");
        ws = Ws.connect(url, 8000, new Ws.Listener() {
            @Override public void onMessage(String text) { handle(text); }
            @Override public void onClose(int code, String reason) { closed(code, reason); }
        });
        Net.track(ws);
        ws.send(setup().toString());
        Log.w("live: setup sent" + (lean ? " (lean)" : ""));
        if (sender == null) {
            sender = new Thread(this::sendLoop, "tvpilot-live-send");
            sender.setDaemon(true);
            sender.start();
        }
    }

    /** The only thread that writes after setup: a slow network can delay the audio, never the mic. */
    private void sendLoop() {
        try {
            while (true) {
                String m = outbox.take();
                if (m == STOP) return;
                Ws w = ws;
                if (w == null || ended) return;
                try {
                    w.send(m);
                } catch (IOException e) {
                    Log.w("live: send failed: " + e.getMessage());
                    fail("Lost the connection to Gemini Live", !outputStarted);
                    return;
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    JSONObject setup() throws Exception {
        JSONObject gen = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                        .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice))));
        JSONObject s = new JSONObject().put("model", "models/" + model).put("generationConfig", gen)
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("outputAudioTranscription", new JSONObject())
                // We say when you start and stop talking (see the class note).
                .put("realtimeInputConfig", new JSONObject().put("automaticActivityDetection", new JSONObject().put("disabled", true)));
        JSONObject in = new JSONObject();
        if (!lean) in.put("languageCodes", new JSONArray().put("en-IN").put("hi-IN"));
        s.put("inputAudioTranscription", in);
        if (tools != null) {
            JSONArray decl = tools.declarations();
            if (lean) for (int i = 0; i < decl.length(); i++) decl.getJSONObject(i).remove("behavior");
            else for (int i = 0; i < decl.length(); i++) {
                // finish runs in the background with a SILENT reply, so the model doesn't speak its answer twice.
                JSONObject d = decl.getJSONObject(i);
                if ("finish".equals(d.optString("name"))) d.put("behavior", "NON_BLOCKING");
            }
            s.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decl)));
        }
        return new JSONObject().put("setup", s);
    }

    // MARK: - Sending

    /** 16 kHz mono PCM from the mic (queued until the session is ready). */
    void audio(short[] pcm, int n) {
        if (ended || n <= 0) return;
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) { b[2 * i] = (byte) pcm[i]; b[2 * i + 1] = (byte) (pcm[i] >> 8); }
        String msg = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"" + Base64.getEncoder().encodeToString(b) + "\"}}}";
        synchronized (pendingAudio) {
            if (activityClosed) return;
            if (!activityOpen) {   // the mic opened: the user's turn starts here
                activityOpen = true;
                if (!ready) pendingAudio.add(ACTIVITY_START); else send(ACTIVITY_START);
            }
            audioChunks++;
            if (!ready) { pendingAudio.add(msg); return; }
        }
        send(msg);
    }

    private static final String ACTIVITY_START = "{\"realtimeInput\":{\"activityStart\":{}}}";
    private static final String ACTIVITY_END = "{\"realtimeInput\":{\"activityEnd\":{}}}";

    /** Gemini has transcribed some of what was said. */
    boolean heardAnything() { synchronized (heard) { return heard.length() > 0; } }

    /** The mic closed: flush whatever the server is still holding and let it answer. */
    void audioEnd() {
        audioEnded = true;
        audioEndAt = lastActivity = System.currentTimeMillis();
        Log.w("live: end of speech after " + audioChunks * 100 + " ms of audio" + (ready ? "" : " (session not ready yet)"));
        synchronized (pendingAudio) {
            if (!activityOpen || activityClosed) return;
            activityClosed = true;
            if (!ready) { pendingAudio.add(ACTIVITY_END); return; }
        }
        send(ACTIVITY_END);
    }

    /** A typed request (no audio): one complete user turn. */
    void text(String t) {
        audioEnded = true;
        audioEndAt = lastActivity = System.currentTimeMillis();
        synchronized (pendingAudio) {
            if (!ready) { pendingText = t; return; }
        }
        sendText(t);
    }

    private void sendText(String t) {
        try {
            send(new JSONObject().put("clientContent", new JSONObject().put("turns", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", t))))).put("turnComplete", true)).toString());
        } catch (Exception e) {
            fail("Couldn't send to Gemini Live: " + e.getMessage(), !outputStarted);
        }
    }

    /** A screenshot as a video frame (the API takes at most one per second). */
    private void frame(byte[] jpeg) {
        if (jpeg == null || ended) return;
        long wait = 1050 - (System.currentTimeMillis() - lastFrameAt);
        if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
        lastFrameAt = System.currentTimeMillis();
        send("{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"" + Base64.getEncoder().encodeToString(jpeg) + "\"}}}");
    }

    private void send(String msg) {
        if (ended) return;
        outbox.offer(msg);
    }

    // MARK: - Receiving

    private void handle(String text) {
        if (ended) return;
        JSONObject m;
        try { m = new JSONObject(text); } catch (Exception e) { Log.w("live: unreadable message " + Net.shorten(text, 80)); return; }
        lastActivity = System.currentTimeMillis();
        if (m.has("setupComplete")) { onReady(); return; }
        JSONObject sc = m.optJSONObject("serverContent");
        if (sc != null) { content(sc); return; }
        JSONObject tc = m.optJSONObject("toolCall");
        if (tc != null) { toolCall(tc); return; }
        if (m.has("toolCallCancellation")) { Log.w("live: tool call cancelled by the server"); return; }
        if (m.has("goAway")) { Log.w("live: server will close soon " + m.optJSONObject("goAway")); return; }
        Object err = m.opt("error");
        if (err != null) {
            String r = err instanceof JSONObject ? ((JSONObject) err).optString("message", err.toString()) : err.toString();
            Log.w("live: server error " + Net.shorten(r, 200));
            Prefs.lastLive("error from Gemini: " + Net.shorten(r, 160));
            fail("Gemini Live error: " + Net.shorten(r, 90), !outputStarted);
        }
    }

    private void onReady() {
        List<String> queued;
        synchronized (pendingAudio) {
            ready = true;
            queued = new ArrayList<>(pendingAudio);
            pendingAudio.clear();
        }
        Log.w("live: session ready (" + model + ", " + voice + ")" + (queued.isEmpty() ? "" : ", sending " + queued.size() + " queued messages"));
        ev.ready();
        // First look at the screen, then the audio said so far (frames and audio are separate streams anyway).
        if (tools != null) worker.execute(() -> {
            try { frame(tools.screen()); } catch (Exception e) { Log.w("live: no first screenshot: " + e.getMessage()); }
        });
        for (String q : queued) send(q);
        String t = pendingText;
        pendingText = null;
        if (t != null) sendText(t);
    }

    private void content(JSONObject sc) {
        JSONObject it = sc.optJSONObject("inputTranscription");
        if (it != null && !it.optString("text").isEmpty()) { synchronized (heard) { heard.append(it.optString("text")); } ev.heard(heard()); }
        JSONObject ot = sc.optJSONObject("outputTranscription");
        if (ot != null && !ot.optString("text").isEmpty()) {
            outputStarted = true;
            String piece = ot.optString("text");
            synchronized (said) {
                // A new spoken part after a tool call starts without a space ("on YouTube.I've opened...").
                int n = said.length();
                if (n > 0 && ".!?".indexOf(said.charAt(n - 1)) >= 0 && Character.isLetterOrDigit(piece.charAt(0))) said.append(' ');
                said.append(piece);
            }
            ev.said(said());
        }
        JSONObject mt = sc.optJSONObject("modelTurn");
        JSONArray parts = mt == null ? null : mt.optJSONArray("parts");
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            JSONObject inline = p.optJSONObject("inlineData");
            if (inline != null && inline.optString("mimeType", "audio/pcm").startsWith("audio")) {
                byte[] pcm = Base64.getDecoder().decode(inline.optString("data"));
                if (!outputStarted) Log.w("live: first audio of the reply");
                outputStarted = true;
                turnDone = false;
                player.write(pcm, 0, pcm.length);
            } else if (p.has("text") && !p.optBoolean("thought")) {
                outputStarted = true;
            }
        }
        if (sc.optBoolean("interrupted")) player.flush();
        if (sc.optBoolean("turnComplete")) {
            String status = sc.optString("interactionStatus", "");
            idle = !"IN_PROGRESS".equals(status);
            turnDone = true;
            turnDoneAt = System.currentTimeMillis();
        }
    }

    private void toolCall(JSONObject tc) {
        JSONArray calls = tc.optJSONArray("functionCalls");
        if (calls == null || tools == null) return;
        outputStarted = true;
        turnDone = false;
        toolsRunning.incrementAndGet();
        worker.execute(() -> {
            JSONArray responses = new JSONArray();
            boolean acted = false;
            try {
                for (int i = 0; i < calls.length() && !ended; i++) {
                    JSONObject fc = calls.getJSONObject(i);
                    String name = fc.optString("name");
                    JSONObject args = fc.optJSONObject("args");
                    if (args == null) args = new JSONObject();
                    Log.w("live tool: " + name + " " + args);
                    JSONObject result;
                    if ("finish".equals(name)) {
                        finishCalled = true;
                        finishAt = System.currentTimeMillis();
                        finishSummary = args.optString("summary");
                        result = new JSONObject().put("result", "ok");
                        if (!lean) result.put("scheduling", "SILENT");
                    } else {
                        ev.step(describe(name, args));
                        try {
                            result = tools.run(name, args);
                            if (!"get_screen".equals(name)) acted = true;
                        } catch (InterruptedException e) {
                            return;
                        } catch (Exception e) {
                            result = new JSONObject().put("error", Net.explain(e));
                        }
                    }
                    responses.put(new JSONObject().put("id", fc.optString("id")).put("name", name).put("response", result));
                }
                if (ended) return;
                boolean wantsScreen = acted;
                for (int i = 0; i < calls.length(); i++) if ("get_screen".equals(calls.getJSONObject(i).optString("name"))) wantsScreen = true;
                if (wantsScreen) {
                    try { frame(tools.screen()); } catch (Exception e) { Log.w("live: screenshot failed: " + e.getMessage()); }
                }
                lastActivity = System.currentTimeMillis();
                send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", responses)).toString());
            } catch (Exception e) {
                fail("A TV action failed: " + Net.explain(e), false);
            } finally {
                toolsRunning.decrementAndGet();
                lastActivity = System.currentTimeMillis();
            }
        });
    }

    private static String describe(String name, JSONObject a) {
        switch (name) {
            case "run_tv_task": return "Working on \u201C" + a.optString("command") + "\u201D";
            case "press_key": return "Pressing " + a.optString("key").toLowerCase(Locale.US).replace('_', ' ');
            case "open_app": return "Opening " + a.optString("app");
            case "search_app": return "Searching " + a.optString("app") + " for " + a.optString("query");
            case "open_link": return "Opening a link";
            case "select_item": return "Picking " + a.optString("title");
            case "type_text": return "Typing \u201C" + a.optString("text") + "\u201D";
            case "tap": return "Tapping the screen";
            case "scroll": return "Scrolling " + a.optString("direction");
            case "get_screen": return "Looking at the screen";
            default: return name.replace('_', ' ');
        }
    }

    private void closed(int code, String reason) {
        Net.untrack(ws);
        if (ended) return;
        Log.w("live: closed " + code + " " + reason);
        lastClose = "closed " + code + (reason == null || reason.isEmpty() ? "" : ": " + Net.shorten(reason, 160)) + (ready ? "" : ", before setup finished");
        // A rejected setup field (older/newer API): try once more with the plainest setup.
        String low = reason == null ? "" : reason.toLowerCase(Locale.US);
        if (!ready && !lean && !cancelled && (code == 1007 || (code == 1008 && (low.contains("invalid") || low.contains("unknown name"))))) {
            lean = true;
            Log.w("live: setup refused (" + reason + "), retrying without optional fields");
            new Thread(() -> {
                try { connect(); } catch (Exception e) { fail(explainConnect(e), true); }
            }, "tvpilot-live-retry").start();
            return;
        }
        if (code == 1000 && (finishCalled || turnDone) && toolsRunning.get() == 0) { end(); return; }
        fail(explainClose(code, reason), !outputStarted);
    }

    // MARK: - Ending

    String heard() { synchronized (heard) { return heard.toString().trim(); } }
    String said() { synchronized (said) { return said.toString().trim(); } }
    String summary() { return finishSummary; }
    boolean outputStarted() { return outputStarted; }

    private synchronized void end() {
        if (ended) return;
        ended = true;
        Prefs.lastLive("ok: heard \u201C" + Net.shorten(heard(), 60) + "\u201D, replied " + (said().isEmpty() ? "(no words)" : "\u201C" + Net.shorten(said(), 60) + "\u201D"));
        shutdown();
        ev.finished(heard(), said());
    }

    private synchronized void fail(String reason, boolean beforeOutput) {
        if (ended) return;
        ended = true;
        Log.w("live: failed: " + reason);
        if (!cancelled && !reason.startsWith("Gemini Live error"))
            Prefs.lastLive("failed: " + reason + (lastClose == null ? "" : " [" + lastClose + "]"));
        shutdown();
        if (!cancelled) ev.failed(reason, beforeOutput);
    }

    /** Back / mic: stop talking and close at once. */
    synchronized void cancel() {
        cancelled = true;
        if (ended) { player.stop(); return; }
        ended = true;
        shutdown();
    }

    private void shutdown() {
        outbox.clear();
        outbox.offer(STOP);
        Ws w = ws;
        if (w != null) { Net.untrack(w); w.close(1000, "done"); }
        worker.shutdownNow();
        if (cancelled) player.stop();
        else new Thread(() -> {   // let the last words play out, then free the speaker
            long until = System.currentTimeMillis() + 15_000;
            while (!player.drained() && System.currentTimeMillis() < until && !cancelled) try { Thread.sleep(60); } catch (InterruptedException e) { break; }
            player.stop();
        }, "tvpilot-live-drain").start();
    }

    /** Waits (from a background thread) until the voice has finished playing. */
    void awaitQuiet(long maxMs) {
        long until = System.currentTimeMillis() + maxMs;
        while (!player.drained() && System.currentTimeMillis() < until && !cancelled) try { Thread.sleep(60); } catch (InterruptedException e) { return; }
    }

    static String explainConnect(Exception e) {
        if (e instanceof Ws.HandshakeException) {
            Ws.HandshakeException h = (Ws.HandshakeException) e;
            return Net.http(h.status, h.body, "Gemini Live").user;
        }
        if (e instanceof IOException) return Net.explain(Net.classify((IOException) e, "Gemini Live"));
        return "Gemini Live failed: " + Net.explain(e);
    }

    static String explainClose(int code, String reason) {
        String r = reason == null ? "" : reason;
        String low = r.toLowerCase(Locale.US);
        if (low.contains("api key")) return "Gemini rejected the key \u2014 check it in Settings";
        if (low.contains("quota") || low.contains("exhausted") || code == 1013) return "Gemini Live is out of quota or busy right now";
        if (low.contains("not found") || low.contains("not supported") || low.contains("unsupported"))
            return "Gemini Live isn't available for this key (" + Net.shorten(r, 80) + ")";
        if (code == 1006) return "Lost the connection to Gemini Live";
        if (code == 1011) return "Gemini Live had an internal error" + (r.isEmpty() ? "" : " (" + Net.shorten(r, 60) + ")");
        return "Gemini Live closed the session" + (r.isEmpty() ? " (" + code + ")" : ": " + Net.shorten(r, 90));
    }
}
