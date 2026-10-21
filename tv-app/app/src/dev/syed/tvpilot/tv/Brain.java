package dev.syed.tvpilot.tv;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The same brain as the phone app: instant answers for plain remote commands, and Liquid AI d1-3B on the VM for the
 * rest. d1 is a decision model, so it only picks from options; the words to search come from the command itself.
 */
final class Brain {

    static final class Action {
        final String type;   // open, search, press, type, pick
        final Tv.App app;
        final String text;
        final Tv.Key key;
        final int times;
        final boolean afterOpen;
        Action(String type, Tv.App app, String text, Tv.Key key, int times, boolean afterOpen) {
            this.type = type; this.app = app; this.text = text; this.key = key; this.times = times; this.afterOpen = afterOpen;
        }
        static Action open(Tv.App a) { return new Action("open", a, null, null, 1, false); }
        static Action search(Tv.App a, String q) { return new Action("search", a, q, null, 1, false); }
        static Action press(Tv.Key k, int n) { return new Action("press", null, null, k, n, false); }
        static Action pick(String t, boolean afterOpen) { return new Action("pick", null, t, null, 1, afterOpen); }
    }

    static final class Plan {
        final List<Action> actions;
        final String say;
        final Double confidence;
        /** The decider found nothing for the TV to do: probably a question or small talk. */
        boolean other;
        Plan markOther() { other = true; return this; }
        Plan(List<Action> actions, String say, Double confidence) { this.actions = actions; this.say = say; this.confidence = confidence; }
        static Plan of(String say, Action... a) { return new Plan(Arrays.asList(a), say, null); }
        static Plan none(String say, Double conf) { return new Plan(Collections.<Action>emptyList(), say, conf); }
    }

    // MARK: - Plain remote commands, no model needed

    static Plan quick(String command) {
        String c = command.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (c.startsWith("please ")) c = c.substring(7);
        if (c.endsWith(" please")) c = c.substring(0, c.length() - 7);
        if (c.startsWith("hey tv ")) c = c.substring(7);
        c = c.trim();
        Integer n = times(c);
        if (c.matches("(volume up|louder|increase (the )?volume|turn (it )?up)( \\w+)*")) return p(Tv.Key.VOLUME_UP, n == null ? 3 : n);
        if (c.matches("(volume down|quieter|lower (the )?volume|decrease (the )?volume|turn (it )?down)( \\w+)*")) return p(Tv.Key.VOLUME_DOWN, n == null ? 3 : n);
        if (in(c, "mute", "unmute", "mute it", "mute the tv", "silence")) return p(Tv.Key.MUTE, 1);
        if (in(c, "pause", "play", "resume", "stop", "pause it", "play it", "resume it", "pause the video", "play pause")) return p(Tv.Key.PLAY_PAUSE, 1);
        if (in(c, "next", "next episode", "skip", "next video", "next song")) return p(Tv.Key.NEXT, 1);
        if (in(c, "previous", "previous episode", "previous video", "go back a video")) return p(Tv.Key.PREVIOUS, 1);
        if (c.matches("(fast forward|forward|skip ahead)( \\w+)*")) return p(Tv.Key.FAST_FORWARD, n == null ? 1 : n);
        if (c.matches("(rewind|go back a bit)( \\w+)*")) return p(Tv.Key.REWIND, n == null ? 1 : n);
        if (in(c, "back", "go back")) return p(Tv.Key.BACK, 1);
        if (in(c, "home", "go home", "home screen")) return p(Tv.Key.HOME, 1);
        if (in(c, "ok", "select", "enter", "click")) return p(Tv.Key.OK, 1);
        if (c.matches("(go |move )?(up|down|left|right)( \\w+)*")) {
            Matcher m = Pattern.compile("up|down|left|right").matcher(c);
            m.find();
            Tv.Key k = m.group().equals("up") ? Tv.Key.UP : m.group().equals("down") ? Tv.Key.DOWN : m.group().equals("left") ? Tv.Key.LEFT : Tv.Key.RIGHT;
            return p(k, n == null ? 1 : n);
        }
        if (in(c, "turn off", "turn off the tv", "tv off", "power off", "sleep", "switch off")) return p(Tv.Key.SLEEP, 1);
        return null;
    }

