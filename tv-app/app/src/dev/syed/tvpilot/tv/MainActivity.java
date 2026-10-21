package dev.syed.tvpilot.tv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The TV app's one screen: setup status, the override switch, the mic test, and what the test found. */
public final class MainActivity extends Activity implements Pilot.Host {
    private float k;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SetupServer server = new SetupServer();
    private FrameLayout root;
    private TextView heroTitle, heroText, diag;
    private Row setupRow, overrideRow, micRow, testRow, inputRow, keyRow, geminiRow, modelRow, playRow, viaRow, assistantRow, googleRow, speakRow, voiceRow, asrRow, whoRow, freeRow, typeRow, brainRow, deciderRow, liquidRow, vmLinkRow, vmKeyRow, visionRow, rulesRow, autoRow;
    private boolean adbRunning;
    private String adbStatus;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            main.postDelayed(this, 600);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.init(this);        // must come first: on a cold start (service off) Prefs is still null
        Prefs.clearDisabled();   // the old list could turn your Google TV home back on; it's never used again
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(px(96), px(72), px(96), px(40));
        root.addView(cols, new FrameLayout.LayoutParams(-1, -1));

        // Left: who we are and what the test found
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        cols.addView(left, new LinearLayout.LayoutParams(0, -1, 0.42f));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        left.addView(logo, new LinearLayout.LayoutParams(px(132), px(132)));
        left.addView(text("TV Pilot TV", 64, Color.WHITE, true), margins(0, 26, 0, 0));
        left.addView(text("1.0.0 \u00B7 your AI voice remote", 26, 0xFF9A9AA0, false), margins(0, 6, 0, 0));
        heroTitle = text("", 34, Color.WHITE, true);
        left.addView(heroTitle, margins(0, 54, 0, 0));
        heroText = text("", 24, 0xFFB8B8BE, false);
        heroText.setLineSpacing(0, 1.15f);
        left.addView(heroText, margins(0, 10, 40, 0));
        diag = text("", 19, 0xFF6E6E74, false);
        diag.setTypeface(Typeface.MONOSPACE);
        diag.setLineSpacing(0, 1.2f);
        left.addView(diag, margins(0, 34, 40, 0));

