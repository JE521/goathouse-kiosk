package org.goathouse.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Locale;

/** tvOS-style settings: rows of values; a row opens a short ✓ list or flips a toggle. Back goes up one page. */
public class SettingsActivity extends Activity {
    interface Page { void build(LinearLayout into); String title(); }

    private final ArrayDeque<Page> stack = new ArrayDeque<>();
    private Prefs prefs;
    private FrameLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.of(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        show(main());
    }

    @Override protected void onResume() {
        super.onResume();
        if (!stack.isEmpty()) render();
    }

    @Override public void onBackPressed() {
        if (stack.size() > 1) { stack.pop(); render(); } else finish();
    }

    private void show(Page p) { stack.push(p); render(); }

    private void render() {
        Page p = stack.peek();
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(28);
        col.setPadding(pad, pad, pad, pad);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.pill(this, stack.size() > 1 ? "‹  Back" : "‹  Done", v -> onBackPressed()));
        TextView t = Ui.text(this, p.title(), 28, Ui.TEXT, true);
        t.setPadding(dp(18), 0, 0, 0);
        top.addView(t);
        col.addView(top);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(18), 0, 0);
        p.build(body);
        col.addView(body);
        sv.addView(col);
        // keep rows readable on wide screens
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, dp(720)), -1,
                Gravity.CENTER_HORIZONTAL);
        root.addView(sv, lp);
    }

    // ---------- pages ----------

    private Page main() {
        return page("Settings", body -> {
            Ha ha = Ha.get(this);
            header(body, "Home Assistant");
            LinearLayout g = group(body);
            row(g, "Connection", ha.connected() ? "Connected" : ha.status(), v -> startActivity(new Intent(this, SetupActivity.class)));
            int n = prefs.tiles().size();
            row(g, "Tiles", (n == 0 ? "Automatic" : n + " picked") + " · change from phone", v -> startActivity(new Intent(this, SetupActivity.class)));

            header(body, "Screen");
            g = group(body);
            toggle(g, "Dim at night", Prefs.NIGHT_DIM, true);
            if (prefs.on(Prefs.NIGHT_DIM, true)) {
                row(g, "Night starts", hour(prefs.num(Prefs.NIGHT_FROM, 22)), v -> show(hours("Night starts", Prefs.NIGHT_FROM, 22, new int[]{19, 20, 21, 22, 23, 0})));
                row(g, "Morning", hour(prefs.num(Prefs.NIGHT_TO, 7)), v -> show(hours("Morning", Prefs.NIGHT_TO, 7, new int[]{5, 6, 7, 8, 9})));
                row(g, "Night brightness", prefs.num(Prefs.NIGHT_LEVEL, 5) + "%", v -> show(choice("Night brightness", Prefs.NIGHT_LEVEL, 5,
                        new int[]{1, 5, 10, 20, 40}, i -> i + "%")));
            }
            toggle(g, "Move clock slowly (protects the screen)", Prefs.DRIFT, true);
            row(g, "Orientation", Tiles.cap(prefs.get(Prefs.ORIENTATION, "auto")), v -> show(textChoice("Orientation", Prefs.ORIENTATION, "auto",
                    new String[]{"auto", "landscape", "portrait"})));

            header(body, "Performance");
            g = group(body);
            String mode = prefs.get(Prefs.PERFORMANCE, "auto");
            row(g, "Mode", "auto".equals(mode) ? "Automatic (" + (prefs.lite() ? "Lite" : "Full") + " on this tablet)" : Tiles.cap(mode),
                    v -> show(textChoice("Performance", Prefs.PERFORMANCE, "auto", new String[]{"auto", "lite", "full"})));
            hint(body, "Lite turns off gradients and motion and shows shorter lists. Automatic picks Lite under 1.6 GB of RAM.");

            header(body, "Coming next");
            g = group(body);
            disabled(g, "Music Assistant speaker", prefs.lite() ? "Off in Lite" : "Not in this build yet");
            disabled(g, "Voice assistant", prefs.lite() ? "Off in Lite" : "Not in this build yet");
            disabled(g, "Photo background", prefs.lite() ? "Off in Lite" : "Not in this build yet");

            header(body, "Tablet");
            g = group(body);
            toggle(g, "24-hour clock", "clock_24", false);
            row(g, "Temperature unit", "C".equals(prefs.get(Ui.TEMP_UNIT, "F")) ? "°C" : "°F (default)",
                    v -> show(textChoice("Temperature unit", Ui.TEMP_UNIT, "F", new String[]{"F", "C"})));
            String rs = Dash.of(this).roomSensor();
            Ha.Ent rse = Ha.get(this).states.get(rs);
            row(g, "Indoor temperature", rs.isEmpty() ? "Pick a sensor" : rse != null ? rse.name() : rs, v -> show(pickSensor()));
            row(g, "Android settings", "Wi-Fi, sound, date", v -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
            });
            Jellyfin jfl = Jellyfin.get(this);
            row(g, "Jellyfin cast", jfl.linked() ? jfl.status : "Not linked · tap to link",
                    v -> startActivity(new Intent(this, JellyfinLinkActivity.class)));
            Goatprint gp = Goatprint.get(this);
            row(g, "Fingerprints", gp.entries().size() + " enrolled · " + gp.status(), v -> show(fingerprints()));
            for (String pkg : new String[]{"dev.imranr.obtainium.fdroid", "dev.imranr.obtainium"}) {
                Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                if (li != null) { row(g, "Obtainium", "App updates", v -> { try { startActivity(li); } catch (Exception ignored) { } }); break; }
            }

            header(body, "About");
            g = group(body);
            String ver = "";
            try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
            info(g, "Version", ver);
            info(g, "Tablet", Build.MANUFACTURER + " " + Build.MODEL);
            info(g, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            info(g, "Memory", String.format(Locale.US, "%.1f GB", Prefs.totalRamMb(this) / 1024f));
            String ip = SetupActivity.lanIp();
            info(g, "Address", ip == null ? "No network" : ip);
        });
    }

    /** Temperature sensors from HA (device_class temperature or a °F/°C unit) for the indoor reading. */
    private Page pickSensor() {
        return page("Indoor temperature", body -> {
            LinearLayout g = group(body);
            String cur = Dash.of(this).roomSensor();
            row(g, "None", cur.isEmpty() ? "✓" : "", x -> { prefs.put(Dash.ROOM_SENSOR, ""); onBackPressed(); });
            java.util.List<Ha.Ent> list = new java.util.ArrayList<>();
            for (Ha.Ent e : Ha.get(this).states.values()) {
                if (!"sensor".equals(e.domain()) || e.unavailable()) continue;
                String u = e.attrs.optString("unit_of_measurement");
                if ("temperature".equals(e.attrs.optString("device_class")) || "°F".equals(u) || "°C".equals(u)) list.add(e);
            }
            java.util.Collections.sort(list, (a, b) -> a.name().compareToIgnoreCase(b.name()));
            for (Ha.Ent e : list) {
                String val = Tiles.deg(e.state, "°C".equals(e.attrs.optString("unit_of_measurement"))) + Tiles.unit();
                row(g, e.name(), e.id.equals(cur) ? "✓" : val, x -> { prefs.put(Dash.ROOM_SENSOR, e.id); onBackPressed(); });
            }
        });
    }

    // ---------- goatprint ----------

    private Goatprint.Entry pendingDelete;

    private Page fingerprints() {
        return page("Fingerprints", body -> {
            Goatprint gp = Goatprint.get(this);
            LinearLayout g = group(body);
            info(g, "Reader", gp.status());
            header(body, "Enrolled");
            g = group(body);
            if (gp.entries().isEmpty()) info(g, "Nobody yet", "The first finger becomes the admin");
            for (Goatprint.Entry e : gp.entries())
                row(g, Tiles.cap(e.name) + (e.admin ? " · admin" : "") + (e.duress ? " · duress" : ""), e.finger.replace('_', ' ') + " · " + e.templates.size() + " samples"
                        + (e.origin.isEmpty() ? "" : " · from " + e.origin),
                        v -> { pendingDelete = e; show(confirmDelete()); });
            header(body, "Add");
            g = group(body);
            row(g, "Add a finger", gp.hasAdmin() ? "An admin finger approves" : "First finger becomes admin", v -> GoatprintEnrollActivity.open(this, null));
            GoatprintNode n = GoatprintNode.get(this);
            header(body, "Home network");
            g = group(body);
            info(g, "Events to Home Assistant", n.status());
            row(g, "This tablet's identity", n.node(), v -> show(identity()));
            java.util.Map<String, String> acc = n.accepted();
            info(g, "Takes enrollments from", acc.isEmpty() ? "Nobody · add one in Setup" : android.text.TextUtils.join(", ", acc.keySet()));
            row(g, "Self-test", "Signing and import against goatprint's test vectors", v -> show(page("Self-test", b2 -> {
                android.widget.TextView t = Style.t(this, GoatprintNode.selfTest(this), 16, 400, Style.WHITE80);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                b2.addView(t);
            })));
        });
    }

    /** QR + text of the gpnode1 line, for `sudo goatprintctl trust <line>` on the enrolling node. */
    private Page identity() {
        return page("This tablet's identity", body -> {
            String id = GoatprintNode.get(this).identity();
            android.widget.ImageView q = new android.widget.ImageView(this);
            q.setImageBitmap(SetupActivity.qr(id, Style.u(360)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Style.u(360), Style.u(360));
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            lp.topMargin = lp.bottomMargin = Style.u(16);
            body.addView(q, lp);
            android.widget.TextView t = Style.t(this, id, 15, 400, Style.WHITE70);
            t.setTextIsSelectable(true);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.addView(t);
            android.widget.TextView h = Style.t(this, "On the enrolling node (the desk PC): sudo goatprintctl trust <this line>. Then tablet Setup → Fingerprints: paste that node's `goatprintctl id` to accept its enrollments.", 15, 400, Style.WHITE60);
            h.setPadding(0, Style.u(16), 0, 0);
            body.addView(h);
        });
    }

    private Page confirmDelete() {
        Goatprint.Entry e = pendingDelete;
        return page("Remove " + Tiles.cap(e.name) + " " + e.finger.replace('_', ' ') + "?", body -> {
            LinearLayout g = group(body);
            row(g, "Remove this finger", "", v -> { Goatprint.get(this).delete(e.name, e.finger); onBackPressed(); });
        });
    }

    interface Label { String of(int v); }

    private Page hours(String title, String key, int def, int[] values) { return choice(title, key, def, values, this::hour); }

    private Page choice(String title, String key, int def, int[] values, Label label) {
        return page(title, body -> {
            LinearLayout g = group(body);
            int cur = prefs.num(key, def);
            for (int v : values) row(g, label.of(v), v == cur ? "✓" : "", x -> { prefs.put(key, v); onBackPressed(); });
        });
    }

    private Page textChoice(String title, String key, String def, String[] values) {
        return page(title, body -> {
            LinearLayout g = group(body);
            String cur = prefs.get(key, def);
            for (String v : values) row(g, Tiles.cap(v), v.equals(cur) ? "✓" : "", x -> { prefs.put(key, v); onBackPressed(); });
        });
    }

    private String hour(int h) {
        boolean h24 = Ui.h24(this);
        if (h24) return String.format(Locale.US, "%02d:00", h);
        return (h % 12 == 0 ? 12 : h % 12) + ":00 " + (h < 12 ? "AM" : "PM");
    }

    // ---------- row builders ----------

    interface Builder { void build(LinearLayout into); }

    private Page page(String title, Builder b) {
        return new Page() {
            @Override public void build(LinearLayout into) { b.build(into); }
            @Override public String title() { return title; }
        };
    }

    private void header(LinearLayout body, String s) {
        TextView t = Ui.text(this, s, 15, Ui.TEXT3, true);
        t.setPadding(dp(6), dp(18), 0, dp(8));
        body.addView(t);
    }

    private void hint(LinearLayout body, String s) {
        TextView t = Ui.text(this, s, 13, Ui.HINT, false);
        t.setPadding(dp(6), dp(8), dp(6), 0);
        body.addView(t);
    }

    private LinearLayout group(LinearLayout body) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Ui.round(Ui.GLASS, dp(20)));
        body.addView(g);
        return g;
    }

    private void row(LinearLayout g, String label, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(60));
        r.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView l = Ui.text(this, label, 18, Ui.TEXT, true);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = Ui.text(this, value + (click != null && !"✓".equals(value) ? "  ›" : ""), 16, "✓".equals(value) ? Ui.TEXT : Ui.TEXT3, false);
        v.setGravity(Gravity.END);
        v.setMaxWidth(dp(320));
        r.addView(v);
        if (click != null) {
            r.setBackground(Ui.round(0, dp(20)));
            r.setOnClickListener(click);
            Ui.pressable(r, 0x00FFFFFF, Ui.GLASS_PRESSED);
        }
        if (g.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(0x1AFFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
            lp.leftMargin = dp(20);
            g.addView(div, lp);
        }
        g.addView(r);
    }

    private void toggle(LinearLayout g, String label, String key, boolean def) {
        row(g, label, prefs.on(key, def) ? "On" : "Off", v -> { prefs.put(key, !prefs.on(key, def)); render(); });
    }

    private void info(LinearLayout g, String label, String value) { row(g, label, value, null); }

    private void disabled(LinearLayout g, String label, String value) {
        row(g, label, value, null);
        g.getChildAt(g.getChildCount() - 1).setAlpha(0.45f);
    }

    private int dp(float v) { return Ui.dp(this, v); }
}
