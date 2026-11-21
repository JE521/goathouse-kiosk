package org.goathouse.kiosk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Home Assistant over its websocket API: auth, one subscribe_entities stream (initial snapshot + compact diffs) and
 * service calls. Reconnects forever with back-off. All listener callbacks run on the main thread.
 */
final class Ha {
    interface Listener {
        void onStatus(boolean connected, String message);
        void onChanged(Set<String> entityIds);
    }
    interface Result { void done(JSONObject result, String error); }

    static final class Ent {
        final String id;
        String state = "";
        JSONObject attrs = new JSONObject();
        double lastChanged;
        Ent(String id) { this.id = id; }
        String domain() { return id.substring(0, id.indexOf('.')); }
        String name() {
            String n = attrs.optString("friendly_name", "");
            return n.isEmpty() ? id.substring(id.indexOf('.') + 1).replace('_', ' ') : n;
        }
        boolean isOn() { return "on".equals(state) || "playing".equals(state) || "active".equals(state) || "home".equals(state) || "open".equals(state); }
        boolean unavailable() { return "unavailable".equals(state) || "unknown".equals(state); }
    }

    private static Ha instance;
    static synchronized Ha get(Context c) {
        if (instance == null) instance = new Ha(c.getApplicationContext());
        return instance;
    }

    final Map<String, Ent> states = new ConcurrentHashMap<>();
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, Result> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(1);
    private volatile MiniWebSocket ws;
    private volatile boolean running, authed;
    private volatile int subId = -1;
    private volatile String status = "Not set up";
    private Thread loop;

    private Ha(Context c) {
        ctx = c;
        applyTimeZone(Prefs.of(c).raw().getString("ha_tz", ""));
    }

