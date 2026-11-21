package org.goathouse.kiosk;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone side of setup (from the TV launcher): a tiny HTTP server that only runs while the Setup screen is open,
 * answers only requests carrying the one-time PIN in the QR code, and never sends the saved token back.
 */
final class SetupServer {
    interface Listener { void onSaved(); }

    static final int PORT = 8780;
    static final String[] ORDER = {"weather", "person", "calendar", "light", "switch", "scene", "script", "climate", "timer",
            "todo", "media_player", "fan", "cover", "lock", "input_boolean", "button", "binary_sensor", "sensor"};
    static final Map<String, String> LABEL = new HashMap<>();
    static {
        String[][] l = {{"weather", "Weather (first one shows by the clock)"}, {"person", "People"}, {"calendar", "Calendars"},
                {"light", "Lights"}, {"switch", "Switches"}, {"scene", "Scenes"}, {"script", "Scripts"}, {"climate", "Thermostats"},
                {"timer", "Timers"}, {"todo", "Lists"}, {"media_player", "Speakers & players"}, {"fan", "Fans"}, {"cover", "Blinds & doors"},
                {"lock", "Locks"}, {"input_boolean", "Toggles"}, {"button", "Buttons"}, {"binary_sensor", "Door / motion sensors"}, {"sensor", "Sensors"}};
        for (String[] p : l) LABEL.put(p[0], p[1]);
    }
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final Listener listener;
    final String pin;
    private ServerSocket server;
    private volatile boolean running;

