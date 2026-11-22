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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jellyfin cast target for every linked account. A Jellyfin session belongs to one user and only that user sees it
 * in the cast menu, so the tablet links any number of accounts (Quick Connect each, no passwords stored) and keeps one
 * session websocket per account, each with its own device id. "Play" from any of them opens CastActivity; streams and
 * progress reports then use the account that cast (the active one).
 */
final class Jellyfin {
    private static final String TAG = "Jellyfin";
    static final String URL_KEY = "jf_url", ACCOUNTS = "jf_accounts";
    // single-account keys from 0.2.0, migrated on first start
    private static final String OLD_TOKEN = "jf_token", OLD_USER_ID = "jf_user", OLD_USER_NAME = "jf_user_name";

    interface Remote { void onPlaystate(String command, long seekTicks); }

    /** One linked Jellyfin user. */
    final class Account {
        final String id, name, token;
        volatile MiniWebSocket ws;
        volatile boolean up;
        Account(String id, String name, String token) { this.id = id; this.name = name; this.token = token; }

        String deviceId() { return baseDeviceId() + "-" + id.substring(0, Math.min(8, id.length())); }

        String auth() {
            return "MediaBrowser Client=\"Goathouse Kiosk\", Device=\"" + SendspinService.playerName(ctx).replace("\"", "")
                    + "\", DeviceId=\"" + deviceId() + "\", Version=\"0.2\", Token=\"" + token + "\"";
        }
    }

    private static Jellyfin instance;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Thread> loops = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile int generation;
    volatile String status = "Not linked";
    /** The account whose cast is playing (or last played). */
    volatile Account active;
    static volatile Remote remote;      // the open CastActivity

    static synchronized Jellyfin get(Context c) {
        if (instance == null) instance = new Jellyfin(c.getApplicationContext());
        return instance;
    }

    private Jellyfin(Context c) { ctx = c; migrate(); }

    String server() { return Prefs.of(ctx).get(URL_KEY, "").replaceAll("/+$", ""); }
    boolean linked() { return !server().isEmpty() && !accounts().isEmpty(); }

    String baseDeviceId() {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return "goathouse-kiosk-" + (id == null ? "x" : id);
    }

    // ---------- accounts ----------