    private static Plan p(Tv.Key k, int n) { return Plan.of(k.label, Action.press(k, n)); }

    private static boolean in(String c, String... options) {
        for (String o : options) if (c.equals(o)) return true;
        return false;
    }

    // MARK: - Pulling the useful words out of a command

    private static final List<String> LIST_PHRASES = Arrays.asList(
            "from my continue watching list", "from my continue watching", "from continue watching", "in continue watching",
            "on continue watching", "continue watching list", "continue watching", "from my watchlist", "from my watch list",
            "from my list", "in my list", "my list", "watchlist", "recently watched", "where i left off", "resume");

    private static final List<String> FILLER = Arrays.asList(
            "hey tv", "ok tv", "hey", "please", "can you", "could you", "would you", "i want to", "i wanna",
            "i'd like to", "let's", "lets", "for me", "on the tv", "on tv",
            "play", "watch", "put on", "search for", "search", "find", "show me", "look up",
            "stream", "start", "open", "launch", "some", "the app", "app", "application", "now");

    static boolean mentionsList(String command) {
        String c = command.toLowerCase(Locale.US);
        for (String p : LIST_PHRASES) if (c.contains(p)) return true;
        return false;
    }

    static Tv.App findApp(String command, List<Tv.App> apps) {
        String c = command.toLowerCase(Locale.US);
        Tv.App best = null;
        int len = 0;
        for (Tv.App a : apps) for (String alias : a.aliases) {
            if (alias.length() > len && Pattern.compile("\\b" + Pattern.quote(alias) + "\\b").matcher(c).find()) { best = a; len = alias.length(); }
        }
        return best;
    }

    static String query(String command, List<Tv.App> apps) {
        String c = " " + command.toLowerCase(Locale.US) + " ";
        List<String> aliases = new ArrayList<>();
        for (Tv.App a : apps) aliases.addAll(a.aliases);
        Collections.sort(aliases, (x, y) -> y.length() - x.length());
        for (String alias : aliases) {
            c = c.replaceAll("\\s(on|in|from|using|with)\\s+" + Pattern.quote(alias) + "\\b", " ");
            c = c.replaceAll("\\b" + Pattern.quote(alias) + "\\b", " ");
        }
        List<String> words = new ArrayList<>(LIST_PHRASES);
        words.addAll(FILLER);
        Collections.sort(words, (x, y) -> y.length() - x.length());
        for (String f : words) c = c.replaceAll("\\b" + Pattern.quote(f) + "\\b", " ");
        c = c.replaceAll("[?!.,]", " ").replaceAll("\\s+", " ").trim();
        for (int i = 0; i < 3; i++) c = c.replaceAll("^(and|then|the|from|in|on|to)\\s+", "").replaceAll("\\s+(and|then|from|in|on)$", "").trim();
        return c;
    }

    private static final Map<String, Integer> NUMBERS = new LinkedHashMap<>();
    static {
        String[] w = {"once", "1", "twice", "2", "two", "2", "three", "3", "four", "4", "five", "5", "six", "6", "seven", "7",
                "eight", "8", "nine", "9", "ten", "10", "a lot", "6", "a bit", "2", "little", "2"};
        for (int i = 0; i < w.length; i += 2) NUMBERS.put(w[i], Integer.parseInt(w[i + 1]));
    }

    static Integer times(String command) {
        String c = command.toLowerCase(Locale.US);
        Matcher m = Pattern.compile("(\\d{1,2})").matcher(c);
        if (m.find()) return Integer.parseInt(m.group(1));
        for (Map.Entry<String, Integer> e : NUMBERS.entrySet()) if (c.contains(e.getKey())) return e.getValue();
        return null;
    }

    // MARK: - d1

