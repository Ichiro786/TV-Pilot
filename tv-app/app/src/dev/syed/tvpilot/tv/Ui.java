package dev.syed.tvpilot.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What's on the TV, as data: the UI tree (from the accessibility service, or a uiautomator dump), plus a classifier
 * that names the screen (player, search keyboard, title page, episode list, profile picker, rows, dialog...) from the
 * generic Android TV / Leanback / Compose-for-TV patterns. Pure Java so the JVM tests run exactly this code.
 */
final class Ui {
    private Ui() {}

    static final class Node {
        int idx, parent = -1, depth;
        String cls = "", text = "", desc = "", resId = "", pkg = "", hint = "";
        int l, t, r, b;
        boolean clickable, focusable, focused, selected, editable, scrollable, checked, enabled = true, visible = true;
        /** The live AccessibilityNodeInfo (Android only), for ACTION_CLICK / ACTION_SET_TEXT. */
        transient Object handle;
        /** Text of this node and its children, for nodes that have none of their own (Compose cards, tiles). */
        String deep = "";

        int cx() { return (l + r) / 2; }
        int cy() { return (t + b) / 2; }
        int w() { return r - l; }
        int h() { return b - t; }
        boolean contains(int x, int y) { return x >= l && x <= r && y >= t && y <= b; }
        String label() {
            String s = !text.trim().isEmpty() ? text.trim() : !desc.trim().isEmpty() ? desc.trim() : deep.trim();
            if (s.isEmpty() && !hint.isEmpty()) s = hint;
            return s.replaceAll("\\s+", " ");
        }
        String shortId() { int i = resId.indexOf(":id/"); return i >= 0 ? resId.substring(i + 4) : resId; }
        String simpleCls() { int i = cls.lastIndexOf('.'); return i >= 0 ? cls.substring(i + 1) : cls; }
        boolean interactive() { return (focusable || clickable || editable) && enabled && w() > 6 && h() > 6; }
        @Override public String toString() {
            return "[" + idx + "] " + simpleCls() + " '" + Net.shorten(label(), 50) + "'" + (shortId().isEmpty() ? "" : " #" + shortId())
                    + (focused ? " FOCUSED" : "") + (selected ? " selected" : "") + (editable ? " editable" : "")
                    + " @" + l + "," + t + "-" + r + "," + b;
        }
    }

    static final class Snapshot {
        final List<Node> nodes = new ArrayList<>();
        String pkg = "", activity = "", source = "none";
        int width = 1920, height = 1080;
        long at = System.currentTimeMillis();

        boolean empty() { return nodes.isEmpty(); }

        Node focused() {
            Node best = null;
            for (Node n : nodes) if (n.focused && n.visible && (best == null || n.depth >= best.depth)) best = n;
            return best;
        }

        /** Useful text on screen: how much the tree says (Netflix / Prime / YouTube's Cobalt say almost nothing). */
        int textCount() {
            int c = 0;
            for (Node n : nodes) if (!n.text.trim().isEmpty() || !n.desc.trim().isEmpty()) c++;
            return c;
        }

        /** Rich enough to navigate by the tree alone. */
        boolean usable() { return textCount() >= 3 && interactive().size() >= 2; }

        List<Node> interactive() {
            List<Node> out = new ArrayList<>();
            for (Node n : nodes) if (n.interactive() && n.visible && onScreen(n)) out.add(n);
            return out;
        }

        boolean onScreen(Node n) { return n.r > 0 && n.b > 0 && n.l < width && n.t < height; }

        String allText() {
            StringBuilder b = new StringBuilder();
            for (Node n : nodes) { String s = n.label(); if (!s.isEmpty() && (n.text.length() > 0 || n.desc.length() > 0)) b.append(s).append(" | "); }
            return b.toString();
        }

