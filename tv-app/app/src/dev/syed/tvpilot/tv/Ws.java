package dev.syed.tvpilot.tv;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small WebSocket client (RFC 6455) over a plain or TLS socket: enough for the Gemini Live API. Text and binary
 * messages both arrive as UTF-8 strings (Gemini sends its JSON in binary frames). Pure Java, no Android classes, so it
 * can be tested on a desktop JVM.
 */
final class Ws implements Closeable {
    interface Listener {
        void onMessage(String text);
        /** The connection ended: code 1000 = normal, 1006 = dropped without a close frame. */
        void onClose(int code, String reason);
    }

    static final class HandshakeException extends IOException {
        final int status;
        final String body;
        HandshakeException(int status, String body) { super("WebSocket refused: HTTP " + status + " " + body); this.status = status; this.body = body; }
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom RNG = new SecureRandom();
    private static final boolean ANDROID = String.valueOf(System.getProperty("java.vm.vendor")).toLowerCase(java.util.Locale.US).contains("android")
            || String.valueOf(System.getProperty("java.vm.name")).toLowerCase(java.util.Locale.US).contains("dalvik");

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private volatile boolean closed, closeNotified;
    private Listener listener;
    private Thread reader;
    /** One writer at a time. A close never waits long for it: a write stuck on a dead network must not block Back. */
    private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();

    /** Tests only: open the TCP connection here (a local tunnel) while TLS and Host still use the URL's host. */
    static volatile InetSocketAddress testRoute;

    private Ws(Socket s, InputStream in) throws IOException {
        socket = s;
        this.in = new DataInputStream(in);
        out = s.getOutputStream();
    }

