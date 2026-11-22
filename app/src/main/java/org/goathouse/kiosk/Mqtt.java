package org.goathouse.kiosk;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Small MQTT 3.1.1 client: TLS, username/password, retained Last Will, QoS 0/1 publish, QoS 1 subscribe, keepalive,
 * reconnect with backoff. Just enough for goatprint (no Paho: it loads its transports by ServiceLoader, which minify
 * and Android 5 both make fragile). TLS trusts the system CAs plus ISRG Root X1/X2 bundled in res/raw, because
 * Android 5–7.0 predate Let's Encrypt's roots; the hostname is checked against the certificate.
 */
final class Mqtt {
    private static final String TAG = "Mqtt";

    interface Callback {
        void onConnected(Mqtt m);
        void onMessage(Mqtt m, String topic, byte[] payload, boolean retained);
        void onStatus(String status);
    }

    private final Context ctx;
    private final String host, user, pass, clientId, willTopic;
    private final int port;
    private final boolean tls;
    private final byte[] willPayload;
    private final Callback cb;
    private volatile boolean running;
    private volatile Socket sock;
    private volatile OutputStream out;
    private volatile boolean connected;
    private int nextId = 1;
    private Thread thread;

    Mqtt(Context ctx, String host, int port, boolean tls, String user, String pass, String clientId,
         String willTopic, String willPayload, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.host = host; this.port = port; this.tls = tls; this.user = user; this.pass = pass; this.clientId = clientId;
        this.willTopic = willTopic; this.willPayload = willPayload.getBytes(StandardCharsets.UTF_8); this.cb = cb;
    }

