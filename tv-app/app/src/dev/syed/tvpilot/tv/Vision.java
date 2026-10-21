package dev.syed.tvpilot.tv;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The phone app's vision agent: looks at the TV, decides one step, does it, looks again. Each turn sends only the
 * newest screenshot plus a short log, so every call stays small. Uses Gemini through its OpenAI-style endpoint.
 */
final class Vision {
    static final String URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";

    interface Steps { void step(String s); }

    static final class Result {
        final String say;
        final boolean success;
        Result(String say, boolean success) { this.say = say; this.success = success; }
    }

    static final class ApiException extends Exception { ApiException(String m) { super(m); } }

    private static volatile String workingEffort;
    private final Tv tv;
    private final List<Tv.App> apps;
    private final Map<String, Tv.App> byPkg;
    private final String key, model, rules;

    Vision(Tv tv, List<Tv.App> apps, String key, String model, String rules) {
        this.tv = tv; this.apps = apps; this.byPkg = Tv.index(apps); this.key = key; this.model = model; this.rules = rules == null ? "" : rules.trim();
    }

    private String system() {
        StringBuilder keys = new StringBuilder();
        for (Tv.Key k : Tv.Key.values()) if (k != Tv.Key.SLEEP && k != Tv.Key.WAKE) keys.append(keys.length() == 0 ? "" : ", ").append(k.name());
        StringBuilder appList = new StringBuilder();
        for (Tv.App a : apps) appList.append("\n").append(a.label).append(" = ").append(a.pkg).append(a.searchUrl != null ? " [search]" : "");
        return (rules.isEmpty() ? "" : "THE USER'S OWN RULES. These come first and override everything below whenever they apply:\n" + rules + "\n\n")
                + "You operate a Google TV for the user, like a person holding the remote and looking at the screen.\n"
                + "Every turn you get the TASK, a log of what you already did, and a fresh SCREENSHOT of the TV.\n"
                + "Reply with ONLY one JSON object, no markdown:\n"
                + "{\"thought\":\"what you see and why this step, max 25 words\",\"action\":{...},\"say\":\"short friendly reply, only with done/fail\"}\n\n"
                + "Actions (exactly one per turn):\n"
                + "  {\"type\":\"open_app\",\"package\":\"<package from APPS>\"}\n"
                + "  {\"type\":\"search\",\"package\":\"<package>\",\"query\":\"words\"}   opens the app's own search results directly (only apps marked [search])\n"
                + "  {\"type\":\"tap\",\"x\":0-1000,\"y\":0-1000}   tap a point on a 0-1000 grid over the screenshot (0,0 top-left)\n"
                + "  {\"type\":\"key\",\"key\":\"<KEY>\",\"times\":1}   remote button: " + keys + "\n"
                + "  {\"type\":\"swipe\",\"direction\":\"up|down|left|right\"}   scroll the content (down = see what's below)\n"
                + "  {\"type\":\"text\",\"text\":\"words\",\"submit\":true}   type into the focused text box\n"
                + "  {\"type\":\"wait\",\"ms\":1500}   the screen is loading (up to 6000 ms)\n"
                + "  {\"type\":\"done\"}   the task is finished (video playing, or the asked state is reached)\n"
                + "  {\"type\":\"fail\"}   impossible; explain in \"say\"\n\n"
                + "How to work:\n"
                + "- Look carefully before acting. Tap the CENTER of the thing you want.\n"
                + "- TV apps follow a focus highlight. If a tap did not change the screen, switch to the D-pad: move focus with UP/DOWN/LEFT/RIGHT until the right item is highlighted, then OK.\n"
                + "- FOCUS FIRST: before pressing OK, name the item that has the focus highlight RIGHT NOW in \"thought\". If it is not your target, move focus and look again.\n"
                + "- Links, ads, \"support/donate\" cards and anything that opens a browser are never what the user wants. If a chooser, browser or web page appears, press BACK once and move focus away.\n"
                + "- Each log line ends with what happened after it (\"screen did NOT change\", \"now in app X\"). Trust it: an action that changed nothing did nothing.\n"
                + "- Never repeat the same failing action more than twice; try another way.\n"
                + "- Only say \"done\" when the screenshot proves it. After opening a video or episode, if a details page shows Play/Resume/Watch, press it first.\n"
                + "- \"Latest\"/\"newest\" video of a channel: search the creator, open their CHANNEL, open the \"Videos\" tab and play the FIRST tile.\n"
                + "- Picking from Continue Watching / My List: find the row on the app's home screen; scroll if needed.\n"
                + "- YouTube and TizenTube are the same YouTube TV interface. When the user says YouTube, use the app marked as YouTube in APPS.\n"
                + "- Keep \"say\" under 15 words and in the user's language. Never invent what you can't see.\n\n"
                + "APPS (label = package, [search] = supports the search action):" + appList;
    }

