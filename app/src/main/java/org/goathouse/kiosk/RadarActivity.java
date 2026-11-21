package org.goathouse.kiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/** Tap the weather: live radar loop (RainViewer, centred on home) plus now, the next hours and the next days. */
public class RadarActivity extends Activity implements Ha.Listener {
    private Ha ha;
    private Dash dash;
    private TextView nowTemp, nowCond, nowSub;
    private LinearLayout hours, days;
    private RadarMap web;

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
        renderNow();
        loadForecasts();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override protected void onPause() { super.onPause(); ha.removeListener(this); }

    private int u(float v) { return Style.u(v); }

    @SuppressLint("SetJavaScriptEnabled")
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
        TextView t = Style.t(this, "Radar", 30, 500, Style.WHITE);
        t.setPadding(u(18), 0, 0, 0);
        top.addView(t);
        page.addView(top, new LinearLayout.LayoutParams(-1, u(60)));

        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1);
        blp.topMargin = u(22);
        page.addView(body, blp);

        if (web == null) {
            float lat = Prefs.of(this).raw().getFloat("ha_lat", 42.66f), lon = Prefs.of(this).raw().getFloat("ha_lon", -73.79f);
            web = new RadarMap(this, lat, lon, dash.mapTiles());
        } else if (web.getParent() != null) ((android.view.ViewGroup) web.getParent()).removeView(web);
        FrameLayout mapBox = new FrameLayout(this);
        mapBox.setBackground(Style.fill(0xFF121826, u(28)));
        mapBox.setClipToOutline(true);
        mapBox.addView(web, new FrameLayout.LayoutParams(-1, -1));
        body.addView(mapBox, new LinearLayout.LayoutParams(u(840), -1));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -1, 1);
        slp.leftMargin = u(20);
        body.addView(side, slp);

        LinearLayout now = card();
        now.addView(Style.tcaps(this, "Now", 14, Style.WHITE70));
        LinearLayout nt = new LinearLayout(this);
        nt.setGravity(Gravity.BOTTOM);
        nt.setPadding(0, u(6), 0, 0);
        nowTemp = Style.t(this, "–", 52, 300, Style.WHITE);
        nowCond = Style.t(this, "", 20, 500, Style.WHITE);
        nowCond.setPadding(u(12), 0, 0, u(4));
        nt.addView(nowTemp); nt.addView(nowCond);
        now.addView(nt);
        nowSub = Style.t(this, "", 15, 400, Style.WHITE80);
        nowSub.setPadding(0, u(6), 0, 0);
        now.addView(nowSub);
        side.addView(now, gap());

        LinearLayout hc = card();
        hc.addView(Style.tcaps(this, "Next hours", 14, Style.WHITE70));
        hours = new LinearLayout(this);
        hours.setOrientation(LinearLayout.VERTICAL);
        hc.addView(hours);
        side.addView(hc, gap());

        LinearLayout dc = card();
        dc.addView(Style.tcaps(this, "Next days", 14, Style.WHITE70));
        days = new LinearLayout(this);
        days.setOrientation(LinearLayout.VERTICAL);
        dc.addView(days);
        side.addView(dc, new LinearLayout.LayoutParams(-1, -2));

        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(u(20), u(16), u(20), u(16));
        c.setBackground(Style.glassU(22));
        return c;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = u(12);
        return lp;
    }

    @Override public void onStatus(boolean connected, String message) { if (connected) loadForecasts(); }
    @Override public void onChanged(Set<String> ids) { if (ids.contains(dash.weather())) renderNow(); }

    private void renderNow() {
        Ha.Ent w = ha.states.get(dash.weather());
        if (w == null) return;
        nowTemp.setText(Tiles.deg(w.attrs.optString("temperature", "–")) + "°");
        nowCond.setText(HomeActivity.condition(w.state));
        StringBuilder s = new StringBuilder();
        if (w.attrs.has("wind_speed")) s.append("Wind ").append(Tiles.num(w.attrs.optString("wind_speed"))).append(" ").append(w.attrs.optString("wind_speed_unit", "mph"));
        if (w.attrs.has("humidity")) s.append(s.length() > 0 ? " · " : "").append("Humidity ").append(w.attrs.optInt("humidity")).append("%");
        nowSub.setText(s.toString());
    }

    private void loadForecasts() {
        String id = dash.weather();
        if (!ha.connected() || ha.states.get(id) == null) return;
        try {
            ha.callService("weather", "get_forecasts", id, new JSONObject().put("type", "hourly"), true, (res, err) -> {
                JSONArray f = HomeActivity.forecast(res, id);
                hours.removeAllViews();
                for (int i = 1; f != null && i < Math.min(f.length(), 6); i++) {
                    JSONObject o = f.optJSONObject(i);
                    Date d = Ui.parseIso(o.optString("datetime"));
                    hours.addView(row(d == null ? "" : HomeActivity.hourLabel(d), describe(o), Tiles.deg(o.optString("temperature")) + "°", null));
                }
            });
            ha.callService("weather", "get_forecasts", id, new JSONObject().put("type", "daily"), true, (res, err) -> {
                JSONArray f = HomeActivity.forecast(res, id);
                days.removeAllViews();
                for (int i = 1; f != null && i < Math.min(f.length(), 6); i++) {
                    JSONObject o = f.optJSONObject(i);
                    Date d = Ui.parseIso(o.optString("datetime"));
                    days.addView(row(d == null ? "" : new SimpleDateFormat("EEE", Locale.getDefault()).format(d), describe(o),
                            Tiles.deg(o.optString("temperature")) + "°", Tiles.deg(o.optString("templow")) + "°"));
                }
            });
        } catch (Exception ignored) { }
    }

    private static String describe(JSONObject o) {
        String c = HomeActivity.condition(o.optString("condition", ""));
        int p = o.optInt("precipitation_probability", 0);
        return p >= 30 ? c + " · " + p + "%" : c;
    }

    private View row(String when, String what, String hi, String lo) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(Style.t(this, when, 16, 400, Style.WHITE80), new LinearLayout.LayoutParams(u(74), -2));
        boolean wet = what.contains("ain") || what.contains("now") || what.contains("torm") || what.contains("%");
        r.addView(Style.t(this, what, 16, wet ? 600 : 400, wet ? Style.AMBER : Style.WHITE), new LinearLayout.LayoutParams(0, -2, 1));
        if (lo != null) {
            TextView l = Style.t(this, lo, 16, 400, Style.WHITE60);
            l.setPadding(0, 0, u(10), 0);
            r.addView(l);
        }
        r.addView(Style.t(this, hi, 18, 500, Style.WHITE));
        r.setLayoutParams(new LinearLayout.LayoutParams(-1, u(40)));
        return r;
    }
}
