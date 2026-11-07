package dev.syed.tvpilot.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * Settings -> Accuracy test: runs ~20 standard tasks (assets/rulebook/evals.json) one after another on this TV, logs
 * pass/fail with the steps and UI dumps into the learn log, and keeps a summary for Diagnostics and the phone page.
 * Tasks for apps that aren't installed are skipped. HOME between tasks. Stop by selecting the row again.
 */
final class Evals {
    private Evals() {}

    interface Progress { void line(String s); }

    static volatile Thread running;

    static boolean busy() { return running != null; }

    static void stop(Context c) { Thread t = running; running = null; if (t != null) t.interrupt(); Net.abortAll(); Tv.get(c).abort(); }

    static void start(Context c, Progress p) {
        if (running != null) return;
        final Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            StringBuilder sum = new StringBuilder();
            int pass = 0, fail = 0, skip = 0;
            long t0 = System.currentTimeMillis();
            try {
                JSONArray evals;
                try (InputStream in = app.getAssets().open("rulebook/evals.json")) { evals = new JSONObject(Net.read(in, 200_000)).getJSONArray("evals"); }
                Tv tv = Tv.get(app);
                java.util.List<Tv.App> apps = tv.apps();
                for (int i = 0; i < evals.length() && running == Thread.currentThread(); i++) {
                    JSONObject e = evals.getJSONObject(i);
                    String cmd = e.getString("command"), check = e.optString("check");
                    Tv.App named = Brain.findApp(cmd, apps);
                    boolean needsApp = cmd.matches("(?i).*\\b(on|in) (netflix|prime video|jiohotstar|crunchyroll|nuvio|youtube)\\b.*");
                    if (needsApp && named == null) { skip++; sum.append("\u2013 ").append(cmd).append(" (app not installed)\n"); continue; }
                    p.line("(" + (i + 1) + "/" + evals.length() + ") " + cmd);
                    tv.key(Tv.Key.HOME, 1);
                    Thread.sleep(2500);
                    long s0 = System.currentTimeMillis();
                    Agent.Result r;
                    try {
                        r = new Agent(app).run(cmd, st -> {}, true);
                    } catch (InterruptedException ie) {
                        throw ie;
                    } catch (Exception ex) {
                        r = new Agent.Result(Net.explain(ex), false);
                    }
                    boolean ok = r.success;
                    String fg = tv.foreground();
                    if (check.startsWith("app:")) {
                        String want = check.substring(4), f = fg == null ? "" : fg.toLowerCase(Locale.US);
                        ok = ok && (f.contains(want) || (want.equals("youtube") && f.contains("tizentube")));
                    }
                    long ms = System.currentTimeMillis() - s0;
                    if (ok) pass++; else fail++;
                    String line = (ok ? "\u2713 " : "\u2717 ") + cmd + " \u2014 " + Net.shorten(r.say, 80) + " (" + ms / 1000 + " s)";
                    sum.append(line).append('\n');
                    p.line(line);
                    Learn.event("eval", new JSONObject().put("command", cmd).put("ok", ok).put("say", r.say).put("ms", ms).put("front", fg == null ? "" : fg)
                            .put("detail", r.detail == null ? new JSONObject() : r.detail));
                }
            } catch (InterruptedException ignored) {
                sum.append("(stopped)\n");
            } catch (Exception e) {
                sum.append("\u2717 test stopped: ").append(Net.explain(e)).append('\n');
            }
            String head = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date()) + "  " + pass + " passed, " + fail + " failed, " + skip + " skipped, "
                    + (System.currentTimeMillis() - t0) / 60000 + " min";
            Prefs.lastEval(head + "\n" + sum);
            p.line("Accuracy test: " + head);
            running = null;
        }, "tvpilot-evals");
        running = t;
        t.start();
    }
}
