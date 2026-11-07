package dev.syed.tvpilot.tv;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1.2.0's executor: closed-loop, verify-after-every-action control of any TV app.
 *
 *   observe  = UI tree (accessibility, else uiautomator) + screenshot; the screen is named from the tree, or by the
 *              decision model from the screenshot when the app draws its own UI (Netflix, Prime, YouTube/Cobalt)
 *   route    = deterministic first: JustWatch title link -> rulebook deep link -> app search link -> in-app search
 *   act      = "select element": focus it (ACTION_FOCUS / D-pad moves planned from the focused node's position, or from
 *              the model's focus-cell/target-cell answers on the grid), press OK, then ACTION_CLICK / tap as fallbacks
 *   verify   = after every action: did the focus move, did the screen change to the expected kind, is it the right
 *              title, is video playing (motion between two frames + the model's yes/no)
 *   escalate = Gemini for that one step when d1-ultra is unsure or the same step failed twice
 *   learn    = per app: is the tree usable, does it report focus, which search route / typing / select method worked
 * Pure Java over the Device interface, so the JVM tests drive a simulated TV with the real d1-ultra.
 */
final class Driver {
    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23, BACK = 4, HOME = 3, ENTER = 66;

    interface Listener { void status(String app, String screen, String step); }

    static final class Config {
        String country = "IN", rules = "", profile = "";
        boolean skipIntro;
        /** Package of YouTube to use for "play X" with nothing else matching. */
        String defaultPlayer;
        int maxSteps = 28;
        long deadlineMs = 150_000;
    }

    static final class Result {
        boolean success, needsUser;
        String say = "", question, app = "", screen = "";
        List<String> options = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        long ms;
        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("success", success).put("outcome", say).put("app_in_front", app).put("screen", screen).put("ms", ms);
                if (needsUser) o.put("needs_user", new JSONObject().put("question", question).put("options", new org.json.JSONArray(options)));
                o.put("steps", new org.json.JSONArray(trace.size() > 10 ? trace.subList(trace.size() - 10, trace.size()) : trace));
            } catch (Exception ignored) {}
            return o;
        }
    }

    static final class Stop extends Exception { final Result r; Stop(Result r) { super(r.say); this.r = r; } }

    final Device d;
    final Decider dec;
    final Gem gem;
    final Config cfg;
    final Listener ui;
    private final Result res = new Result();
    private final long started = System.currentTimeMillis();
    private String task = "";
    private int calls;

    Driver(Device d, Decider dec, Gem gem, Config cfg, Listener ui) {
        this.d = d; this.dec = dec; this.gem = gem; this.cfg = cfg == null ? new Config() : cfg; this.ui = ui;
    }

    // MARK: - Observing

    final class Obs {
        Ui.Snapshot s;
        Ui.Screen screen;
        Rulebook.App app;
        String pkg;
        Device.Frame frame;
        boolean treeOk;
        /** Filled in by look() for apps without a usable tree. */
        String visionKind, focusCell;
        Ui.Node focused() { return s == null ? null : s.focused(); }
        String kind() { return screen != null && screen.confidence >= 0.5 ? screen.kind.name() : visionKind != null ? visionKind : screen == null ? "?" : screen.kind.name(); }
    }

    Obs observe(boolean image) throws Exception {
        check();
        Obs o = new Obs();
        o.s = d.tree();
        o.pkg = o.s != null && !o.s.pkg.isEmpty() ? o.s.pkg : d.foreground();
        if (o.s != null && o.s.pkg.isEmpty()) o.s.pkg = o.pkg == null ? "" : o.pkg;
        o.app = Rulebook.forPkg(o.pkg);
        o.treeOk = o.s != null && o.s.usable();
        o.screen = Ui.classify(o.s == null ? new Ui.Snapshot() : o.s, Rulebook.known(o.pkg));
        if (image || !o.treeOk) o.frame = d.capture(1280);
        if (o.pkg != null && o.s != null) Rulebook.learn(o.pkg, "tree", o.treeOk ? "usable" : o.s.textCount() > 0 ? "thin" : "none");
        if (o.pkg != null && o.treeOk) Rulebook.learn(o.pkg, "tree_focus", o.focused() != null ? "yes" : "no");
        return o;
    }

    /** Waits until the screen stops changing (tree text and focus, or the picture), at most maxMs. */
    Obs settle(long maxMs, boolean image) throws Exception {
        long end = System.currentTimeMillis() + maxMs;
        Obs prev = observe(false);
        while (System.currentTimeMillis() < end) {
            Thread.sleep(prev.treeOk ? 250 : 450);
            Obs now = observe(false);
            boolean same;
            if (now.treeOk && prev.treeOk) same = now.s.allText().equals(prev.s.allText()) && sameNode(now.focused(), prev.focused()) && eq(now.pkg, prev.pkg);
            else same = now.frame != null && prev.frame != null && Ui.diff(now.frame.sig, prev.frame.sig) < 2.0 && eq(now.pkg, prev.pkg);
            prev = now;
            if (same) break;
        }
        if (image && prev.frame == null) prev.frame = d.capture(1280);
        return prev;
    }

    /** For apps that draw their own UI: one decision-model call that names the screen, finds the focus highlight and (optionally) the target. */
    static final Map<String, String> KINDS = Decider.map(
            "BROWSE", "an app's home or browse screen: rows of posters/thumbnails, maybe a menu on the left or top",
            "KEYBOARD", "a search screen with an on-screen keyboard or a search text box",
            "SEARCH_RESULTS", "search results (posters or thumbnails for a typed query)",
            "DETAIL", "a title's details page: big title/logo, description, a Play / Resume / Watch button",
            "EPISODES", "a dedicated list of a show's episodes (numbered episodes, season selector) without a big Play button",
            "PLAYER", "a video playing (full screen, maybe with a controls bar or 'Skip' button)",
            "PROFILE", "a 'Who's watching?' / choose-profile screen: a few avatars or name boxes in a row",
            "DIALOG", "a popup dialog or message box over the app",
            "LOGIN", "a sign-in, subscribe, buy or plan screen",
            "LOADING", "a loading spinner or black/blank screen",
            "LAUNCHER", "the TV's home screen / launcher or another app");

    static final class Look {
        String kind, focus, target, dir;
        double dirConf, kindConf, targetConf, focusConf, targetFocused, extra;
        Decider.Answer a;
    }

    Look look(Obs o, String target, String extraYesNo) throws Exception {
        if (o.frame == null) o.frame = d.capture(1280);
        byte[] grid = d.annotate(o.frame, null, true);
        JSONObject q = new JSONObject();
        q.put("screen", Decider.choice("What kind of screen is this TV showing?", KINDS));
        q.put("focus", Decider.choice("TV apps mark the selected item with a focus highlight (a bright border, a white/colored box, the item enlarged or lifted, or inverted colors). "
                + "In the SECOND image (labelled grid), which cell contains the CENTER of the item that has the focus highlight right now?", Marks.cellCriteria("no focus highlight is visible")));
        if (target != null) {
            q.put("target", Decider.choice("In the SECOND image (labelled grid), which cell contains the CENTER of " + target + "?", Marks.cellCriteria("it is not visible on this screen")));
            q.put("on_target", Decider.noul("Is the item that has the focus highlight right now " + target + "?"));
            q.put("dir", Decider.choice("On a TV remote, which ONE button moves the focus highlight closer to " + target + "? Focus moves to the nearest item in that direction.",
                    Decider.map("UP", "press UP", "DOWN", "press DOWN", "LEFT", "press LEFT", "RIGHT", "press RIGHT", "OK", "it is already highlighted")));
        }
        if (extraYesNo != null) q.put("extra", Decider.noul(extraYesNo));
        String state = "Two images of the same TV screen: the first is clean, the second has a labelled grid (rows A-F top to bottom, columns 1-8 left to right). "
                + "App in front: " + (o.app != null ? o.app.label : o.pkg) + ". TASK: " + task + "\n" + (o.app != null ? o.app.brief(null) : "");
        Decider.Answer a = dec.ask(state, Arrays.asList(o.frame.jpeg, grid), q, 12_000);
        calls++;
        Look l = new Look();
        l.a = a;
        l.kind = a.choice("screen"); l.kindConf = a.conf("screen");
        l.focus = a.choice("focus"); l.focusConf = a.conf("focus");
        if (target != null) { l.target = a.choice("target"); l.targetConf = a.conf("target"); l.targetFocused = a.noul("on_target"); l.dir = a.choice("dir"); l.dirConf = a.conf("dir"); }
        if (extraYesNo != null) l.extra = a.noul("extra");
        o.visionKind = l.kind;
        o.focusCell = l.focus;
        log("look " + a.ms + " ms: screen=" + l.kind + String.format(Locale.US, "(%.2f)", l.kindConf) + " focus=" + l.focus
                + (target != null ? " target=" + l.target + String.format(Locale.US, "(%.2f) on_target=%.2f dir=%s(%.2f)", l.targetConf, l.targetFocused, l.dir, l.dirConf) : "")
                + (extraYesNo != null ? String.format(Locale.US, " extra=%.2f", l.extra) : ""));
        return l;
    }

    // MARK: - Selecting things

    /** Focus the node and press OK; verify the screen changed. Learns which way works in this app. */
    boolean select(Obs o, Ui.Node target, String what) throws Exception {
        step("Selecting " + what);
        String pkg = o.pkg;
        String method = Rulebook.learnedString(pkg, "select_method");
        if (focusTree(target, what)) {
            Obs before = observe(false);
            d.key(OK, 1);
            if (changed(before, 2500)) { Rulebook.learn(pkg, "select_method", "focus+ok"); trace("selected " + what + " (focus + OK)"); return true; }
        }
        Ui.Snapshot s = d.tree();
        Ui.Node t = refind(s, target);
        if (t != null && !"tap".equals(method)) {
            Obs before = observe(false);
            if (d.click(t) && changed(before, 2500)) { Rulebook.learn(pkg, "select_method", "click"); trace("selected " + what + " (accessibility click)"); return true; }
        }
        if (t != null) {
            Obs before = observe(false);
            d.tap(t.cx(), t.cy());
            if (changed(before, 2500)) { Rulebook.learn(pkg, "select_method", "tap"); Rulebook.learn(pkg, "taps", "work"); trace("selected " + what + " (tap)"); return true; }
        }
        trace("couldn't select " + what);
        return false;
    }

    /** Moves the focus onto the node using its position (closed loop on the focused node). */
    boolean focusTree(Ui.Node target, String what) throws Exception {
        Ui.Node lastF = null;
        int stuck = 0, moves = 0;
        int blockedAxis = -1;
        for (int i = 0; i < 40; i++) {
            check();
            Ui.Snapshot s = d.tree();
            Ui.Node t = refind(s, target);
            Ui.Node f = s.focused();
            if (t == null) { log("target '" + what + "' vanished"); return false; }
            if (f != null && (sameNode(f, t) || inside(f, t) || inside(t, f) && t.interactive() == false)) { if (moves > 0) trace("focus on " + what + " after " + moves + " moves"); return true; }
            if (f == null) {
                // No focus reported: ask the app to focus it directly (ACTION_FOCUS), else give up (the caller clicks/taps).
                if (i == 0 && d.focus(t)) { Thread.sleep(250); Ui.Node nf = d.tree().focused(); if (nf != null && (sameNode(nf, t) || inside(nf, t))) return true; }
                return false;
            }
            if (lastF != null && sameNode(lastF, f)) stuck++; else stuck = 0;
            lastF = f;
            if (stuck >= 2) { log("focus is stuck at " + f.label()); return false; }
            int dx = t.cx() - f.cx(), dy = t.cy() - f.cy();
            boolean inRail = f.r < s.width * 0.3 && t.l > s.width * 0.3;
            int key;
            if (inRail) key = RIGHT;
            else if (Math.abs(dy) > Math.max(f.h(), t.h()) / 2 && blockedAxis != 1) key = dy > 0 ? DOWN : UP;
            else key = dx > 0 ? RIGHT : LEFT;
            if (stuck == 1) { blockedAxis = (key == UP || key == DOWN) ? 1 : 0; key = blockedAxis == 1 ? (dx > 0 ? RIGHT : LEFT) : (dy > 0 ? DOWN : UP); }
            int times = 1;
            if ((key == LEFT || key == RIGHT) && f.w() > 0) times = Math.max(1, Math.min(6, Math.abs(dx) / Math.max(1, f.w() + 12)));
            if ((key == UP || key == DOWN) && f.h() > 0) times = Math.max(1, Math.min(4, Math.abs(dy) / Math.max(1, f.h() + 12)));
            if (!"a11y".equals(s.source)) times = Math.max(1, times);   // uiautomator is slow: take bigger steps
            else times = Math.min(times, 2);
            d.key(key, times);
            moves += times;
            waitFocusChange(f, 700);
        }
        return false;
    }

    private void waitFocusChange(Ui.Node old, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(90);
            Ui.Node f = d.tree().focused();
            if (f != null && !sameNode(f, old)) { Thread.sleep(120); return; }
        }
    }

    /** Apps without a tree: focus the target described in words, using the model's grid answers. */
    boolean focusVision(String target, int maxMoves) throws Exception {
        String lastFocus = null;
        int same = 0, notVisible = 0, unsure = 0;
        for (int i = 0; i < maxMoves; i++) {
            check();
            Obs o = observe(true);
            Look l = look(o, target, null);
            if (l.targetFocused > 0.7 || (l.target != null && l.target.equals(l.focus) && l.targetFocused > 0.45) || ("OK".equals(l.dir) && l.dirConf > 0.7 && l.targetFocused > 0.4)) { trace("focus on " + target); return true; }
            if ("none".equals(l.target)) { if (++notVisible >= 2) { log("not visible: " + target); return false; } continue; }
            int[] f = Marks.cellRC(l.focus), t = Marks.cellRC(l.target);
            if (f == null || t == null || l.focusConf < 0.25) {
                if (++unsure >= 2) {
                    JSONObject g = gemStep("Move the focus highlight onto " + target + ".", o, null);
                    if (g != null && doGemAction(g, o, null)) continue;
                    return false;
                }
                d.key(DOWN, 1); Thread.sleep(500);   // nothing focused yet (hero / video): one press usually shows the highlight
                continue;
            }
            if (l.focus.equals(lastFocus)) same++; else same = 0;
            lastFocus = l.focus;
            int dr = t[0] - f[0], dc = t[1] - f[1];
            int key, times = 1;
            int modelKey = keyCode(l.dir);
            if (same >= 2) key = Math.abs(dc) > 0 ? (dc > 0 ? RIGHT : LEFT) : (dr > 0 ? DOWN : UP);   // that direction did nothing: other axis
            else if (modelKey > 0 && modelKey != OK && l.dirConf >= 0.5) key = modelKey;          // the model's own call on direction
            else if (Math.abs(dr) >= 1) key = dr > 0 ? DOWN : UP;
            else key = dc > 0 ? RIGHT : LEFT;
            if ((key == LEFT || key == RIGHT) && Math.abs(dc) >= 3 && (key == RIGHT) == (dc > 0) && Math.abs(dr) == 0) times = 2;
            if (same >= 4) { log("focus doesn't move"); return false; }
            d.key(key, times);
            Thread.sleep(380);
        }
        return false;
    }

    /** Is it the right thing in focus? Then OK, and check that something happened. */
    boolean selectVision(String target) throws Exception {
        step("Selecting " + target);
        if (!focusVision(target, 14)) return false;
        Obs before = observe(true);
        d.key(OK, 1);
        if (changed(before, 3000)) { trace("selected " + target); return true; }
        trace("OK on " + target + " changed nothing");
        return false;
    }

    // MARK: - Verifying

    /** Did anything visible change after an action (screen, app, focus)? */
    boolean changed(Obs before, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(250);
            String fg = d.foreground();
            if (!eq(fg, before.pkg)) return true;
            Ui.Snapshot s = d.tree();
            if (before.treeOk && s.usable()) {
                if (!s.allText().equals(before.s.allText()) || !sameNode(s.focused(), before.focused())) return true;
            } else {
                Device.Frame f = d.capture(640);
                if (before.frame == null) before.frame = d.capture(640);
                else if (Ui.diff(f.sig, before.frame.sig) > 3.0) return true;
            }
        }
        return false;
    }

    /** Video playing: the player screen in the tree, or motion between frames that the model confirms is video. */
    boolean playing(String title) throws Exception {
        for (int i = 0; i < 4; i++) {
            check();
            Obs o = observe(true);
            if (o.treeOk && o.screen.kind == Ui.Kind.PLAYER && o.screen.confidence >= 0.7) { trace("player is on (" + o.screen.why + ")"); return true; }
            handleInterstitial(o);
            Thread.sleep(900);
            Device.Frame f2 = d.capture(640);
            Device.Frame f1 = d.capture(640);
            double motion = Ui.diff(f1.sig, f2.sig);
            Look l = look(o, null, "Is a video or live stream playing on this screen (full-screen video, possibly with a controls overlay, a buffering spinner or an ad)?"
                    + (title == null ? "" : " It should be '" + title + "'."));
            boolean kindPlayer = "PLAYER".equals(l.kind) && l.kindConf > 0.5;
            log(String.format(Locale.US, "playing? motion=%.1f model=%.2f kind=%s", motion, l.extra, l.kind));
            if ((kindPlayer || l.extra > 0.6) && (motion > 1.2 || l.extra > 0.85)) { trace("video is playing"); return true; }
            if ("DETAIL".equals(l.kind) || "EPISODES".equals(l.kind)) return false;
            Thread.sleep(1500);   // still loading
        }
        return false;
    }

    // MARK: - Popups, profiles, sign-in

    /** Clears what stands between us and the goal. Returns true if it did something. */
    boolean handleInterstitial(Obs o) throws Exception {
        String kind = o.kind();
        if ("PROFILE".equals(kind)) {
            step("Choosing the profile");
            if (!cfg.profile.isEmpty() && o.treeOk) {
                List<Ui.Hit> h = Ui.find(o.s, cfg.profile, 0.8);
                if (!h.isEmpty() && select(o, h.get(0).node, "profile " + cfg.profile)) return true;
            }
            Obs before = observe(true);
            d.key(OK, 1);   // the rulebook: the last used profile is focused
            changed(before, 3000);
            trace("picked the focused profile");
            settle(3000, false);
            return true;
        }
        if ("LOGIN".equals(kind)) {
            res.say = (o.app != null ? o.app.label : "The app") + " needs you to sign in or subscribe first.";
            throw new Stop(res);
        }
        if ("DIALOG".equals(kind) && o.treeOk && o.app != null) {
            String all = Ui.norm(o.s.allText());
            for (JSONObject rule : o.app.dialogs()) {
                if (rule == null || rule.has("only_if")) continue;
                boolean hit = false;
                for (int i = 0; i < rule.optJSONArray("when").length(); i++) if (all.contains(Ui.norm(rule.optJSONArray("when").optString(i)))) hit = true;
                if (!hit) continue;
                List<String> press = new ArrayList<>();
                if (rule.optJSONArray("press") != null) for (int i = 0; i < rule.optJSONArray("press").length(); i++) press.add(rule.optJSONArray("press").optString(i));
                Ui.Node b = Ui.button(o.s, press);
                if (b != null) { trace("dialog: pressing " + b.label()); return select(o, b, b.label()); }
                if ("BACK".equals(rule.optString("key"))) { d.key(BACK, 1); trace("dialog: BACK"); return true; }
            }
        }
        return false;
    }

    // MARK: - Watching something

    /** "play X (S/E) on Y": the deterministic routes first, the model only for what they can't settle. */
    Result play(Goal g) {
        task = "play " + g;
        try {
            if (g.title.isEmpty()) { res.say = "What should I play?"; return finish(false); }
            String pkg = g.pkg;
            List<String> installed = d.installed();
            // 1. Where is it, and the app's own link to it.
            JustWatch.Title jw = null;
            if (!g.music) {
                try {
                    step("Looking up " + g.title);
                    List<JustWatch.Title> all = JustWatch.search(g.title, cfg.country, 5000);
                    jw = JustWatch.best(all, g.title, g.year);
                    if (jw != null && g.movie) for (JustWatch.Title t : all) if ("MOVIE".equals(t.type) && t.score >= 0.86) { jw = t; break; }
                    if (jw != null && g.show) for (JustWatch.Title t : all) if ("SHOW".equals(t.type) && t.score >= 0.86) { jw = t; break; }
                    log("JustWatch: " + (jw == null ? "no close match in " + all : jw));
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    log("JustWatch unavailable: " + Net.explain(e));
                }
            }
            if (pkg == null && jw != null) {
                List<String> where = new ArrayList<>();
                for (String prov : jw.offers.keySet()) for (String p : Rulebook.packagesForProvider(prov)) if (installed.contains(p) && !where.contains(p)) where.add(p);
                if (where.isEmpty() && cfg.defaultPlayer == null) { res.say = jw.title + " isn't on any app installed on this TV."; return finish(false); }
                if (where.size() == 1) pkg = where.get(0);
                else if (where.size() > 1) {
                    res.needsUser = true;
                    res.question = jw.title + " is on " + labels(where) + ". Which app?";
                    for (String p : where) res.options.add(label(p));
                    res.say = res.question;
                    return finish(false);
                }
            }
            if (pkg == null) pkg = cfg.defaultPlayer;
            if (pkg == null) { res.say = "Which app should I play " + g.title + " in?"; res.needsUser = true; res.question = res.say; return finish(false); }
            Rulebook.App app = Rulebook.forPkg(pkg);
            String link = null;
            if (jw != null && app != null) link = titleLink(app, jw, pkg);
            if (jw != null && link == null && app != null && !app.justwatch.isEmpty() && Collections.disjoint(app.justwatch, jw.offers.keySet())) {
                // Named an app that doesn't have it; say where it is instead of searching in vain.
                List<String> where = new ArrayList<>();
                for (String prov : jw.offers.keySet()) for (String p : Rulebook.packagesForProvider(prov)) if (installed.contains(p) && !where.contains(p)) where.add(p);
                if (!where.isEmpty()) {
                    res.needsUser = true;
                    res.question = jw.title + " isn't on " + app.label + ", but it's on " + labels(where) + ". Play it there?";
                    for (String p : where) res.options.add(label(p));
                    res.options.add("Search " + app.label + " anyway");
                    res.say = res.question;
                    return finish(false);
                }
            }
            // 2. Get to the title page (or the results) with as little navigation as possible.
            boolean onTitle = false;
            if (link != null) onTitle = openLink(app, pkg, link, jw);
            if (!onTitle) {
                if (!searchFor(app, pkg, g)) { if (res.say.isEmpty()) res.say = "I couldn't search " + label(pkg) + " for " + g.title + "."; return finish(false); }
                if (!pickResult(app, pkg, g)) { if (res.say.isEmpty()) res.say = "I couldn't find " + Goal.nice(g.title) + " in " + label(pkg) + "."; return finish(false); }
            }
            // 3. Title page -> season / episode -> Play -> verify playback.
            return finishTitle(app, pkg, g);
        } catch (Stop s) {
            return finish(false);
        } catch (InterruptedException e) {
            res.say = "Stopped.";
            return finish(false);
        } catch (Exception e) {
            res.say = Net.explain(e);
            log("play failed: " + e);
            return finish(false);
        }
    }

    String titleLink(Rulebook.App app, JustWatch.Title jw, String pkg) {
        JSONObject tl = app.titleLink();
        if (tl == null) return null;
        if ("imdb".equals(tl.optString("from"))) {
            if (jw.imdb == null) return null;
            return String.format(Locale.US, "SHOW".equals(jw.type) ? tl.optString("url_series") : tl.optString("url_movie"), jw.imdb);
        }
        for (String prov : app.justwatch) {
            String u = jw.offers.get(prov);
            if (u != null && !u.isEmpty()) return tl.optBoolean("https") ? u.replace("http://", "https://") : u;
        }
        return null;
    }

    /** Opens a title link the way the rulebook says, then checks we really landed on that title. */
    boolean openLink(Rulebook.App app, String pkg, String link, JustWatch.Title jw) throws Exception {
        JSONObject tl = app.titleLink();
        step("Opening " + jw.title + " in " + label(pkg));
        if (tl.optBoolean("force_stop") && pkg.equals(d.foreground())) d.shell("am force-stop " + Ui.q(pkg));
        StringBuilder cmd = new StringBuilder("am start -W -a android.intent.action.VIEW -d ").append(Ui.q(link));
        if (!tl.optString("category").isEmpty()) cmd.append(" -c ").append(tl.optString("category"));
        if (!tl.optString("flags").isEmpty()) cmd.append(" -f ").append(tl.optString("flags"));
        if (!tl.optString("extras").isEmpty()) cmd.append(' ').append(tl.optString("extras"));
        String comp = tl.optString("component");
        cmd.append(' ').append(comp.isEmpty() ? Ui.q(pkg) : "-n " + Ui.q(comp));
        String out = d.shell(cmd + " 2>&1");
        log("link: " + Net.shorten(out.replace('\n', ' '), 200));
        if (out.contains("Error") || out.contains("unable to resolve") || out.contains("does not exist")) { trace("the app refused the link"); Rulebook.learn(pkg, "title_link", "refused"); return false; }
        if (!waitApp(pkg, 9000)) { trace("the link didn't open " + label(pkg) + " (" + d.foreground() + ")"); Rulebook.learn(pkg, "title_link", "wrong app"); if (!eq(d.foreground(), pkg)) d.key(BACK, 1); return false; }
        for (int i = 0; i < 5; i++) {
            Obs o = settle(6000, true);
            boolean titled = onTitlePage(o, jw.title);   // also names the screen (profile picker, dialog...) for apps without a tree
            if (titled) { trace("on the " + jw.title + " page (link)"); Rulebook.learn(pkg, "title_link", "works"); return true; }
            if (handleInterstitial(o)) continue;
            if (o.treeOk && o.screen.kind == Ui.Kind.PLAYER) return true;
            if (!o.treeOk && "PLAYER".equals(o.visionKind)) return true;
            Thread.sleep(1200);
        }
        trace("the link opened " + label(pkg) + " but not the title page");
        Rulebook.learn(pkg, "title_link", "lands elsewhere");
        return false;
    }

    boolean onTitlePage(Obs o, String title) throws Exception {
        if (o.treeOk) {
            boolean named = !Ui.find(o.s, title, 0.8).isEmpty();
            return named && (o.screen.kind == Ui.Kind.DETAIL || o.screen.kind == Ui.Kind.EPISODES || Ui.button(o.s, o.app.buttons("play")) != null);
        }
        Look l = look(o, null, "Is this the details page (or episode list) of '" + title + "'?");
        return ("DETAIL".equals(l.kind) || "EPISODES".equals(l.kind)) && l.extra > 0.5 || l.extra > 0.8;
    }

    boolean waitApp(String pkg, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { check(); if (pkg.equals(d.foreground())) return true; Thread.sleep(300); }
        return false;
    }

    /** Search link, then the app's own search screen. Ends on the results. */
    boolean searchFor(Rulebook.App app, String pkg, Goal g) throws Exception {
        JSONObject sl = app == null ? null : app.searchLink();
        if (sl != null && !"broken".equals(Rulebook.learnedString(pkg, "search_link"))) {
            String q = java.net.URLEncoder.encode(g.title, "UTF-8");
            if (!sl.optString("space").isEmpty()) q = q.replace("+", sl.optString("space"));
            String url = sl.optString("url").replace("%s", q);
            String comp = app.j.optString("component");
            step("Searching " + label(pkg) + " for " + Goal.nice(g.title));
            String out = d.shell("am start -W -a android.intent.action.VIEW -d " + Ui.q(url) + " " + (comp.isEmpty() ? Ui.q(pkg) : "-n " + Ui.q(comp)) + " 2>&1");
            if (!out.contains("Error") && waitApp(pkg, 9000)) {
                Obs o = settle(5000, true);
                handleInterstitial(o);
                if (resultsFor(o, g.title)) { trace("search results for " + g.title + " (link)"); Rulebook.learn(pkg, "search_route", "link"); return true; }
                trace("the search link opened " + label(pkg) + " but no results showed");
            }
            Rulebook.learn(pkg, "search_link", "broken");
        }
        // The app's own search screen.
        if (!pkg.equals(d.foreground())) {
            step("Opening " + label(pkg));
            launch(pkg);
            if (!waitApp(pkg, 9000)) { res.say = label(pkg) + " didn't open."; return false; }
        }
        Obs o = settle(6000, true);
        for (int i = 0; i < 2 && handleInterstitial(o); i++) o = settle(5000, true);
        // Deep inside the app (a title page, the player): go back towards its home, where the menu with Search is.
        for (int i = 0; i < 3; i++) {
            String k = o.treeOk ? o.screen.kind.name() : o.visionKind == null ? look(o, null, null).kind : o.visionKind;
            if (!("DETAIL".equals(k) || "EPISODES".equals(k) || "PLAYER".equals(k) || "DIALOG".equals(k))) break;
            d.key(BACK, 1);
            Thread.sleep(800);
            if (!pkg.equals(d.foreground())) { launch(pkg); waitApp(pkg, 8000); }
            o = settle(3000, true);
        }
        if (!"KEYBOARD".equals(o.kind())) {
            step("Opening search");
            boolean ok = false;
            if (o.treeOk) {
                Ui.Node b = Ui.button(o.s, app.buttons("search"));
                if (b == null) for (Ui.Node n : o.s.interactive()) if (n.resId.toLowerCase(Locale.US).contains("search") || n.desc.toLowerCase(Locale.US).contains("search")) { b = n; break; }
                if (b != null) ok = select(o, b, "Search");
            }
            if (!ok && !o.treeOk) ok = selectVision("the Search button or search icon (magnifying glass) in the app's menu");
            if (!ok) {
                // Leanback/Compose rails: LEFT until the rail opens, then look again.
                d.key(LEFT, 3); Thread.sleep(600);
                o = observe(true);
                if (o.treeOk) { Ui.Node b = Ui.button(o.s, app.buttons("search")); if (b != null) ok = select(o, b, "Search"); }
                else ok = selectVision("the Search item or magnifying-glass icon in the menu");
            }
            if (!ok) { res.say = "I couldn't find the search in " + label(pkg) + "."; return false; }
            o = settle(4000, true);
        }
        if (!typeQuery(o, pkg, g.title)) return false;
        Obs r = settle(4000, true);
        Rulebook.learn(pkg, "search_route", "in-app");
        return resultsFor(r, g.title) || true;   // results may load lazily; pickResult verifies the actual tile
    }

    boolean resultsFor(Obs o, String title) throws Exception {
        if (o.treeOk) return !Ui.find(o.s, title, 0.75).isEmpty();
        Look l = look(o, null, "Does this screen show search results (posters, thumbnails or a list) for '" + title + "'?");
        return l.extra > 0.5;
    }

    boolean typeQuery(Obs o, String pkg, String text) throws Exception {
        step("Typing " + text);
        Ui.Node box = null;
        if (o.treeOk) for (Ui.Node n : o.s.nodes) if (n.editable && n.visible) { box = n; if (n.focused) break; }
        boolean typed = false;
        if (box != null && d.setText(box, text)) {
            Thread.sleep(400);
            for (Ui.Node n : d.tree().nodes) if (n.editable && Ui.norm(n.text).contains(Ui.norm(text))) typed = true;
            if (typed) Rulebook.learn(pkg, "typing", "set_text");
        }
        if (!typed) {
            if (box != null && !box.focused) focusTree(box, "the search box");
            d.typeText(text);
            Thread.sleep(500);
            Ui.Snapshot s = d.tree();
            if (s.usable()) typed = s.allText().toLowerCase(Locale.US).contains(text.toLowerCase(Locale.US).split(" ")[0]);
            else {
                Obs v = observe(true);
                Look l = look(v, null, "Does the search box or typed-text area show '" + text + "' (or the start of it)?");
                typed = l.extra > 0.5;
            }
            if (typed) Rulebook.learn(pkg, "typing", "input_text");
        }
        if (!typed) { trace("the typed text didn't appear"); res.say = "I couldn't type into " + label(pkg) + "'s search."; return false; }
        trace("typed \"" + text + "\"");
        if (!"no".equals(Rulebook.learnedString(pkg, "enter_submits"))) { d.key(ENTER, 1); Thread.sleep(900); }
        return true;
    }

    /** On the results: the tile for the title (text match, else the model's eyes), selected and verified. */
    boolean pickResult(Rulebook.App app, String pkg, Goal g) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            check();
            Obs o = settle(3000, true);
            if (o.treeOk) {
                List<Ui.Hit> hits = new ArrayList<>();
                for (Ui.Hit h : Ui.find(o.s, g.title, 0.7)) if (!h.node.editable && h.node.label().length() > 1 && h.node.h() > 20) hits.add(h);
                if (!hits.isEmpty()) {
                    Ui.Hit best = hits.get(0);
                    log("best result: " + best.node + String.format(Locale.US, " (%.2f)", best.score));
                    if (best.score < 0.85 && hits.size() > 1) best = chooseByModel(o, hits, g.title);
                    if (select(o, best.node, "'" + best.node.label() + "'")) return true;
                }
                if (attempt == 0) { d.key(DOWN, 1); Thread.sleep(600); continue; }   // results below the keyboard / suggestions
            } else {
                String target = (pkg.contains("youtube") || pkg.contains("tizentube")) ? (g.latest ? "the CHANNEL result for '" + g.title + "' (round avatar)" : "the first video thumbnail that matches '" + g.title + "' (not an ad, not a Short)")
                        : "the poster or tile for the title '" + g.title + "' among the search results (not a keyboard key or a text suggestion)";
                if (selectVision(target)) return true;
                if (attempt == 0) { d.key(RIGHT, 1); Thread.sleep(500); }
            }
        }
        // The model couldn't place it: Gemini, one step.
        Obs o = observe(true);
        JSONObject gstep = gemStep("Select the search result for '" + g.title + "'.", o, o.treeOk ? Ui.marks(o.s, 30) : null);
        return gstep != null && doGemAction(gstep, o, o.treeOk ? Ui.marks(o.s, 30) : null);
    }

    Ui.Hit chooseByModel(Obs o, List<Ui.Hit> hits, String title) throws Exception {
        List<Ui.Node> nodes = new ArrayList<>();
        for (Ui.Hit h : hits) if (nodes.size() < 8) nodes.add(h.node);
        byte[] img = d.annotate(o.frame, nodes, false);
        JSONObject q = new JSONObject().put("pick", Decider.choice("Which marked element is the result for '" + title + "' the user wants (the main title, not a trailer, clip or similarly named title)?",
                Marks.markCriteria(nodes, o.s, "none of them")));
        Decider.Answer a = dec.ask("Search results on a TV. TASK: " + task, Collections.singletonList(img), q, 10_000);
        calls++;
        String c = a.choice("pick");
        log("model picked " + c + String.format(Locale.US, " (%.2f)", a.conf("pick")));
        if (c != null && c.startsWith("m")) try { return hits.get(Integer.parseInt(c.substring(1))); } catch (Exception ignored) {}
        return hits.get(0);
    }

    /** Title page: confirm it's the right one, pick season/episode, press Play, verify playback. */
    Result finishTitle(Rulebook.App app, String pkg, Goal g) throws Exception {
        Obs o = settle(5000, true);
        for (int i = 0; i < 2 && handleInterstitial(o); i++) o = settle(4000, true);
        if ((o.treeOk && o.screen.kind == Ui.Kind.PLAYER) || (!o.treeOk && "PLAYER".equals(o.visionKind))) {
            if (playing(g.title)) { res.say = "Playing " + Goal.nice(g.title) + "."; return finish(true); }
        }
        boolean youtube = pkg.contains("youtube") || pkg.contains("tizentube");
        if (youtube) {
            if (g.latest) {
                step("Opening the Videos tab");
                if (o.treeOk) { Ui.Node v = Ui.button(o.s, Collections.singletonList("Videos")); if (v != null) select(o, v, "Videos"); }
                else selectVision("the 'Videos' tab on the channel page");
                Thread.sleep(800);
                if (!selectVision("the FIRST video thumbnail in the Videos grid (the newest one, top-left)")) { res.say = "I opened the channel but couldn't start its newest video."; return finish(false); }
            }
            if (playing(g.title)) { res.say = "Playing " + Goal.nice(g.title) + " on YouTube."; return finish(true); }
            res.say = "I found " + Goal.nice(g.title) + " but couldn't confirm it's playing.";
            return finish(false);
        }
        if (g.season != null || g.episode != null) {
            if (!pickEpisode(o, app, pkg, g)) { if (res.say.isEmpty()) res.say = "I opened " + Goal.nice(g.title) + " but couldn't find " + se(g) + "."; return finish(false); }
        } else {
            if (!pressPlay(app, pkg, g)) { if (res.say.isEmpty()) res.say = "I opened " + Goal.nice(g.title) + " but couldn't press Play."; return finish(false); }
        }
        for (int i = 0; i < 3; i++) {
            Obs p = settle(4000, true);
            if (handleInterstitial(p)) continue;
            if (pkg.startsWith("com.nuvio") || pkg.startsWith("com.stremio")) pickFirstStream(p);
            break;
        }
        if (playing(g.title)) { res.say = "Playing " + Goal.nice(g.title) + (g.season != null || g.episode != null ? ", " + se(g) : "") + " on " + label(pkg) + "."; return finish(true); }
        res.say = "I pressed Play on " + Goal.nice(g.title) + " but the video didn't start.";
        return finish(false);
    }

    boolean pressPlay(Rulebook.App app, String pkg, Goal g) throws Exception {
        Obs o = observe(true);
        List<String> never = app.buttons("never");
        if (o.treeOk) {
            List<String> words = new ArrayList<>(app.buttons("play"));
            if (g.latest) words.add(0, "Watch Latest Episode");
            Ui.Node b = Ui.button(o.s, words);
            if (b != null && Ui.button(singleton(o.s, b), never) == null) return select(o, b, "'" + b.label() + "'");
        }
        String target = "the main Play / Resume / Watch Now / Continue Watching button" + (g.latest ? " (or 'Watch Latest Episode')" : "") + " (NOT Rent, Buy, Subscribe or Trailer)";
        Look l = look(o, target, "Is the focused button a Play, Resume, Watch or Continue button (not Rent/Buy/Subscribe/Trailer)?");
        if (l.extra > 0.7 || l.targetFocused > 0.7) {
            Obs before = observe(true);
            d.key(OK, 1);
            if (changed(before, 3000)) { trace("pressed the focused Play button"); return true; }
        }
        return selectVision(target);
    }

    boolean pickEpisode(Obs o, Rulebook.App app, String pkg, Goal g) throws Exception {
        step("Finding " + se(g));
        if (o.treeOk) {
            if (g.season != null) {
                Ui.Node sn = Ui.seasonNode(o.s, g.season);
                if (sn == null) { Ui.Node ep = Ui.button(o.s, app.buttons("episodes")); if (ep != null) { select(o, ep, "Episodes"); o = settle(3000, false); sn = Ui.seasonNode(o.s, g.season); } }
                if (sn == null) { for (int i = 0; i < 6 && sn == null; i++) { d.key(DOWN, 1); Thread.sleep(400); o = observe(false); sn = Ui.seasonNode(o.s, g.season); } }
                if (sn == null) { res.say = "I couldn't find season " + g.season + " of " + Goal.nice(g.title) + "."; return false; }
                if (!focusTree(sn, "Season " + g.season)) return false;
                if (!sn.selected) { Obs b = observe(false); d.key(OK, 1); changed(b, 1500); }
                trace("season " + g.season);
                o = settle(3000, false);
            }
            int ep = g.episode == null ? 1 : g.episode;
            Ui.Node en = Ui.episodeNode(o.s, ep);
            for (int i = 0; i < 30 && en == null; i++) {   // scroll through the episode list
                Ui.Node f = o.s.focused();
                d.key(f != null && f.w() > f.h() * 2.2 ? DOWN : RIGHT, 1);
                Thread.sleep(300);
                o = observe(false);
                en = Ui.episodeNode(o.s, ep);
            }
            if (en == null) { res.say = "I couldn't find episode " + ep + "."; return false; }
            return select(o, en, "Episode " + ep);
        }
        if (g.season != null) {
            String st = "the control labelled 'Season " + g.season + "' (a tab, a list entry or a dropdown item)";
            boolean got = focusVision(st, 12);
            if (!got) {
                selectVision("the 'Episodes', 'Seasons' or 'Episodes & more' button, or the season dropdown");
                got = focusVision("'Season " + g.season + "' in the season list", 12);
            }
            if (!got) { res.say = "I couldn't find season " + g.season + "."; return false; }
            d.key(OK, 1);   // season tabs switch on focus or on OK; the change can be tiny, so it isn't required
            Thread.sleep(700);
            trace("season " + g.season);
        }
        int ep = g.episode == null ? 1 : g.episode;
        for (int i = 0; i < 6; i++) {
            if (selectVision("episode " + ep + " in the episode list (number " + ep + ", 'Episode " + ep + "' or 'E" + ep + "')" + (g.season != null ? " of season " + g.season : ""))) return true;
            d.key(DOWN, 2); Thread.sleep(500);
        }
        return false;
    }

    void pickFirstStream(Obs o) throws Exception {
        if (!o.treeOk) return;
        String all = Ui.norm(o.s.allText());
        if (!(all.contains("1080p") || all.contains("4k") || all.contains("720p") || all.contains("streams") || all.contains("size"))) return;
        Ui.Node f = o.s.focused();
        if (f != null) { d.key(OK, 1); trace("picked the first stream"); }
    }

    /** "continue X from Continue Watching / My List": find the tile on the app's home rows, then the title flow. */
    Result pickFromRow(String pkg, Goal g, String row) {
        task = "play " + g.title + " from " + row;
        try {
            if (pkg != null && !pkg.equals(d.foreground())) { step("Opening " + label(pkg)); launch(pkg); if (!waitApp(pkg, 9000)) { res.say = label(pkg) + " didn't open."; return finish(false); } }
            if (pkg == null) pkg = d.foreground();
            Rulebook.App app = Rulebook.forPkg(pkg);
            Obs o = settle(6000, true);
            for (int i = 0; i < 2 && handleInterstitial(o); i++) o = settle(5000, true);
            step("Looking for " + Goal.nice(g.title) + " in " + row);
            for (int scan = 0; scan < 8; scan++) {
                if (o.treeOk) {
                    List<Ui.Hit> h = Ui.find(o.s, g.title, 0.8);
                    if (!h.isEmpty() && select(o, h.get(0).node, "'" + h.get(0).node.label() + "'")) return finishTitle(app, pkg, g);
                } else {
                    Look l = look(o, "the tile for '" + g.title + "'" + (row.isEmpty() ? "" : " (in the '" + row + "' row)"), null);
                    if (l.target != null && !"none".equals(l.target) && selectVision("the tile for '" + g.title + "'")) return finishTitle(app, pkg, g);
                }
                d.key(DOWN, 1);
                Thread.sleep(500);
                o = observe(true);
            }
            res.say = "I couldn't spot " + Goal.nice(g.title) + (row.isEmpty() ? "" : " in " + row) + ".";
            return finish(false);
        } catch (Stop s) {
            return finish(false);
        } catch (InterruptedException e) {
            res.say = "Stopped.";
            return finish(false);
        } catch (Exception e) {
            res.say = Net.explain(e);
            return finish(false);
        }
    }

    // MARK: - Anything else, step by step

    static final Map<String, String> ACTIONS = Decider.map(
            "select", "Select one element on screen (move the focus to it and press OK)",
            "type", "Type the search words into the search box",
            "back", "Press BACK (go back a screen or close a popup)",
            "more", "The needed item isn't visible: move further down / right to reveal more",
            "wait", "The screen is still loading",
            "stuck", "It can't be done here (needs sign-in, subscription, or doesn't exist)");

    /** Any task the routes don't cover (settings pages, unknown apps, "open my list"...). */
    Result generic(String what, String query) {
        task = what;
        String lastAct = "";
        int fails = 0;
        try {
            for (int step = 1; step <= cfg.maxSteps; step++) {
                Obs o = observe(true);
                if (handleInterstitial(o)) continue;
                List<Ui.Node> marks = o.treeOk ? Ui.marks(o.s, 30) : null;
                byte[] img = d.annotate(o.frame, marks, marks == null);
                JSONObject q = new JSONObject();
                q.put("done", Decider.noul("Looking at this TV screen, is this task already completed: " + what + "?"));
                q.put("action", Decider.choice("What is the single best next action for the task: " + what + "?", ACTIONS));
                q.put("target", marks != null
                        ? Decider.choice("If selecting something: which marked element should be selected next for the task '" + what + "'?", Marks.markCriteria(marks, o.s, "nothing on screen fits"))
                        : Decider.choice("If selecting something: in the labelled grid image, which cell holds the element to select next for the task '" + what + "'?", Marks.cellCriteria("nothing on screen fits")));
                if (marks == null) q.put("focus", Decider.choice("In the labelled grid image, which cell holds the item with the focus highlight?", Marks.cellCriteria("no focus highlight visible")));
                String state = "TV screen" + (marks != null ? " with numbered elements (m0, m1...)" : ", first clean then with a labelled grid A1..F8") + ". App: " + (o.app != null ? o.app.label : o.pkg)
                        + ". Screen: " + o.kind() + ". Step " + step + ". Done so far: " + lastTrace(5) + "\n" + (o.app != null ? o.app.brief(o.screen.confidence >= 0.5 ? o.screen.kind : null) : "")
                        + (marks != null ? "\nELEMENTS:\n" + String.join("\n", Marks.lines(marks, o.s)) : "") + (cfg.rules.isEmpty() ? "" : "\nUSER RULES: " + cfg.rules);
                Decider.Answer a = dec.ask(state, marks != null ? Collections.singletonList(img) : Arrays.asList(o.frame.jpeg, img), q, 12_000);
                calls++;
                double done = a.noul("done");
                String act = a.choice("action"), tgt = a.choice("target");
                double conf = a.conf("action");
                log(String.format(Locale.US, "step %d (%d ms): done=%.2f action=%s(%.2f) target=%s(%.2f)", step, a.ms, done, act, conf, tgt, a.conf("target")));
                Learn.step(what, o.s, o.screen, act + " " + tgt, "", d.annotate(o.frame, null, false));
                if (done > 0.8) { res.say = "Done."; return finish(true); }
                boolean escalate = (conf < 0.45 || fails >= 2) && gem != null && gem.available();
                if (escalate) {
                    JSONObject gs = gemStep(what, o, marks);
                    if (gs != null) {
                        if ("done".equals(gs.optString("action"))) { res.say = gs.optString("say", "Done."); return finish(true); }
                        if ("fail".equals(gs.optString("action"))) { res.say = gs.optString("say", "That can't be done here."); return finish(false); }
                        if (doGemAction(gs, o, marks)) { fails = 0; continue; }
                    }
                }
                String sig = act + ":" + tgt;
                boolean ok;
                switch (act == null ? "wait" : act) {
                    case "select":
                        if (marks != null && tgt != null && tgt.startsWith("m")) ok = select(o, marks.get(Integer.parseInt(tgt.substring(1))), "'" + marks.get(Integer.parseInt(tgt.substring(1))).label() + "'");
                        else if (marks == null && tgt != null && !"none".equals(tgt)) ok = moveToCell(o, tgt, a.choice("focus")) && pressOk();
                        else ok = false;
                        break;
                    case "type": ok = query != null && !query.isEmpty() && typeQuery(o, o.pkg, query); break;
                    case "back": d.key(BACK, 1); Thread.sleep(700); ok = true; break;
                    case "more": d.key(DOWN, 1); Thread.sleep(500); ok = true; break;
                    case "stuck": res.say = "That can't be done here."; return finish(false);
                    default: Thread.sleep(1200); ok = true;
                }
                trace(act + (tgt != null && !"none".equals(tgt) && "select".equals(act) ? " " + tgt : "") + (ok ? "" : " (no effect)"));
                fails = ok && !sig.equals(lastAct) ? 0 : fails + 1;
                lastAct = sig;
            }
            res.say = "I ran out of steps.";
            return finish(false);
        } catch (Stop s) {
            return finish(false);
        } catch (InterruptedException e) {
            res.say = "Stopped.";
            return finish(false);
        } catch (Exception e) {
            res.say = Net.explain(e);
            return finish(false);
        }
    }

    private boolean pressOk() throws Exception {
        Obs before = observe(true);
        d.key(OK, 1);
        return changed(before, 2500);
    }

    /** Grid-only: move focus from its cell to the target cell, re-checking each move. */
    boolean moveToCell(Obs o, String targetCell, String focusCell) throws Exception {
        int[] t = Marks.cellRC(targetCell), f = Marks.cellRC(focusCell);
        if (t == null) return false;
        for (int i = 0; i < 10 && f != null && (f[0] != t[0] || f[1] != t[1]); i++) {
            int key = f[0] != t[0] ? (t[0] > f[0] ? DOWN : UP) : (t[1] > f[1] ? RIGHT : LEFT);
            d.key(key, 1);
            Thread.sleep(400);
            Obs n = observe(true);
            Look l = look(n, "the item at grid cell " + targetCell + " on the previous screen", null);
            f = Marks.cellRC(l.focus);
            if (l.target != null && !"none".equals(l.target)) t = Marks.cellRC(l.target);
        }
        return f != null;
    }

    // MARK: - Gemini, one step

    JSONObject gemStep(String what, Obs o, List<Ui.Node> marks) {
        if (gem == null || !gem.available()) return null;
        try {
            step("Taking a closer look");
            byte[] img = d.annotate(o.frame, marks, marks == null);
            String system = "You operate an Android TV with a remote for the user. Decide ONE next action from the screenshot. "
                    + "Reply with JSON only: {\"thought\":\"<=20 words\",\"action\":\"select|key|type|done|fail\",\"mark\":\"m3\" or \"cell\":\"B4\",\"key\":\"UP|DOWN|LEFT|RIGHT|OK|BACK\",\"times\":1,\"text\":\"\",\"say\":\"\"}. "
                    + "TV apps follow a focus highlight: name what is focused now before choosing. Never pick Rent/Buy/Subscribe or ads.";
            String user = "TASK: " + what + "\nApp: " + (o.app != null ? o.app.label : o.pkg) + "\n" + (o.app != null ? o.app.brief(null) : "") + "\nDone so far: " + lastTrace(6)
                    + (marks != null ? "\nNumbered elements:\n" + String.join("\n", Marks.lines(marks, o.s)) : "\nThe second image has a labelled grid (A1 top-left .. F8 bottom-right); give \"cell\" for select.")
                    + (cfg.rules.isEmpty() ? "" : "\nUSER RULES: " + cfg.rules);
            JSONObject r = gem.ask(system, user, marks != null ? Collections.singletonList(img) : Arrays.asList(o.frame.jpeg, img), 25_000);
            log("Gemini (" + gem.lastMs + " ms): " + r);
            return r;
        } catch (Exception e) {
            log("Gemini step failed: " + Net.explain(e));
            return null;
        }
    }

    boolean doGemAction(JSONObject g, Obs o, List<Ui.Node> marks) throws Exception {
        String a = g.optString("action");
        switch (a) {
            case "select": {
                String m = g.optString("mark");
                if (marks != null && m.startsWith("m")) {
                    try { Ui.Node n = marks.get(Integer.parseInt(m.substring(1))); return select(o, n, "'" + n.label() + "'"); } catch (Exception e) { return false; }
                }
                String c = g.optString("cell");
                if (!c.isEmpty()) { Look l = look(o, "the item in grid cell " + c, null); return moveToCell(o, c, l.focus) && pressOk(); }
                return false;
            }
            case "key": {
                int code = keyCode(g.optString("key"));
                if (code < 0) return false;
                Obs before = observe(true);
                d.key(code, Math.max(1, Math.min(6, g.optInt("times", 1))));
                trace("Gemini: " + g.optString("key") + " x" + g.optInt("times", 1));
                return changed(before, 2000);
            }
            case "type": return !g.optString("text").isEmpty() && typeQuery(o, o.pkg, g.optString("text"));
            default: return false;
        }
    }

    static int keyCode(String k) {
        switch (k == null ? "" : k.toUpperCase(Locale.US)) {
            case "UP": return UP; case "DOWN": return DOWN; case "LEFT": return LEFT; case "RIGHT": return RIGHT;
            case "OK": case "SELECT": case "CENTER": return OK; case "BACK": return BACK; case "ENTER": return ENTER;
            default: return -1;
        }
    }

    // MARK: - Inputs and settings, deterministic

    /** "switch to HDMI 2": the TV input framework's passthrough URI, no menus. */
    Result input(String which) {
        task = "switch input to " + which;
        try {
            step("Switching to " + which);
            String out = d.shell("dumpsys tv_input 2>/dev/null | grep -iE 'inputId|mId=|label|HDMI' | head -60");
            List<String> ids = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:inputId|mId)=([^,\\s}]+)").matcher(out);
            while (m.find()) if (!ids.contains(m.group(1))) ids.add(m.group(1));
            String num = which.replaceAll("\\D+", "");
            String pick = null;
            for (String id : ids) {
                String low = id.toLowerCase(Locale.US);
                if (low.contains("hdmi") && (num.isEmpty() || low.endsWith(num) || low.contains("hdmi" + num) || low.contains("hw" + (Integer.parseInt(num.isEmpty() ? "1" : num) + 1)))) { pick = id; break; }
            }
            if (pick == null && !num.isEmpty()) for (String id : ids) if (id.toLowerCase(Locale.US).matches(".*hw" + (Integer.parseInt(num) + 1) + "$")) pick = id;
            log("tv inputs: " + ids + " -> " + pick);
            if (pick == null) { res.say = "I couldn't find " + which + " on this TV."; return finish(false); }
            String r = d.shell("am start -a android.intent.action.VIEW -d " + Ui.q("content://android.media.tv/passthrough/" + java.net.URLEncoder.encode(pick, "UTF-8")) + " 2>&1");
            if (r.contains("Error")) { res.say = "The TV refused to switch input."; return finish(false); }
            res.say = "Switched to " + which + ".";
            Rulebook.learn("inputs", "passthrough", "used " + pick);
            return finish(true);
        } catch (Exception e) {
            res.say = Net.explain(e);
            return finish(false);
        }
    }

    /** Settings pages by intent (rulebook), then the row by its text. */
    Result settings(String page, String row) {
        task = "open settings " + page + (row == null ? "" : " / " + row);
        try {
            Rulebook.App s = Rulebook.known("com.android.tv.settings");
            String action = s == null || s.j.optJSONObject("intents") == null ? "android.settings.SETTINGS" : s.j.optJSONObject("intents").optString(page, "android.settings.SETTINGS");
            step("Opening " + page + " settings");
            d.shell("am start -a " + action + " 2>&1");
            Thread.sleep(1500);
            if (row == null) { res.say = "Opened " + page + " settings."; return finish(true); }
            Obs o = settle(3000, false);
            List<Ui.Hit> h = Ui.find(o.s, row, 0.75);
            if (!h.isEmpty() && select(o, h.get(0).node, row)) { res.say = "Opened " + row + "."; return finish(true); }
            return generic("in Settings, open " + row, null);
        } catch (Exception e) {
            res.say = Net.explain(e);
            return finish(false);
        }
    }

    // MARK: - helpers

    void launch(String pkg) throws Exception {
        Rulebook.App a = Rulebook.known(pkg);
        String comp = a == null ? "" : a.j.optString("component");
        if (!comp.isEmpty()) d.shell("am start -n " + Ui.q(comp));
        else d.shell("monkey -p " + Ui.q(pkg) + " -c android.intent.category.LEANBACK_LAUNCHER 1");
    }

    static Ui.Node refind(Ui.Snapshot s, Ui.Node t) {
        if (t == null || s == null) return null;
        Ui.Node best = null;
        double bestScore = 0;
        String lbl = t.label();
        for (Ui.Node n : s.nodes) {
            double sc = 0;
            if (!lbl.isEmpty() && n.label().equals(lbl)) sc += 2;
            if (!t.resId.isEmpty() && n.resId.equals(t.resId)) sc += 0.6;
            if (n.cls.equals(t.cls)) sc += 0.3;
            if (Math.abs(n.w() - t.w()) < 8 && Math.abs(n.h() - t.h()) < 8) sc += 0.4;
            if (Math.abs(n.l - t.l) < 8 && Math.abs(n.t - t.t) < 8) sc += 0.8;
            if (lbl.isEmpty() && sc < 1.4) continue;
            if (!lbl.isEmpty() && sc < 2) continue;
            if (sc > bestScore) { bestScore = sc; best = n; }
        }
        return best;
    }

    static boolean sameNode(Ui.Node a, Ui.Node b) {
        if (a == null || b == null) return a == b;
        return a.l == b.l && a.t == b.t && a.r == b.r && a.b == b.b && a.label().equals(b.label());
    }

    static boolean inside(Ui.Node in, Ui.Node out) { return in.l >= out.l - 4 && in.r <= out.r + 4 && in.t >= out.t - 4 && in.b <= out.b + 4; }

    private static Ui.Snapshot singleton(Ui.Snapshot s, Ui.Node n) {
        Ui.Snapshot o = new Ui.Snapshot();
        o.width = s.width; o.height = s.height;
        o.nodes.add(n);
        return o;
    }

    static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    static String se(Goal g) { return (g.season != null ? "season " + g.season : "") + (g.season != null && g.episode != null ? " " : "") + (g.episode != null ? "episode " + g.episode : ""); }

    String label(String pkg) { Rulebook.App a = Rulebook.known(pkg); return a != null ? a.label : pkg; }

    String labels(List<String> pkgs) {
        List<String> l = new ArrayList<>();
        for (String p : pkgs) l.add(label(p));
        if (l.size() <= 1) return String.join("", l);
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    private void check() throws InterruptedException, Stop {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (System.currentTimeMillis() - started > cfg.deadlineMs) { res.say = "That was taking too long, so I stopped."; throw new Stop(res); }
    }

    private String curApp = "", curScreen = "";

    void step(String s) {
        if (ui != null) ui.status(curApp, curScreen, s);
        log(s);
    }

    void trace(String s) {
        res.trace.add(s);
        log("\u2192 " + s);
    }

    private String lastTrace(int n) {
        if (res.trace.isEmpty()) return "nothing yet";
        return String.join("; ", res.trace.subList(Math.max(0, res.trace.size() - n), res.trace.size()));
    }

    static void log(String s) { Log.w("driver: " + s); }

    private Result finish(boolean ok) {
        res.success = ok;
        res.ms = System.currentTimeMillis() - started;
        try {
            String fg = d.foreground();
            res.app = fg == null ? "" : label(fg);
        } catch (Exception ignored) {}
        log((ok ? "DONE" : "STOPPED") + " in " + res.ms + " ms, " + calls + " model calls: " + res.say);
        try { Learn.event("result", new JSONObject().put("task", task).put("ok", ok).put("say", res.say).put("ms", res.ms).put("calls", calls).put("trace", new org.json.JSONArray(res.trace))); } catch (Exception ignored) {}
        return res;
    }
}
