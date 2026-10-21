package dev.syed.tvpilot.tv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * A tiny page on the TV (http://TV-IP:7913) so keys can be pasted from the phone instead of typed with the remote.
 * Runs only while TV Pilot TV's main screen is open, on your home network.
 */
final class SetupServer {
    static final int PORT = 7913;
    private ServerSocket server;
    private Runnable onSaved;

    void start(Runnable onSaved) {
        this.onSaved = onSaved;
        if (server != null) return;
        new Thread(() -> {
            try (ServerSocket s = new ServerSocket(PORT)) {
                server = s;
                while (!s.isClosed()) {
                    try (Socket c = s.accept()) {
                        c.setSoTimeout(8000);
                        handle(c);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w("phone setup page off: " + e.getMessage());
            } finally {
                server = null;
            }
        }, "tvpilot-setup").start();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void handle(Socket c) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) return;
        int length = 0;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        }
        String message = "";
        if (request.startsWith("POST")) {
            char[] body = new char[Math.min(length, 8192)];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            String key = null, model = null, liquid = null, vmLink = null, vmKey = null;
            for (String pair : new String(body, 0, read).split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String k = pair.substring(0, eq), v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                if (k.equals("key")) key = v;
                if (k.equals("model")) model = v;
                if (k.equals("liquid")) liquid = v;
                if (k.equals("vmlink")) vmLink = v;
                if (k.equals("vmkey")) vmKey = v;
            }
            if (key != null && !key.trim().isEmpty()) Prefs.geminiKey(key);
            if (model != null) Prefs.model(model);
            if (liquid != null && !liquid.trim().isEmpty()) Prefs.liquidKey(liquid);
            if (vmLink != null && !vmLink.trim().isEmpty()) Prefs.vmLink(vmLink);
            if (vmKey != null && !vmKey.trim().isEmpty()) Prefs.vmKey(vmKey);
            message = "<p class=ok>Saved on the TV ✓</p>";
            Log.w("settings saved from the phone");
            if (onSaved != null) onSaved.run();
        }
        String has = Prefs.geminiKey().isEmpty() ? "not set" : "set ✓ (leave blank to keep it)";
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>TV Pilot TV</title><style>body{font-family:system-ui,sans-serif;background:#000;color:#fff;margin:0;padding:28px}"
                + "h1{font-size:26px;margin:0 0 6px}p{color:#aaa}label{display:block;margin:22px 0 8px;color:#ddd}"
                + "input{width:100%;box-sizing:border-box;padding:16px;border-radius:18px;border:1px solid #333;background:#141416;color:#fff;font-size:16px}"
                + "button{margin-top:26px;width:100%;padding:16px;border:0;border-radius:28px;background:#fff;color:#000;font-size:17px;font-weight:600}"
                + ".ok{color:#7CFFA4;font-weight:600}</style></head><body><h1>TV Pilot TV</h1><p>Settings for the TV, from your phone.</p>"
                + message + "<form method=post action='/'><label>Gemini API key (" + has + ")</label>"
                + "<input name=key type=password autocomplete=off placeholder='AIza…'>"
                + "<label>Liquid API key, for d1-ultra (" + (Prefs.liquidKey().isEmpty() ? "not set" : "set ✓ (leave blank to keep it)") + ")</label>"
                + "<input name=liquid type=password autocomplete=off>"
                + "<label>Your server link, optional (" + (Prefs.hasVm() ? "set ✓ (leave blank to keep it)" : "not set") + ")</label>"
                + "<input name=vmlink autocomplete=off placeholder='https://8080-your-server…'>"
                + "<label>Your server key (" + (Prefs.vmKey().isEmpty() ? "not set" : "set ✓ (leave blank to keep it)") + ")</label>"
                + "<input name=vmkey type=password autocomplete=off>"
                + "<label>Gemini model</label><input name=model value='" + Prefs.model().replace("'", "") + "'>"
                + "<button>Save to TV</button></form></body></html>";
        byte[] out = html.getBytes(StandardCharsets.UTF_8);
        OutputStream o = c.getOutputStream();
        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.write(out);
        o.flush();
    }
}
