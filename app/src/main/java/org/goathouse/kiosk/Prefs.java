package org.goathouse.kiosk;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** All settings live in one private prefs file. Secrets (the HA token) never leave the device. */
final class Prefs {
    static final String HA_URL = "ha_url", HA_TOKEN = "ha_token";
    static final String TILES = "tiles";                 // newline-separated entity ids, in display order
    static final String NIGHT_DIM = "night_dim", NIGHT_FROM = "night_from", NIGHT_TO = "night_to", NIGHT_LEVEL = "night_level";
    static final String DRIFT = "drift", ORIENTATION = "orientation", PERFORMANCE = "performance";
    static final String VERSION = "version";             // bumped on every save so screens know to rebuild

    private final SharedPreferences p;
    private final Context ctx;

    private Prefs(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences("kiosk", Context.MODE_PRIVATE);
    }

    static Prefs of(Context c) { return new Prefs(c); }

    String get(String k) { return p.getString(k, ""); }
    String get(String k, String def) { return p.getString(k, def); }
    boolean on(String k, boolean def) { return p.getBoolean(k, def); }
    int num(String k, int def) { return p.getInt(k, def); }
    SharedPreferences raw() { return p; }

    void put(String k, String v) { p.edit().putString(k, v).putLong(VERSION, System.currentTimeMillis()).apply(); }
    void put(String k, boolean v) { p.edit().putBoolean(k, v).putLong(VERSION, System.currentTimeMillis()).apply(); }
    void put(String k, int v) { p.edit().putInt(k, v).putLong(VERSION, System.currentTimeMillis()).apply(); }

    boolean hasHa() { return !get(HA_URL).isEmpty() && !get(HA_TOKEN).isEmpty(); }

    List<String> tiles() {
        String s = get(TILES);
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        for (String id : Arrays.asList(s.split("\n"))) if (!id.trim().isEmpty()) out.add(id.trim());
        return out;
    }

    /** "auto" decides from RAM: 1 GB-class tablets get Lite (no blur, no animations, slower refresh). */
    boolean lite() {
        String mode = get(PERFORMANCE, "auto");
        if ("lite".equals(mode)) return true;
        if ("full".equals(mode)) return false;
        return totalRamMb(ctx) < 1600;
    }

    static long totalRamMb(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem / (1024 * 1024);
    }
}
