package dev.syed.tvpilot.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV controlling itself: the same moves as the phone app (keys, launching, deep links, reading and tapping the
 * screen, screenshots), sent to the TV's own debugging port.
 */
final class Tv {
    enum Key {
        PLAY_PAUSE(85, "Play / pause"), BACK(4, "Back"), HOME(3, "Home"), UP(19, "Up"), DOWN(20, "Down"),
        LEFT(21, "Left"), RIGHT(22, "Right"), OK(23, "OK"), NEXT(87, "Next"), PREVIOUS(88, "Previous"),
        FAST_FORWARD(90, "Fast forward"), REWIND(89, "Rewind"), VOLUME_UP(24, "Volume up"),
        VOLUME_DOWN(25, "Volume down"), MUTE(164, "Mute"), SLEEP(223, "Turn off"), WAKE(224, "Turn on");
        final int code;
        final String label;
        Key(int code, String label) { this.code = code; this.label = label; }

        static Key named(String s) {
            for (Key k : values()) if (k.name().equalsIgnoreCase(s)) return k;
            return null;
        }
    }

    static final class App {
        final String pkg, label, searchUrl, component;
        final List<String> aliases;
        App(String pkg, String label, List<String> aliases, String searchUrl, String component) {
            this.pkg = pkg; this.label = label; this.aliases = aliases; this.searchUrl = searchUrl; this.component = component;
        }
        App(String pkg, String label, String... aliases) { this(pkg, label, Arrays.asList(aliases), null, null); }
    }

    static final List<App> KNOWN = Arrays.asList(
            new App("io.gh.reisxd.tizentube.cobalt", "TizenTube", Arrays.asList("tizentube", "tizen tube", "youtube", "yt"),
                    "https://www.youtube.com/results?search_query=%s", "io.gh.reisxd.tizentube.cobalt/dev.cobalt.app.MainActivity"),
            new App("com.google.android.youtube.tv", "YouTube", Arrays.asList("youtube", "yt"), "https://www.youtube.com/results?search_query=%s", null),
            new App("com.google.android.youtube.tvmusic", "YouTube Music", "youtube music", "yt music"),
            new App("com.netflix.ninja", "Netflix", Collections.singletonList("netflix"), "https://www.netflix.com/search?q=%s", null),
            new App("com.amazon.amazonvideo.livingroom", "Prime Video", "prime video", "prime", "amazon"),
            new App("in.startv.hotstar", "JioHotstar", "jiohotstar", "jio hotstar", "hotstar"),
            new App("in.startv.hotstartv", "JioHotstar", "jiohotstar", "jio hotstar", "hotstar"),
            new App("com.spotify.tv.android", "Spotify", Collections.singletonList("spotify"), "spotify:search:%s", null),
            new App("com.crunchyroll.crunchyroid", "Crunchyroll", Arrays.asList("crunchyroll", "crunchy"), "https://www.crunchyroll.com/search?q=%s", null),
            new App("com.sonyliv", "SonyLIV", "sonyliv", "sony liv"),
            new App("com.graymatrix.did", "ZEE5", "zee5", "zee 5"),
            new App("com.plexapp.android", "Plex", "plex"),
            new App("org.videolan.vlc", "VLC", "vlc"),
            new App("org.xbmc.kodi", "Kodi", "kodi"),
            new App("com.stremio.one", "Stremio", "stremio"),
            new App("com.google.android.apps.tv.launcherx", "Google TV home", "google tv", "home screen"));

    private static Tv instance;
    private final Context app;
    private Adb adb;
    private List<App> apps;
    private int[] display;

    static synchronized Tv get(Context c) {
        if (instance == null) instance = new Tv(c.getApplicationContext());
        return instance;
    }

    private Tv(Context c) { app = c; }

    // MARK: - Shell

    /** Runs a shell command on this TV, reconnecting once if the link dropped. */
    synchronized String shell(String cmd) throws Exception {
        try {
            if (adb == null) adb = Adb.connect(app, 40_000, null);
            return adb.shell(cmd).trim();
        } catch (Exception e) {
            if (adb != null) adb.close();
            adb = Adb.connect(app, 40_000, null);
            return adb.shell(cmd).trim();
        }
    }

    private synchronized byte[] exec(String cmd) throws Exception {
        try {
            if (adb == null) adb = Adb.connect(app, 40_000, null);
            return adb.exec(cmd);
        } catch (Exception e) {
            if (adb != null) adb.close();
            adb = Adb.connect(app, 40_000, null);
            return adb.exec(cmd);
        }
    }

    // MARK: - Apps

