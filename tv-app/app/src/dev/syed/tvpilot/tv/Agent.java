package dev.syed.tvpilot.tv;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * One command, done on this TV, the same way the phone app does it: plain remote commands instantly, then the
 * Gemini vision agent when it's chosen and has a key (falling back to d1 if Gemini fails), otherwise d1's plan.
 */
final class Agent {
    static final class Result {
        final String say;
        final boolean success;
        /** Nothing for the TV to do: hand it to the conversation instead. */
        boolean chat;
        Result(String say, boolean success) { this.say = say; this.success = success; }
    }

    private final Context app;
    private final Tv tv;

    Agent(Context c) { app = c.getApplicationContext(); tv = Tv.get(app); }

    Result run(String command, Vision.Steps steps) throws Exception { return run(command, steps, false); }

    /** routedTv: the conversation layer already decided this is a TV action, so never bounce it back as chat. */
    Result run(String command, Vision.Steps steps, boolean routedTv) throws Exception {
        Brain.Plan quick = Brain.quick(command);
        String brain = Prefs.brain();
        String key = Prefs.geminiKey();
        boolean gemini = !key.isEmpty() && !"d1".equals(brain);
        Log.w("agent: \"" + command + "\" via " + (quick != null ? "quick" : "vision".equals(brain) && gemini ? "Gemini vision" : gemini ? "hybrid (" + Brain.deciderName() + " first)" : Brain.deciderName()));
        if (quick != null) return execute(quick, steps);

        steps.step("Connecting to the TV");
        tv.shell("echo ok");
        steps.step("Looking at your apps");
        List<Tv.App> apps = tv.apps();
        Tv.App onScreen = tv.byPkg(tv.foreground());

        if ("vision".equals(brain) && gemini) {
            try {
                return vision(command, onScreen, apps, steps, null);
            } catch (Vision.ApiException e) {
                Log.w("vision failed, d1 takes over: " + e.getMessage());
                steps.step("Gemini failed, asking " + Brain.deciderName());
            }
            gemini = false;
        }

        // The decision model first: fast and cheap for the usual "open X", "play Y on Z", volume, playback.
        steps.step("Deciding (" + Brain.deciderName() + ")");
        Brain.Plan plan = null;
        String hint;
        try {
            plan = Brain.plan(command, apps, onScreen);
            Log.w(Brain.lastDecider + " plan: " + plan.actions.size() + " actions, \"" + plan.say + "\"" + (plan.confidence != null ? " (" + Math.round(plan.confidence * 100) + "%)" : ""));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (!gemini) throw e;
            Log.w("decider failed: " + e.getMessage());
        }
        if (plan != null && plan.other && !routedTv) {
            Result chat = new Result(plan.say, false);
            chat.chat = true;
            return chat;
        }
        if (!gemini) return execute(plan, steps);

        boolean sure = plan != null && !plan.actions.isEmpty() && (plan.confidence == null || plan.confidence >= 0.6);
        if (sure) {
            Result r;
            try {
                r = execute(plan, steps);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                r = new Result(e.getMessage() == null ? e.toString() : e.getMessage(), false);
            }
            if (r.success) return r;
            hint = Brain.lastDecider + " already tried \"" + plan.say + "\" but: " + r.say + ". Carry on from the current screen.";
        } else {
            hint = plan == null ? null : Brain.lastDecider + " wasn't sure what to do (" + plan.say + ").";
        }
        // Gemini only when it's needed: the decider couldn't do it, wasn't sure, or it went wrong.
        Log.w("Gemini takes over" + (hint == null ? "" : ": " + hint));
        steps.step("Gemini is taking over");
        try {
            return vision(command, tv.byPkg(tv.foreground()), apps, steps, hint);
        } catch (Vision.ApiException e) {
            Log.w("vision failed too: " + e.getMessage());
            return new Result(plan != null && !plan.say.isEmpty() ? plan.say : "Gemini isn't answering right now: " + e.getMessage(), false);
        }
    }

    private Result vision(String command, Tv.App onScreen, List<Tv.App> apps, Vision.Steps steps, String hint) throws Exception {
        String rules = Prefs.rules();
        if (hint != null) rules = (rules.isEmpty() ? "" : rules + "\n") + "Note: " + hint;
        Vision.Result r = new Vision(tv, apps, Prefs.geminiKey(), Prefs.visionModel(), rules).run(command, onScreen, steps, 22);
        return new Result(r.say, r.success);
    }

    private Result execute(Brain.Plan plan, Vision.Steps steps) throws Exception {
        if (plan == null) return new Result("I couldn't work that out.", false);
        if (plan.actions.isEmpty()) return new Result(plan.say, false);
        steps.step(plan.say);
        Tv.App onScreen = null;
        for (Brain.Action a : plan.actions) {
            switch (a.type) {
                case "open":
                    if (!a.app.pkg.equals(tv.foreground())) tv.launch(a.app);
                    onScreen = a.app;
                    break;
                case "search":
                    findAndPlay(a.app != null ? a.app : onScreen, a.text, steps);
                    break;
                case "press":
                    tv.key(a.key, a.times);
                    break;
                case "type":
                    tv.typeText(a.text);
                    break;
                case "pick": {
                    if (a.afterOpen && onScreen != null) tv.waitForApp(onScreen.pkg, 8000);
                    List<String> seen = new ArrayList<>();
                    String hit = tv.pickOnScreen(a.text, 3, null, seen);
                    if (hit == null) return new Result(notFound(a.text, seen), false);
                    Thread.sleep(1500);
                    tv.tapLabel("resume", "continue watching", "continue", "play", "watch now");
                    break;
                }
            }
        }
        return new Result(plan.say, true);
    }

    /** Deep link when the app has one; otherwise open it, look on its home rows, then use its own search box. */
    private void findAndPlay(Tv.App a, String query, Vision.Steps steps) throws Exception {
        if (a == null) throw new IllegalStateException("Which app should I play " + query + " in?");
        if (a.searchUrl != null) {
            tv.deepSearch(a, query, Prefs.autoPlay());
            return;
        }
        if (!a.pkg.equals(tv.foreground())) {
            steps.step("Opening " + a.label);
            tv.launch(a);
            tv.waitForApp(a.pkg, 8000);
        }
        List<String> seen = new ArrayList<>();
        steps.step("Looking for " + query);
        String hit = tv.pickOnScreen(query, 2, null, seen);
        if (hit == null) {
            Integer boxY = tv.searchInsideApp(query);
            if (boxY != null) hit = tv.pickOnScreen(query, 3, boxY, seen);
        }
        if (hit == null) throw new IllegalStateException(notFound(query, seen));
        Thread.sleep(1800);
        tv.tapLabel("resume", "continue watching", "continue", "play", "watch now");
    }

    private static String notFound(String title, List<String> seen) {
        if (seen.isEmpty()) return "Couldn't read the screen, so I couldn't find " + title + ".";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : seen) {
            if (n++ >= 6) break;
            sb.append(n > 1 ? ", " : "").append(s.length() > 24 ? s.substring(0, 24) : s);
        }
        return "Couldn't spot " + title + ". I could see: " + sb;
    }
}
