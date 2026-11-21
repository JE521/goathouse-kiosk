package org.goathouse.kiosk;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;

/**
 * Local relay for live transcodes. Android 5's media player (NuCachedSource2) aborts on HTTP streams without a
 * Content-Length, which is exactly what a live Jellyfin transcode is. The relay fetches the stream and hands it to
 * the player on 127.0.0.1 with a large declared length. One stream at a time; a new one replaces the old.
 */
final class StreamRelay {
    private static final String TAG = "Relay";
    private static ServerSocket server;
    private static volatile String upstream;
    private static volatile int generation;

    /** Returns a http://127.0.0.1 URL the player can open that relays {@code url}. */
    static synchronized String wrap(String url) {
        upstream = url;
        generation++;
        try {
            if (server == null || server.isClosed()) {
                server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
                Thread t = new Thread(StreamRelay::accept, "relay");
                t.setDaemon(true);
                t.start();
            }
            return "http://127.0.0.1:" + server.getLocalPort() + "/s" + generation + ".ts";
        } catch (Exception e) {
            Log.w(TAG, "start: " + e);
            return url;
        }
    }

    private static void accept() {
        while (true) {
            try {
                Socket s = server.accept();
                new Thread(() -> serve(s), "relay-conn").start();
            } catch (Exception e) {
                Log.w(TAG, "accept: " + e);
                return;
            }
        }
    }

    private static void serve(Socket s) {
        int gen = generation;
        String url = upstream;
        HttpURLConnection c = null;
        try (Socket sock = s) {
            InputStream req = sock.getInputStream();
            byte[] head = new byte[8192];
            int n = 0, r;
            // read the request head (we ignore Range: a live transcode can only be read from where it starts)
            while (n < head.length && (r = req.read(head, n, head.length - n)) > 0) {
                n += r;
                String h = new String(head, 0, n, "ISO-8859-1");
                if (h.contains("\r\n\r\n")) break;
            }
            OutputStream out = sock.getOutputStream();
            if (url == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes("ISO-8859-1")); return; }
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            InputStream in = c.getInputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: 20000000000\r\n"
                    + "Accept-Ranges: none\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            byte[] buf = new byte[64 * 1024];
            while (gen == generation && (r = in.read(buf)) > 0) out.write(buf, 0, r);
        } catch (Exception e) {
            Log.i(TAG, "stream ended: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
