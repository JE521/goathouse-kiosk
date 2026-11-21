package org.goathouse.kiosk;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * What one tablet's dashboard shows. Every value is an HA entity id (or a pattern) editable from the phone setup page.
 * Defaults come from the house profile (assets/house.json, built in from the gitignored house/ folder, see
 * house.example.json) so one build carries a household's preset; without one they're generic. Missing entities are hidden.
 */
final class Dash {
    static final String MAIN = "dash_main", ROOMS = "dash_rooms", SHOP = "dash_shop", MEDIA = "dash_media",
            WEATHER = "dash_weather", FLIGHTS = "dash_flights", KEEP = "dash_keep", TRASH_DAY = "dash_trash_day",
            BIRTHDAYS = "dash_birthdays", ALARM_SWITCH = "dash_alarm_switch", ALARM_TIME = "dash_alarm_time",
            PRESENCE = "dash_presence", MAP_TILES = "dash_map_tiles",
            LAYOUT = "dash_layout", LISTS = "dash_lists", CLIMATE = "dash_climate", ROOM_SENSOR = "dash_room_sensor",
            CALENDARS = "dash_calendars", PEOPLE = "dash_people", BED_FROM = "dash_bed_from", BED_TO = "dash_bed_to";

    private final Prefs p;
    private final Context ctx;
    private Dash(Context c) { p = Prefs.of(c); ctx = c.getApplicationContext(); }
    static Dash of(Context c) { return new Dash(c); }

    String main() { return p.get(MAIN, def(MAIN, "light.kitchen")); }
    List<String> rooms() { return lines(p.get(ROOMS, def(ROOMS, ""))); }
    String shop() { return p.get(SHOP, def(SHOP, "todo.shopping_list")); }
    /** Now Playing source: the speaker set on the phone page, else this tablet's own Music Assistant player (by name). */
    String media() {
        String set = mediaSetting();
        if (!set.isEmpty()) return set;
        String name = SendspinService.playerName(ctx);
        String own = "";
        for (Ha.Ent e : Ha.get(ctx).states.values()) {
            if (!"media_player".equals(e.domain()) || e.unavailable()) continue;
            boolean ma = "music_assistant".equals(e.attrs.optString("app_id")) || e.attrs.has("mass_player_type");
            String fn = e.attrs.optString("friendly_name");
            if (ma && (fn.equalsIgnoreCase(name) || fn.equalsIgnoreCase(name + " player"))) { own = e.id; break; }
        }
        if (own.isEmpty()) return "";
        // Music on a group this speaker belongs to (e.g. "Everywhere"): show and control the group instead.
        Ha.Ent me = Ha.get(ctx).states.get(own);
        if (me != null && ("playing".equals(me.state) || "paused".equals(me.state)) && !me.attrs.optString("media_title").isEmpty()) return own;
        for (Ha.Ent g : Ha.get(ctx).states.values()) {
            if (!"media_player".equals(g.domain()) || g.id.equals(own) || !"playing".equals(g.state)) continue;
            org.json.JSONArray members = g.attrs.optJSONArray("group_members");
            for (int i = 0; members != null && i < members.length(); i++)
                if (own.equals(members.optString(i)) && members.length() > 1) return g.id;
        }
        return own;
    }
    String weather() { return p.get(WEATHER, def(WEATHER, "weather.forecast_home")); }
    String flights() { return p.get(FLIGHTS, def(FLIGHTS, "")); }
    /** "Last to bed" leaves these on (id prefixes). Switches (e.g. the back porch) are never touched by it. */
    List<String> keep() { return lines(p.get(KEEP, def(KEEP, ""))); }
    /** Calendar.DAY_OF_WEEK of trash night (Tuesday); 0 = no trash alert. */
    int trashDay() { return p.num(TRASH_DAY, defNum(TRASH_DAY, 0)); }
    String birthdays() { return p.get(BIRTHDAYS, def(BIRTHDAYS, "")); }
    String alarmSwitch() { return p.get(ALARM_SWITCH, def(ALARM_SWITCH, "")); }
    String alarmTime() { return p.get(ALARM_TIME, def(ALARM_TIME, "")); }
    /** Optional motion/presence sensor that wakes the night screen; blank = wake on touch only. */
    String presence() { return p.get(PRESENCE, def(PRESENCE, "")); }
    /** Radar basemap tile URL with {z}/{x}/{y}; blank = OpenStreetMap, darkened (open, no key). */
    String mediaSetting() { return p.get(MEDIA, ""); }

