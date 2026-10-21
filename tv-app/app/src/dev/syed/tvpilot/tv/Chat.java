package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/** The talking part: Gemini decides whether you asked the TV to do something or just asked a question,
 *  and writes what the assistant says back (spoken by our own voice server). */
final class Chat {
    private Chat() {}

    static final class Route {
        final boolean tv;     // true: do it on the TV; false: just answer
        final String say;     // what to say now (a short "Sure, opening Netflix" or the full answer)
        Route(boolean tv, String say) { this.tv = tv; this.say = say; }
    }

    private static final Deque<String[]> history = new ArrayDeque<>();   // {you, assistant}, newest last

    static synchronized void remember(String you, String me) {
        if (you == null || me == null || me.isEmpty()) return;
        history.addLast(new String[] {you, me});
        while (history.size() > 6) history.removeFirst();
    }

    private static synchronized JSONArray past() throws Exception {
        JSONArray a = new JSONArray();
        for (String[] h : history) {
            a.put(new JSONObject().put("role", "user").put("content", h[0]));
            a.put(new JSONObject().put("role", "assistant").put("content", h[1]));
        }
        return a;
    }

    private static final String PERSONA = "You are TV Pilot, a friendly voice assistant on this Android TV, like Google Assistant. "
            + "Everything you write is spoken aloud by a text-to-speech voice, so write plain spoken sentences: "
            + "no emoji, no markdown, no lists, no URLs. Be warm, casual and brief.";

    /** Decides what kind of request this is and what to say first. Null when Gemini isn't reachable. */
    static Route route(String command, String onScreen) {
        try {
            String system = PERSONA + "\n\nDecide what the user wants. Reply with JSON only: {\"mode\":\"tv\"|\"chat\",\"say\":\"...\"}.\n"
                    + "mode tv: anything the TV should do (open or play something, search an app, change volume, settings, go home, pause, "
                    + "turn things on or off). say = a short natural acknowledgement of 3 to 8 words that names the thing, "
                    + "like \"Sure, putting on One Piece.\" or \"Okay, opening YouTube.\" Never claim it is already done.\n"
                    + "mode chat: questions, small talk, facts, recommendations, jokes, anything not needing the TV to act. "
                    + "say = the full spoken answer, at most 3 short sentences. If you recommend something to watch, "
                    + "you may end with an offer like \"Want me to put it on?\"\n"
                    + "If the user says yes to an offer you just made, that is mode tv.\n"
                    + "On screen right now: " + (onScreen == null ? "unknown" : onScreen) + ".";
            JSONObject o = json(call(system, command, 400, true));
            if (o == null) return null;
            String say = o.optString("say").trim();
            boolean tv = !"chat".equals(o.optString("mode"));
            if (!tv && say.isEmpty()) return null;
            return new Route(tv, say);
        } catch (Exception e) {
            Log.w("chat route failed: " + e.getMessage());
            return null;
        }
    }

    /** Turns the agent's outcome into one natural spoken sentence. Falls back to the outcome itself. */
    /** A plain conversational answer, used when routing failed and the decider found nothing for the TV to do. */
    static String answer(String command) {
        try {
            String system = PERSONA + "\n\nAnswer the user in at most 3 short spoken sentences. Reply with JSON only: {\"say\":\"...\"}.";
            JSONObject o = json(call(system, command, 400, true));
            String s = o == null ? "" : o.optString("say").trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            Log.w("chat answer failed: " + e.getMessage());
            return null;
        }
    }

    static String finish(String command, String outcome, boolean ok) {
        try {
            String system = PERSONA + "\n\nThe user asked: \"" + command + "\". The TV agent " + (ok ? "finished" : "could not finish")
                    + " and reported: \"" + outcome + "\". Tell the user in one short spoken sentence (under 15 words) what happened"
                    + (ok ? "." : ", and if it helps, what they could try instead.") + " Do not invent anything the report doesn't say. "
                    + "Reply with JSON only: {\"say\":\"...\"}.";
            JSONObject o = json(call(system, "Write the reply.", 200, false));
            String s = o == null ? "" : o.optString("say").trim();
            return s.isEmpty() ? outcome : s;
        } catch (Exception e) {
            return outcome;
        }
    }

    private static JSONObject json(String text) {
        if (text == null) return null;
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try { return new JSONObject(text.substring(s, e + 1)); } catch (Exception ex) { return null; }
    }

    private static String call(String system, String user, int maxTokens, boolean withHistory) throws Exception {
        String key = Prefs.geminiKey();
        if (key.isEmpty()) return null;
        JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", system));
        if (withHistory) { JSONArray p = past(); for (int i = 0; i < p.length(); i++) msgs.put(p.get(i)); }
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        JSONObject body = new JSONObject().put("model", Prefs.chatModel()).put("temperature", 0.6).put("max_tokens", maxTokens)
                .put("reasoning_effort", "none").put("response_format", new JSONObject().put("type", "json_object")).put("messages", msgs);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(Vision.URL).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            String text = Server.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 200) {
                JSONArray ch = new JSONObject(text).optJSONArray("choices");
                return ch == null ? null : ch.optJSONObject(0).optJSONObject("message").optString("content");
            }
            Log.w("chat: Gemini said " + code + ": " + (text.length() > 120 ? text.substring(0, 120) : text));
            if (code != 400) return null;
            body.remove("reasoning_effort");   // some models don't take it: try once more without
        }
        return null;
    }
}
