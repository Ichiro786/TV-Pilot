package dev.syed.tvpilot.tv;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Every network call goes through here: short connect timeouts, a hard total deadline per call (a watchdog cuts the
 * connection, so a server that accepts and then never answers can't hang a run), connections that are always closed,
 * and failures turned into one plain sentence the bar can show and the voice can say.
 */
final class Net {
    private Net() {}

    /** A failure with a sentence for the user (what broke and what to do). */
    static final class Fail extends IOException {
        final String user;
        final int code;          // HTTP status, or 0
        final boolean offline;   // couldn't reach it at all (vs. it answered with an error)
        Fail(String user, int code, boolean offline, String detail) {
            super(detail == null ? user : detail);
            this.user = user; this.code = code; this.offline = offline;
        }
    }

    static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }

    private static final Timer TIMER = new Timer("tvpilot-net-watchdog", true);
    /** Everything open for the current run; cancel() closes it all so blocked threads wake up at once. */
    private static final Set<Closeable> OPEN = Collections.synchronizedSet(new HashSet<Closeable>());

    static void track(Closeable c) { if (c != null) OPEN.add(c); }
    static void untrack(Closeable c) { if (c != null) OPEN.remove(c); }

    /** Back / timeout: drop every connection still open. */
    static void abortAll() {
        Closeable[] all;
        synchronized (OPEN) { all = OPEN.toArray(new Closeable[0]); OPEN.clear(); }
        for (Closeable c : all) try { c.close(); } catch (Exception ignored) {}
    }

    /** DNS with a time limit (Android's resolver can stall for a long time on a bad network). */
    static InetAddress resolve(final String host, int ms) throws IOException {
        FutureTask<InetAddress> f = new FutureTask<>(new Callable<InetAddress>() {
            @Override public InetAddress call() throws Exception { return InetAddress.getByName(host); }
        });
        Thread t = new Thread(f, "tvpilot-dns");
        t.setDaemon(true);
        t.start();
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new UnknownHostException("looking up " + host + " took too long");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(String.valueOf(c));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("stopped");
        }
    }

    /**
     * One HTTP request. connectMs bounds the connect, totalMs the whole call (send + wait + read).
     * Returns the status and body; never throws for an HTTP error status (callers decide), only for no answer.
     */
    static Response request(String url, String method, String contentType, byte[] body, String[][] headers,
                            int connectMs, int totalMs, String service) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("stopped");
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        Closeable closer = new Closeable() { @Override public void close() { c.disconnect(); } };
        final boolean[] fired = {false};
        TimerTask dog = new TimerTask() { @Override public void run() { fired[0] = true; c.disconnect(); } };
        TIMER.schedule(dog, totalMs);
        track(closer);
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(Math.max(1000, totalMs));
            c.setRequestMethod(method);
            c.setUseCaches(false);
            if (headers != null) for (String[] h : headers) if (h[1] != null) c.setRequestProperty(h[0], h[1]);
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream(), 2 * 1024 * 1024);
            return new Response(code, text);
        } catch (IOException e) {
            if (fired[0]) throw new Fail(service + " didn't answer in " + (totalMs / 1000) + " seconds", 0, true, e.toString());
            throw classify(e, service);
        } finally {
            dog.cancel();
            untrack(closer);
            c.disconnect();
        }
    }

    /** For streamed responses: cuts the connection after totalMs. Cancel the returned task when done. */
    static TimerTask watch(final HttpURLConnection c, long totalMs) {
        TimerTask dog = new TimerTask() { @Override public void run() { c.disconnect(); } };
        TIMER.schedule(dog, totalMs);
        return dog;
    }

    static Response postJson(String url, String json, String[][] headers, int connectMs, int totalMs, String service) throws IOException {
        return request(url, "POST", "application/json", json.getBytes(StandardCharsets.UTF_8), headers, connectMs, totalMs, service);
    }

    /** Turns an I/O failure into a sentence. */
    static IOException classify(IOException e, String service) {
        if (e instanceof Fail) return e;
        if (e instanceof InterruptedIOException && !(e instanceof SocketTimeoutException)) return e;   // cancelled
        String m = String.valueOf(e.getMessage());
        if (e instanceof UnknownHostException)
            return new Fail("Can't find " + service + " \u2014 check the TV's internet and the link in Settings", 0, true, e.toString());
        if (e instanceof SocketTimeoutException)
            return new Fail(service + " didn't answer in time", 0, true, e.toString());
        if (e instanceof ConnectException || e instanceof NoRouteToHostException || m.contains("ECONNREFUSED") || m.contains("ENETUNREACH"))
            return new Fail(service + " is offline", 0, true, e.toString());
        if (e instanceof javax.net.ssl.SSLException)
            return new Fail("Secure connection to " + service + " failed (check the TV's date and time)", 0, true, e.toString());
        return new Fail(service + " connection failed: " + shorten(m, 80), 0, true, e.toString());
    }

    /** A sentence for an HTTP error status. */
    static Fail http(int code, String body, String service) {
        String detail = shorten(errorMessage(body), 120);
        String user;
        boolean badKey = code == 400 && detail.toLowerCase(java.util.Locale.US).contains("api key");
        if (code == 401 || code == 403 || badKey) user = service + " rejected the key \u2014 check it in Settings";
        else if (code == 429) user = service + " is out of quota or rate-limited right now";
        else if (code == 404) user = service + " says not found (" + detail + ")";
        else if (code == 502 || code == 503 || code == 504) user = service + " is down or still waking up";
        else if (code >= 500) user = service + " had an internal error (" + code + ")";
        else user = service + " said " + code + (detail.isEmpty() ? "" : ": " + detail);
        return new Fail(user, code, code == 502 || code == 503 || code == 504, service + " " + code + ": " + detail);
    }

    /** The "message" of a Google/OpenAI-style error body, or the body itself. */
    static String errorMessage(String body) {
        if (body == null) return "";
        try {
            org.json.JSONObject o = new org.json.JSONObject(body);
            Object err = o.opt("error");
            if (err instanceof org.json.JSONObject) return ((org.json.JSONObject) err).optString("message", body);
            if (err instanceof String) return (String) err;
            if (o.has("detail")) return o.optString("detail");
        } catch (Exception ignored) {}
        return body.replace('\n', ' ').trim();
    }

    /** A sentence for any exception from a run (what the bar shows and the voice says). */
    static String explain(Throwable e) {
        if (e instanceof Fail) return ((Fail) e).user;
        if (e instanceof OutOfMemoryError) return "The TV ran out of memory for that step";
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return shorten(m, 160);
    }

    static String read(InputStream in, int max) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > max) break;
            }
            return b.toString("UTF-8");
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    static String shorten(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > n ? s.substring(0, n) + "\u2026" : s;
    }
}
