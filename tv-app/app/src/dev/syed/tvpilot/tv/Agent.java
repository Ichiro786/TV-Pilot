package dev.syed.tvpilot.tv;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One command, done on this TV. 1.2.0: plain remote commands instantly; inputs and settings by intent; the decision
 * model names the intent; then the Driver does it closed-loop (deep links first, the UI tree + set-of-marks screenshot
 * for d1-ultra, D-pad focus planning, verification after every step, Gemini for single unsure steps).
 * The 1.1 Gemini-only vision agent is still there for Classic strategy "vision".
 */
final class Agent {
    static final class Result {
        final String say;
        final boolean success;
        /** Nothing for the TV to do: hand it to the conversation instead. */
        boolean chat;
        /** The executor needs a choice from the user (which app, which profile...). */
        boolean needsUser;
        String question;
        List<String> options = new ArrayList<>();
        JSONObject detail;
        Result(String say, boolean success) { this.say = say; this.success = success; }
    }

    private final Context app;
    private final Tv tv;

    Agent(Context c) { app = c.getApplicationContext(); tv = Tv.get(app); }

    Result run(String command, Vision.Steps steps) throws Exception { return run(command, steps, false); }

    static Decider decider() {
        Decider.Config c = new Decider.Config();
        c.liquidKey = Prefs.liquidKey();
        c.model = Prefs.liquidModel();
        if (Prefs.hasVm()) { c.vmUrl = Server.url(8080) + "/v1/systemone"; c.vmKey = Prefs.vmKey(); }
        c.preferVm = ("self".equals(Prefs.decider()) || c.liquidKey.isEmpty()) && c.vmUrl != null;
        return new Decider(c);
    }

    Driver driver(Vision.Steps steps, List<Tv.App> apps) {
        Driver.Config cfg = new Driver.Config();
        cfg.rules = Prefs.rules();
        cfg.profile = Prefs.profile();
        cfg.skipIntro = cfg.rules.toLowerCase(Locale.US).contains("skip intro");
        Tv.App yt = Brain.defaultPlayer(apps);
        cfg.defaultPlayer = yt == null ? null : yt.pkg;
        Gem gem = Prefs.geminiKey().isEmpty() ? null : new Gem(Prefs.geminiKey(), Prefs.visionModel());
        return new Driver(tv, decider(), gem, cfg, (a, s, st) -> steps.step(st));
    }

    private static final Pattern INPUT = Pattern.compile("\\b(hdmi|h d m i)\\s*(\\d)?\\b|\\b(switch|change|go) (to )?(the )?input\\b|\\binput (\\d)\\b");
    private static final Pattern SETTINGS = Pattern.compile("\\b(open |go to |show )?(the )?(wi-?fi|wifi|bluetooth|display|picture|sound|audio|date|time|apps?|accessibility|developer|storage|about|network|inputs?)\\s+settings\\b|\\b(open |go to )(the )?settings\\b");

    /** routedTv: the conversation layer already decided this is a TV action, so never bounce it back as chat. */
    Result run(String command, Vision.Steps steps, boolean routedTv) throws Exception {
        Brain.Plan quick = Brain.quick(command);
        String brain = Prefs.brain();
        boolean gemini = !Prefs.geminiKey().isEmpty() && !"d1".equals(brain);
        Log.w("agent: \"" + command + "\" via " + (quick != null ? "quick" : "vision".equals(brain) && gemini ? "Gemini vision" : "driver (" + Brain.deciderName() + " + screen)"));
        if (quick != null) return execute(quick, steps);

        steps.step("Connecting to the TV");
        tv.shell("echo ok");
        steps.step("Looking at your apps");
        List<Tv.App> apps = tv.apps();
        Tv.App onScreen = tv.byPkg(tv.foreground());
        String low = command.toLowerCase(Locale.US);

        Matcher im = INPUT.matcher(low);
        if (im.find()) {
            String n = im.group(2) != null ? im.group(2) : im.group(6);
            return wrap(driver(steps, apps).input("HDMI " + (n == null ? "1" : n)));
        }
        Matcher sm = SETTINGS.matcher(low);
        if (sm.find()) {
            String page = sm.group(3) == null ? "settings" : sm.group(3).replace("-", "");
            if (page.startsWith("app")) page = "apps";
            if (page.equals("picture")) page = "display";
            if (page.equals("audio")) page = "sound";
            if (page.equals("time")) page = "date";
            if (page.equals("network")) page = "wifi";
            if (page.startsWith("input")) page = "inputs";
            return wrap(driver(steps, apps).settings(page, null));
        }

        if ("vision".equals(brain) && gemini) {
            try {
                return vision(command, onScreen, apps, steps, null);
            } catch (Vision.ApiException e) {
                Log.w("vision failed, the driver takes over: " + e.getMessage());
            }
        }

        steps.step("Deciding (" + Brain.deciderName() + ")");
        Brain.Plan plan = null;
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
        if (plan != null && !plan.actions.isEmpty()) {
            Brain.Action first = plan.actions.get(0), last = plan.actions.get(plan.actions.size() - 1);
            if (last.type.equals("search")) {
                Tv.App named = Brain.findApp(command, apps);
                Tv.App target = last.app;
                Rulebook.App front = onScreen == null ? null : Rulebook.known(onScreen.pkg);
                // Not named: the app in front if it's a streaming app, else let the title decide (JustWatch, then YouTube).
                String pkg = named != null ? named.pkg
                        : target != null && target == onScreen && front != null && !front.j.optBoolean("protected") ? onScreen.pkg : null;
                if (pkg == null && target != null && target != onScreen && !target.aliases.contains("youtube")) pkg = target.pkg;   // d1 recognised a misspelt app name
                Goal g = Goal.of(command, last.text, pkg);
                Log.w("goal: " + g);
                return wrap(driver(steps, apps).play(g));
            }
            if (last.type.equals("pick")) {
                Tv.App a = first.type.equals("open") ? first.app : onScreen;
                Goal g = Goal.of(command, last.text, a == null ? null : a.pkg);
                String row = low.contains("my list") || low.contains("watchlist") || low.contains("watch list") ? "My List" : "Continue Watching";
                return wrap(driver(steps, apps).pickFromRow(a == null ? null : a.pkg, g, row));
            }
            boolean sure = plan.confidence == null || plan.confidence >= 0.6;
            if (sure) return execute(plan, steps);
        }
        // Not a known shape (or the decider wasn't sure): the step-by-step loop over the live screen.
        Log.w("generic task: " + command);
        return wrap(driver(steps, apps).generic(command, Brain.query(command, apps)));
    }

    private static Result wrap(Driver.Result d) {
        Result r = new Result(d.say, d.success);
        r.needsUser = d.needsUser;
        r.question = d.question;
        r.options = d.options;
        r.detail = d.json();
        return r;
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
        for (Brain.Action a : plan.actions) {
            switch (a.type) {
                case "open":
                    if (!a.app.pkg.equals(tv.foreground())) tv.launch(a.app);
                    tv.waitForApp(a.app.pkg, 8000);
                    if (!a.app.pkg.equals(tv.foreground())) return new Result(a.app.label + " didn't open.", false);   // verified, not assumed
                    break;
                case "press":
                    tv.key(a.key, a.times);
                    break;
                case "type":
                    tv.typeText(a.text);
                    break;
                default:
                    break;
            }
        }
        return new Result(plan.say, true);
    }
}
