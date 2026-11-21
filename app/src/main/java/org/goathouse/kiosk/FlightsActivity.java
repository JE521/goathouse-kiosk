package org.goathouse.kiosk;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;

/** Tap the plane: what's overhead (flightradar24 sensor) — a little radar plot and a card per flight. */
public class FlightsActivity extends Activity implements Ha.Listener {
    private Ha ha;
    private Dash dash;
    private Plot plot;
    private LinearLayout cards;
    private TextView count;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ha = Ha.get(this);
        dash = Dash.of(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override protected void onResume() {
        super.onResume();
        Style.brightness(this, false);
        Style.measure(this);
        build();
        ha.addListener(this);
        render();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override protected void onPause() { super.onPause(); ha.removeListener(this); }

    private int u(float v) { return Style.u(v); }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        root.addView(Kit.backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        root.addView(Kit.scrim(this, 0x8C060910), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(u(40), u(28), u(40), u(40));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Kit.iconButton(this, "back", u(52), Style.GLASS, Style.WHITE, "Back", v -> finish()));
        TextView t = Style.t(this, "Overhead now", 30, 500, Style.WHITE);
        t.setPadding(u(18), 0, 0, 0);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        count = Style.t(this, "", 17, 400, Style.WHITE80);
        top.addView(count);
        page.addView(top, new LinearLayout.LayoutParams(-1, u(60)));
        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1);
        blp.topMargin = u(22);
        page.addView(body, blp);
        plot = new Plot(this);
        plot.setBackground(Style.fill(0xE6121826, u(28)));
        body.addView(plot, new LinearLayout.LayoutParams(u(700), -1));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        sv.addView(cards);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -1, 1);
        slp.leftMargin = u(20);
        body.addView(sv, slp);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override public void onStatus(boolean connected, String message) { }
    @Override public void onChanged(Set<String> ids) { if (ids.contains(dash.flights())) render(); }

    private void render() {
        Ha.Ent e = ha.states.get(dash.flights());
        JSONArray f = e == null ? null : e.attrs.optJSONArray("flights");
        int n = f == null ? 0 : f.length();
        count.setText(n == 0 ? "Nothing overhead right now" : n == 1 ? "1 plane nearby" : n + " planes nearby");
        plot.set(f, Prefs.of(this).raw().getFloat("ha_lat", 0), Prefs.of(this).raw().getFloat("ha_lon", 0));
        cards.removeAllViews();
        for (int i = 0; i < n; i++) cards.addView(card(f.optJSONObject(i)), cardLp());
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = u(14);
        return lp;
    }

    private View card(JSONObject f) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(u(22), u(20), u(22), u(20));
        c.setBackground(Style.glassU(24));
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.BOTTOM);
        t.addView(Style.t(this, f.optString("callsign", f.optString("flight_number", "?")), 26, 600, Style.WHITE));
        String sub = join(f.optString("airline_short", f.optString("airline", "")), f.optString("aircraft_model", ""));
        TextView s = Style.t(this, sub, 16, 400, Style.WHITE70);
        s.setPadding(u(12), 0, 0, u(3));
        t.addView(s);
        c.addView(t);
        String from = city(f, "origin"), to = city(f, "destination");
        if (!from.isEmpty() || !to.isEmpty()) {
            TextView route = Style.t(this, (from.isEmpty() ? "?" : from) + "  →  " + (to.isEmpty() ? "?" : to), 20, 400, Style.WHITE);
            route.setPadding(0, u(10), 0, 0);
            c.addView(route);
        }
        LinearLayout stats = new LinearLayout(this);
        stats.setPadding(0, u(14), 0, 0);
        int alt = f.optInt("altitude", 0);
        double kts = f.optDouble("ground_speed", 0), km = f.optDouble("distance", 0);
        stats.addView(stat(alt > 0 ? String.format(Locale.US, "%,d ft", alt) : "–", "Altitude"), new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(stat(kts > 0 ? Math.round(kts * 1.15078) + " mph" : "–", "Speed"), new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(stat(km > 0 ? String.format(Locale.US, "%.0f mi", km * 0.621371) : "–", "Away"), new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(stats);
        return c;
    }

    private View stat(String v, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(Style.t(this, v, 24, 300, Style.WHITE));
        TextView l = Style.tcaps(this, label, 13, Style.WHITE60);
        l.setPadding(0, u(4), 0, 0);
        c.addView(l);
        return c;
    }

    private static String city(JSONObject f, String which) {
        String c = f.optString("airport_" + which + "_city", "");
        return c.isEmpty() || "null".equals(c) ? f.optString("airport_" + which + "_code_iata", "") : c;
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty() || "null".equals(a)) return b == null || "null".equals(b) ? "" : b;
        if (b == null || b.isEmpty() || "null".equals(b)) return a;
        return a + " · " + b;
    }

    /** Range rings around home, each plane placed by its real position and pointing along its heading. */
    static final class Plot extends View {
        private JSONArray flights;
        private float lat, lon;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), lbl = new Paint(Paint.ANTI_ALIAS_FLAG), box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path plane = new Path();
        Plot(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE); ring.setColor(0xFF24304A); ring.setStrokeWidth(Style.u(2));
            ring.setPathEffect(new DashPathEffect(new float[]{Style.u(6), Style.u(8)}, 0));
            dot.setColor(Style.CREAM);
            lbl.setColor(Style.WHITE); lbl.setTypeface(Style.font(c, 600)); lbl.setTextSize(Style.u(15)); lbl.setTextAlign(Paint.Align.CENTER);
            box.setColor(0x99000000);
            float s = Style.u(1.4f);
            plane.moveTo(0, -12 * s); plane.lineTo(1.6f * s, -4 * s); plane.lineTo(9 * s, 1 * s); plane.lineTo(9 * s, 4 * s); plane.lineTo(1.6f * s, 1 * s);
            plane.lineTo(1.4f * s, 8 * s); plane.lineTo(4.5f * s, 10 * s); plane.lineTo(4.5f * s, 12 * s); plane.lineTo(0, 10.5f * s);
            plane.lineTo(-4.5f * s, 12 * s); plane.lineTo(-4.5f * s, 10 * s); plane.lineTo(-1.4f * s, 8 * s); plane.lineTo(-1.6f * s, 1 * s);
            plane.lineTo(-9 * s, 4 * s); plane.lineTo(-9 * s, 1 * s); plane.lineTo(-1.6f * s, -4 * s); plane.close();
        }
        void set(JSONArray f, float lat, float lon) { flights = f; this.lat = lat; this.lon = lon; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.86f;
            c.drawCircle(cx, cy, r, ring);
            c.drawCircle(cx, cy, r / 2, ring);
            c.drawCircle(cx, cy, Style.u(10), dot);
            if (flights == null || lat == 0) return;
            double maxKm = 30;   // outer ring
            for (int i = 0; i < flights.length(); i++) {
                JSONObject f = flights.optJSONObject(i);
                double flat = f.optDouble("latitude", lat), flon = f.optDouble("longitude", lon);
                double dx = (flon - lon) * 111.32 * Math.cos(Math.toRadians(lat)), dy = (flat - lat) * 110.57;
                float x = cx + (float) (dx / maxKm * r), y = cy - (float) (dy / maxKm * r);
                x = Math.max(Style.u(30), Math.min(getWidth() - Style.u(30), x));
                y = Math.max(Style.u(30), Math.min(getHeight() - Style.u(40), y));
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(i == 0 ? Style.AMBER : Style.CREAM);
                c.save(); c.translate(x, y); c.rotate(f.optInt("heading", 0)); c.drawPath(plane, p); c.restore();
                String cs = f.optString("callsign", "");
                float w = lbl.measureText(cs) + Style.u(20);
                c.drawRoundRect(x - w / 2, y + Style.u(20), x + w / 2, y + Style.u(46), Style.u(10), Style.u(10), box);
                c.drawText(cs, x, y + Style.u(39), lbl);
            }
        }
    }
}
