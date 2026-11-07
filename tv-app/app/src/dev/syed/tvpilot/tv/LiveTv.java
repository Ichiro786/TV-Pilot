package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The TV actions Gemini Live may call, done through the TV's own debugging connection (the same moves as Classic). */
final class LiveTv implements Live.Tools {
    static final String[] KEYS = LiveDecl.KEYS;

    private final Tv tv;
    private final android.content.Context app;
    /** Progress of a run_tv_task, for the bar. */
    volatile Vision.Steps steps;
    private final List<Tv.App> apps;
    private final Map<String, Tv.App> byPkg;
    /** Why the TV can't be controlled right now (debugging off...), or null. */
    final String unavailable;

    LiveTv(android.content.Context app, Tv tv, List<Tv.App> apps, String unavailable) {
        this.app = app;
        this.tv = tv;
        this.apps = apps == null ? new ArrayList<Tv.App>() : apps;
        this.byPkg = Tv.index(this.apps);
        this.unavailable = unavailable;
    }

    /** The app list for the system instruction. */
    String appList() {
        StringBuilder b = new StringBuilder();
        for (Tv.App a : apps) b.append("\n- ").append(a.label).append(" (").append(a.pkg).append(")").append(a.searchUrl != null ? " [direct search]" : "");
        return b.length() == 0 ? "\n(unknown)" : b.toString();
    }

    /** One line per installed app from the rulebook, so Live knows how each app behaves and when to ask. */
    String rulebookSummary() {
        StringBuilder b = new StringBuilder();
        for (Tv.App a : apps) {
            Rulebook.App r = Rulebook.known(a.pkg);
            if (r == null || r.j.optBoolean("protected")) continue;
            b.append("\n- ").append(r.summary());
        }
        Rulebook.App g = Rulebook.generic();
        if (g != null) b.append("\n- In any app: ").append(g.summary());
        return b.toString();
    }

    @Override public JSONArray declarations() throws Exception { return LiveDecl.declarations(); }

    @Override public byte[] screen() throws Exception {
        if (unavailable != null) return null;
        return tv.screenshot(960).jpeg;
    }

