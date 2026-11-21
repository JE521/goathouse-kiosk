package org.goathouse.kiosk;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Jellyfin cast target: the tablet signs in with Quick Connect (no password stored), keeps a session websocket open
 * and shows up in the Jellyfin apps' cast menu. "Play" opens CastActivity full screen; pause/seek/stop come back the
 * same way, and CastActivity reports progress so the phone's remote shows where playback is.
 */
final class Jellyfin {
    private static final String TAG = "Jellyfin";
    static final String URL_KEY = "jf_url", TOKEN = "jf_token", USER_ID = "jf_user", USER_NAME = "jf_user_name";

    interface Remote { void onPlaystate(String command, long seekTicks); }

    private static Jellyfin instance;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile MiniWebSocket ws;
    volatile String status = "Not linked";
    static volatile Remote remote;      // the open CastActivity

    static synchronized Jellyfin get(Context c) {
        if (instance == null) instance = new Jellyfin(c.getApplicationContext());
        return instance;
    }

    private Jellyfin(Context c) { ctx = c; }

    String server() { return Prefs.of(ctx).get(URL_KEY, "").replaceAll("/+$", ""); }
    String token() { return Prefs.of(ctx).get(TOKEN, ""); }
    String userId() { return Prefs.of(ctx).get(USER_ID, ""); }
    boolean linked() { return !server().isEmpty() && !token().isEmpty(); }

    String deviceId() {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return "goathouse-kiosk-" + (id == null ? "x" : id);
    }

    String auth(boolean withToken) {
        String a = "MediaBrowser Client=\"Goathouse Kiosk\", Device=\"" + SendspinService.playerName(ctx).replace("\"", "")
                + "\", DeviceId=\"" + deviceId() + "\", Version=\"0.2\"";
        return withToken && !token().isEmpty() ? a + ", Token=\"" + token() + "\"" : a;
    }

    // ---------- HTTP ----------

