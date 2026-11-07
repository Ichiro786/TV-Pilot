package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

/** What Gemini Live is told (instructions and the TV functions). Pure Java, so the real-API test uses exactly these. */
final class LiveDecl {
    private LiveDecl() {}

    static final String[] KEYS = {"UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME", "PLAY_PAUSE", "NEXT", "PREVIOUS",
            "FAST_FORWARD", "REWIND", "VOLUME_UP", "VOLUME_DOWN", "MUTE"};

    static JSONArray declarations() throws Exception {
        JSONArray keys = new JSONArray();
        for (String k : KEYS) keys.put(k);
        return new JSONArray()
                .put(fn("run_tv_task", "PREFERRED for anything that takes more than one step on the TV (open an app and play or find "
                        + "something, pick from Continue Watching, change a setting...). Hands the request to TV Pilot's own agent (a fast "
                        + "decision model first, screen-reading vision only when needed), which does all the steps and returns the outcome. "
                        + "Pass ONE clean English command with names spelled correctly as the user meant them (fix speech-recognition "
                        + "mistakes from context, e.g. \"dhrubh rathee\" -> \"Dhruv Rathee\"), naming the app ONLY if the user named one (never add an app yourself: TV Pilot finds where the title is and asks if needed), e.g. "
                        + "\"play Dhruv Rathee's latest video on YouTube\". Speak the returned outcome to the user.",
                        props().put("command", str("The cleaned-up command")), "command"))
                .put(fn("press_key", "Press a remote button: D-pad (UP/DOWN/LEFT/RIGHT), OK to select, BACK, HOME, media controls "
                        + "(PLAY_PAUSE, NEXT, PREVIOUS, FAST_FORWARD, REWIND) and volume (VOLUME_UP, VOLUME_DOWN, MUTE). "
                        + "Use it to move the focus highlight and to control playback or volume.",
                        props().put("key", new JSONObject().put("type", "STRING").put("enum", keys).put("description", "The button"))
                                .put("times", new JSONObject().put("type", "INTEGER").put("description", "How many presses, 1-10 (volume: 3 is a normal step)")),
                        "key"))
                .put(fn("open_app", "Open an installed app by its name or package (from the APPS list).",
                        props().put("app", str("App name or package")), "app"))
                .put(fn("search_app", "Search inside an app for a show, movie, video, song or channel. Uses the app's direct search link "
                        + "when it has one, otherwise opens the app and types into its search box.",
                        props().put("app", str("App name or package")).put("query", str("What to search for")), "app", "query"))
                .put(fn("open_link", "Open a deep link or web URL, optionally in a specific app.",
                        props().put("url", str("The URL or deep link")).put("app", str("Optional app name or package")), "url"))
                .put(fn("select_item", "Find a tile, row item or button on the current screen by its visible title and select it.",
                        props().put("title", str("The visible title of the item")), "title"))
                .put(fn("tap", "Tap a point on the screen, on a 0-1000 grid over the latest screenshot (0,0 = top left). Prefer select_item or the D-pad on TV apps.",
                        props().put("x", integer("0-1000 from the left")).put("y", integer("0-1000 from the top")), "x", "y"))
                .put(fn("type_text", "Type text into the focused text box.",
                        props().put("text", str("The text")).put("submit", new JSONObject().put("type", "BOOLEAN").put("description", "Press Enter afterwards (default true)")), "text"))
                .put(fn("scroll", "Scroll the content up, down, left or right.",
                        props().put("direction", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("up").put("down").put("left").put("right"))), "direction"))
                .put(fn("get_screen", "Look at the TV again: sends a fresh screenshot and returns the app in front and the text visible on screen.", props()))
                .put(fn("finish", "Call when the request is complete (or impossible) and you have said your final short reply. Ends the session.",
                        props().put("summary", str("One short sentence: what happened")), "summary"));
    }

    private static JSONObject fn(String name, String desc, JSONObject props, String... required) throws Exception {
        JSONObject params = new JSONObject().put("type", "OBJECT").put("properties", props);
        if (required.length > 0) {
            JSONArray r = new JSONArray();
            for (String s : required) r.put(s);
            params.put("required", r);
        }
        // BLOCKING: the model waits for each result before going on, so actions on the TV happen in order.
        return new JSONObject().put("name", name).put("description", desc).put("parameters", params).put("behavior", "BLOCKING");
    }

    private static JSONObject props() { return new JSONObject(); }
    private static JSONObject str(String d) throws Exception { return new JSONObject().put("type", "STRING").put("description", d); }
    private static JSONObject integer(String d) throws Exception { return new JSONObject().put("type", "INTEGER").put("description", d); }

    static String instructions(String rules, String past, String unavailable, String appList) { return instructions(rules, past, unavailable, appList, ""); }

    static String instructions(String rules, String past, String unavailable, String appList, String rulebook) {
        rules = rules == null ? "" : rules.trim();
        if (past == null) past = "";
        return (rules.isEmpty() ? "" : "THE USER'S OWN RULES (they override everything below):\n" + rules + "\n\n")
                + "You are TV Pilot, a friendly voice assistant built into this Android TV, like Google Assistant. The user is in India "
                + "and speaks English, Hindi or Hinglish: reply in the same language and style they use, briefly and warmly, in plain spoken "
                + "sentences (your words are spoken aloud).\n"
                + "You can see the TV: a screenshot arrives as video when the session starts and after every action you take.\n"
                + "To do something on the TV, call the functions. Before acting, say a very short acknowledgement (3-8 words) like "
                + "\"Sure, opening Netflix.\" For any multi-step task (open an app and play or find something, pick from a list, settings) "
                + "call run_tv_task with ONE cleaned-up command: fix speech-recognition mistakes from context so names are spelled right "
                + "(\"dhrubh rathee\" means \"Dhruv Rathee\"), and name the app if the user did. It does all the steps and returns the outcome; "
                + "say that outcome in one short sentence. Use the direct tools (press_key, open_app, search_app, select_item, tap...) only for a "
                + "simple one-shot action (volume, pause, back, home, one key) or when run_tv_task fails or reports it's unavailable, "
                + "checking the new screenshot after each. Prefer search_app or open_app over clicking through menus. On TV apps, move the focus with the D-pad and press OK; "
                + "check which item is highlighted before pressing OK. Never open web browsers, ads or donate links.\n"
                + "When the task is done, say one short sentence about it and call finish. For questions and small talk just answer in "
                + "at most three short sentences, then call finish. If something is impossible, say why in one sentence and call finish.\n"
                + "Don't claim something is done unless the screenshot shows it.\n"
                + (unavailable != null ? "IMPORTANT: the TV can't be controlled right now (" + unavailable + "). If the user asks for a TV action, tell them that.\n" : "")
                + "HOW run_tv_task WORKS: it finds the title through the app's own links (or the app's search), reads the screen, moves the focus with the D-pad and "
                + "VERIFIES each step (right title page, video actually playing). Its result has success, outcome, screen, the last steps, and sometimes needs_user "
                + "{question, options}: then ask the user exactly that (briefly) and call run_tv_task again with one complete command including their answer. "
                + "Before calling it, ask only when the rulebook below says a choice is needed and the user didn't give it (e.g. several apps have the title, which profile). "
                + "Season/episode: put them in the command (\"play Stranger Things season 2 episode 3 on Netflix\"); if the user didn't say, don't ask - it resumes or starts the show.\n"
                + "Never say something is playing unless the result's success is true.\n"
                + (rulebook == null || rulebook.isEmpty() ? "" : "RULEBOOK (how the installed apps work):" + rulebook + "\n")
                + "APPS installed on this TV:" + appList
                + (past.isEmpty() ? "" : "\n\nRecent conversation:\n" + past);
    }

}