    /** Connects (DNS, TCP, TLS and the upgrade all within connectMs) and starts reading on its own thread. */
    static Ws connect(String url, int connectMs, Listener l) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equalsIgnoreCase(u.getScheme());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        long deadline = System.currentTimeMillis() + connectMs;
        InetSocketAddress route = testRoute;
        InetSocketAddress target = route != null ? route : new InetSocketAddress(Net.resolve(host, connectMs), port);
        Socket raw = new Socket();
        Socket s = raw;
        try {
            raw.setTcpNoDelay(true);
            raw.connect(target, remaining(deadline));
            raw.setSoTimeout(remaining(deadline));
            if (tls) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
                try {
                    SSLParameters p = ssl.getSSLParameters();
                    p.setServerNames(java.util.Collections.singletonList(new SNIHostName(host)));
                    if (!ANDROID) p.setEndpointIdentificationAlgorithm("HTTPS");
                    try { p.setApplicationProtocols(new String[] {"http/1.1"}); } catch (Throwable ignored) {}   // never h2 for a WebSocket
                    ssl.setSSLParameters(p);
                } catch (RuntimeException ignored) {}
                ssl.startHandshake();
                if (ANDROID) {   // Android's default verifier really checks; a desktop JVM's refuses everything
                    HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
                    if (!hv.verify(host, ssl.getSession())) throw new javax.net.ssl.SSLPeerUnverifiedException("certificate doesn't match " + host);
                }
                s = ssl;
            }
            String key = Base64.getEncoder().encodeToString(random(16));
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                    + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + (port == (tls ? 443 : 80) ? "" : ":" + port) + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\nUser-Agent: TVPilotTV\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(req.getBytes(StandardCharsets.US_ASCII));
            o.flush();
            InputStream is = s.getInputStream();
            String head = readHead(is);
            String[] lines = head.split("\r\n");
            int status = 0;
            try { status = Integer.parseInt(lines[0].split(" ")[1]); } catch (Exception ignored) {}
            String accept = null;
            int length = -1;
            for (String line : lines) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim().toLowerCase(java.util.Locale.US), v = line.substring(c + 1).trim();
                if (k.equals("sec-websocket-accept")) accept = v;
                if (k.equals("content-length")) try { length = Integer.parseInt(v); } catch (Exception ignored) {}
            }
            if (status != 101) {
                if (length < 0) length = 4096;   // no length given: read what comes before the server closes
                String body = "";
                try {
                    if (length > 0) {
                        byte[] b = new byte[Math.min(length, 8192)];
                        int got = 0, r;
                        while (got < b.length && (r = is.read(b, got, b.length - got)) > 0) got += r;
                        body = new String(b, 0, got, StandardCharsets.UTF_8);
                    }
                } catch (IOException ignored) {}
                throw new HandshakeException(status, body);
            }
            String want = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
            if (!want.equals(accept)) throw new IOException("WebSocket handshake failed (bad accept key)");
            s.setSoTimeout(0);
            Ws ws = new Ws(s, is);
            ws.listener = l;
            ws.reader = new Thread(ws::readLoop, "tvpilot-ws");
            ws.reader.setDaemon(true);
            ws.reader.start();
            return ws;
        } catch (SocketTimeoutException e) {
            close(s);
            throw new SocketTimeoutException("connecting took longer than " + connectMs / 1000 + " s");
        } catch (IOException e) {
            close(s);
            throw e;
        } catch (Exception e) {
            close(s);
            throw new IOException(e.toString());
        }
    }

    private static int remaining(long deadline) throws SocketTimeoutException {
        long r = deadline - System.currentTimeMillis();
        if (r <= 0) throw new SocketTimeoutException("connect deadline passed");
        return (int) r;
    }

    private static String readHead(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = is.read();
            if (c < 0) throw new EOFException("the server closed the connection during the handshake");
            b.write(c);
            if (b.size() > 32 * 1024) throw new IOException("handshake reply too long");
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : c == '\r' ? 1 : 0;
        }
        return b.toString("ISO-8859-1");
    }

    boolean isOpen() { return !closed; }

    void send(String text) throws IOException { frame(1, text.getBytes(StandardCharsets.UTF_8)); }

    /** Sends a close frame and shuts the socket. Safe to call more than once. */
    void close(int code, String reason) {
        if (!closed) {
            try {
                byte[] r = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
                byte[] p = new byte[2 + Math.min(r.length, 120)];
                p[0] = (byte) (code >> 8);
                p[1] = (byte) code;
                System.arraycopy(r, 0, p, 2, p.length - 2);
                frame(8, p);
            } catch (IOException ignored) {}
        }
        closed = true;
        close(socket);
        notifyClose(code, reason);
    }

    @Override public void close() { close(1000, "bye"); }

    private void frame(int opcode, byte[] payload) throws IOException {
        if (closed && opcode != 8) throw new IOException("the connection is closed");
        if (opcode == 8) {
            try {
                if (!writeLock.tryLock(300, java.util.concurrent.TimeUnit.MILLISECONDS)) throw new IOException("busy writing");
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        } else {
            writeLock.lock();
        }
        try {
            writeFrame(opcode, payload);
        } finally {
            writeLock.unlock();
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        int n = payload.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream(n + 14);
        b.write(0x80 | opcode);
        if (n < 126) b.write(0x80 | n);
        else if (n < 65536) { b.write(0x80 | 126); b.write(n >> 8); b.write(n); }
        else { b.write(0x80 | 127); for (int i = 7; i >= 0; i--) b.write((int) ((long) n >> (8 * i))); }
        byte[] mask = random(4);
        b.write(mask, 0, 4);
        byte[] m = new byte[n];
        for (int i = 0; i < n; i++) m[i] = (byte) (payload[i] ^ mask[i & 3]);
        b.write(m, 0, n);
        try {
            out.write(b.toByteArray());
            out.flush();
        } catch (IOException e) {
            closed = true;
            throw e;
        }
    }

    private void readLoop() {
        int code = 1006;
        String reason = "connection dropped";
        try {
            ByteArrayOutputStream msg = null;
            while (!closed) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len < 0 || len > 64L * 1024 * 1024) throw new IOException("frame too large");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
                byte[] p = new byte[(int) len];
                in.readFully(p);
                if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                if (op == 8) {
                    code = p.length >= 2 ? ((p[0] & 0xff) << 8) | (p[1] & 0xff) : 1005;
                    reason = p.length > 2 ? new String(p, 2, p.length - 2, StandardCharsets.UTF_8) : "";
                    try { frame(8, p.length >= 2 ? new byte[] {p[0], p[1]} : new byte[0]); } catch (IOException ignored) {}
                    break;
                } else if (op == 9) {
                    try { frame(10, p); } catch (IOException ignored) {}
                } else if (op == 10) {
                    // pong: nothing to do
                } else if (op == 1 || op == 2 || op == 0) {
                    if (op != 0) msg = new ByteArrayOutputStream(p.length);
                    if (msg == null) throw new IOException("continuation without a start");
                    msg.write(p, 0, p.length);
                    if (fin) {
                        String text = msg.toString("UTF-8");
                        msg = null;
                        try {
                            listener.onMessage(text);
                        } catch (RuntimeException e) {
                            // A bug in the handler must not kill the connection silently: report it as the close reason.
                            code = 1011;
                            reason = "handler error: " + e;
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) reason = "connection dropped: " + e.getMessage();
        }
        closed = true;
        close(socket);
        notifyClose(code, reason);
    }

    private void notifyClose(int code, String reason) {
        synchronized (this) {
            if (closeNotified) return;
            closeNotified = true;
        }
        Listener l = listener;
        if (l != null) try { l.onClose(code, reason == null ? "" : reason); } catch (RuntimeException ignored) {}
    }

    private static void close(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }
}