        /** A stable fingerprint of the screen's structure (for "did anything change" and the learn log). */
        String signature() {
            StringBuilder b = new StringBuilder(pkg).append('/').append(activity);
            int n = 0;
            for (Node x : nodes) if (!x.resId.isEmpty() && n++ < 25) b.append(';').append(x.shortId());
            return Integer.toHexString(b.toString().hashCode());
        }

        Node byIdx(int i) { return i >= 0 && i < nodes.size() ? nodes.get(i) : null; }
    }

    // MARK: - uiautomator dump

    private static final Pattern NODE = Pattern.compile("<node\\b([^>]*?)(/?)>|</node>");
    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");

    static Snapshot parseDump(String xml, int width, int height) {
        Snapshot s = new Snapshot();
        s.width = width; s.height = height;
        if (xml == null || !xml.contains("<hierarchy")) return s;
        s.source = "uiautomator";
        Matcher m = NODE.matcher(xml);
        List<Integer> stack = new ArrayList<>();
        while (m.find()) {
            if (m.group(0).startsWith("</")) { if (!stack.isEmpty()) stack.remove(stack.size() - 1); continue; }
            String a = m.group(1);
            Node n = new Node();
            n.idx = s.nodes.size();
            n.parent = stack.isEmpty() ? -1 : stack.get(stack.size() - 1);
            n.depth = stack.size();
            n.text = attr(a, "text"); n.desc = attr(a, "content-desc"); n.resId = attr(a, "resource-id");
            n.cls = attr(a, "class"); n.pkg = attr(a, "package"); n.hint = attr(a, "hint");
            n.clickable = "true".equals(attr(a, "clickable")); n.focusable = "true".equals(attr(a, "focusable"));
            n.focused = "true".equals(attr(a, "focused")); n.selected = "true".equals(attr(a, "selected"));
            n.scrollable = "true".equals(attr(a, "scrollable")); n.checked = "true".equals(attr(a, "checked"));
            n.enabled = !"false".equals(attr(a, "enabled"));
            n.editable = n.cls.contains("EditText");
            Matcher b = BOUNDS.matcher(attr(a, "bounds"));
            if (b.find()) { n.l = Integer.parseInt(b.group(1)); n.t = Integer.parseInt(b.group(2)); n.r = Integer.parseInt(b.group(3)); n.b = Integer.parseInt(b.group(4)); }
            s.nodes.add(n);
            if (s.pkg.isEmpty() && !n.pkg.isEmpty()) s.pkg = n.pkg;
            if (m.group(2).isEmpty()) stack.add(n.idx);
        }
        fillDeep(s);
        return s;
    }

    /** Gives text-less containers (cards, tiles) the text of their children, so "the tile called X" can be found. */
    static void fillDeep(Snapshot s) {
        for (int i = s.nodes.size() - 1; i >= 0; i--) {
            Node n = s.nodes.get(i);
            String own = !n.text.trim().isEmpty() ? n.text.trim() : n.desc.trim();
            String acc = own.isEmpty() ? n.deep : own;
            if (n.parent >= 0 && !acc.isEmpty()) {
                Node p = s.nodes.get(n.parent);
                if (p.deep.length() < 120) p.deep = p.deep.isEmpty() ? acc : acc + " \u00B7 " + p.deep;
            }
        }
        // children were visited in reverse, so their order in deep is reversed; flip each list back
        for (Node n : s.nodes) if (n.deep.contains(" \u00B7 ")) {
            List<String> parts = new ArrayList<>(Arrays.asList(n.deep.split(" \u00B7 ")));
            java.util.Collections.reverse(parts);
            n.deep = String.join(" \u00B7 ", parts);
        }
    }

    static String attr(String a, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(a);
        if (!m.find()) return "";
        return m.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", " ");
    }

