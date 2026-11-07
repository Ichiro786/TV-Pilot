package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decision model (Liquid d1-ultra, or the user's own d1 on the VM) answering typed questions about the CURRENT
 * SCREEN: the screenshot goes in the top-level "images" array (Liquid's documented format: base64 data URLs, JPEG,
 * up to 8 images, < 4.5 MB, <= 10,000 32x32 patches). Before 1.2.0 the app sent d1 only {"command","app_on_screen"}:
 * it never saw the TV. Pure Java (no Android), so the real-API tests run this exact code.
 */
final class Decider {
    static final String LIQUID = "https://api.liquid.ai/decisions/v1/systemone";

    static final class Config {
        String liquidKey = "", model = "d1-ultra", liquidUrl = LIQUID;
        /** The user's own d1 server (null: none). */
        String vmUrl, vmKey;
        boolean preferVm;
    }

    static final class Answer {
        final JSONObject answers;
        final long ms;
        final String by;
        final int tokens;
        Answer(JSONObject a, long ms, String by, int tokens) { answers = a; this.ms = ms; this.by = by; this.tokens = tokens; }
        String choice(String q) { JSONObject o = answers.optJSONObject(q); return o == null ? null : o.optString("choice", null); }
        double conf(String q) {
            JSONObject o = answers.optJSONObject(q);
            if (o == null) return 0;
            JSONObject p = o.optJSONObject("probabilities");
            String c = o.optString("choice", null);
            if (p != null && c != null) return p.optDouble(c, o.optDouble("confidence", 0));
            return o.optDouble("confidence", 0);
        }
        double noul(String q) { JSONObject o = answers.optJSONObject(q); return o == null ? 0.5 : o.optDouble("noul", 0.5); }
        /** Options ranked by probability. */
        List<String> ranked(String q) {
            List<String> out = new ArrayList<>();
            JSONObject o = answers.optJSONObject(q);
            JSONObject p = o == null ? null : o.optJSONObject("probabilities");
            if (p == null) { if (choice(q) != null) out.add(choice(q)); return out; }
            for (Iterator<String> it = p.keys(); it.hasNext(); ) out.add(it.next());
            out.sort((a, b) -> Double.compare(p.optDouble(b), p.optDouble(a)));
            return out;
        }
        double prob(String q, String option) {
            JSONObject o = answers.optJSONObject(q);
            JSONObject p = o == null ? null : o.optJSONObject("probabilities");
            return p == null ? 0 : p.optDouble(option, 0);
        }
    }

    /** Tests only: where to write the last request body. */
    static String DEBUG_DUMP;
    static int dumpN;
    private final Config cfg;
    volatile long lastMs;
    volatile String lastError;
    private volatile long liquidDownUntil;

    Decider(Config c) { cfg = c; }

    boolean available() { return !cfg.liquidKey.isEmpty() || cfg.vmUrl != null; }

    String name() { return !cfg.liquidKey.isEmpty() && !cfg.preferVm ? cfg.model : "d1 (your server)"; }

    static JSONObject choice(String instructions, Map<String, String> criteria) throws Exception {
        return new JSONObject().put("type", "choice").put("instructions", instructions).put("criteria", new JSONObject(criteria));
    }

    static JSONObject noul(String instructions) throws Exception {
        return new JSONObject().put("type", "noul").put("instructions", instructions);
    }

    static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String dataUrl(byte[] jpeg) { return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg); }

    /** state: text or JSON; images: JPEGs (in order); questions: name -> question. */
    Answer ask(Object state, List<byte[]> images, JSONObject questions, int timeoutMs) throws Exception {
        JSONObject body = new JSONObject().put("state", state).put("questions", questions);
        if (images != null && !images.isEmpty()) {
            JSONArray imgs = new JSONArray();
            for (byte[] b : images) if (b != null) imgs.put(dataUrl(b));
            body.put("images", imgs);
        }
        long t0 = System.currentTimeMillis();
        Exception liquidFail = null;
        if (DEBUG_DUMP != null) try { java.nio.file.Files.write(java.nio.file.Paths.get(DEBUG_DUMP + (dumpN++) + ".json"), body.toString().getBytes("UTF-8")); } catch (Exception ignored) {}
        if (!cfg.liquidKey.isEmpty() && !cfg.preferVm && System.currentTimeMillis() > liquidDownUntil) {
            try {
                JSONObject lb = new JSONObject(body.toString()).put("model", cfg.model);
                Net.Response r = Net.postJson(cfg.liquidUrl, lb.toString(), new String[][] {{"Authorization", "Bearer " + cfg.liquidKey}}, 6000, timeoutMs, "Liquid");
                if (r.code != 200) throw Net.http(r.code, r.body, "Liquid");
                JSONObject res = new JSONObject(r.body);
                lastMs = System.currentTimeMillis() - t0;
                JSONObject u = res.optJSONObject("usage");
                return new Answer(res.getJSONObject("answers"), lastMs, cfg.model, u == null ? 0 : u.optInt("input_tokens"));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                liquidFail = e;
                lastError = Net.explain(e);
                int code = e instanceof Net.Fail ? ((Net.Fail) e).code : 0;
                if (cfg.vmUrl != null) liquidDownUntil = System.currentTimeMillis() + (code == 401 || code == 402 || code == 403 || code == 429 ? 5 * 60_000 : 20_000);
                Log.w("d1-ultra failed: " + lastError);
            }
        }
        if (cfg.vmUrl == null) {
            if (liquidFail != null) throw liquidFail;
            throw new Net.Fail("No decision model set (Liquid key or your d1 server)", 0, false, "no decider");
        }
        t0 = System.currentTimeMillis();
        Net.Response r = Net.postJson(cfg.vmUrl, body.toString(), cfg.vmKey == null || cfg.vmKey.isEmpty() ? new String[0][] : new String[][] {{"Authorization", "Bearer " + cfg.vmKey}}, 6000, Math.max(timeoutMs, 20_000), "Your d1 server");
        if (r.code == 400 && images != null && !images.isEmpty()) {   // an older server without image support: text only
            body.remove("images");
            r = Net.postJson(cfg.vmUrl, body.toString(), cfg.vmKey == null || cfg.vmKey.isEmpty() ? new String[0][] : new String[][] {{"Authorization", "Bearer " + cfg.vmKey}}, 6000, Math.max(timeoutMs, 20_000), "Your d1 server");
        }
        if (r.code != 200) throw Net.http(r.code, r.body, "Your d1 server");
        lastMs = System.currentTimeMillis() - t0;
        return new Answer(new JSONObject(r.body).getJSONObject("answers"), lastMs, "d1 (your server)", 0);
    }
}