    /** The apps with a TV launcher icon, named the way people say them. Cached after the first look. */
    synchronized List<App> apps() throws Exception {
        if (apps != null) return apps;
        String out = shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER");
        Set<String> installed = new HashSet<>();
        for (String l : out.split("\n")) if (l.contains("/")) installed.add(l.trim().substring(0, l.trim().indexOf('/')));
        if (installed.isEmpty()) for (String l : shell("pm list packages -3").split("\n")) if (l.startsWith("package:")) installed.add(l.substring(8).trim());
        installed.remove(Setup.PKG);
        List<App> here = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        boolean tizen = installed.contains("io.gh.reisxd.tizentube.cobalt");
        for (App a : KNOWN) {
            if (!installed.contains(a.pkg) || !labels.add(a.label)) continue;
            // With TizenTube installed, "YouTube" means TizenTube; the official app answers only to "official YouTube".
            if (tizen && a.pkg.equals("com.google.android.youtube.tv"))
                a = new App(a.pkg, "YouTube (official)", Arrays.asList("official youtube", "youtube official", "google youtube"), a.searchUrl, null);
            here.add(a);
        }
        List<App> others = new ArrayList<>();
        Set<String> generic = new HashSet<>(Arrays.asList("tv", "app", "android", "androidtv", "atv", "leanback", "mobile", "client", "player", "one", "ninja"));
        Set<String> tld = new HashSet<>(Arrays.asList("com", "org", "net", "in", "io", "dev"));
        for (String pkg : installed) {
            boolean known = false;
            for (App a : KNOWN) if (a.pkg.equals(pkg)) known = true;
            if (known || pkg.startsWith("com.android.") || pkg.startsWith("android") || pkg.contains(".overlay")) continue;
            String[] parts = pkg.split("\\.");
            String raw = parts[parts.length - 1];
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!generic.contains(parts[i].toLowerCase(Locale.US)) && !tld.contains(parts[i])) { raw = parts[i]; break; }
            }
            String name = raw.substring(0, 1).toUpperCase(Locale.US) + raw.substring(1);
            others.add(new App(pkg, name, name.toLowerCase(Locale.US)));
        }
        Collections.sort(others, (x, y) -> x.label.compareToIgnoreCase(y.label));
        here.addAll(others);
        apps = here;
        Log.w("apps: " + here.size());
        return apps;
    }

    App byPkg(String pkg) throws Exception {
        if (pkg == null) return null;
        for (App a : apps()) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    String foreground() {
        try {
            String out = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2");
            Matcher m = Pattern.compile("([a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+)/").matcher(out);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    void waitForApp(String pkg, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (pkg.equals(foreground())) break;
            Thread.sleep(350);
        }
        Thread.sleep(1500);
    }

    void launch(App a) throws Exception {
        if (a.component != null) shell("am start -n " + q(a.component));
        else shell("monkey -p " + q(a.pkg) + " -c android.intent.category.LEANBACK_LAUNCHER 1");
    }

    /** Deep-link search. Never uses Google's global search (it pops up the Assistant). */
    void deepSearch(App a, String query, boolean autoPlayFirst) throws Exception {
        String enc = URLEncoder.encode(query, "UTF-8");
        String url = a.searchUrl.replace("%s", a.searchUrl.startsWith("spotify:") ? enc.replace("+", "%20") : enc);
        boolean wasOpen = a.pkg.equals(foreground());
        String target = a.component != null ? "-n " + q(a.component) : q(a.pkg);
        shell("am start -a android.intent.action.VIEW -d " + q(url) + " " + target);
        if (autoPlayFirst && (a.label.startsWith("YouTube") || a.label.equals("TizenTube"))) {
            Thread.sleep(wasOpen ? 3500 : 6500);
            key(Key.OK, 1);
        }
    }

    void key(Key k, int times) throws Exception {
        int n = Math.max(1, Math.min(25, times));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : "; ").append("input keyevent ").append(k.code);
        shell(sb.toString());
    }

    void tap(int x, int y) throws Exception { shell("input tap " + x + " " + y); }

    void swipe(int x1, int y1, int x2, int y2) throws Exception { shell("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " 350"); }

    void typeText(String text) throws Exception {
        String t = text.length() > 100 ? text.substring(0, 100) : text;
        shell("input text " + q(t.replace(" ", "%s")));
    }

    // MARK: - Reading the screen

    static final class Item {
        final String text;
        final int x, y;
        Item(String text, int x, int y) { this.text = text; this.x = x; this.y = y; }
    }

    List<Item> readScreen() throws Exception {
        String xml = shell("uiautomator dump --compressed /sdcard/tvpilot.xml >/dev/null 2>&1 && cat /sdcard/tvpilot.xml; rm -f /sdcard/tvpilot.xml");
        List<Item> out = new ArrayList<>();
        if (!xml.contains("<hierarchy")) return out;
        Matcher m = Pattern.compile("<node\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String node = m.group();
            String label = attr(node, "text");
            if (label.trim().isEmpty()) label = attr(node, "content-desc");
            label = label.trim();
            if (label.isEmpty() || attr(node, "class").contains("EditText") || attr(node, "package").equals(Setup.PKG)) continue;
            Matcher b = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matcher(attr(node, "bounds"));
            if (!b.find()) continue;
            int x1 = Integer.parseInt(b.group(1)), y1 = Integer.parseInt(b.group(2)), x2 = Integer.parseInt(b.group(3)), y2 = Integer.parseInt(b.group(4));
            if (x2 > x1 && y2 > y1) out.add(new Item(label, (x1 + x2) / 2, (y1 + y2) / 2));
        }
        return out;
    }

    private static String attr(String node, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(node);
        if (!m.find()) return "";
        return m.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", " ");
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    static Item bestMatch(List<Item> items, String title, Integer avoidY) {
        String want = norm(title);
        if (want.isEmpty()) return null;
        Set<String> wantWords = new HashSet<>();
        for (String w : want.split(" ")) if (w.length() > 1) wantWords.add(w);
        Item best = null;
        double bestScore = 0.5;
        for (Item it : items) {
            if (avoidY != null && Math.abs(it.y - avoidY) <= 70) continue;
            String have = norm(it.text);
            if (have.isEmpty()) continue;
            Set<String> haveWords = new HashSet<>(Arrays.asList(have.split(" ")));
            double score;
            if (have.equals(want)) score = 1;
            else if (have.contains(want)) score = 0.9 - (have.length() - want.length()) / 400.0;
            else if (want.contains(have) && have.length() >= 5) score = 0.7;
            else {
                int c = 0;
                for (String w : wantWords) if (haveWords.contains(w)) c++;
                score = c / (double) Math.max(1, wantWords.size()) * 0.65;
            }
            if (score >= bestScore) { bestScore = score; best = it; }
        }
        return best;
    }

    /** Finds the tile whose title best matches and selects it (tap, then OK for D-pad apps). Returns its text or null. */
    String pickOnScreen(String title, int attempts, Integer avoidY, List<String> seen) throws Exception {
        for (int i = 0; i < attempts; i++) {
            List<Item> items = readScreen();
            seen.clear();
            for (Item it : items) seen.add(it.text);
            Item best = bestMatch(items, title, avoidY);
            if (best != null) {
                tap(best.x, best.y);
                Thread.sleep(700);
                key(Key.OK, 1);
                return best.text;
            }
            if (i < attempts - 1) Thread.sleep(1200);
        }
        return null;
    }

    Integer tapLabel(String... labels) throws Exception {
        List<Item> items = readScreen();
        Item hit = null;
        for (String l : labels) { for (Item it : items) if (norm(it.text).equals(l)) { hit = it; break; } if (hit != null) break; }
        if (hit == null) for (String l : labels) { for (Item it : items) if (norm(it.text).startsWith(l) && it.text.length() < 24) { hit = it; break; } if (hit != null) break; }
        if (hit == null) return null;
        tap(hit.x, hit.y);
        return hit.y;
    }

    Integer searchInsideApp(String query) throws Exception {
        Integer y = tapLabel("search", "search movies", "search movies and shows", "find");
        if (y == null) return null;
        Thread.sleep(1200);
        typeText(query);
        Thread.sleep(300);
        shell("input keyevent 66");
        Thread.sleep(2500);
        return y;
    }

    // MARK: - Screenshots for the vision model

    static final class Shot {
        final byte[] jpeg;
        final int width, height;
        final int[] sig;
        Shot(byte[] jpeg, int width, int height, int[] sig) { this.jpeg = jpeg; this.width = width; this.height = height; this.sig = sig; }
    }

    Shot screenshot(int maxWidth) throws Exception {
        byte[] png = exec("screencap -p");
        Bitmap full = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (full == null) throw new IllegalStateException("couldn't take a screenshot of the TV");
        if (display == null) {
            Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(shell("wm size"));
            int w = full.getWidth(), h = full.getHeight();
            while (m.find()) { w = Integer.parseInt(m.group(1)); h = Integer.parseInt(m.group(2)); }
            display = new int[] {w, h};
        }
        float scale = Math.min(1f, maxWidth / (float) full.getWidth());
        Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(full, (int) (full.getWidth() * scale), (int) (full.getHeight() * scale), true) : full;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 72, out);
        Bitmap tiny = Bitmap.createScaledBitmap(small, 64, 36, true);
        int[] sig = new int[64 * 36];
        for (int i = 0; i < sig.length; i++) {
            int c = tiny.getPixel(i % 64, i / 64);
            sig[i] = (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3;
        }
        tiny.recycle();
        if (small != full) small.recycle();
        full.recycle();
        return new Shot(out.toByteArray(), display[0], display[1], sig);
    }

    static double diff(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return 99;
        long s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / (double) a.length;
    }

    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    static Map<String, App> index(List<App> apps) {
        Map<String, App> m = new LinkedHashMap<>();
        for (App a : apps) m.put(a.pkg, a);
        return m;
    }
}