    boolean connected() { return connected; }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "mqtt");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        try { send(0xE0, new byte[0]); } catch (Exception ignored) { }   // DISCONNECT: a clean close skips the Will
        closeSock();
    }

    // ---------- connection loop ----------

    private void run() {
        long backoff = 1000;
        while (running) {
            try {
                connect();
                backoff = 1000;
                readLoop();
            } catch (Exception e) {
                if (running) Log.w(TAG, "connection: " + e);
                cb.onStatus(running ? "MQTT: " + shortMsg(e) : "MQTT off");
            } finally {
                connected = false;
                closeSock();
            }
            if (!running) break;
            try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.length() > 80 ? m.substring(0, 80) : m;
    }

    private void connect() throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 10_000);
        if (tls) {
            SSLSocket ss = (SSLSocket) sslContext().getSocketFactory().createSocket(s, host, port, true);
            ss.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss.getSession())) {
                ss.close();
                throw new IOException("certificate is not for " + host);
            }
            s = ss;
        }
        s.setSoTimeout(10_000);       // wake up to ping; keepalive is 30 s
        sock = s;
        out = s.getOutputStream();
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        str(v, "MQTT");
        v.write(4);                                                    // protocol level 3.1.1
        int flags = 0x02 | 0x04 | 0x08 | 0x20;                        // clean session, will, will QoS 1, will retain
        if (!user.isEmpty()) flags |= 0x80;
        if (!pass.isEmpty()) flags |= 0x40;
        v.write(flags);
        v.write(0); v.write(30);                                       // keepalive seconds
        str(v, clientId);
        str(v, willTopic);
        bin(v, willPayload);
        if (!user.isEmpty()) str(v, user);
        if (!pass.isEmpty()) str(v, pass);
        send(0x10, v.toByteArray());
        DataInputStream in = new DataInputStream(s.getInputStream());
        int type = in.readUnsignedByte();
        byte[] body = readBody(in);
        if ((type & 0xF0) != 0x20 || body.length < 2) throw new IOException("no CONNACK");
        if (body[1] != 0) throw new IOException(body[1] == 4 || body[1] == 5 ? "login refused" : "connect refused (" + body[1] + ")");
        connected = true;
        cb.onStatus("MQTT connected");
        cb.onConnected(this);
    }

    private void readLoop() throws Exception {
        DataInputStream in = new DataInputStream(sock.getInputStream());
        long lastPing = System.currentTimeMillis(), lastHeard = lastPing;
        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastHeard > 60_000) throw new IOException("broker went quiet");
            if (now - lastPing > 20_000) { ping(); lastPing = now; }
            int type;
            try {
                type = in.readUnsignedByte();
            } catch (java.net.SocketTimeoutException t) {
                continue;
            }
            lastHeard = System.currentTimeMillis();
            byte[] body = readBody(in);
            switch (type & 0xF0) {
                case 0x30: onPublish(type, body); break;
                case 0x40: case 0x90: case 0xD0: break;          // PUBACK, SUBACK, PINGRESP
                default: Log.d(TAG, "ignoring packet " + Integer.toHexString(type));
            }
        }
    }

    private void onPublish(int header, byte[] b) throws IOException {
        int tl = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        String topic = new String(b, 2, tl, StandardCharsets.UTF_8);
        int pos = 2 + tl, qos = (header >> 1) & 3;
        if (qos > 0) {
            int id = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
            pos += 2;
            send(0x40, new byte[]{(byte) (id >> 8), (byte) id});    // PUBACK
        }
        byte[] payload = new byte[b.length - pos];
        System.arraycopy(b, pos, payload, 0, payload.length);
        try {
            cb.onMessage(this, topic, payload, (header & 1) != 0);
        } catch (Exception e) {
            Log.w(TAG, "handler for " + topic + ": " + e);           // never kill the connection over one message
        }
    }

    private void ping() { try { send(0xC0, new byte[0]); } catch (Exception ignored) { } }

    // ---------- outgoing ----------

    void subscribe(String topic) {
        try {
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            int id = id();
            v.write(id >> 8); v.write(id);
            str(v, topic);
            v.write(1);
            send(0x82, v.toByteArray());
        } catch (Exception e) { Log.w(TAG, "subscribe " + topic + ": " + e); }
    }

    /** Returns false if not connected (the message is dropped, like Paho's offline behaviour without a buffer). */
    boolean publish(String topic, byte[] payload, int qos, boolean retain) {
        if (!connected) return false;
        try {
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            str(v, topic);
            if (qos > 0) { int id = id(); v.write(id >> 8); v.write(id); }
            v.write(payload);
            send(0x30 | (qos > 0 ? 0x02 : 0) | (retain ? 1 : 0), v.toByteArray());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "publish " + topic + ": " + e);
            closeSock();
            return false;
        }
    }

    boolean publish(String topic, String payload, int qos, boolean retain) {
        return publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, retain);
    }

    private synchronized int id() { int i = nextId; nextId = nextId >= 65535 ? 1 : nextId + 1; return i; }

    private synchronized void send(int header, byte[] body) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("not connected");
        ByteArrayOutputStream p = new ByteArrayOutputStream(body.length + 5);
        p.write(header);
        int len = body.length;
        do {
            int d = len % 128;
            len /= 128;
            p.write(len > 0 ? d | 0x80 : d);
        } while (len > 0);
        p.write(body);
        o.write(p.toByteArray());
        o.flush();
    }

    private void closeSock() {
        Socket s = sock;
        sock = null; out = null; connected = false;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private static byte[] readBody(DataInputStream in) throws IOException {
        int len = 0, mul = 1, d;
        do {
            d = in.readUnsignedByte();
            len += (d & 0x7F) * mul;
            mul *= 128;
            if (mul > 128 * 128 * 128 * 128) throw new IOException("bad length");
        } while ((d & 0x80) != 0);
        if (len > 4 * 1024 * 1024) throw new IOException("packet too big");
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    private static void str(ByteArrayOutputStream v, String s) throws IOException { bin(v, s.getBytes(StandardCharsets.UTF_8)); }

    private static void bin(ByteArrayOutputStream v, byte[] b) throws IOException {
        v.write(b.length >> 8); v.write(b.length);
        v.write(b);
    }

    // ---------- TLS: system CAs + bundled ISRG roots ----------

    private static SSLContext ssl;

    private synchronized SSLContext sslContext() throws Exception {
        if (ssl != null) return ssl;
        TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        sys.init((KeyStore) null);
        KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
        extra.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = ctx.getResources().openRawResource(R.raw.isrg_roots)) {
            int i = 0;
            for (java.security.cert.Certificate c : cf.generateCertificates(in)) extra.setCertificateEntry("isrg" + i++, c);
        }
        TrustManagerFactory bundled = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        bundled.init(extra);
        X509TrustManager a = first(sys.getTrustManagers()), b = first(bundled.getTrustManagers());
        X509TrustManager both = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                a.checkClientTrusted(chain, auth);
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                try { a.checkServerTrusted(chain, auth); } catch (java.security.cert.CertificateException e) { b.checkServerTrusted(chain, auth); }
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> l = new ArrayList<>();
                for (X509Certificate c : a.getAcceptedIssuers()) l.add(c);
                for (X509Certificate c : b.getAcceptedIssuers()) l.add(c);
                return l.toArray(new X509Certificate[0]);
            }
        };
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, new TrustManager[]{both}, null);
        ssl = c;
        return c;
    }

    private static X509TrustManager first(TrustManager[] tms) {
        for (TrustManager t : tms) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no X509TrustManager");
    }
}