    static Plan plan(String command, List<Tv.App> apps, Tv.App onScreen) throws Exception {
        Plan q = quick(command);
        if (q != null) return q;

        Map<String, Tv.App> ids = new LinkedHashMap<>();
        for (int i = 0; i < apps.size(); i++) ids.put("app" + i, apps.get(i));
        JSONObject all = new JSONObject()
                .put("intent", choice("What does the person want the TV to do?", map(
                        "watch", "Watch, play or find specific content: a show, movie, anime, video, song, channel or topic",
                        "pick_on_screen", "Choose something already on a list or row, such as continue watching, my list, recently watched, or the item on screen",
                        "open_app", "Open an app, without naming anything to watch",
                        "playback", "Control what is playing: pause, play, resume, stop, skip, next, previous, rewind, fast forward",
                        "navigate", "Move around the screen: up, down, left, right, select, back, home",
                        "volume", "Change the sound: louder, quieter, mute, unmute",
                        "power", "Turn the TV off or on, or put it to sleep",
                        "other", "Anything else")))
                .put("playback", choice("Which playback control is meant?", map(
                        "play_pause", "Pause, play, resume or stop", "next", "Next video, song or episode, or skip",
                        "previous", "Previous video, song or episode", "fast_forward", "Skip ahead within what is playing",
                        "rewind", "Go back a bit within what is playing")))
                .put("navigate", choice("Which way or button?", map("up", "Up", "down", "Down", "left", "Left", "right", "Right",
                        "ok", "Select, open, click or enter", "back", "Go back", "home", "Go to the home screen")))
                .put("volume", choice("Which volume change?", map("up", "Louder", "down", "Quieter", "mute", "Mute or unmute")))
                .put("power", choice("Off or on?", map("off", "Turn off or sleep", "on", "Turn on or wake up")));
        Map<String, String> appChoices = new LinkedHashMap<>();
        for (Map.Entry<String, Tv.App> e : ids.entrySet())
            appChoices.put(e.getKey(), e.getValue().label + " (" + android.text.TextUtils.join(", ", e.getValue().aliases) + ")");
        appChoices.put("none", "No app is named and none is clearly implied");
        all.put("app", choice("Which app does the command name or clearly imply?", appChoices));

        JSONObject state = new JSONObject().put("command", command).put("app_on_screen", onScreen == null ? JSONObject.NULL : onScreen.label);
        JSONObject answers = ask(all, state, "intent", "app");
        String kindNow = answers.getJSONObject("intent").getString("choice");
        if (Arrays.asList("playback", "navigate", "volume", "power").contains(kindNow))
            answers.put(kindNow, ask(all, state, kindNow).getJSONObject(kindNow));

        JSONObject intent = answers.getJSONObject("intent");
        String kind = intent.getString("choice");
        JSONObject probs = intent.optJSONObject("probabilities");
        double conf = probs != null ? probs.optDouble(kind, 1) : intent.optDouble("confidence", 1);
        Tv.App app = ids.get(answers.getJSONObject("app").getString("choice"));
        if (app == null) app = findApp(command, apps);
        Integer n = times(command);
        if (conf < 0.3) return Plan.none("I'm not sure what you meant. Try \"play lofi on YouTube\".", conf).markOther();

        String eff = mentionsList(command) && Arrays.asList("watch", "open_app", "other").contains(kind) ? "pick_on_screen" : kind;
        switch (eff) {
            case "pick_on_screen": {
                String title = query(command, apps);
                List<Action> acts = new ArrayList<>();
                boolean needsOpen = app != null && app != onScreen;
                if (needsOpen) acts.add(Action.open(app));
                if (!title.isEmpty()) acts.add(Action.pick(title, needsOpen));
                if (title.isEmpty() && app != null) return new Plan(acts, "Opening " + app.label, conf);
                if (title.isEmpty()) return Plan.none("Which title should I pick?", conf);
                return new Plan(acts, "Looking for " + title + (app != null ? " in " + app.label : " on screen"), conf);
            }
            case "watch": {
                String query = query(command, apps);
                Tv.App target = app != null ? app : onScreen;
                if (!query.isEmpty() && target == null) return Plan.none("Which app should I play " + query + " in?", conf);
                if (!query.isEmpty()) return new Plan(Collections.singletonList(Action.search(target, query)), "Finding " + query + " in " + target.label, conf);
                if (app != null) return new Plan(Collections.singletonList(Action.open(app)), "Opening " + app.label, conf);
                return Plan.none("What should I play?", conf);
            }
            case "open_app":
                return app != null ? new Plan(Collections.singletonList(Action.open(app)), "Opening " + app.label, conf)
                        : Plan.none("I couldn't find that app on your TV.", conf);
            case "playback": {
                String ch = answers.getJSONObject("playback").getString("choice");
                Tv.Key k = ch.equals("next") ? Tv.Key.NEXT : ch.equals("previous") ? Tv.Key.PREVIOUS : ch.equals("fast_forward") ? Tv.Key.FAST_FORWARD
                        : ch.equals("rewind") ? Tv.Key.REWIND : Tv.Key.PLAY_PAUSE;
                return new Plan(Collections.singletonList(Action.press(k, n == null ? 1 : n)), k.label, conf);
            }
            case "navigate": {
                String ch = answers.getJSONObject("navigate").getString("choice");
                Tv.Key k = Tv.Key.named(ch.equals("ok") ? "OK" : ch);
                if (k == null) k = Tv.Key.OK;
                return new Plan(Collections.singletonList(Action.press(k, n == null ? 1 : n)), k.label, conf);
            }
            case "volume": {
                String ch = answers.getJSONObject("volume").getString("choice");
                Tv.Key k = ch.equals("down") ? Tv.Key.VOLUME_DOWN : ch.equals("mute") ? Tv.Key.MUTE : Tv.Key.VOLUME_UP;
                return new Plan(Collections.singletonList(Action.press(k, k == Tv.Key.MUTE ? 1 : n == null ? 3 : n)), k.label, conf);
            }
            case "power": {
                boolean on = answers.getJSONObject("power").getString("choice").equals("on");
                if (on) return Plan.none("The TV's already on.", conf);
                return new Plan(Collections.singletonList(Action.press(Tv.Key.SLEEP, 1)), "Turning the TV off", conf);
            }
            default:
                return Plan.none("That's not something I can do on the TV yet.", conf).markOther();
        }
    }