        // Right: the rows
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(px(12), px(12), px(12), px(60));
        cols.addView(scroll, new LinearLayout.LayoutParams(0, -1, 0.58f));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        testRow = new Row(list, "Talk to TV Pilot", v -> testNow());
        typeRow = new Row(list, "Type a command", v -> prompt("What should TV Pilot do?", "", false, t -> {
            if (t.trim().isEmpty()) return;
            String err = Pilot.get(this).command(t.trim());
            if (err != null) typeRow.flash(err);
            else moveTaskToBack(true);
        }));
        brainRow = new Row(list, "Brain", v -> { String cur = Prefs.brain(); Prefs.brain("hybrid".equals(cur) ? "vision" : "vision".equals(cur) ? "d1" : "hybrid"); refresh(); });
        deciderRow = new Row(list, "Decision model", v -> { Prefs.decider("liquid".equals(Prefs.decider()) ? "self" : "liquid"); refresh(); });
        liquidRow = new Row(list, "Liquid API key", v -> prompt("Liquid API key", "", true, k -> { if (!k.trim().isEmpty()) Prefs.liquidKey(k); refresh(); }));
        vmLinkRow = new Row(list, "Your server link", v -> prompt("Your server link (any one port's link)", Prefs.vmLink(), false, t -> { Prefs.vmLink(t); refresh(); }));
        vmKeyRow = new Row(list, "Your server key", v -> prompt("Your server key", "", true, t -> { if (!t.trim().isEmpty()) Prefs.vmKey(t); refresh(); }));
        visionRow = new Row(list, "Vision model", v -> prompt("Gemini vision model", Prefs.visionModel(), false, t -> { if (!t.trim().isEmpty()) Prefs.visionModel(t.trim()); refresh(); }));
        rulesRow = new Row(list, "Your rules", v -> prompt("Rules TV Pilot always follows", Prefs.rules(), false, t -> { Prefs.rules(t); refresh(); }));
        autoRow = new Row(list, "Auto-play first YouTube result", v -> { Prefs.autoPlay(!Prefs.autoPlay()); refresh(); });
        setupRow = new Row(list, "Mic button service", v -> setup());
        overrideRow = new Row(list, "Override Google Assistant mic button", v -> { Prefs.override(!Prefs.override()); refresh(); });
        micRow = new Row(list, "Microphone permission", v -> requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1));
        keyRow = new Row(list, "Learn the mic button", v -> learn());
        assistantRow = new Row(list, "Make TV Pilot the TV's assistant", v -> adb("Setting TV Pilot as the assistant…",
                a -> Setup.makeAssistant(this, false, a)));
        googleRow = new Row(list, "Google voice search app", v -> {
            Boolean on = Setup.googleVoiceOn(this);
            if (on == null) { googleRow.flash("Not installed on this TV"); return; }
            adb(on ? "Switching Google voice search off…" : "Switching Google voice search on…", a -> Setup.setGoogleVoice(this, !on, a));
        });
        speakRow = new Row(list, "Voice replies", v -> { Prefs.speak(!Prefs.speakSetting()); refresh(); });
        voiceRow = new Row(list, "Voice", v -> pickVoice());
        asrRow = new Row(list, "Speech to words", v -> {
            Prefs.asr("gemini".equals(Prefs.asr()) ? "server" : "gemini");
            refresh();
        });
        whoRow = new Row(list, "Who has the mic?", v -> adb("Asking the TV which apps hold the mic\u2026", a -> Setup.findMicHolders(this, a)));
        freeRow = new Row(list, "Free the mic", v -> {
            if (!Setup.holders.isEmpty()) adb("Stopping " + android.text.TextUtils.join(", ", Setup.holders) + "\u2026", a -> Setup.freeMic(this, a));
            else freeRow.flash("Run \u201CWho has the mic?\u201D first");
        });
        inputRow = new Row(list, "Microphone input", v -> nextInput());
        geminiRow = new Row(list, "Gemini API key", v -> editKey());
        modelRow = new Row(list, "Gemini model", v -> editModel());
        playRow = new Row(list, "Play the last recording", v -> {
            if (!Pilot.get(this).playLast()) playRow.flash("Nothing recorded yet");
        });
        viaRow = new Row(list, "Listen from", v -> { Prefs.viaActivity(!Prefs.viaActivity()); refresh(); });
        setContentView(root);
        testRow.view.requestFocus();

        if (!Setup.micAllowed(this)) requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
        if (!Setup.serviceEnabled(this) && Setup.canWriteSettings(this)) Setup.enableService(this);
    }

    @Override protected void onResume() {
        super.onResume();
        server.start(() -> main.post(this::refresh));
        main.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        server.stop();
        PilotService.learnUntil = 0;
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        refresh();
    }

    // MARK: - Actions

    private void testNow() {
        if (!Setup.micAllowed(this)) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        PilotService s = PilotService.instance;
        if (Prefs.viaActivity()) {
            startActivity(new Intent(this, AssistActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } else {
            Pilot.get(this).trigger(s != null ? s : this);
        }
    }

    private void setup() {
        if (Setup.serviceEnabled(this)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (Setup.canWriteSettings(this) && Setup.enableService(this)) {
            refresh();
            return;
        }
        if (adbRunning) return;
        adbRunning = true;
        adbStatus = "Connecting to the TV's debugging…";
        refresh();
        new Thread(() -> {
            String err = Setup.viaAdb(this, () -> main.post(() -> {
                adbStatus = "On the TV's prompt choose \u201CAlways allow\u201D, then OK.";
                refresh();
            }));
            main.post(() -> {
                adbRunning = false;
                if (err == null) {
                    adbStatus = null;
                } else {
                    adbStatus = "Automatic setup failed (" + err + "). Opening Accessibility: turn on \u201CTV Pilot TV mic button\u201D.";
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
                }
                refresh();
            });
        }, "tvpilot-adb").start();
    }

    /** The voice list from the VM; picking one plays a short preview in that voice. */
    private void pickVoice() {
        voiceRow.flash("Getting the voices\u2026");
        new Thread(() -> {
            java.util.List<String> vs;
            String err = null;
            try {
                vs = Server.voices();
            } catch (Exception e) {
                vs = null;
                err = e.getMessage();
            }
            final java.util.List<String> list = vs;
            final String e2 = err;
            main.post(() -> {
                if (list == null || list.isEmpty()) { voiceRow.flash("Couldn't reach the voice server: " + e2); return; }
                final String[] items = list.toArray(new String[0]);
                int cur = Math.max(0, list.indexOf(Prefs.voice()));
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Pick a voice \u00B7 select one to hear it")
                        .setSingleChoiceItems(items, cur, (d, which) -> {
                            Prefs.voice(items[which]);
                            Pilot.preview(items[which], null);
                            refresh();
                        })
                        .setPositiveButton("Done", (d, w) -> Speaker.stop())
                        .setOnDismissListener(d -> refresh())
                        .show();
            });
        }, "tvpilot-voices").start();
    }

    private interface AdbJob { String run(Adb.Approval a); }

    /** Runs a setup step through the TV's own debugging, showing progress on the setup row. */
    private void adb(String status, AdbJob job) {
        if (adbRunning) return;
        adbRunning = true;
        adbStatus = status;
        refresh();
        new Thread(() -> {
            String err = job.run(() -> main.post(() -> {
                adbStatus = "On the TV's prompt choose \u201CAlways allow\u201D, then OK.";
                refresh();
            }));
            main.post(() -> {
                adbRunning = false;
                adbStatus = err == null ? null : "Debugging step failed: " + err;
                if (err == null) setupRow.flash("Done \u2713");
                refresh();
            });
        }, "tvpilot-adb").start();
    }

    private void learn() {
        if (PilotService.instance == null) {
            keyRow.flash("Turn on the mic button service first");
            return;
        }
        if ((Prefs.micKey() > 0 || !Prefs.learnedAssistants().isEmpty()) && PilotService.learnUntil < System.currentTimeMillis()
                && PilotService.lastLearned != null) {
            // Pressed again after learning: forget and start over.
            Prefs.micKey(-1);
            Prefs.clearAssistants();
            PilotService.lastLearned = null;
        }
        PilotService.learnUntil = System.currentTimeMillis() + 10_000;
        refresh();
    }

    private void nextInput() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        List<AudioDeviceInfo> ins = new ArrayList<>();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) ins.add(d);
        int cur = Prefs.device(), idx = -1;
        for (int i = 0; i < ins.size(); i++) if (ins.get(i).getId() == cur) idx = i;
        int next = idx + 1;
        Prefs.device(next >= ins.size() ? 0 : ins.get(next).getId());
        refresh();
    }

    private String inputName() {
        int cur = Prefs.device();
        if (cur == 0) return "Auto (the TV decides)";
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) if (d.getId() == cur) return Recorder.describe(d);
        return "Auto (the TV decides)";
    }

    private void editKey() {
        prompt("Gemini API key", "", true, v -> { if (!v.trim().isEmpty()) Prefs.geminiKey(v); refresh(); });
    }

    private void editModel() {
        prompt("Gemini model", Prefs.model(), false, v -> { Prefs.model(v); refresh(); });
    }

    private interface Done { void done(String v); }

    private void prompt(String title, String value, boolean secret, Done done) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(e)
                .setPositiveButton("Save", (d, w) -> done.done(e.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    // MARK: - Status

    private void refresh() {
        boolean service = Setup.serviceEnabled(this), running = PilotService.instance != null, mic = Setup.micAllowed(this);
        setupRow.set(adbStatus != null ? adbStatus
                : service ? (running ? "On \u2713  (select to open Accessibility)" : "Enabled, starting…")
                : "Off \u2014 select to switch it on automatically", service && running);
        overrideRow.set(Prefs.override() ? "On \u2014 the remote's mic button opens TV Pilot" : "Off \u2014 the mic button goes to Google as usual",
                Prefs.override());
        micRow.set(mic ? "Allowed \u2713" : "Not allowed \u2014 select to allow", mic);
        boolean learning = PilotService.learnUntil > System.currentTimeMillis();
        String learnedPopups = Prefs.learnedAssistants().isEmpty() ? "" : " \u00B7 popup " + String.join(", ", Prefs.learnedAssistants());
        String learnedKey = Prefs.micKey() > 0 ? "key " + KeyEvent.keyCodeToString(Prefs.micKey()).replace("KEYCODE_", "") : "";
        keyRow.set(learning ? "Press the remote's mic button now…"
                : !learnedKey.isEmpty() || !learnedPopups.isEmpty() ? "Learned: " + learnedKey + learnedPopups + "  (select to redo)"
                : "Select, then press the mic button: catches its key or Google's popup", !learnedKey.isEmpty() || !learnedPopups.isEmpty());
        boolean asst = Setup.isTvAssistant(this);
        assistantRow.set(asst ? "Set \u2713 \u2014 the TV hands the mic button to TV Pilot" : "Not set \u2014 select (uses the TV's debugging)", asst);
        Boolean gv = Setup.googleVoiceOn(this);
        googleRow.set(gv == null ? "Not on this TV" : gv ? "On \u2014 select to switch off (voice search in other apps stops)" : "Off \u2014 select to switch back on", gv != null && !gv);
        inputRow.set(inputName(), false);
        String ip = SetupServer.ip();
        geminiRow.set((Prefs.geminiKey().isEmpty() ? "Not set" : "Set \u2713") + (ip == null ? "" : "  \u2014 or paste it from your phone: http://" + ip + ":" + SetupServer.PORT),
                !Prefs.geminiKey().isEmpty());
        modelRow.set(Prefs.model(), false);
        String brain = Prefs.brain();
        boolean vision = !"d1".equals(brain);
        boolean liquid = "liquid".equals(Prefs.decider());
        deciderRow.set(liquid ? (Prefs.liquidKey().isEmpty() ? "Liquid d1-ultra \u2014 add the Liquid key below; your d1 runs until then" : "Liquid " + Prefs.liquidModel() + " (your d1 on the VM if it fails)")
                : "d1-3B on your VM (free)", liquid && !Prefs.liquidKey().isEmpty());
        vmLinkRow.set(Prefs.hasVm() ? "Set \u2713 \u2014 d1 :8080, Parakeet :8081, Phonon :8082" : "Not set \u2014 your own d1 / Parakeet / Phonon server (optional)" + (ip == null ? "" : ", or paste it from your phone: http://" + ip + ":" + SetupServer.PORT), Prefs.hasVm());
        vmKeyRow.set(Prefs.vmKey().isEmpty() ? "Not set" : "Set \u2713", !Prefs.vmKey().isEmpty());
        liquidRow.set((Prefs.liquidKey().isEmpty() ? "Not set" : "Set \u2713") + (ip == null ? "" : "  \u2014 or paste it from your phone: http://" + ip + ":" + SetupServer.PORT), !Prefs.liquidKey().isEmpty());
        typeRow.set("Test any action without speaking, e.g. \u201Cplay lofi on YouTube\u201D", false);
        brainRow.set("hybrid".equals(brain) ? "Hybrid: the decision model acts first, Gemini steps in only when needed"
                : "vision".equals(brain) ? "Gemini vision every time (decision model is the backup)"
                : "Decision model only: fastest, no Gemini", vision && !Prefs.geminiKey().isEmpty());
        visionRow.set(Prefs.visionModel(), false);
        rulesRow.set(Prefs.rules().isEmpty() ? "None \u2014 e.g. \u201CPrefer Nuvio for movies\u201D" : Prefs.rules(), false);
        autoRow.set(Prefs.autoPlay() ? "On" : "Off", Prefs.autoPlay());
        whoRow.set(Setup.holders.isEmpty() ? "Asks Android which apps hold the mic, including hidden ones" : "Holding it: " + android.text.TextUtils.join(", ", Setup.holders), !Setup.holders.isEmpty());
        freeRow.set(!Setup.holders.isEmpty() ? "Stop " + android.text.TextUtils.join(", ", Setup.holders) + " for now (nothing gets switched off)"
                : "Stops (never switches off) the app \u201CWho has the mic?\u201D finds", false);
        speakRow.set(!Prefs.speakSetting() ? "Off \u2014 replies only show on screen" : Prefs.hasVm() ? "On \u2014 TV Pilot answers out loud (Phonon on your server)" : "On, but needs your server link (Phonon) \u2014 replies show on screen until then", Prefs.speak());
        voiceRow.set(Prefs.voice() + "  \u2014 select to hear and pick another", false);
        asrRow.set("gemini".equals(Prefs.asr()) || !Prefs.hasVm() ? (Prefs.geminiKey().isEmpty() ? "Gemini (needs a key below)" : "Gemini") + (Prefs.hasVm() ? "" : " \u2014 add your server link for Parakeet") : "Parakeet on your server", false);
        viaRow.set(Prefs.viaActivity() ? "See-through screen in front (best for the mic)" : "Overlay over the app (doesn't pause what's playing)", false);
        testRow.set(running ? "Or just press the mic button on your remote" : "Records on this screen", false);

        String last = Prefs.lastTest();
        if (last.isEmpty()) {
            heroTitle.setText(running ? "Press the remote's mic button" : "Switch on the mic button service");
            heroText.setText(running ? "Speak a command. If the bar shows your voice moving, your remote's mic works with TV Pilot."
                    : "Select \u201CMic button service\u201D. It sets itself up through the TV's debugging, or opens Accessibility for you.");
        } else {
            boolean ok = last.contains("\u2713");
            heroTitle.setText(ok ? "Your remote's mic works \u2713" : "Last test");
            heroText.setText(last.substring(Math.min(last.length(), 7)));
        }
        diag.setText("Keys seen: " + PilotService.recentKeys() + "\nWindows seen: " + PilotService.recentWindows()
                + "\nOther recordings open now: " + Recorder.othersRecording(this) + "\n" + Log.recent(12));
    }

    // MARK: - Pilot.Host (the bar on this screen)

    @Override public void attach(MicBar bar) {
        root.addView(bar, new FrameLayout.LayoutParams(-1, Math.round(330 * k), Gravity.BOTTOM));
    }

    @Override public void detach(MicBar bar) {
        root.removeView(bar);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (PilotService.instance == null && PilotService.isMicKey(code)) {
            if (e.getRepeatCount() == 0) testNow();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && Pilot.get(this).showing()) {
            Pilot.get(this).cancel();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // MARK: - Views

    private int px(float v) {
        return Math.round(v * k);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(px(l), px(t), px(r), px(b));
        return lp;
    }

    /** A setting row: a rounded card that turns white with black text when focused, with a soft overshoot. */
    private final class Row {
        final LinearLayout view;
        final TextView title, value;
        final GradientDrawable bg = new GradientDrawable();
        private String flash;
        private boolean on;

        Row(LinearLayout parent, String name, View.OnClickListener click) {
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(px(36), px(22), px(36), px(24));
            view.setFocusable(true);
            view.setClickable(true);
            bg.setCornerRadius(px(34));
            bg.setColor(0xFF161618);
            view.setBackground(bg);
            title = text(name, 29, Color.WHITE, true);
            value = text("", 21, 0xFF9A9AA0, false);
            view.addView(title);
            view.addView(value, margins(0, 4, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, px(14));
            parent.addView(view, lp);
            view.setOnClickListener(click);
            view.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.035f : 1f).scaleY(f ? 1.035f : 1f).setDuration(f ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(f ? 1.6f : 0f)).start();
                paint(f);
            });
        }

        void set(String v, boolean on) {
            this.on = on;
            value.setText(flash != null ? flash : v);
            paint(view.hasFocus());
        }

        void flash(String s) {
            flash = s;
            value.setText(s);
            main.postDelayed(() -> { flash = null; refresh(); }, 2500);
        }

        private void paint(boolean f) {
            bg.setColor(f ? 0xFFF2F2F5 : 0xFF161618);
            title.setTextColor(f ? 0xFF0B0B0C : Color.WHITE);
            value.setTextColor(f ? 0xFF4A4A50 : on ? 0xFF9CE7B4 : 0xFF9A9AA0);
        }
    }
}