    synchronized List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.of(ctx).get(ACCOUNTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!o.optString("token").isEmpty()) out.add(new Account(o.getString("id"), o.optString("name"), o.getString("token")));
            }
        } catch (Exception e) { Log.w(TAG, "accounts: " + e); }
        return out;
    }

    private synchronized void saveAccounts(List<Account> list) {
        JSONArray a = new JSONArray();
        try {
            for (Account x : list) a.put(new JSONObject().put("id", x.id).put("name", x.name).put("token", x.token));
        } catch (Exception ignored) { }
        Prefs.of(ctx).put(ACCOUNTS, a.toString());
    }

    private synchronized void addAccount(Account acc) {
        List<Account> list = accounts();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(acc.id)) list.remove(i);
        list.add(acc);
        saveAccounts(list);
    }

    /** Forget one account (its session drops; the token is revoked server-side best-effort). */
    void remove(String userId) {
        List<Account> list = accounts();
        Account gone = null;
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(userId)) gone = list.remove(i);
        saveAccounts(list);
        if (gone != null) {
            final Account g = gone;
            new Thread(() -> { try { call(g, "POST", "/Sessions/Logout", null); } catch (Exception ignored) { } }).start();
        }
        restart();
    }

    private void migrate() {
        Prefs p = Prefs.of(ctx);
        String t = p.get(OLD_TOKEN, "");
        if (t.isEmpty()) return;
        addAccount(new Account(p.get(OLD_USER_ID, "user"), p.get(OLD_USER_NAME, ""), t));
        p.put(OLD_TOKEN, "");
    }

    // ---------- HTTP ----------

    JSONObject call(Account a, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        String auth = a != null ? a.auth() : "MediaBrowser Client=\"Goathouse Kiosk\", Device=\""
                + SendspinService.playerName(ctx).replace("\"", "") + "\", DeviceId=\"" + baseDeviceId() + "-link\", Version=\"0.2\"";
        c.setRequestProperty("Authorization", auth);
        c.setRequestProperty("X-Emby-Authorization", auth);
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

    /** As the account that's casting (CastActivity). */
    JSONObject call(String method, String path, JSONObject body) throws Exception { return call(activeOrFirst(), method, path, body); }

    void post(String path, JSONObject body) {
        Account a = activeOrFirst();
        new Thread(() -> { try { call(a, "POST", path, body); } catch (Exception e) { Log.w(TAG, path + ": " + e); } }).start();
    }

    private Account activeOrFirst() {
        Account a = active;
        if (a != null) return a;
        List<Account> l = accounts();
        return l.isEmpty() ? null : l.get(0);
    }

    String userId() { Account a = activeOrFirst(); return a == null ? "" : a.id; }
    private String token() { Account a = activeOrFirst(); return a == null ? "" : a.token; }
    private String deviceId() { Account a = activeOrFirst(); return a == null ? baseDeviceId() : a.deviceId(); }

    // ---------- Quick Connect linking ----------

    interface LinkListener { void code(String code); void done(String user, String error); }

    /** Shows a code to type into a signed-in Jellyfin app (profile → Quick Connect), then adds that account. */
    void link(String serverUrl, LinkListener l) {
        String url = serverUrl.trim().replaceAll("/+$", "");
        if (!url.equals(server())) { Prefs.of(ctx).put(URL_KEY, url); saveAccounts(new ArrayList<>()); }   // new server: start over
        new Thread(() -> {
            try {
                JSONObject init = call(null, "POST", "/QuickConnect/Initiate", null);
                String secret = init.getString("Secret");
                main.post(() -> l.code(init.optString("Code")));
                for (int i = 0; i < 150; i++) {          // 5 minutes
                    Thread.sleep(2000);
                    JSONObject st = call(null, "GET", "/QuickConnect/Connect?secret=" + URLEncoder.encode(secret, "UTF-8"), null);
                    if (st.optBoolean("Authenticated")) {
                        JSONObject r = call(null, "POST", "/Users/AuthenticateWithQuickConnect", new JSONObject().put("Secret", secret));
                        JSONObject u = r.getJSONObject("User");
                        addAccount(new Account(u.getString("Id"), u.optString("Name"), r.getString("AccessToken")));
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

    // ---------- session websockets, one per account ----------

    synchronized void start() {
        if (running) return;
        running = true;
        int gen = ++generation;
        for (Account a : accounts()) {
            Thread t = new Thread(() -> loop(a, gen), "jellyfin-" + a.name);
            t.setDaemon(true);
            loops.put(a.id, t);
            t.start();
        }
        updateStatus();
    }

    void restart() {
        synchronized (this) { running = false; generation++; }
        for (Thread t : loops.values()) t.interrupt();
        loops.clear();
        main.postDelayed(this::start, 1500);
    }

    private void updateStatus() {
        List<Account> all = accounts();
        if (server().isEmpty() || all.isEmpty()) { status = "Not linked"; return; }
        StringBuilder up = new StringBuilder();
        for (Account a : all) if (a.up) up.append(up.length() > 0 ? ", " : "").append(a.name);
        status = up.length() > 0 ? "Ready to cast · " + up : "Connecting · " + all.size() + (all.size() == 1 ? " account" : " accounts");
    }

    private void loop(Account a, int gen) {
        int backoff = 2;
        while (running && gen == generation) {
            final Object done = new Object();
            final boolean[] closed = {false};
            try {
                // Jellyfin 10.11+ refuses the legacy api_key= parameter: authenticate the upgrade with the header.
                String url = server().replaceFirst("^http", "ws") + "/socket?deviceId=" + a.deviceId();
                MiniWebSocket w = new MiniWebSocket();
                a.ws = w;
                w.connect(url, new MiniWebSocket.Listener() {
                    @Override public void onText(String text) { handle(a, w, text); }
                    @Override public void onClosed(String why) { synchronized (done) { closed[0] = true; done.notifyAll(); } }
                }, "Authorization: " + a.auth() + "\r\n");
                call(a, "POST", "/Sessions/Capabilities/Full", new JSONObject()
                        .put("PlayableMediaTypes", new JSONArray().put("Audio").put("Video"))
                        .put("SupportedCommands", new JSONArray().put("SetVolume").put("Mute").put("Unmute").put("ToggleMute").put("DisplayMessage"))
                        .put("SupportsMediaControl", true)
                        .put("SupportsPersistentIdentifier", true));
                a.up = true;
                updateStatus();
                backoff = 2;
                synchronized (done) { while (!closed[0] && running && gen == generation) done.wait(30000); }
                if (!closed[0]) w.close();
            } catch (InterruptedException stop) {
                MiniWebSocket w = a.ws;
                if (w != null) new Thread(w::close).start();
                break;
            } catch (Exception e) {
                Log.w(TAG, a.name + " session: " + e);
                if (e.getMessage() != null && e.getMessage().contains("HTTP 401")) status = a.name + ": sign-in expired, link again";
            }
            a.up = false;
            updateStatus();
            sleep(backoff * 1000L);
            backoff = Math.min(backoff * 2, 60);
        }
        a.up = false;
    }

    private void handle(Account a, MiniWebSocket w, String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("MessageType");
            Object data = m.opt("Data");
            switch (type) {
                case "ForceKeepAlive":
                    long every = Math.max(5000, (long) (m.optDouble("Data", 60) * 1000 / 2));
                    Thread k = new Thread(() -> {
                        while (running && a.ws == w) {
                            try { w.sendText("{\"MessageType\":\"KeepAlive\"}"); Thread.sleep(every); }
                            catch (Exception e) { return; }
                        }
                    }, "jf-keepalive-" + a.name);
                    k.setDaemon(true);
                    k.start();
                    break;
                case "Play":
                    if (data instanceof JSONObject) main.post(() -> play(a, (JSONObject) data));
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

    private void play(Account a, JSONObject d) {
        JSONArray ids = d.optJSONArray("ItemIds");
        if (ids == null || ids.length() == 0) return;
        active = a;
        Intent i = new Intent(ctx, CastActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ids", ids.toString())
                .putExtra("index", d.optInt("StartIndex", 0))
                .putExtra("start", d.optLong("StartPositionTicks", 0))
                .putExtra("audioIndex", d.has("AudioStreamIndex") ? d.optInt("AudioStreamIndex") : -1)
                .putExtra("subIndex", d.has("SubtitleStreamIndex") ? d.optInt("SubtitleStreamIndex") : -1);
        ctx.startActivity(i);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    // ---------- streams (transcoded to what old tablets decode: H.264 + AAC, ≤720p), as the casting account ----------

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