    private static JSONObject ask(JSONObject all, JSONObject state, String... names) throws Exception {
        JSONObject qs = new JSONObject();
        for (String n : names) qs.put(n, all.getJSONObject(n));
        JSONObject body = new JSONObject().put("state", state).put("questions", qs);
        String lk = Prefs.liquidKey();
        if ("liquid".equals(Prefs.decider()) && !lk.isEmpty() && System.currentTimeMillis() > liquidDownUntil) {
            try {
                JSONObject lb = new JSONObject(body.toString()).put("model", Prefs.liquidModel());
                JSONObject a = postWith(LIQUID, lk, lb, 15000).getJSONObject("answers");
                lastDecider = Prefs.liquidModel();
                return a;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                liquidDownUntil = System.currentTimeMillis() + 5 * 60_000;   // credit out or API down: use the VM for a while
                Log.w("Liquid " + Prefs.liquidModel() + " failed, your d1 takes over: " + e.getMessage());
            }
        }
        JSONObject res = post(Server.url(8080) + "/v1/systemone", body, 60000);
        lastDecider = "d1 on the VM";
        return res.getJSONObject("answers");
    }

    static final String LIQUID = "https://api.liquid.ai/decisions/v1/systemone";
    private static volatile long liquidDownUntil;
    static volatile String lastDecider = "d1";

    static String deciderName() {
        return "liquid".equals(Prefs.decider()) && !Prefs.liquidKey().isEmpty() ? Prefs.liquidModel() : "d1";
    }

    private static JSONObject postWith(String url, String key, JSONObject body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(timeout);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String text = Server.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code != 200) throw new IllegalStateException("said " + code + ": " + (text.length() > 100 ? text.substring(0, 100) : text));
        return new JSONObject(text);
    }

    static JSONObject post(String url, JSONObject body, int timeout) throws Exception {
        HttpURLConnection c = Server.open(url, "POST", timeout);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String text = Server.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code != 200) throw new IllegalStateException("server said " + code + (code == 502 || code == 503 ? " (the VM may be waking up, try again)" : ""));
        return new JSONObject(text);
    }

    private static JSONObject choice(String instructions, Map<String, String> criteria) throws Exception {
        return new JSONObject().put("type", "choice").put("instructions", instructions).put("criteria", new JSONObject(criteria));
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