    // ---------- "room" layout (a bedroom panel, like the HA room dashboards): alarms + lists instead of timers + shopping ----------
    boolean room() { return "room".equals(p.get(LAYOUT, "kitchen")); }
    /** Hallway: tapping the idle screen (or a goatprint scan) opens the personal brief instead of the controls. */
    boolean hallway() { return "hallway".equals(p.get(LAYOUT, "kitchen")); }

    // ---------- goatprint people: "person.x=calendar.a,calendar.b;todo.y" per line ----------
    List<String> peopleLines() {
        return lines(p.get(PEOPLE, def(PEOPLE, "")));
    }
    List<String> people() {
        List<String> out = new java.util.ArrayList<>();
        for (String l : peopleLines()) out.add(l.split("=", 2)[0].trim());
        return out;
    }
    private String[] personParts(String person) {
        for (String l : peopleLines()) {
            String[] kv = l.split("=", 2);
            if (kv[0].trim().equals(person)) {
                String[] parts = (kv.length > 1 ? kv[1] : "").split(";", -1);
                return new String[]{parts[0].trim(), parts.length > 1 ? parts[1].trim() : ""};
            }
        }
        return new String[]{"", ""};
    }
    List<String> personCalendars(String person) {
        List<String> out = new java.util.ArrayList<>();
        for (String c : personParts(person)[0].split(",")) if (!c.trim().isEmpty()) out.add(c.trim());
        return out;
    }
    String personTodo(String person) { return personParts(person)[1]; }
    /** To-do lists shown as tabs in the room layout (first = default). */
    List<String> lists() { return lines(p.get(LISTS, def(LISTS, "todo.shopping_list"))); }
    String climate() { return p.get(CLIMATE, def(CLIMATE, "")); }
    /** Calendars whose next events show under the alarms in the room layout. */
    List<String> calendars() { return lines(p.get(CALENDARS, def(CALENDARS, ""))); }
    /** Template sensor with the room's temperature/humidity text (e.g. sensor.bedroom_climate). */
    String roomSensor() { return p.get(ROOM_SENSOR, def(ROOM_SENSOR, "")); }
    /** Second alarm: same entity names with _2 instead of _1. */
    String alarm2Switch() { return alarmSwitch().replaceAll("_1$", "_2"); }
    String alarm2Time() { return alarmTime().replaceAll("_1_time$", "_2_time"); }
    String playerName() { return SendspinService.playerName(ctx); }
    String mapTiles() { return p.get(MAP_TILES, def(MAP_TILES, "")); }
    int bedFrom() { return p.num(BED_FROM, 20); }
    int bedTo() { return p.num(BED_TO, 6); }

    boolean bedtime() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int a = bedFrom(), b = bedTo();
        return a > b ? (h >= a || h < b) : (h >= a && h < b);
    }

    /** Trash alert: from 00:00 on trash day until 05:00 the next morning. */
    boolean trashNight() {
        int day = trashDay();
        if (day == 0) return false;
        Calendar c = Calendar.getInstance();
        int dow = c.get(Calendar.DAY_OF_WEEK), h = c.get(Calendar.HOUR_OF_DAY);
        int next = day % 7 + 1;
        return dow == day || (dow == next && h < 5);
    }

    // ---------- house profile defaults ----------
    private static org.json.JSONObject house;

    private String def(String key, String generic) {
        org.json.JSONObject h = house(ctx);
        if (!h.has(key)) return generic;
        Object v = h.opt(key);
        if (v instanceof org.json.JSONArray) {
            StringBuilder b = new StringBuilder();
            org.json.JSONArray a = (org.json.JSONArray) v;
            for (int i = 0; i < a.length(); i++) b.append(i > 0 ? "\n" : "").append(a.optString(i));
            return b.toString();
        }
        return String.valueOf(v);
    }

    private int defNum(String key, int generic) { return house(ctx).optInt(key, generic); }

    static synchronized org.json.JSONObject house(Context c) {
        if (house != null) return house;
        try (java.io.InputStream in = c.getAssets().open("house.json")) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            house = new org.json.JSONObject(o.toString("UTF-8"));
        } catch (Exception e) {
            house = new org.json.JSONObject();     // no profile: generic defaults
        }
        return house;
    }

    static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : s.split("\n")) if (!l.trim().isEmpty()) out.add(l.trim());
        return out;
    }
}