    Result run(String task, Tv.App onScreen, Steps steps, int maxSteps) throws Exception {
        List<String> log = new ArrayList<>();
        String system = system(), lastAction = "", prevFront = null;
        int repeats = 0;
        int[] prevSig = null;
        int unreadable = 0;
        long started = System.currentTimeMillis();
        for (int step = 1; step <= maxSteps; step++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (System.currentTimeMillis() - started > 150_000) return new Result("That took too long, so I stopped. Try saying it more simply.", false);
            steps.step("Looking at the screen (step " + step + ")");
            Tv.Shot shot = tv.screenshot(1024);
            Log.w("vision step " + step + ": screenshot " + shot.jpeg.length / 1024 + " KB");
            String front = tv.foreground();
            if (!log.isEmpty() && prevSig != null) {
                String outcome;
                if (front != null && prevFront != null && !front.equals(prevFront))
                    outcome = isChooser(front) ? "a popup/browser opened (" + front + "), that was WRONG" : "now in " + label(front);
                else if (Tv.diff(prevSig, shot.sig) < 0.5) outcome = "screen did NOT change (check where the focus is)";
                else outcome = "screen changed";
                log.set(log.size() - 1, log.get(log.size() - 1) + " -> " + outcome);
            }
            prevSig = shot.sig;
            prevFront = front;
            StringBuilder user = new StringBuilder();
            user.append("TASK: ").append(task).append("\n");
            user.append("App in front now: ").append(front != null ? label(front) + " (" + front + ")" : onScreen != null ? onScreen.label : "unknown").append("\n");
            user.append("Step ").append(step).append(" of ").append(maxSteps).append(".\n");
            if (log.isEmpty()) user.append("Nothing done yet.\n");
            else {
                user.append("Done so far:\n");
                for (int i = Math.max(0, log.size() - 12); i < log.size(); i++) user.append("- ").append(log.get(i)).append("\n");
            }
            if (!rules.isEmpty()) user.append("REMEMBER THE USER'S RULES:\n").append(rules).append("\n");
            if (repeats >= 2) user.append("WARNING: you repeated the same action and it isn't working. Do something different.\n");
            user.append("SCREENSHOT attached. Your JSON:");

            steps.step("Asking Gemini (step " + step + ")");
            JSONObject reply = ask(system, user.toString(), shot.jpeg);
            JSONObject action = reply.optJSONObject("action");
            if (action == null) {
                // Not a usable answer (cut off, or not JSON). Three in a row means Gemini isn't working for this.
                if (++unreadable >= 3) throw new ApiException("Gemini's replies couldn't be read: " + reply.optString("raw").replace('\n', ' '));
                Log.w("vision step " + step + ": unreadable reply: " + reply.optString("raw").replace('\n', ' '));
                steps.step("Gemini's answer was cut off, trying again");
                continue;
            }
            unreadable = 0;
            Log.w("vision step " + step + ": " + action);
            String type = action.optString("type").toLowerCase(Locale.US);
            String thought = reply.optString("thought");
            if (thought.length() > 160) thought = thought.substring(0, 160);
            String say = reply.optString("say");
            repeats = action.toString().equals(lastAction) ? repeats + 1 : 0;
            lastAction = action.toString();
            String nice = thought.contains(".") ? thought.substring(0, thought.indexOf('.')) : thought;
            if (nice.length() > 60) nice = nice.substring(0, 60);
            if (!nice.isEmpty() && !type.equals("done") && !type.equals("fail") && !type.equals("wait")) steps.step(nice);

            switch (type) {
                case "done": return new Result(say.isEmpty() ? "Done" : say, true);
                case "fail": return new Result(say.isEmpty() ? "I couldn't do that on the TV." : say, false);
                case "open_app": {
                    Tv.App a = byPkg.get(action.optString("package"));
                    if (a == null) for (Tv.App x : apps) if (x.label.equalsIgnoreCase(action.optString("package"))) a = x;
                    if (a == null) log.add("open_app " + action.optString("package") + " FAILED: not an installed package");
                    else {
                        steps.step("Opening " + a.label);
                        tv.launch(a);
                        tv.waitForApp(a.pkg, 8000);
                        log.add("opened " + a.label);
                    }
                    break;
                }
                case "search": {
                    Tv.App a = byPkg.get(action.optString("package"));
                    if (a == null && front != null) a = byPkg.get(front);
                    String query = action.optString("query");
                    if (a == null || a.searchUrl == null || query.isEmpty()) log.add("search FAILED: that app has no direct search; use its search screen instead");
                    else {
                        steps.step("Searching " + a.label + " for " + query);
                        tv.deepSearch(a, query, false);
                        log.add("searched " + a.label + " for \"" + query + "\"");
                        Thread.sleep(a.pkg.equals(front) ? 2500 : 5500);
                    }
                    break;
                }
                case "tap": {
                    int x = px(action.optInt("x", 500), shot.width), y = px(action.optInt("y", 500), shot.height);
                    tv.tap(x, y);
                    log.add("tapped (" + action.optInt("x") + "," + action.optInt("y") + ") - " + thought);
                    Thread.sleep(1300);
                    break;
                }
                case "key": {
                    Tv.Key k = Tv.Key.named(action.optString("key"));
                    if (k == null || k == Tv.Key.SLEEP) log.add("key " + action.optString("key") + " FAILED: unknown key");
                    else {
                        int n = Math.max(1, Math.min(10, action.optInt("times", 1)));
                        tv.key(k, n);
                        log.add("pressed " + k.name() + " x" + n + " - " + thought);
                        Thread.sleep(k == Tv.Key.OK ? 1300 : 600);
                    }
                    break;
                }
                case "swipe": {
                    int w = shot.width, h = shot.height;
                    String d = action.optString("direction").toLowerCase(Locale.US);
                    if (d.equals("up")) tv.swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10);
                    else if (d.equals("left")) tv.swipe(w * 3 / 10, h / 2, w * 7 / 10, h / 2);
                    else if (d.equals("right")) tv.swipe(w * 7 / 10, h / 2, w * 3 / 10, h / 2);
                    else tv.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10);
                    log.add("swiped " + d);
                    Thread.sleep(900);
                    break;
                }
                case "text": {
                    String t = action.optString("text");
                    tv.typeText(t);
                    if (action.optBoolean("submit", true)) { Thread.sleep(250); tv.shell("input keyevent 66"); }
                    log.add("typed \"" + t + "\"");
                    Thread.sleep(2000);
                    break;
                }
                default:
                    steps.step("Waiting for the screen to load");
                    log.add("waited");
                    Thread.sleep(Math.max(300, Math.min(6000, action.optLong("ms", 1500))));
            }
        }
        return new Result("I ran out of steps. Here's where I got to.", false);
    }

    private String label(String pkg) {
        Tv.App a = byPkg.get(pkg);
        return a != null ? a.label : pkg;
    }

    private static int px(int v, int size) {
        return Math.max(1, Math.min(size - 1, (int) (Math.max(0, Math.min(1000, v)) / 1000.0 * size)));
    }

    private static boolean isChooser(String pkg) {
        if (pkg.equals("android")) return true;
        String p = pkg.toLowerCase(Locale.US);
        for (String s : new String[] {"intentresolver", "resolver", "browser", "chrome", "tvbro", "browsehere", "downloader"}) if (p.contains(s)) return true;
        return false;
    }

    private JSONObject ask(String system, String user, byte[] jpeg) throws Exception {
        String img = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP);
        String[] efforts = {workingEffort != null ? workingEffort : "minimal", "low", null};
        JSONObject res = null;
        String lastErr = "vision model unreachable";
        for (String effort : efforts) {
            JSONObject body = new JSONObject().put("model", model).put("temperature", 0).put("max_tokens", 1500)
                    .put("response_format", new JSONObject().put("type", "json_object"))
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", system))
                            .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                                    .put(new JSONObject().put("type", "text").put("text", user))
                                    .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", img))))));
            if (effort != null) body.put("reasoning_effort", effort);
            else body.remove("response_format");   // last try: the plainest request
            HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            try {
                try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                String text = Server.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code == 200) { res = new JSONObject(text); workingEffort = effort; break; }
                lastErr = "Gemini said " + code + ": " + (text.length() > 120 ? text.substring(0, 120) : text);
                Log.w("vision: " + lastErr);
                if (code != 400) break;
            } catch (java.io.IOException e) {
                lastErr = e.getMessage();
                break;
            }
        }
        if (res == null) throw new ApiException(lastErr);
        JSONArray ch = res.optJSONArray("choices");
        String text = ch == null ? "" : ch.optJSONObject(0).optJSONObject("message").optString("content");
        String raw = text.length() > 160 ? text.substring(0, 160) : text;
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return new JSONObject().put("raw", raw.isEmpty() ? "(empty)" : raw);
        try {
            JSONObject o = new JSONObject(text.substring(s, e + 1));
            if (o.optJSONObject("action") == null && o.has("type")) o = new JSONObject().put("action", o);   // bare action
            if (o.optJSONObject("action") == null) o.put("raw", raw);
            return o;
        } catch (Exception ex) {
            return new JSONObject().put("raw", raw);
        }
    }
}