    // MARK: - Matching titles

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replace('&', ' ').replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** 0..1: how well an on-screen label matches the wanted title (exact, contained, word overlap, close spelling). */
    static double match(String label, String want) {
        String h = norm(label), w = norm(want);
        if (h.isEmpty() || w.isEmpty()) return 0;
        if (h.equals(w)) return 1;
        String h2 = h.startsWith("the ") ? h.substring(4) : h, w2 = w.startsWith("the ") ? w.substring(4) : w;
        if (h2.equals(w2)) return 0.97;
        if (h.startsWith(w + " season") || h.startsWith(w + " s ") || h.startsWith(w + " part")) return 0.95;
        if (h.startsWith(w + " ")) return 0.8;   // "crown for christmas" is not "crown"
        if (h.contains(w)) return Math.max(0.6, 0.92 - (h.length() - w.length()) / 300.0);
        if (w.contains(h) && h.length() >= 4) return 0.55 + 0.3 * h.length() / w.length();
        Set<String> ww = words(w), hw = words(h);
        if (ww.isEmpty()) return 0;
        int c = 0;
        for (String x : ww) { if (hw.contains(x)) c++; else for (String y : hw) if (x.length() > 4 && lev(x, y) <= 1) { c++; break; } }
        double overlap = c / (double) ww.size();
        double sim = 1 - lev(h.length() > 60 ? h.substring(0, 60) : h, w) / (double) Math.max(h.length(), w.length());
        return Math.max(overlap * 0.8, sim > 0.8 ? sim * 0.9 : 0);
    }

    private static Set<String> words(String s) {
        Set<String> o = new HashSet<>();
        for (String x : s.split(" ")) if (x.length() > 1 && !STOP.contains(x)) o.add(x);
        return o;
    }
    private static final Set<String> STOP = new HashSet<>(Arrays.asList("the", "a", "an", "of", "and", "ka", "ki", "ke"));