    JSONObject call(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Authorization", auth(true));
        c.setRequestProperty("X-Emby-Authorization", auth(true));
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code + " " + path);
        String s;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            s = b.toString("UTF-8");
        } catch (Exception empty) { s = ""; }
        c.disconnect();
        s = s.trim();
        return s.startsWith("{") ? new JSONObject(s) : new JSONObject();
    }

    void post(String path, JSONObject body) {
        new Thread(() -> { try { call("POST", path, body); } catch (Exception e) { Log.w(TAG, path + ": " + e); } }).start();
    }

    // ---------- Quick Connect linking ----------

    interface LinkListener { void code(String code); void done(String user, String error); }

    /** Shows a code to type into a signed-in Jellyfin app (profile → Quick Connect), then stores the token. */
    void link(String serverUrl, LinkListener l) {
        Prefs.of(ctx).put(URL_KEY, serverUrl.trim().replaceAll("/+$", ""));
        Prefs.of(ctx).put(TOKEN, "");
        new Thread(() -> {
            try {
                JSONObject init = call("POST", "/QuickConnect/Initiate", null);
                String secret = init.getString("Secret");
                main.post(() -> l.code(init.optString("Code")));
                for (int i = 0; i < 150; i++) {          // 5 minutes
                    Thread.sleep(2000);
                    JSONObject st = call("GET", "/QuickConnect/Connect?secret=" + URLEncoder.encode(secret, "UTF-8"), null);
                    if (st.optBoolean("Authenticated")) {
                        JSONObject r = call("POST", "/Users/AuthenticateWithQuickConnect", new JSONObject().put("Secret", secret));
                        JSONObject u = r.getJSONObject("User");
                        Prefs p = Prefs.of(ctx);
                        p.put(TOKEN, r.getString("AccessToken"));
                        p.put(USER_ID, u.getString("Id"));
                        p.put(USER_NAME, u.optString("Name"));
                        restart();
                        main.post(() -> l.done(u.optString("Name"), null));
                        return;
                    }
                }
                main.post(() -> l.done(null, "Code expired, try again"));
            } catch (Exception e) {
                main.post(() -> l.done(null, e.getMessage()));
            }
        }).start();
    }

    void unlink() {
        Prefs p = Prefs.of(ctx);
        p.put(TOKEN, "");
        p.put(USER_ID, "");
        restart();
    }

    // ---------- session websocket ----------

    synchronized void start() {
        if (running) return;
        running = true;
        new Thread(this::loop, "jellyfin").start();
    }

    void restart() {
        running = false;
        MiniWebSocket w = ws;
        if (w != null) w.close();
        main.postDelayed(this::start, 1500);
    }

    private void loop() {
        int backoff = 2;
        while (running) {
            if (!linked()) { status = "Not linked"; sleep(5000); continue; }
            final Object done = new Object();
            final boolean[] closed = {false};
            try {
                // Jellyfin 10.11+ refuses the legacy api_key= parameter: authenticate the upgrade with the header.
                String url = server().replaceFirst("^http", "ws") + "/socket?deviceId=" + deviceId();
                MiniWebSocket w = new MiniWebSocket();
                ws = w;
                w.connect(url, new MiniWebSocket.Listener() {
                    @Override public void onText(String text) { handle(w, text); }
                    @Override public void onClosed(String why) { synchronized (done) { closed[0] = true; done.notifyAll(); } }
                }, "Authorization: " + auth(true) + "\r\n");
                call("POST", "/Sessions/Capabilities/Full", new JSONObject()
                        .put("PlayableMediaTypes", new JSONArray().put("Audio").put("Video"))
                        .put("SupportedCommands", new JSONArray().put("SetVolume").put("Mute").put("Unmute").put("ToggleMute").put("DisplayMessage"))
                        .put("SupportsMediaControl", true)
                        .put("SupportsPersistentIdentifier", true));
                status = "Ready to cast · " + Prefs.of(ctx).get(USER_NAME, "");
                backoff = 2;
                synchronized (done) { while (!closed[0] && running) done.wait(30000); }
            } catch (Exception e) {
                status = "Can't reach Jellyfin: " + e.getMessage();
                Log.w(TAG, "session: " + e);
            }
            sleep(backoff * 1000L);
            backoff = Math.min(backoff * 2, 60);
        }
    }

    private long keepAliveMs = 30000;
    private Thread keepAlive;

    private void handle(MiniWebSocket w, String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("MessageType");
            Object data = m.opt("Data");
            switch (type) {
                case "ForceKeepAlive":
                    keepAliveMs = Math.max(5000, (long) (m.optDouble("Data", 60) * 1000 / 2));
                    if (keepAlive == null || !keepAlive.isAlive()) {
                        keepAlive = new Thread(() -> {
                            while (running && ws == w) {
                                try { w.sendText("{\"MessageType\":\"KeepAlive\"}"); Thread.sleep(keepAliveMs); }
                                catch (Exception e) { return; }
                            }
                        }, "jf-keepalive");
                        keepAlive.start();
                    }
                    break;
                case "Play":
                    if (data instanceof JSONObject) main.post(() -> play((JSONObject) data));
                    break;
                case "Playstate":
                    if (data instanceof JSONObject) {
                        JSONObject d = (JSONObject) data;
                        String cmd = d.optString("Command");
                        long ticks = d.optLong("SeekPositionTicks", -1);
                        main.post(() -> { Remote r = remote; if (r != null) r.onPlaystate(cmd, ticks); });
                    }
                    break;
                case "GeneralCommand":
                    if (data instanceof JSONObject) general((JSONObject) data);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "message: " + e);
        }
    }

    private void general(JSONObject d) {
        String name = d.optString("Name");
        JSONObject args = d.optJSONObject("Arguments");
        android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        switch (name) {
            case "SetVolume":
                int v = args == null ? 50 : args.optInt("Volume", 50);
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(max * v / 100f), 0);
                break;
            case "Mute": am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, true); break;
            case "Unmute": am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, false); break;
            case "DisplayMessage":
                String msg = args == null ? "" : args.optString("Text", args.optString("Header", ""));
                main.post(() -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show());
                break;
        }
    }

    private void play(JSONObject d) {
        JSONArray ids = d.optJSONArray("ItemIds");
        if (ids == null || ids.length() == 0) return;
        Intent i = new Intent(ctx, CastActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ids", ids.toString())
                .putExtra("index", d.optInt("StartIndex", 0))
                .putExtra("start", d.optLong("StartPositionTicks", 0))
                .putExtra("audioIndex", d.has("AudioStreamIndex") ? d.optInt("AudioStreamIndex") : -1)
                .putExtra("subIndex", d.has("SubtitleStreamIndex") ? d.optInt("SubtitleStreamIndex") : -1);
        ctx.startActivity(i);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    // ---------- streams (transcoded to what old tablets decode: H.264 + AAC, ≤720p) ----------

    String videoUrl(String id, String playSession, int audioIndex, long startTicks) {
        // One continuous MPEG-TS stream (old Android plays it reliably; HLS refuses StartTimeTicks and the old
        // MediaPlayer can't seek far into a playlist that is still being transcoded). Seeking = new stream at the new point.
        return server() + "/Videos/" + id + "/stream.ts?static=false&MediaSourceId=" + id + "&DeviceId=" + deviceId()
                + "&PlaySessionId=" + playSession + "&ApiKey=" + token()
                + "&Container=ts&VideoCodec=h264&AudioCodec=aac&MaxWidth=1280&MaxHeight=720&VideoBitrate=4000000&AudioBitrate=192000"
                + "&AudioChannels=2&h264-profile=high,main,baseline&h264-level=41"
                + "&EstimateContentLength=true"     // Android 5 NuCachedSource2 aborts on streams of unknown length
                + (audioIndex >= 0 ? "&AudioStreamIndex=" + audioIndex : "")
                + (startTicks > 0 ? "&StartTimeTicks=" + startTicks : "");
    }

    String audioUrl(String id, String playSession) {
        return server() + "/Audio/" + id + "/universal?UserId=" + userId() + "&DeviceId=" + deviceId()
                + "&PlaySessionId=" + playSession + "&ApiKey=" + token()
                + "&MaxStreamingBitrate=320000&Container=mp3,aac,m4a|aac,m4b|aac,flac,ogg&TranscodingContainer=mp3"
                + "&TranscodingProtocol=http&AudioCodec=mp3";
    }

    String imageUrl(String id) { return server() + "/Items/" + id + "/Images/Primary?maxHeight=600&ApiKey=" + token(); }
}
