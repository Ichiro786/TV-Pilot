package dev.syed.tvpilot.tv;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * The learn log: what each screen really looked like on this TV (compact UI dump, the screen type TV Pilot decided,
 * what it did and what happened), one JSON line per step in learn/YYYY-MM-DD.jsonl, plus a small screenshot for
 * screens the tree can't describe. Exported from the phone setup page (/learn) so the rulebook can be corrected from
 * real data. Capped at ~25 MB; the oldest files go first.
 */
final class Learn {
    private Learn() {}
    private static File dir;
    static volatile boolean on = true;
    private static final long CAP = 25L * 1024 * 1024;

    static void init(File base) {
        dir = new File(base, "learn");
        if (!dir.exists() && !dir.mkdirs()) dir = null;
    }

    static File dir() { return dir; }

    static synchronized void step(String task, Ui.Snapshot s, Ui.Screen screen, String action, String outcome, byte[] thumb) {
        if (!on || dir == null) return;
        try {
            JSONObject o = new JSONObject().put("t", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                    .put("task", task).put("pkg", s == null ? "" : s.pkg).put("activity", s == null ? "" : s.activity)
                    .put("source", s == null ? "" : s.source).put("screen", screen == null ? "" : screen.toString())
                    .put("action", action).put("outcome", outcome);
            if (s != null) {
                o.put("sig", s.signature());
                o.put("nodes", Ui.compact(s, 70));
            }
            if (thumb != null && (s == null || !s.usable())) {
                String name = System.currentTimeMillis() + ".jpg";
                File img = new File(dir, name);
                try (FileOutputStream f = new FileOutputStream(img)) { f.write(thumb); }
                o.put("image", name);
            }
            append(new File(dir, new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".jsonl"), o.toString() + "\n");
        } catch (Exception e) {
            Log.w("learn log: " + e.getMessage());
        }
    }

    static synchronized void event(String kind, JSONObject data) {
        if (dir == null) return;
        try {
            data.put("kind", kind).put("t", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            append(new File(dir, new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".jsonl"), data.toString() + "\n");
        } catch (Exception ignored) {}
    }

    private static void append(File f, String line) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, true)) { out.write(line.getBytes(StandardCharsets.UTF_8)); }
        trim();
    }

    private static long lastTrim;

    private static void trim() {
        if (System.currentTimeMillis() - lastTrim < 60_000) return;
        lastTrim = System.currentTimeMillis();
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= CAP) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : fs) { if (total <= CAP * 0.8) break; total -= f.length(); f.delete(); }
    }

    static synchronized String stats() {
        if (dir == null) return "off";
        File[] fs = dir.listFiles();
        if (fs == null) return "empty";
        long total = 0;
        int lines = 0, imgs = 0;
        for (File f : fs) {
            total += f.length();
            if (f.getName().endsWith(".jpg")) imgs++;
            else try { lines += new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n").length; } catch (Exception ignored) {}
        }
        return lines + " steps, " + imgs + " screenshots, " + (total / 1024) + " KB";
    }

    static synchronized void clear() {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