    SetupServer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        pin = String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    void start() {
        running = true;
        new Thread(() -> {
            try (ServerSocket s = new ServerSocket(PORT)) {
                server = s;
                while (running) {
                    Socket c = s.accept();
                    new Thread(() -> handle(c), "setup-client").start();
                }
            } catch (Exception e) {
                if (running) Log.w("SetupServer", "stopped: " + e);
            }
        }, "setup-server").start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0], target = req[1];
            int len = 0;
            for (String l : lines) if (l.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
            byte[] body = new byte[Math.min(len, 262144)];
            int off = 0;
            while (off < body.length) {
                int n = in.read(body, off, body.length - off);
                if (n < 0) break;
                off += n;
            }
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Map<String, List<String>> q = parse(target.contains("?") ? target.substring(target.indexOf('?') + 1) : "");
            Map<String, List<String>> form = parse(new String(body, 0, off, UTF8));
            String givenPin = q.containsKey("pin") ? first(q, "pin") : first(form, "pin");
            OutputStream out = sock.getOutputStream();
            if (!pin.equals(givenPin)) {
                send(out, 403, page("Wrong or expired link", "<p>Open <b>Setup</b> on the tablet again and scan the new code.</p>"));
                return;
            }
            if ("POST".equals(method) && "/save".equals(path)) {
                save(form);
                send(out, 200, form(true));
                listener.onSaved();
            } else {
                send(out, 200, form(false));
            }
        } catch (Exception e) {
            Log.w("SetupServer", "client: " + e);
        }
    }

    private static final String[][] DASH_FIELDS = {
            {Dash.MAIN, "Main light (big button)", "light.kitchen"},
            {Dash.ROOMS, "Room buttons, one per line", "light.living_room"},
            {Dash.SHOP, "Shopping list", "todo.shopping_list"},
            {SendspinService.PLAYER_NAME, "This tablet's speaker name in Music Assistant", "Kitchen panel"},
            {Dash.MEDIA, "Speaker for Now Playing (blank = this tablet)", "media_player.kitchen_tablet"},
            {Dash.WEATHER, "Weather", "weather.forecast_home"},
            {Dash.FLIGHTS, "Planes overhead sensor", "sensor.flightradar24_current_in_area"},
            {Dash.KEEP, "Last to bed keeps on (id prefixes, one per line)", "light.kitchen"},
            {Dash.BIRTHDAYS, "Birthdays calendar", "calendar.contact_birthdays"},
            {Dash.ALARM_SWITCH, "Alarm switch (night screen)", "switch.…_alarm_1"},
            {Dash.ALARM_TIME, "Alarm time (night screen)", "time.…_alarm_1_time"},
            {Dash.PRESENCE, "Presence sensor that wakes the night screen (optional)", "binary_sensor.kitchen_presence"},
            {Dash.MAP_TILES, "Radar map tiles (optional, {z}/{x}/{y} URL)", "blank = OpenStreetMap, darkened"},
            {Dash.LAYOUT, "Layout: kitchen (timers + shopping), room (alarms + lists) or hallway (goatprint brief)", "kitchen"},
            {Dash.LISTS, "Room layout: to-do lists as tabs, one per line", "todo.tasks"},
            {Dash.CLIMATE, "Room layout: thermostat (optional)", "climate.thermostat"},
            {Dash.ROOM_SENSOR, "Room layout: room temperature sensor (optional)", "sensor.bedroom_temperature"},
            {Dash.CALENDARS, "Room layout: calendars for Coming up, one per line", "calendar.personal"},
            {Dash.PEOPLE, "Hallway: people, one per line: person.x=calendar.a,calendar.b;todo.y", "person.alex=calendar.alex;todo.alex"}};

    private void save(Map<String, List<String>> f) {
        Prefs p = Prefs.of(ctx);
        String oldName = SendspinService.playerName(ctx);
        for (String[] d : DASH_FIELDS) {
            String v = first(f, d[0]);
            if (v != null) p.put(d[0], v.replace("\r", "").trim());
        }
        if (!oldName.equals(SendspinService.playerName(ctx))) SendspinService.restart(ctx);
        String td = first(f, Dash.TRASH_DAY);
        if (td != null) try { p.put(Dash.TRASH_DAY, Integer.parseInt(td)); } catch (NumberFormatException ignored) { }
        String url = first(f, Prefs.HA_URL);
        if (url != null) {
            url = url.trim().replaceAll("/+$", "");
            if (!url.isEmpty() && !url.matches("^[a-zA-Z]+://.*")) url = "http://" + url;
            p.put(Prefs.HA_URL, url);
        }
        String token = first(f, Prefs.HA_TOKEN);
        if (f.containsKey("clear_token")) p.put(Prefs.HA_TOKEN, "");
        else if (token != null && !token.trim().isEmpty()) p.put(Prefs.HA_TOKEN, token.trim());
        if (f.containsKey("tiles_present")) {
            List<String> picked = f.containsKey("tile") ? f.get("tile") : new ArrayList<>();
            StringBuilder b = new StringBuilder();
            for (String id : picked) { if (b.length() > 0) b.append('\n'); b.append(id); }
            p.put(Prefs.TILES, b.toString());
        }
    }

    private String form(boolean justSaved) {
        Prefs p = Prefs.of(ctx);
        Ha ha = Ha.get(ctx);
        StringBuilder b = new StringBuilder();
        if (justSaved) b.append("<div class=ok>Saved. The tablet is updating — reload this page in a few seconds to pick tiles.</div>");
        b.append("<form method=post action='/save'><input type=hidden name=pin value='").append(pin).append("'>");
        b.append("<section><h2>Home Assistant</h2><p class=sub>Use the LAN address (http://…:8123). Old tablets can't do modern HTTPS.</p>");
        boolean ok = ha.connected();
        b.append("<p class='st ").append(ok ? "good'>✓ Connected" : "bad'>✕ " + esc(ha.status())).append("</p>");
        b.append("<label>Server address</label><input name='").append(Prefs.HA_URL).append("' value='").append(esc(p.get(Prefs.HA_URL)))
                .append("' placeholder='http://192.168.1.10:8123' autocapitalize=off autocorrect=off spellcheck=false>");
        boolean saved = !p.get(Prefs.HA_TOKEN).isEmpty();
        b.append("<label>Long-lived token</label><input type=password name='").append(Prefs.HA_TOKEN).append("' placeholder='")
                .append(saved ? "saved, leave blank to keep" : "Profile → Security → Long-lived access tokens").append("' autocomplete=off>");
        if (saved) b.append("<label class=chk><input type=checkbox name=clear_token> remove saved token</label>");
        b.append("<p class=hint>Make the token on a non-admin user (e.g. <b>kiosk</b>) — the tablet can only do what that user can.</p></section>");

        if (ok) {
            List<String> picked = p.tiles();
            boolean auto = picked.isEmpty();
            Map<String, List<Ha.Ent>> groups = ha.pickable();
            b.append("<section><h2>On this tablet</h2><p class=sub>").append(auto ? "Nothing picked yet: showing all lights, weather and people."
                    : picked.size() + " picked.").append(" Tick what this room needs.</p><input type=hidden name=tiles_present value=1>");
            for (String d : ORDER) {
                List<Ha.Ent> list = groups.get(d);
                if (list == null || list.isEmpty()) continue;
                int on = 0;
                for (Ha.Ent e : list) if (picked.contains(e.id)) on++;
                boolean big = list.size() > 12;
                b.append("<details").append(!big || on > 0 ? " open" : "").append("><summary>").append(esc(LABEL.get(d)))
                        .append(" <span class=n>").append(on > 0 ? on + " / " : "").append(list.size()).append("</span></summary><div class=grid>");
                for (Ha.Ent e : list) {
                    b.append("<label class=chk><input type=checkbox name=tile value='").append(esc(e.id)).append("'")
                            .append(picked.contains(e.id) ? " checked" : "").append("> <span>").append(esc(e.name()))
                            .append("<small>").append(esc(e.id)).append("</small></span></label>");
                }
                b.append("</div></details>");
            }
            b.append("</section>");
        }
        Dash dash = Dash.of(ctx);
        b.append("<section><h2>This dashboard</h2><p class=sub>Entity ids for this tablet. Blank hides that part.</p>");
        for (String[] d : DASH_FIELDS) {
            String cur = currentDash(dash, d[0]);
            b.append("<label>").append(esc(d[1])).append("</label>");
            if (d[0].equals(Dash.ROOMS) || d[0].equals(Dash.KEEP) || d[0].equals(Dash.LISTS) || d[0].equals(Dash.PEOPLE) || d[0].equals(Dash.CALENDARS))
                b.append("<textarea name='").append(d[0]).append("' rows=4 placeholder='").append(esc(d[2])).append("'>").append(esc(cur)).append("</textarea>");
            else
                b.append("<input name='").append(d[0]).append("' value='").append(esc(cur)).append("' placeholder='").append(esc(d[2]))
                        .append("' autocapitalize=off autocorrect=off spellcheck=false>");
        }
        String[] days = {"No trash alert", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        b.append("<label>Trash night</label><select name='").append(Dash.TRASH_DAY).append("'>");
        for (int i = 0; i < days.length; i++)
            b.append("<option value=").append(i).append(dash.trashDay() == i ? " selected" : "").append(">").append(days[i]).append("</option>");
        b.append("</select></section>");
        b.append("<button type=submit>Save</button></form>");
        return page("Tablet setup", b.toString());
    }

    private static String currentDash(Dash d, String key) {
        switch (key) {
            case Dash.MAIN: return d.main();
            case Dash.ROOMS: return android.text.TextUtils.join("\n", d.rooms());
            case Dash.SHOP: return d.shop();
            case Dash.MEDIA: return d.mediaSetting();
            case Dash.WEATHER: return d.weather();
            case Dash.FLIGHTS: return d.flights();
            case Dash.KEEP: return android.text.TextUtils.join("\n", d.keep());
            case Dash.BIRTHDAYS: return d.birthdays();
            case Dash.ALARM_SWITCH: return d.alarmSwitch();
            case Dash.ALARM_TIME: return d.alarmTime();
            case Dash.PRESENCE: return d.presence();
            case Dash.MAP_TILES: return d.mapTiles();
            case Dash.LAYOUT: return d.hallway() ? "hallway" : d.room() ? "room" : "kitchen";
            case Dash.LISTS: return android.text.TextUtils.join("\n", d.lists());
            case Dash.CLIMATE: return d.climate();
            case Dash.ROOM_SENSOR: return d.roomSensor();
            case Dash.CALENDARS: return android.text.TextUtils.join("\n", d.calendars());
            case Dash.PEOPLE: return android.text.TextUtils.join("\n", d.peopleLines());
            case SendspinService.PLAYER_NAME: return d.playerName();
            default: return "";
        }
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title><style>"
                + ":root{color-scheme:dark}body{margin:0;background:#0b0d12;color:#eef;font:16px/1.4 system-ui,sans-serif}"
                + "main{max-width:620px;margin:0 auto;padding:20px 16px 60px}h1{font-weight:600;margin:8px 0 4px}"
                + "section{background:#151922;border:1px solid #232a36;border-radius:16px;padding:16px;margin:16px 0}"
                + "h2{margin:0;font-size:19px}.sub{margin:2px 0 10px;color:#9aa4b8;font-size:14px}"
                + "label{display:block;font-size:13px;color:#b8c2d6;margin:12px 0 4px}"
                + "input:not([type=checkbox]),textarea,select{width:100%;box-sizing:border-box;background:#0b0d12;color:#fff;border:1px solid #2c3444;"
                + "border-radius:10px;padding:12px;font-size:16px}input:focus{outline:2px solid #7cc4ff}"
                + ".chk{display:flex;gap:10px;align-items:flex-start;color:#dfe6f3;font-size:15px;margin:6px 0}.chk input{margin-top:3px}"
                + ".chk small{display:block;color:#6f7a8f;font-size:11px}"
                + "details{border-top:1px solid #232a36;padding:8px 0}summary{cursor:pointer;font-weight:600;padding:6px 0}.n{color:#7d879b;font-weight:400}"
                + ".grid{display:grid;grid-template-columns:1fr 1fr;gap:0 14px}.hint{color:#7d879b;font-size:13px;margin:8px 0 0}"
                + "button{width:100%;padding:15px;border:0;border-radius:14px;background:#e9eef7;color:#0b0d12;font-size:17px;font-weight:600;"
                + "position:sticky;bottom:12px}"
                + ".st{margin:0 0 6px;font-size:14px}.good{color:#7ee2a8}.bad{color:#ff8f8f}"
                + ".ok{background:#163a26;color:#9ff0bf;border-radius:12px;padding:12px;margin:12px 0}"
                + "</style></head><body><main><h1>" + title + "</h1>" + body + "</main></body></html>";
    }

    private static void send(OutputStream out, int code, String html) throws Exception {
        byte[] b = html.getBytes(UTF8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Forbidden") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(Charset.forName("US-ASCII")));
        out.write(b);
        out.flush();
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) != -1) {
            h.write(c);
            if (h.size() > 16384) return null;
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return h.toString("ISO-8859-1");
        }
        return null;
    }

    private static Map<String, List<String>> parse(String s) throws Exception {
        Map<String, List<String>> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), "UTF-8");
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), "UTF-8");
            List<String> l = m.get(k);
            if (l == null) { l = new ArrayList<>(); m.put(k, l); }
            l.add(v);
        }
        return m;
    }

    private static String first(Map<String, List<String>> m, String k) {
        List<String> v = m.get(k);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