    static int lev(String a, String b) {
        int[] p = new int[b.length() + 1], c = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            c[0] = i;
            for (int j = 1; j <= b.length(); j++) c[j] = Math.min(Math.min(c[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p; p = c; c = t;
        }
        return p[b.length()];
    }

    static final class Hit {
        final Node node; final double score;
        Hit(Node n, double s) { node = n; score = s; }
    }

    /** Interactive nodes (or the interactive parent of a text node) ranked by how well they match. */
    static List<Hit> find(Snapshot s, String want, double min) {
        List<Hit> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (Node n : s.nodes) {
            if (!n.visible || !s.onScreen(n) || n.editable) continue;
            String own = !n.text.trim().isEmpty() ? n.text : n.desc;
            double sc = Math.max(match(own, want), n.interactive() ? match(n.deep, want) * 0.97 : 0);
            if (sc < min) continue;
            Node target = n.interactive() ? n : interactiveAncestor(s, n);
            if (target == null) target = n;
            if (!used.add(target.idx)) {
                for (int i = 0; i < out.size(); i++) if (out.get(i).node == target && out.get(i).score < sc) out.set(i, new Hit(target, sc));
                continue;
            }
            out.add(new Hit(target, sc));
        }
        out.sort((x, y) -> Double.compare(y.score, x.score));
        return out;
    }

    static Node interactiveAncestor(Snapshot s, Node n) {
        int p = n.parent, hops = 0;
        while (p >= 0 && hops++ < 6) {
            Node x = s.nodes.get(p);
            if (x.interactive()) return x;
            p = x.parent;
        }
        return null;
    }

    /** First node whose label matches any of the words exactly or as a prefix (buttons: Play, Resume, Search...). */
    static Node button(Snapshot s, List<String> labels) {
        if (labels == null) return null;
        for (int pass = 0; pass < 2; pass++)
            for (String want : labels) {
                String w = norm(want);
                for (Node n : s.nodes) {
                    if (!n.visible || !s.onScreen(n)) continue;
                    String h = norm(!n.text.isEmpty() ? n.text : !n.desc.isEmpty() ? n.desc : n.interactive() ? n.deep : "");
                    if (h.isEmpty()) continue;
                    boolean ok = pass == 0 ? h.equals(w) : (h.startsWith(w + " ") && h.length() <= w.length() + 22);
                    if (!ok) continue;
                    Node t = n.interactive() ? n : interactiveAncestor(s, n);
                    return t != null ? t : n;
                }
            }
        return null;
    }

    // MARK: - Naming the screen

    enum Kind { PLAYER, KEYBOARD, SEARCH_RESULTS, DETAIL, EPISODES, PROFILE, DIALOG, LOGIN, RAIL, BROWSE, LOADING, UNKNOWN }

    static final class Screen {
        Kind kind = Kind.UNKNOWN;
        double confidence;
        boolean rail;        // a left navigation rail / drawer is present
        boolean railFocused; // the focus is in it
        int rows;            // horizontal rows of tiles
        String why = "";
        @Override public String toString() { return kind + (rail ? "+rail" : "") + (railFocused ? "(rail focused)" : "") + (rows > 0 ? " rows=" + rows : "") + " " + why; }
    }

    static final List<String> PLAY_WORDS = Arrays.asList("play", "resume", "continue watching", "continue", "watch now", "watch",
            "start watching", "play episode", "watch trailer", "start over", "play from beginning", "watch free", "dekhein", "abhi dekhein");

    /** From the tree only. confidence < 0.5 means "ask the decision model with the screenshot". */
    static Screen classify(Snapshot s, Rulebook.App app) {
        Screen sc = new Screen();
        if (s.empty()) { sc.why = "no UI tree"; return sc; }
        String all = norm(s.allText());
        Node f = s.focused();
        // App-specific signatures from the rulebook come first.
        if (app != null) {
            Kind k = app.signatureMatch(s);
            if (k != null) { sc.kind = k; sc.confidence = 0.9; sc.why = "rulebook signature"; railCheck(s, sc, f); return sc; }
        }
        railCheck(s, sc, f);
        int seek = 0, keys = 0, single = 0;
        boolean edit = false, editFocused = false;
        for (Node n : s.nodes) {
            String c = n.cls.toLowerCase(Locale.US), id = n.resId.toLowerCase(Locale.US);
            if (c.contains("seekbar") || id.contains("seek") || id.contains("progress_bar") || id.contains("scrubber") || id.contains("timebar")) seek++;
            if (n.editable) { edit = true; if (n.focused) editFocused = true; }
            String lbl = n.label();
            if (lbl.length() == 1 && Character.isLetterOrDigit(lbl.charAt(0)) && n.interactive()) single++;
            if (id.contains("keyboard") || id.contains("key_") || id.contains("keyrow")) keys++;
        }
        if (single >= 20 || keys >= 10) { sc.kind = Kind.KEYBOARD; sc.confidence = 0.85; sc.why = single + " letter keys"; return sc; }
        if (editFocused || (edit && all.contains("search"))) { sc.kind = Kind.KEYBOARD; sc.confidence = 0.75; sc.why = "search box"; return sc; }
        if (has(all, "who s watching", "who is watching", "choose profile", "select profile", "switch profile", "kaun dekh raha")) { sc.kind = Kind.PROFILE; sc.confidence = 0.85; sc.why = "profile words"; return sc; }
        if (has(all, "sign in", "log in", "enter mobile number", "enter your mobile", "verify otp", "activation code", "scan the qr", "scan qr")
                && s.interactive().size() < 12) { sc.kind = Kind.LOGIN; sc.confidence = 0.75; sc.why = "login words"; return sc; }
        if (seek > 0 && s.interactive().size() < 14) { sc.kind = Kind.PLAYER; sc.confidence = 0.8; sc.why = "seek bar"; return sc; }
        if (has(all, "skip intro", "skip recap", "skip ad", "next episode in", "up next")) { sc.kind = Kind.PLAYER; sc.confidence = 0.7; sc.why = "player overlay words"; return sc; }
        boolean seasons = Pattern.compile("\\b(season|seasons|s\\d{1,2})\\b").matcher(all).find();
        boolean episodes = Pattern.compile("\\b(episode|episodes|e\\d{1,3}|ep \\d)\\b").matcher(all).find();
        Node play = button(s, PLAY_WORDS);
        if (episodes && seasons && countMatching(s, "\\b(episode|ep|e)\\s?\\d") >= 3) { sc.kind = Kind.EPISODES; sc.confidence = 0.7; sc.why = "episode list"; return sc; }
        if (play != null && play.interactive()) { sc.kind = Kind.DETAIL; sc.confidence = seasons || episodes || has(all, "add to", "watchlist", "my list", "trailer", "more like this") ? 0.85 : 0.6; sc.why = "play button '" + play.label() + "'"; return sc; }
        List<Node> dialogs = new ArrayList<>();
        for (Node n : s.nodes) if (n.cls.contains("AlertDialog") || n.resId.contains("dialog") || n.resId.endsWith("/alertTitle")) dialogs.add(n);
        if (!dialogs.isEmpty() || (s.interactive().size() <= 4 && has(all, "ok", "cancel", "yes", "no", "exit", "not now", "later", "allow", "deny"))) {
            sc.kind = Kind.DIALOG; sc.confidence = 0.65; sc.why = "dialog"; return sc;
        }
        sc.rows = rows(s);
        if (has(all, "results for", "top results", "search results") || (edit && sc.rows > 0)) { sc.kind = Kind.SEARCH_RESULTS; sc.confidence = 0.65; sc.why = "results"; return sc; }
        if (sc.railFocused) { sc.kind = Kind.RAIL; sc.confidence = 0.7; sc.why = "focus in nav rail"; return sc; }
        if (sc.rows >= 2) { sc.kind = Kind.BROWSE; sc.confidence = 0.7; sc.why = "rows of tiles"; return sc; }
        if (s.interactive().isEmpty()) { sc.kind = Kind.LOADING; sc.confidence = 0.4; sc.why = "nothing focusable"; return sc; }
        sc.why = "no pattern";
        sc.confidence = 0.3;
        return sc;
    }

    private static void railCheck(Snapshot s, Screen sc, Node f) {
        // A rail: 3+ small focusable items stacked in a column hugging the left edge (Leanback headers, Compose NavigationDrawer).
        List<Node> col = new ArrayList<>();
        for (Node n : s.interactive()) if (n.l < s.width * 0.06 + 40 && n.r < s.width * 0.3 && n.h() < s.height * 0.2) col.add(n);
        if (col.size() >= 3) {
            sc.rail = true;
            if (f != null) for (Node n : col) if (n == f || (f.l >= n.l && f.r <= n.r + 4 && f.t >= n.t && f.b <= n.b + 4) || (n.l <= f.cx() && f.cx() <= n.r && n.t <= f.cy() && f.cy() <= n.b)) sc.railFocused = true;
        }
    }

    private static int countMatching(Snapshot s, String re) {
        Pattern p = Pattern.compile(re);
        int c = 0;
        for (Node n : s.nodes) if (p.matcher(norm(n.label())).find()) c++;
        return c;
    }

    /** How many horizontal rows of similar tiles: tiles grouped by their top edge. */
    static int rows(Snapshot s) {
        List<Node> tiles = new ArrayList<>();
        for (Node n : s.interactive()) if (n.w() > s.width * 0.06 && n.w() < s.width * 0.5 && n.h() > s.height * 0.06 && n.h() < s.height * 0.6 && n.l > s.width * 0.05) tiles.add(n);
        List<int[]> groups = new ArrayList<>();   // {top, count}
        for (Node n : tiles) {
            boolean put = false;
            for (int[] g : groups) if (Math.abs(g[0] - n.t) < s.height * 0.03) { g[1]++; put = true; break; }
            if (!put) groups.add(new int[] {n.t, 1});
        }
        int r = 0;
        for (int[] g : groups) if (g[1] >= 3) r++;
        return r;
    }

    private static boolean has(String all, String... words) {
        for (String w : words) if (Pattern.compile("(^|\\W)" + Pattern.quote(w) + "(\\W|$)").matcher(all).find()) return true;
        return false;
    }

    /** Mean brightness difference between two 64x36 signatures (0 = identical). */
    static double diff(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return 99;
        long s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / (double) a.length;
    }

    /** Shell-quotes a string. */
    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    // MARK: - Season / episode labels

    static Node seasonNode(Snapshot s, int season) {
        Pattern p = Pattern.compile("\\b(season\\s*" + season + "|s\\s?0?" + season + "|series\\s*" + season + "|seizoen\\s*" + season + ")\\b");
        return firstMatching(s, p);
    }

    static Node episodeNode(Snapshot s, int ep) {
        Pattern p = Pattern.compile("(\\b(episode|ep|e)\\s?0?" + ep + "\\b)|(^0?" + ep + "\\s)|(\\bs\\d{1,2}\\s?e0?" + ep + "\\b)");
        return firstMatching(s, p);
    }

    private static Node firstMatching(Snapshot s, Pattern p) {
        for (Node n : s.nodes) {
            if (!n.visible || !s.onScreen(n)) continue;
            String own = norm(!n.text.isEmpty() ? n.text : n.desc);
            if (own.isEmpty() || !p.matcher(own).find()) continue;
            Node t = n.interactive() ? n : interactiveAncestor(s, n);
            return t != null ? t : n;
        }
        return null;
    }

    // MARK: - For the decision model: the elements, numbered

    /** The elements worth numbering on the screenshot (set-of-marks), most useful first, at most max. */
    static List<Node> marks(Snapshot s, int max) {
        List<Node> in = new ArrayList<>();
        for (Node n : s.interactive()) {
            if (n.w() >= s.width * 0.95 && n.h() >= s.height * 0.9) continue;   // whole-screen containers
            boolean dup = false;
            for (Node o : in) if (Math.abs(o.l - n.l) < 6 && Math.abs(o.t - n.t) < 6 && Math.abs(o.r - n.r) < 6 && Math.abs(o.b - n.b) < 6) { dup = true; break; }
            if (!dup) in.add(n);
        }
        // focused first, then labelled, then by reading order
        in.sort((a, b) -> {
            if (a.focused != b.focused) return a.focused ? -1 : 1;
            boolean la = !a.label().isEmpty(), lb = !b.label().isEmpty();
            if (la != lb) return la ? -1 : 1;
            return a.t / 40 != b.t / 40 ? Integer.compare(a.t, b.t) : Integer.compare(a.l, b.l);
        });
        return in.size() > max ? new ArrayList<>(in.subList(0, max)) : in;
    }

    /** One line per mark for the model's state: "m3: button 'Play' (focused), middle-left". */
    static String describe(Node n, int i, Snapshot s) {
        String where = (n.cy() < s.height / 3 ? "top" : n.cy() > s.height * 2 / 3 ? "bottom" : "middle") + "-"
                + (n.cx() < s.width / 3 ? "left" : n.cx() > s.width * 2 / 3 ? "right" : "center");
        String lbl = n.label();
        return "m" + i + ": " + (n.editable ? "text box" : n.simpleCls().toLowerCase(Locale.US).replace("view", "").replace("layout", " group"))
                + (lbl.isEmpty() ? " (no text)" : " '" + Net.shorten(lbl, 60) + "'")
                + (n.shortId().isEmpty() ? "" : " id=" + n.shortId()) + (n.focused ? " [FOCUSED]" : "") + (n.selected ? " [selected]" : "") + ", " + where;
    }

    /** A compact dump for the learn log. */
    static String compact(Snapshot s, int max) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Node x : s.nodes) {
            if (x.label().isEmpty() && !x.interactive() && x.resId.isEmpty()) continue;
            if (n++ >= max) break;
            b.append(x).append('\n');
        }
        return b.toString();
    }
}