    @Override public JSONObject run(String name, JSONObject a) throws Exception {
        if (unavailable != null) return new JSONObject().put("error", unavailable);
        String result;
        switch (name) {
            case "run_tv_task": {
                String cmd = a.optString("command").trim();
                if (cmd.isEmpty()) return err("empty command");
                Vision.Steps st = steps;
                Agent.Result r = new Agent(app).run(cmd, s -> { if (st != null) st.step(s); }, true);
                JSONObject out = r.detail != null ? r.detail : new JSONObject().put("success", r.success).put("outcome", r.say == null ? "" : r.say);
                out.put("app_in_front", front());
                if (r.needsUser) {
                    out.put("note", "TV Pilot needs a choice from the user before it can continue. Ask the user this question in one short sentence, "
                            + "wait for the answer, then call run_tv_task again with ONE complete command that includes the answer (e.g. \"play Panchayat on Prime Video\").");
                } else {
                    out.put("note", (r.success ? "Done (verified on screen). " : "It did not finish. ") + "Tell the user the outcome in one short sentence"
                            + (r.success ? "." : ", using 'outcome' and the last 'steps'. Don't claim success."));
                }
                return out;
            }
            case "press_key": {
                Tv.Key k = Tv.Key.named(a.optString("key"));
                if (k == null || k == Tv.Key.SLEEP || k == Tv.Key.WAKE) return err("unknown key " + a.optString("key"));
                int n = Math.max(1, Math.min(10, a.optInt("times", k == Tv.Key.VOLUME_UP || k == Tv.Key.VOLUME_DOWN ? 3 : 1)));
                tv.key(k, n);
                Thread.sleep(k == Tv.Key.OK || k == Tv.Key.BACK || k == Tv.Key.HOME ? 1300 : 600);
                result = "pressed " + k.name() + " x" + n;
                break;
            }
            case "open_app": {
                Tv.App app = find(a.optString("app"));
                if (app == null) return err("no installed app called " + a.optString("app") + ". Installed: " + names());
                tv.launch(app);
                tv.waitForApp(app.pkg, 8000);
                result = "opened " + app.label;
                break;
            }
            case "search_app": {
                Tv.App app = find(a.optString("app"));
                String q = a.optString("query").trim();
                if (app == null) return err("no installed app called " + a.optString("app"));
                if (q.isEmpty()) return err("empty query");
                if (app.searchUrl != null) {
                    boolean open = app.pkg.equals(tv.foreground());
                    tv.deepSearch(app, q, false);
                    Thread.sleep(open ? 2500 : 5500);
                    result = "opened " + app.label + "'s search results for \"" + q + "\"";
                } else {
                    if (!app.pkg.equals(tv.foreground())) { tv.launch(app); tv.waitForApp(app.pkg, 8000); }
                    Integer y = tv.searchInsideApp(q);
                    result = y == null ? "opened " + app.label + " but couldn't find its search box; look at the screen and use the D-pad"
                            : "typed \"" + q + "\" into " + app.label + "'s search";
                }
                break;
            }
            case "open_link": {
                String url = a.optString("url").trim();
                if (url.isEmpty() || url.length() > 500) return err("bad url");
                Tv.App app = a.optString("app").isEmpty() ? null : find(a.optString("app"));
                String target = app == null ? "" : " " + (app.component != null ? "-n " + Tv.q(app.component) : Tv.q(app.pkg));
                String out = tv.shell("am start -a android.intent.action.VIEW -d " + Tv.q(url) + target + " 2>&1");
                Thread.sleep(3000);
                result = out.contains("Error") ? "couldn't open it: " + Net.shorten(out, 120) : "opened the link";
                break;
            }
            case "select_item": {
                List<String> seen = new ArrayList<>();
                String hit = tv.pickOnScreen(a.optString("title"), 2, null, seen);
                if (hit == null) return err("couldn't find \"" + a.optString("title") + "\" on screen. Visible: " + join(seen, 15));
                Thread.sleep(1500);
                result = "selected \"" + hit + "\"";
                break;
            }
            case "tap": {
                int[] size = tv.displaySize();
                int x = px(a.optInt("x", 500), size[0]), y = px(a.optInt("y", 500), size[1]);
                tv.tap(x, y);
                Thread.sleep(1300);
                result = "tapped";
                break;
            }
            case "type_text": {
                String t = a.optString("text");
                tv.typeText(t);
                if (a.optBoolean("submit", true)) { Thread.sleep(250); tv.shell("input keyevent 66"); }
                Thread.sleep(1500);
                result = "typed \"" + t + "\"";
                break;
            }
            case "scroll": {
                int[] size = tv.displaySize();
                int w = size[0], h = size[1];
                String d = a.optString("direction").toLowerCase(Locale.US);
                if (d.equals("up")) tv.swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10);
                else if (d.equals("left")) tv.swipe(w * 3 / 10, h / 2, w * 7 / 10, h / 2);
                else if (d.equals("right")) tv.swipe(w * 7 / 10, h / 2, w * 3 / 10, h / 2);
                else tv.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10);
                Thread.sleep(900);
                result = "scrolled " + d;
                break;
            }
            case "get_screen": {
                List<String> labels = new ArrayList<>();
                try { for (Tv.Item it : tv.readScreen()) labels.add(it.text); } catch (Exception e) { labels.add("(couldn't read the text: " + Net.explain(e) + ")"); }
                return new JSONObject().put("app_in_front", front()).put("visible_text", join(labels, 30))
                        .put("note", "A fresh screenshot was just sent as video.");
            }
            default:
                return err("unknown function " + name);
        }
        return new JSONObject().put("result", result).put("app_in_front", front()).put("note", "A fresh screenshot was just sent as video.");
    }

    private String front() {
        String f = tv.foreground();
        if (f == null) return "unknown";
        Tv.App a = byPkg.get(f);
        return a != null ? a.label + " (" + f + ")" : f;
    }

    private Tv.App find(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String n = name.trim();
        Tv.App a = byPkg.get(n);
        if (a != null) return a;
        for (Tv.App x : apps) if (x.label.equalsIgnoreCase(n)) return x;
        String low = n.toLowerCase(Locale.US);
        for (Tv.App x : apps) for (String alias : x.aliases) if (alias.equals(low)) return x;
        Tv.App best = Brain.findApp(n, apps);
        if (best != null) return best;
        for (Tv.App x : apps) if (x.label.toLowerCase(Locale.US).contains(low)) return x;
        return null;
    }

    private String names() {
        List<String> l = new ArrayList<>();
        for (Tv.App a : apps) l.add(a.label);
        return join(l, 40);
    }

    private static String join(List<String> l, int max) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String s : l) {
            if (n++ >= max) break;
            if (b.length() > 0) b.append(", ");
            b.append(s.length() > 40 ? s.substring(0, 40) : s);
        }
        return b.length() == 0 ? "nothing readable" : b.toString();
    }

    private static int px(int v, int size) {
        return Math.max(1, Math.min(size - 1, (int) (Math.max(0, Math.min(1000, v)) / 1000.0 * size)));
    }

    private static JSONObject err(String m) throws Exception { return new JSONObject().put("error", m); }
}
