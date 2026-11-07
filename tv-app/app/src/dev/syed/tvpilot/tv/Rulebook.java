package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in rulebook (assets/rulebook/*.json, versioned) plus what TV Pilot learned on this TV (learned.json in the
 * app's files). One App per streaming app / launcher / settings screen: packages, deep links, how its screens look
 * and how the focus moves on each, the names of its buttons, dialogs and what to press on them. The deterministic
 * routes read it directly; the decision model gets the part for the app and screen in front.
 * Entries marked verified=false come from research, not from Syed's TVs: the verify-after-action loop treats them as
 * hints and the learn log records what really happens so they can be corrected.
 */
final class Rulebook {
    static volatile String version = "?";
    private static final Map<String, App> BY_PKG = new LinkedHashMap<>();
    private static final List<App> ALL = new ArrayList<>();
    private static App generic;
    private static File learnedFile;
    private static JSONObject learned = new JSONObject();

    static final class App {
        final JSONObject j;
        final String id, label;
        final List<String> packages = new ArrayList<>(), aliases = new ArrayList<>(), justwatch = new ArrayList<>();
        App(JSONObject j) {
            this.j = j;
            id = j.optString("id");
            label = j.optString("label", id);
            add(packages, j.optJSONArray("packages"));
            add(aliases, j.optJSONArray("aliases"));
            add(justwatch, j.optJSONArray("justwatch"));
        }

        boolean verified() { return j.optBoolean("verified", false); }
        /** What the accessibility tree is expected to show: full / partial / none (custom renderer). */
        String tree() { return j.optString("tree", "unknown"); }
        JSONObject titleLink() { return j.optJSONObject("title_link"); }
        JSONObject searchLink() { return j.optJSONObject("search_link"); }
        JSONObject search() { JSONObject o = j.optJSONObject("search"); return o == null ? new JSONObject() : o; }
        JSONObject screen(String name) { JSONObject s = j.optJSONObject("screens"); return s == null ? null : s.optJSONObject(name); }
        String profileRule() { return j.optJSONObject("buttons") == null ? "focused" : j.optJSONObject("buttons").optString("profile_default", "focused"); }

        List<String> buttons(String name) {
            List<String> out = new ArrayList<>();
            JSONObject b = j.optJSONObject("buttons");
            if (b != null) add(out, b.optJSONArray(name));
            if (generic != null && generic != this && generic.j.optJSONObject("buttons") != null) add(out, generic.j.optJSONObject("buttons").optJSONArray(name));
            return out;
        }

        /** {when:[texts], press:"label" | key:"BACK"} rules for popups (still watching?, skip intro, ads, updates). */
        List<JSONObject> dialogs() {
            List<JSONObject> out = new ArrayList<>();
            JSONArray a = j.optJSONArray("dialogs");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optJSONObject(i));
            if (generic != null && generic != this) out.addAll(generic.dialogs());
            return out;
        }

        Ui.Kind signatureMatch(Ui.Snapshot s) {
            JSONObject sc = j.optJSONObject("screens");
            if (sc == null || s.empty()) return null;
            String all = Ui.norm(s.allText());
            StringBuilder ids = new StringBuilder();
            for (Ui.Node n : s.nodes) if (!n.resId.isEmpty()) ids.append(' ').append(n.shortId().toLowerCase(Locale.US));
            String act = s.activity.toLowerCase(Locale.US);
            for (Iterator<String> it = sc.keys(); it.hasNext(); ) {
                String name = it.next();
                JSONObject sig = sc.optJSONObject(name) == null ? null : sc.optJSONObject(name).optJSONObject("signature");
                if (sig == null) continue;
                if (any(sig.optJSONArray("activity_any"), act) || anyWord(sig.optJSONArray("text_any"), all) || any(sig.optJSONArray("id_any"), ids.toString())) {
                    Ui.Kind k = kindOf(name);
                    if (k != null) return k;
                }
            }
            return null;
        }

        /** What the decision model is told about this app and (if known) this screen: short, it's read every step. */
        String brief(Ui.Kind kind) {
            StringBuilder b = new StringBuilder();
            b.append("APP RULES (").append(label).append(verified() ? "" : ", unverified").append("): ");
            JSONArray notes = j.optJSONArray("notes");
            if (notes != null) for (int i = 0; i < Math.min(4, notes.length()); i++) b.append(notes.optString(i)).append(' ');
            JSONObject s = kind == null ? null : screen(screenName(kind));
            if (s != null) {
                b.append("\nTHIS SCREEN (").append(screenName(kind)).append("): ").append(s.optString("focus"));
                String look = s.optString("looks");
                if (!look.isEmpty()) b.append(" Looks: ").append(look);
                String ascii = s.optString("ascii");
                if (!ascii.isEmpty() && ascii.length() < 600) b.append("\nLAYOUT:\n").append(ascii);
            }
            String l = learnedBrief(packages.isEmpty() ? id : packages.get(0));
            if (!l.isEmpty()) b.append("\nLEARNED ON THIS TV: ").append(l);
            return b.toString();
        }

        /** One line for Gemini Live's instructions. */
        String summary() {
            StringBuilder b = new StringBuilder(label).append(": ");
            JSONArray notes = j.optJSONArray("notes");
            if (notes != null && notes.length() > 0) b.append(notes.optString(0));
            JSONArray ask = j.optJSONArray("ask_user_when");
            if (ask != null && ask.length() > 0) { b.append(" Ask the user when: "); for (int i = 0; i < ask.length(); i++) b.append(i > 0 ? "; " : "").append(ask.optString(i)); b.append('.'); }
            return b.toString();
        }
    }

    static String screenName(Ui.Kind k) {
        switch (k) {
            case PLAYER: return "player";
            case KEYBOARD: return "search";
            case SEARCH_RESULTS: return "search";
            case DETAIL: return "detail";
            case EPISODES: return "episodes";
            case PROFILE: return "profile";
            case DIALOG: return "dialog";
            case LOGIN: return "login";
            case RAIL: case BROWSE: return "home";
            default: return "home";
        }
    }

    static Ui.Kind kindOf(String name) {
        switch (name) {
            case "player": return Ui.Kind.PLAYER;
            case "search": return Ui.Kind.KEYBOARD;
            case "results": return Ui.Kind.SEARCH_RESULTS;
            case "detail": return Ui.Kind.DETAIL;
            case "episodes": return Ui.Kind.EPISODES;
            case "profile": return Ui.Kind.PROFILE;
            case "dialog": return Ui.Kind.DIALOG;
            case "login": return Ui.Kind.LOGIN;
            case "home": return Ui.Kind.BROWSE;
            default: return null;
        }
    }

    // MARK: - Loading

    /** files: name -> JSON text (assets on the TV, a folder in the tests). learned: where this TV's learnings live. */
    static synchronized void load(Map<String, String> files, File learnedAt) {
        BY_PKG.clear();
        ALL.clear();
        generic = null;
        for (Map.Entry<String, String> e : files.entrySet()) {
            try {
                JSONObject root = new JSONObject(e.getValue());
                if (root.has("rulebook_version")) version = root.optString("rulebook_version");
                JSONArray apps = root.optJSONArray("apps");
                if (apps == null && root.has("id")) apps = new JSONArray().put(root);
                if (apps == null) continue;
                for (int i = 0; i < apps.length(); i++) {
                    App a = new App(apps.getJSONObject(i));
                    if ("generic".equals(a.id)) generic = a;
                    ALL.add(a);
                    for (String p : a.packages) BY_PKG.put(p, a);
                }
            } catch (Exception ex) {
                Log.w("rulebook " + e.getKey() + " unreadable: " + ex.getMessage());
            }
        }
        learnedFile = learnedAt;
        try {
            if (learnedAt != null && learnedAt.exists()) learned = new JSONObject(new String(java.nio.file.Files.readAllBytes(learnedAt.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            learned = new JSONObject();
        }
        Log.w("rulebook " + version + ": " + ALL.size() + " entries, learned about " + learned.length() + " apps");
    }

    static synchronized App forPkg(String pkg) {
        if (pkg == null) return generic;
        App a = BY_PKG.get(pkg);
        return a != null ? a : generic;
    }

    static synchronized App known(String pkg) { return pkg == null ? null : BY_PKG.get(pkg); }

    static synchronized App generic() { return generic; }

    static synchronized List<App> all() { return Collections.unmodifiableList(new ArrayList<>(ALL)); }

    /** Which installed package serves a JustWatch provider (technical name like "netflix", "hotstar"). */
    static synchronized List<String> packagesForProvider(String provider) {
        List<String> out = new ArrayList<>();
        for (App a : ALL) if (a.justwatch.contains(provider)) out.addAll(a.packages);
        return out;
    }

    // MARK: - Learned on this TV

    static synchronized JSONObject learnedFor(String pkg) {
        JSONObject o = learned.optJSONObject(pkg);
        if (o == null) { o = new JSONObject(); try { learned.put(pkg, o); } catch (Exception ignored) {} }
        return o;
    }

    static synchronized void learn(String pkg, String key, Object value) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            JSONObject o = learnedFor(pkg);
            Object old = o.opt(key);
            if (old != null && old.toString().equals(String.valueOf(value))) return;
            o.put(key, value);
            o.put("updated", System.currentTimeMillis());
            save();
        } catch (Exception e) {
            Log.w("learn: " + e.getMessage());
        }
    }

    static synchronized String learnedString(String pkg, String key) {
        JSONObject o = learned.optJSONObject(pkg);
        return o == null ? "" : o.optString(key, "");
    }

    static synchronized String learnedBrief(String pkg) {
        JSONObject o = learned.optJSONObject(pkg);
        if (o == null) return "";
        StringBuilder b = new StringBuilder();
        for (String k : new String[] {"tree", "tree_focus", "search_route", "typing", "focus_model", "rail", "taps"}) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) b.append(k).append('=').append(v).append("; ");
        }
        return b.toString().trim();
    }

    static synchronized String learnedJson() { return learned.toString(); }

    static synchronized void forgetLearned() { learned = new JSONObject(); save(); }

    private static void save() {
        if (learnedFile == null) return;
        try {
            File tmp = new File(learnedFile.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), learned.toString(1).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(learnedFile)) java.nio.file.Files.write(learnedFile.toPath(), learned.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w("learned.json: " + e.getMessage());
        }
    }

    // MARK: - helpers

    private static void add(List<String> to, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) { String s = a.optString(i); if (!s.isEmpty() && !to.contains(s)) to.add(s); }
    }

    private static boolean any(JSONArray a, String hay) {
        if (a == null || hay.isEmpty()) return false;
        for (int i = 0; i < a.length(); i++) { String s = a.optString(i).toLowerCase(Locale.US); if (!s.isEmpty() && hay.contains(s)) return true; }
        return false;
    }

    private static boolean anyWord(JSONArray a, String all) {
        if (a == null || all.isEmpty()) return false;
        for (int i = 0; i < a.length(); i++) {
            String w = Ui.norm(a.optString(i));
            if (!w.isEmpty() && java.util.regex.Pattern.compile("(^|\\W)" + java.util.regex.Pattern.quote(w) + "(\\W|$)").matcher(all).find()) return true;
        }
        return false;
    }
}