    static void applyTimeZone(String tz) {
        if (tz != null && !tz.isEmpty()) java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(tz));
    }

    boolean connected() { return authed; }
    String status() { return status; }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    synchronized void start() {
        if (running) return;
        running = true;
        loop = new Thread(this::runLoop, "ha-loop");
        loop.setDaemon(true);
        loop.start();
    }

    /** Settings changed: drop the socket, the loop reconnects with the new URL/token. */
    void restart() {
        MiniWebSocket w = ws;
        if (w != null) new Thread(w::close).start();
        start();
    }

    private void runLoop() {
        int backoff = 2;
        while (running) {
            Prefs p = Prefs.of(ctx);
            if (!p.hasHa()) { setStatus(false, "Not set up"); sleep(3000); continue; }
            final Object done = new Object();
            final boolean[] closed = {false};
            try {
                String url = p.get(Prefs.HA_URL).replaceFirst("^http", "ws").replaceAll("/+$", "") + "/api/websocket";
                if (url.startsWith("wss")) throw new Exception("use the LAN http:// address (no TLS on old tablets)");
                MiniWebSocket w = new MiniWebSocket();
                ws = w;
                setStatus(false, "Connecting…");
                w.connect(url, new MiniWebSocket.Listener() {
                    @Override public void onText(String text) { lastHeard = System.currentTimeMillis(); handle(w, text); }
                    @Override public void onClosed(String why) {
                        synchronized (done) { closed[0] = true; done.notifyAll(); }
                    }
                });
                lastHeard = System.currentTimeMillis();
                synchronized (done) {
                    while (!closed[0] && running) {
                        done.wait(30000);
                        if (closed[0]) break;
                        // heartbeat: a Wi-Fi drop can leave the socket silently dead; reconnect if HA stops answering
                        if (System.currentTimeMillis() - lastHeard > 75_000L) { w.close(); break; }
                        if (authed) try { send(new JSONObject().put("type", "ping"), null); } catch (Exception ignored) { }
                    }
                }
                backoff = authed ? 2 : backoff;
            } catch (Exception e) {
                setStatus(false, "Can't reach Home Assistant: " + e.getMessage());
            }
            authed = false;
            subId = -1;
            failPending("disconnected");
            sender.shutdownNow();
            sender = java.util.concurrent.Executors.newSingleThreadExecutor();
            if (!"Token rejected".equals(status)) setStatus(false, "Reconnecting…");
            sleep(backoff * 1000L);
            backoff = Math.min(backoff * 2, 30);
        }
    }

    private void handle(MiniWebSocket w, String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            switch (type) {
                case "auth_required":
                    w.sendText(new JSONObject().put("type", "auth").put("access_token", Prefs.of(ctx).get(Prefs.HA_TOKEN)).toString());
                    break;
                case "auth_ok":
                    sender.execute(() -> {
                        try {
                            subId = ids.getAndIncrement();
                            w.sendText(new JSONObject().put("id", subId).put("type", "subscribe_entities").toString());
                        } catch (Exception e) { Log.w("Ha", "subscribe: " + e); }
                    });
                    authed = true;
                    setStatus(true, "Connected");
                    // tablets with a wrong system time zone (shell can't change it) show HA's zone instead
                    send(new JSONObject().put("type", "get_config"), (res, err) -> {
                        String tz = res == null ? "" : res.optString("time_zone", "");
                        JSONObject us = res == null ? null : res.optJSONObject("unit_system");
                        if (us != null) Tiles.haCelsius = "°C".equals(us.optString("temperature"));
                        if (!tz.isEmpty()) { Prefs.of(ctx).raw().edit().putString("ha_tz", tz).apply(); applyTimeZone(tz); }
                        if (res != null && res.has("latitude")) Prefs.of(ctx).raw().edit()
                                .putFloat("ha_lat", (float) res.optDouble("latitude")).putFloat("ha_lon", (float) res.optDouble("longitude")).apply();
                    });
                    break;
                case "auth_invalid":
                    setStatus(false, "Token rejected");
                    w.close();
                    break;
                case "event":
                    if (m.optInt("id") == subId) applyDiff(m.getJSONObject("event"));
                    break;
                case "result": {
                    Result r = pending.remove(m.optInt("id"));
                    if (r != null) {
                        boolean ok = m.optBoolean("success");
                        JSONObject res = m.optJSONObject("result");
                        String err = ok ? null : m.optJSONObject("error") != null ? m.optJSONObject("error").optString("message") : "failed";
                        main.post(() -> r.done(res, err));
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w("Ha", "message: " + e);
        }
    }

    /** subscribe_entities compressed format: a = added (full), c = changed (+ adds, - removes), r = removed ids. */
    private void applyDiff(JSONObject ev) throws Exception {
        Set<String> changed = new HashSet<>();
        JSONObject a = ev.optJSONObject("a");
        if (a != null) for (Iterator<String> it = a.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject s = a.getJSONObject(id);
            Ent e = new Ent(id);
            e.state = s.optString("s");
            e.attrs = s.optJSONObject("a") != null ? s.optJSONObject("a") : new JSONObject();
            e.lastChanged = s.optDouble("lc", 0);
            states.put(id, e);
            changed.add(id);
        }
        JSONObject c = ev.optJSONObject("c");
        if (c != null) for (Iterator<String> it = c.keys(); it.hasNext(); ) {
            String id = it.next();
            Ent e = states.get(id);
            if (e == null) continue;
            JSONObject d = c.getJSONObject(id);
            JSONObject plus = d.optJSONObject("+");
            if (plus != null) {
                if (plus.has("s")) e.state = plus.optString("s");
                if (plus.has("lc")) e.lastChanged = plus.optDouble("lc");
                JSONObject pa = plus.optJSONObject("a");
                if (pa != null) {
                    JSONObject merged = new JSONObject(e.attrs.toString());
                    for (Iterator<String> k = pa.keys(); k.hasNext(); ) { String key = k.next(); merged.put(key, pa.get(key)); }
                    e.attrs = merged;
                }
            }
            JSONObject minus = d.optJSONObject("-");
            if (minus != null && minus.optJSONArray("a") != null) {
                JSONObject merged = new JSONObject(e.attrs.toString());
                JSONArray rm = minus.getJSONArray("a");
                for (int i = 0; i < rm.length(); i++) merged.remove(rm.getString(i));
                e.attrs = merged;
            }
            changed.add(id);
        }
        JSONArray r = ev.optJSONArray("r");
        if (r != null) for (int i = 0; i < r.length(); i++) { states.remove(r.getString(i)); changed.add(r.getString(i)); }
        if (!changed.isEmpty()) main.post(() -> { for (Listener l : listeners) l.onChanged(changed); });
    }

    // ---------- calls ----------

    void callService(String domain, String service, String entityId, JSONObject data) {
        callService(domain, service, entityId, data, false, null);
    }

    void callService(String domain, String service, String entityId, JSONObject data, boolean wantResponse, Result r) {
        try {
            JSONObject m = new JSONObject().put("type", "call_service").put("domain", domain).put("service", service)
                    .put("service_data", data == null ? new JSONObject() : data);
            if (entityId != null) m.put("target", new JSONObject().put("entity_id", entityId));
            if (wantResponse) m.put("return_response", true);
            send(m, r);
        } catch (Exception e) {
            if (r != null) main.post(() -> r.done(null, e.getMessage()));
        }
    }

    void send(JSONObject m, Result r) {
        MiniWebSocket w = ws;
        if (w == null || !authed) { if (r != null) main.post(() -> r.done(null, "not connected")); return; }
        // HA requires message ids to increase in arrival order: number and send them on one thread, in order.
        try {
            sender.execute(() -> {
                int id = ids.getAndIncrement();
                if (r != null) pending.put(id, r);
                try { w.sendText(m.put("id", id).toString()); }
                catch (Exception e) { Result rr = pending.remove(id); if (rr != null) main.post(() -> rr.done(null, e.getMessage())); }
            });
        } catch (java.util.concurrent.RejectedExecutionException reconnecting) {
            if (r != null) main.post(() -> r.done(null, "not connected"));
        }
    }

    /** One sender per connection: a write stuck on a dead socket must not block the next connection (Wi-Fi drop on low battery). */
    private volatile java.util.concurrent.ExecutorService sender = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile long lastHeard;

    /** Entities grouped for the setup page: domain → ids, only kinds the kiosk knows how to show. */
    Map<String, List<Ent>> pickable() {
        Map<String, List<Ent>> m = new HashMap<>();
        for (Ent e : states.values()) {
            if (!Tiles.supports(e.domain())) continue;
            List<Ent> l = m.get(e.domain());
            if (l == null) { l = new ArrayList<>(); m.put(e.domain(), l); }
            l.add(e);
        }
        for (List<Ent> l : m.values()) java.util.Collections.sort(l, (x, y) -> x.name().compareToIgnoreCase(y.name()));
        return m;
    }

    private void failPending(String why) {
        for (Integer id : new ArrayList<>(pending.keySet())) {
            Result r = pending.remove(id);
            if (r != null) main.post(() -> r.done(null, why));
        }
    }

    private void setStatus(boolean ok, String msg) {
        status = msg;
        main.post(() -> { for (Listener l : listeners) l.onStatus(ok, msg); });
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
