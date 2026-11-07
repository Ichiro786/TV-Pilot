package dev.syed.tvpilot.tv;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}
    private static SharedPreferences p;

    static void init(Context c) {
        if (p == null) p = c.getApplicationContext().getSharedPreferences("tvpilot", Context.MODE_PRIVATE);
    }

    /** The remote's Assistant/mic button opens TV Pilot instead of Google Assistant. */
    static boolean override() { return p.getBoolean("override", true); }
    static void override(boolean v) { p.edit().putBoolean("override", v).apply(); }

    /** A key code learned with "Learn mic button" (-1: the standard Search / Assist / Voice Assist keys). */
    static int micKey() { return p.getInt("micKey", -1); }
    static void micKey(int v) { p.edit().putInt("micKey", v).apply(); }

    /** Preferred audio input device id (0: whatever the TV routes). */
    static int device() { return p.getInt("device", 0); }
    static void device(int v) { p.edit().putInt("device", v).apply(); }

    static String geminiKey() { return p.getString("geminiKey", ""); }
    static void geminiKey(String v) { p.edit().putString("geminiKey", v == null ? "" : v.trim()).apply(); }
    static String model() { return p.getString("model", "gemini-3.5-flash-lite"); }
    static void model(String v) { p.edit().putString("model", v == null || v.trim().isEmpty() ? "gemini-3.5-flash-lite" : v.trim()).apply(); }

    static String lastTest() { return p.getString("lastTest", ""); }
    static void lastTest(String v) { p.edit().putString("lastTest", v).apply(); }

    static java.util.Set<String> disabled() { return new java.util.HashSet<>(p.getStringSet("disabledApps", new java.util.HashSet<String>())); }
    static void addDisabled(String pkg) { java.util.Set<String> s = disabled(); s.add(pkg); p.edit().putStringSet("disabledApps", s).apply(); }
    static void clearDisabled() { p.edit().remove("disabledApps").apply(); }

    /** "vision" = Gemini looks at the screen (needs the Gemini key), "d1" = the decision model on the VM. */
    static String chatModel() { return p.getString("chatModel", "gemini-3.5-flash-lite"); }
    static void chatModel(String v) { p.edit().putString("chatModel", v == null || v.trim().isEmpty() ? "gemini-3.5-flash-lite" : v.trim()).apply(); }
    /** "hybrid" (decider first, Gemini when needed), "vision" (Gemini always) or "d1" (decider only). */
    static String brain() { String b = p.getString("brain2", "hybrid"); return b; }
    static String liquidKey() { return p.getString("liquidKey", ""); }
    static void liquidKey(String v) { p.edit().putString("liquidKey", v == null ? "" : v.trim()).apply(); }
    static String liquidModel() { return p.getString("liquidModel", "d1-ultra"); }
    static void liquidModel(String v) { p.edit().putString("liquidModel", v == null || v.trim().isEmpty() ? "d1-ultra" : v.trim()).apply(); }
    /** Which decision model: "liquid" (d1-ultra on the Liquid API) or "self" (d1-3B on the VM). */
    static String decider() { return p.getString("decider", "liquid"); }
    static void decider(String v) { p.edit().putString("decider", v).apply(); }
    static void brain(String v) { p.edit().putString("brain2", v).apply(); }
    static String visionModel() { return p.getString("visionModel", "gemini-3.5-flash-lite"); }
    static void visionModel(String v) { p.edit().putString("visionModel", v).apply(); }
    static String rules() { return p.getString("rules", ""); }
    static void rules(String v) { p.edit().putString("rules", v).apply(); }
    /** Streaming profile to pick on "Who's watching?" (blank: the one that's already focused). */
    static String profile() { return p.getString("profile", ""); }
    static void profile(String v) { p.edit().putString("profile", v == null ? "" : v.trim()).apply(); }
    /** The last accuracy test, for Diagnostics. */
    static String lastEval() { return p == null ? "" : p.getString("lastEval", ""); }
    static void lastEval(String v) { if (p != null) p.edit().putString("lastEval", v).apply(); }
    static boolean learnLog() { return p.getBoolean("learnLog", true); }
    static void learnLog(boolean v) { p.edit().putBoolean("learnLog", v).apply(); Learn.on = v; }
    static boolean autoPlay() { return p.getBoolean("autoPlay", false); }
    static void autoPlay(boolean v) { p.edit().putBoolean("autoPlay", v).apply(); }

    /** Replies are spoken: by Phonon on your server, or the TV's own voice when there's no server or it's asleep. */
    static boolean speak() { return p.getBoolean("speak", true); }
    static boolean speakSetting() { return p.getBoolean("speak", true); }
    static void speak(boolean v) { p.edit().putBoolean("speak", v).apply(); }
    static String voice() { return p.getString("voice", "Maeve"); }
    static void voice(String v) { p.edit().putString("voice", v).apply(); }
    /** Your own server (blank by default): any one of its links, e.g. https://8080-yourvm.example.dev or http://192.168.1.5. */
    static String vmLink() { return p.getString("vmLink", ""); }
    static void vmLink(String v) { p.edit().putString("vmLink", v == null ? "" : v.trim().replaceAll("/+$", "")).apply(); }
    static String vmKey() { return p.getString("vmKey", ""); }
    static void vmKey(String v) { p.edit().putString("vmKey", v == null ? "" : v.trim()).apply(); }
    static boolean hasVm() { return !vmLink().isEmpty(); }

    /** Who turns speech into words: "server" (Parakeet on the VM) or "gemini". */
    static String asr() { return p.getString("asr", "server"); }
    static void asr(String v) { p.edit().putString("asr", v).apply(); }

    /** Apps whose window means "the TV's own voice assistant just opened" (learned + the known ones). */
    static final String[] KNOWN_ASSISTANTS = {"com.google.android.katniss", "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googletv.assistant", "com.google.android.tv.assistant"};
    static java.util.Set<String> learnedAssistants() {
        return new java.util.HashSet<>(p.getStringSet("assistants", new java.util.HashSet<String>()));
    }
    static void addAssistant(String pkg) {
        java.util.Set<String> s = learnedAssistants();
        s.add(pkg);
        p.edit().putStringSet("assistants", s).apply();
    }
    static void clearAssistants() { p.edit().remove("assistants").apply(); }

    /** Which brain answers: "classic" (d1 + Gemini Flash Lite + your ASR / Phonon) or "live" (Gemini Live, speech to speech). */
    static String engine() { return p.getString("engine", "classic"); }
    static void engine(String v) { p.edit().putString("engine", "live".equals(v) ? "live" : "classic").apply(); }
    static boolean live() { return "live".equals(engine()); }
    static String liveModel() { return p.getString("liveModel", "gemini-3.8-live"); }
    static void liveModel(String v) { p.edit().putString("liveModel", v == null || v.trim().isEmpty() ? "gemini-3.8-live" : v.trim()).apply(); }
    /** Gemini Live's own prebuilt voice (separate from the Phonon voice used by Classic). */
    static String liveVoice() { return p.getString("liveVoice", "Puck"); }
    static void liveVoice(String v) { p.edit().putString("liveVoice", v).apply(); }

    /** How the last Gemini Live session ended (close code and reason, or the server's error), for Diagnostics. */
    static String lastLive() { return p == null ? "" : p.getString("lastLive", ""); }
    static void lastLive(String v) {
        if (p != null) p.edit().putString("lastLive", new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date()) + "  " + v).apply();
    }

    /** The last crash (shown in Diagnostics so it's never silent). */
    static String lastCrash() { return p == null ? "" : p.getString("lastCrash", ""); }
    static void lastCrash(String v) { if (p != null) p.edit().putString("lastCrash", v).commit(); }

    /** Diagnostic: record through a see-through activity instead of the overlay (the app is then "in front"). */
    static boolean viaActivity() { return p.getBoolean("viaActivity2", true); }
    static void viaActivity(boolean v) { p.edit().putBoolean("viaActivity2", v).apply(); }
}
