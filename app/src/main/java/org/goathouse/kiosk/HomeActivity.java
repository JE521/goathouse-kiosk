package org.goathouse.kiosk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Idle screen (what a passer-by sees): big clock, weather, tonight's house alert, the running timer floating on the
 * photo, planes drifting overhead, what's playing here. Tap anywhere → controls. At night it goes black with a dim
 * clock until someone touches it (or the optional presence sensor fires).
 */
public class HomeActivity extends Activity implements Ha.Listener, Timers.Listener {
    private final Handler h = new Handler(Looper.getMainLooper());
    private Ha ha;
    private Dash dash;
    private Prefs prefs;
    private Timers timers;
    private FrameLayout root;
    private TextView date, clock, ampm, wxTemp, wxCond, wxSub, alertTop, alertTitle, alertSub, tTime, tLabel, tMore, npTitle, npArtist, status;
    private View wxCard, alertCard, timerBox, npCard;
    private Widgets.Ring tRing;
    private Widgets.Planes planes;
    private WeatherIcon wxIcon;
    private FrameLayout night;
    private TextView nClock, nLine, nTrash, nTime, nLabel;
    private Widgets.Ring nRing;
    private View nTimer, alertView;
    private LinearLayout clockGroup;
    private long lastTouch = SystemClock.uptimeMillis(), lastForecast, lastBirthday, lastDrift;
    private String hiLo = "", rainNext = "", birthday = "";
    private boolean nightShown;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.of(this);
        dash = Dash.of(this);
        ha = Ha.get(this);
        timers = Timers.get(this);
        // Kiosk: no swipe lock screen in front of the dashboard (a PIN/pattern lock is still respected).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        ha.start();
        if (!prefs.hasHa() && b == null) startActivity(new Intent(this, SetupActivity.class));
        else SendspinService.startIfConfigured(this);   // this tablet is a Music Assistant speaker
    }

    @Override protected void onResume() {
        super.onResume();
        // The firmware sometimes ignores the start made during a cold start; once the dashboard is in front it works.
        if (SendspinService.live == null && prefs.hasHa()) h.postDelayed(() -> SendspinService.startIfConfigured(this), 1500);
        String o = prefs.get(Prefs.ORIENTATION, "landscape");
        setRequestedOrientation("portrait".equals(o) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : "auto".equals(o) ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        Style.measure(this);
        build();
        ha.addListener(this);
        timers.add(this);
        lastTouch = SystemClock.uptimeMillis();
        lastForecast = 0; lastBirthday = 0;
        refresh();
        h.post(tick);
        immersive();
    }

    @Override protected void onPause() {
        super.onPause();
        ha.removeListener(this);
        timers.remove(this);
        h.removeCallbacks(tick);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }
    @Override public void onBackPressed() { /* home screen */ }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        lastTouch = SystemClock.uptimeMillis();
        return super.dispatchTouchEvent(e);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private int u(float v) { return Style.u(v); }

    private FrameLayout.LayoutParams at(int w, int hgt, float left, float top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hgt);
        lp.leftMargin = u(left); lp.topMargin = u(top);
        return lp;
    }

    private FrameLayout.LayoutParams atBR(int w, int hgt, float right, float bottom) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hgt, Gravity.END | Gravity.BOTTOM);
        lp.rightMargin = u(right); lp.bottomMargin = u(bottom);
        return lp;
    }

    // ---------- layout (mockup "Kitchen · idle") ----------

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        root.addView(Kit.backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        View sideScrim = new View(this);
        sideScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x9E05080E, 0x4D05080E, 0x1405080E, 0x0005080E}));
        root.addView(sideScrim, new FrameLayout.LayoutParams(-1, -1));
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x8C05080E, 0x0005080E}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, u(260), Gravity.BOTTOM));

        planes = new Widgets.Planes(this);
        root.addView(planes, new FrameLayout.LayoutParams(-1, u(200)));

        clockGroup = new LinearLayout(this);
        clockGroup.setOrientation(LinearLayout.VERTICAL);
        date = Style.tcaps(this, "", 20, Style.WHITE80);
        date.setLetterSpacing(0.32f);
        Style.shade(date);
        LinearLayout row = new LinearLayout(this);
        clock = Style.t(this, "", 220, 200, Style.WHITE);
        Style.shade(clock);
        ampm = Style.t(this, "", 34, 400, Style.WHITE80);
        ampm.setPadding(u(14), u(28), 0, 0);
        Style.shade(ampm);
        row.addView(clock); row.addView(ampm);
        clockGroup.addView(date); clockGroup.addView(row);
        clockGroup.setOnLongClickListener(v -> { startActivity(new Intent(this, SettingsActivity.class)); return true; });
        root.addView(clockGroup, at(-2, -2, 72, 64));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        wxCard = weatherCard();
        left.addView(wxCard, new LinearLayout.LayoutParams(-1, -2));
        alertCard = alertCard();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = u(16);
        left.addView(alertCard, alp);
        upCard = upcomingCard();
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2);
        ulp.topMargin = u(16);
        left.addView(upCard, ulp);
        root.addView(left, at(u(520), -2, 72, 380));

        timerBox = timerFloat();
        root.addView(timerBox, at(u(280), -2, 790, 230));

        npCard = nowPlayingCard();
        root.addView(npCard, atBR(u(360), -2, 56, 56));

        View planeBtn = Kit.iconButton(this, "plane", u(60), Style.GLASS, Style.CREAM, "Planes overhead",
                v -> startActivity(new Intent(this, FlightsActivity.class)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(u(60), u(60), Gravity.BOTTOM);
        plp.leftMargin = u(64); plp.bottomMargin = u(48);
        root.addView(planeBtn, plp);
        planeBtn.setOnLongClickListener(v -> { startActivity(new Intent(this, SettingsActivity.class)); return true; });

        TextView hint = Style.tcaps(this, "Tap anywhere for controls", 15, Style.WHITE70);
        Style.shade(hint);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM);
        hlp.leftMargin = u(140); hlp.bottomMargin = u(68);
        root.addView(hint, hlp);

        status = Style.t(this, "", 15, 500, Style.WHITE70);
        Style.shade(status);
        status.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM);
        slp.leftMargin = u(140); slp.bottomMargin = u(40);
        root.addView(status, slp);

        root.setOnClickListener(v -> startActivity(new Intent(this, dash.hallway() ? BriefActivity.class : ControlsActivity.class)));

        night = nightLayer();
        night.setVisibility(View.GONE);
        root.addView(night, new FrameLayout.LayoutParams(-1, -1));
        nightShown = false;
        setContentView(root);
    }

    private View weatherCard() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(u(24), u(20), u(24), u(20));
        c.setBackground(Style.glassU(22));
        wxIcon = new WeatherIcon(this);
        c.addView(wxIcon, new LinearLayout.LayoutParams(u(64), u(64)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(u(20), 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.BOTTOM);
        wxTemp = Style.t(this, "–", 52, 300, Style.WHITE);
        wxCond = Style.t(this, "", 22, 500, Style.WHITE);
        wxCond.setPadding(u(14), 0, 0, u(4));
        top.addView(wxTemp); top.addView(wxCond);
        wxSub = Style.t(this, "", 17, 400, Style.WHITE80);
        wxSub.setPadding(0, u(6), 0, 0);
        col.addView(top); col.addView(wxSub);
        c.addView(col);
        c.setOnClickListener(v -> startActivity(new Intent(this, RadarActivity.class)));
        Style.pressable(c);
        return c;
    }

    private View alertCard() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(u(24), u(20), u(24), u(20));
        c.setBackground(Style.glassU(22));
        View bar = new View(this);
        bar.setBackground(Style.fill(Style.AMBER, u(3)));
        c.addView(bar, new LinearLayout.LayoutParams(u(6), u(70)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(u(20), 0, 0, 0);
        alertTop = Style.tcaps(this, "Tonight", 14, Style.AMBER);
        alertTitle = Style.t(this, "", 26, 500, Style.WHITE);
        alertTitle.setPadding(0, u(4), 0, u(4));
        alertSub = Style.t(this, "", 16, 400, Style.WHITE80);
        col.addView(alertTop); col.addView(alertTitle); col.addView(alertSub);
        c.addView(col);
        return c;
    }

    private View timerFloat() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout ring = new FrameLayout(this);
        tRing = new Widgets.Ring(this, 7, 0x38FFFFFF, Style.AMBER);
        ring.addView(tRing, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setGravity(Gravity.CENTER);
        tTime = Style.t(this, "", 68, 200, Style.WHITE);
        Style.shade(tTime);
        tLabel = Style.t(this, "", 20, 500, Style.WHITE);
        Style.shade(tLabel);
        tTime.setGravity(Gravity.CENTER); tLabel.setGravity(Gravity.CENTER);
        in.addView(tTime); in.addView(tLabel);
        ring.addView(in, new FrameLayout.LayoutParams(-1, -1));
        col.addView(ring, new LinearLayout.LayoutParams(u(240), u(240)));
        tMore = Style.t(this, "", 18, 400, Style.WHITE80);
        Style.shade(tMore);
        tMore.setPadding(0, u(12), 0, 0);
        col.addView(tMore);
        col.setOnClickListener(v -> startActivity(new Intent(this, dash.hallway() ? BriefActivity.class : ControlsActivity.class)));
        return col;
    }

    private View nowPlayingCard() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(u(14), u(14), u(20), u(14));
        c.setBackground(Style.glassU(22));
        Kit.Icon art = Kit.icon(this, "play", Style.INDIGO_LT);
        FrameLayout artBox = new FrameLayout(this);
        artBox.setBackground(Style.fill(0xFF1E2A44, u(12)));
        artBox.addView(art, new FrameLayout.LayoutParams(u(26), u(26), Gravity.CENTER));
        c.addView(artBox, new LinearLayout.LayoutParams(u(60), u(60)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(u(16), 0, 0, 0);
        col.addView(Style.tcaps(this, "Playing in kitchen", 13, Style.WHITE80));
        npTitle = Style.t(this, "", 19, 600, Style.WHITE);
        npTitle.setSingleLine(true);
        npTitle.setPadding(0, u(4), 0, u(2));
        npArtist = Style.t(this, "", 15, 400, Style.WHITE80);
        npArtist.setSingleLine(true);
        col.addView(npTitle); col.addView(npArtist);
        c.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        c.setOnClickListener(v -> startActivity(new Intent(this, dash.hallway() ? BriefActivity.class : ControlsActivity.class)));
        return c;
    }

    private FrameLayout nightLayer() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xFF000000);
        f.setClickable(true);
        f.setOnClickListener(v -> wake());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        nClock = Style.t(this, "", 200, 200, Style.NIGHT_TEXT);
        nLine = Style.tcaps(this, "", 22, Style.NIGHT_DIM);
        nLine.setTypeface(Style.font(this, 400));
        nLine.setPadding(0, u(16), 0, 0);
        nTrash = Style.tcaps(this, "Trash and recycling out tonight", 20, Style.NIGHT_AMBER);
        nTrash.setTypeface(Style.font(this, 400));
        nTrash.setPadding(0, u(16), 0, 0);
        col.addView(nClock); col.addView(nLine); col.addView(nTrash);
        f.addView(col, at(-2, -2, 96, 240));
        FrameLayout ring = new FrameLayout(this);
        nRing = new Widgets.Ring(this, 6, 0xFF1E1C19, Style.NIGHT_AMBER);
        ring.addView(nRing, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setGravity(Gravity.CENTER);
        nTime = Style.t(this, "", 56, 200, Style.NIGHT_TEXT);
        nLabel = Style.t(this, "", 18, 400, Style.NIGHT_DIM);
        nTime.setGravity(Gravity.CENTER); nLabel.setGravity(Gravity.CENTER);
        in.addView(nTime); in.addView(nLabel);
        ring.addView(in, new FrameLayout.LayoutParams(-1, -1));
        nTimer = ring;
        f.addView(ring, at(u(220), u(220), 860, 270));
        return f;
    }

    // ---------- data ----------

    @Override public void onStatus(boolean connected, String message) {
        if (status != null) status.setText(connected ? "" : message);
    }

    @Override public void onChanged(Set<String> ids) {
        if (ids.contains(dash.weather()) || ids.contains(dash.media()) || ids.contains(dash.flights())
                || ids.contains(dash.alarmSwitch()) || ids.contains(dash.alarmTime())) refresh();
        if (!dash.presence().isEmpty() && ids.contains(dash.presence())) {
            Ha.Ent p = ha.states.get(dash.presence());
            if (p != null && p.isOn()) wake();
        }
    }

    @Override public void onTimersChanged() { refreshTimers(); checkRinging(); }

    private void refresh() {
        tickClock();
        Ha.Ent w = ha.states.get(dash.weather());
        if (w != null) {
            wxTemp.setText(Tiles.deg(w.attrs.optString("temperature", "–")) + "°");
            wxCond.setText(condition(w.state));
            wxIcon.set(w.state);
            StringBuilder s = new StringBuilder(hiLo);
            if (!rainNext.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(rainNext);
            wxSub.setText(s.toString());
            wxCard.setVisibility(View.VISIBLE);
        } else wxCard.setVisibility(View.GONE);

        if (dash.trashNight()) {
            alertTop.setText(trashLabel());
            alertTitle.setText("Trash and recycling out");
            alertSub.setText("Pickup tomorrow morning");
            alertCard.setVisibility(View.VISIBLE);
        } else if (!birthday.isEmpty()) {
            alertTop.setText("TODAY");
            alertTitle.setText(birthday);
            alertSub.setText("Birthday");
            alertCard.setVisibility(View.VISIBLE);
        } else alertCard.setVisibility(View.GONE);

        Ha.Ent m = ha.states.get(dash.media());
        String title = m == null ? "" : m.attrs.optString("media_title", "");
        if (m != null && ("playing".equals(m.state) || "paused".equals(m.state)) && !title.isEmpty()) {
            npTitle.setText(title);
            npArtist.setText(m.attrs.optString("media_artist", m.attrs.optString("media_album_name", "")));
            npCard.setVisibility(View.VISIBLE);
        } else npCard.setVisibility(View.GONE);

        Ha.Ent f = ha.states.get(dash.flights());
        planes.setFlights(f == null ? null : f.attrs.optJSONArray("flights"));
        refreshTimers();
        refreshNightLine();
    }

    private String trashLabel() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.DAY_OF_WEEK) == dash.trashDay() ? "TONIGHT" : "BEFORE PICKUP";
    }

    private void refreshTimers() {
        Timers.T t = timers.soonest();
        if (t == null) { timerBox.setVisibility(View.GONE); nTimer.setVisibility(View.GONE); return; }
        timerBox.setVisibility(View.VISIBLE);
        nTimer.setVisibility(View.VISIBLE);
        tTime.setText(Kit.mmss(t.left()));
        tLabel.setText(t.paused() ? t.label + " · paused" : t.label);
        tRing.set(t.progress());
        nTime.setText(Kit.mmss(t.left()));
        nLabel.setText(t.label);
        nRing.set(t.progress());
        StringBuilder more = new StringBuilder();
        for (Timers.T o : timers.list) {
            if (o == t || o.ringing) continue;
            if (more.length() > 0) more.append("\n");
            more.append(o.label).append(" · ").append(Kit.mmss(o.left()));
        }
        tMore.setText(more.toString());
        tMore.setVisibility(more.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void checkRinging() {
        List<Timers.T> r = timers.ringing();
        if (!r.isEmpty() && alertView == null) {
            wake();
            alertView = Kit.timerAlert(this, r, () -> timers.stopRinging());
            root.addView(alertView, new FrameLayout.LayoutParams(-1, -1));
        } else if (r.isEmpty() && alertView != null) {
            root.removeView(alertView);
            alertView = null;
        }
    }

    private void refreshNightLine() {
        StringBuilder s = new StringBuilder();
        Ha.Ent w = ha.states.get(dash.weather());
        if (w != null) s.append(Tiles.deg(w.attrs.optString("temperature", ""))).append("°");
        if (!rainNext.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(rainNext);
        Ha.Ent sw = ha.states.get(dash.alarmSwitch()), tm = ha.states.get(dash.alarmTime());
        if (sw != null && sw.isOn() && tm != null && tm.state.length() >= 5) {
            String hm = tm.state.substring(0, 5);
            try {
                Date d = new SimpleDateFormat("HH:mm", Locale.US).parse(hm);
                hm = Ui.clock(this, d);
            } catch (Exception ignored) { }
            s.append(s.length() > 0 ? " · " : "").append("ALARM ").append(hm);
        }
        nLine.setText(s.toString().toUpperCase(Locale.getDefault()));
        nTrash.setVisibility(dash.trashNight() ? View.VISIBLE : View.GONE);
    }

    private void loadForecast() {
        String id = dash.weather();
        if (!ha.connected() || ha.states.get(id) == null) return;
        try {
            ha.callService("weather", "get_forecasts", id, new JSONObject().put("type", "daily"), true, (res, err) -> {
                JSONArray f = forecast(res, id);
                if (f == null) android.util.Log.w("Kiosk", "daily forecast: " + err + " " + res);
                if (f != null && f.length() > 0) {
                    JSONObject d = f.optJSONObject(0);
                    hiLo = "High " + Tiles.deg(d.optString("temperature")) + "° · Low " + Tiles.deg(d.optString("templow")) + "°";
                    refresh();
                }
            });
            ha.callService("weather", "get_forecasts", id, new JSONObject().put("type", "hourly"), true, (res, err) -> {
                JSONArray f = forecast(res, id);
                rainNext = "";
                for (int i = 0; f != null && i < Math.min(f.length(), 18); i++) {
                    JSONObject o = f.optJSONObject(i);
                    String c = o.optString("condition", "");
                    if (o.optInt("precipitation_probability", 0) >= 50 || c.contains("rain") || c.contains("snow") || c.contains("pour")) {
                        Date d = Ui.parseIso(o.optString("datetime"));
                        String what = c.contains("snow") ? "Snow" : "Rain";
                        rainNext = i == 0 ? what + " now" : d == null ? what + " later" : what + " after " + hourLabel(d);
                        break;
                    }
                }
                refresh();
            });
        } catch (Exception ignored) { }
    }

    static JSONArray forecast(JSONObject res, String id) {
        JSONObject r = res == null ? null : res.optJSONObject("response");
        JSONObject mine = r == null ? null : r.optJSONObject(id);
        return mine == null ? null : mine.optJSONArray("forecast");
    }

    static String hourLabel(Date d) {
        return new SimpleDateFormat("h a", Locale.US).format(d);
    }

    // ---------- room layout: "Coming up" card (next events from the room's calendars) ----------

    private LinearLayout upCard, upRows;
    private long lastUpcoming;
    private boolean upcomingLoaded;

    private LinearLayout upcomingCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(u(24), u(18), u(24), u(18));
        c.setBackground(Style.glassU(22));
        c.addView(Style.tcaps(this, "Coming up", 14, Style.AMBER));
        upRows = new LinearLayout(this);
        upRows.setOrientation(LinearLayout.VERTICAL);
        upRows.setPadding(0, u(6), 0, 0);
        c.addView(upRows);
        c.setVisibility(View.GONE);
        c.setOnClickListener(v -> startActivity(new Intent(this, ControlsActivity.class)));
        return c;
    }

    private void loadUpcoming() {
        List<String> cals = new ArrayList<>();
        for (String c : dash.calendars()) if (ha.states.get(c) != null) cals.add(c);
        if (cals.isEmpty()) return;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        Calendar c = Calendar.getInstance();
        String start = iso.format(c.getTime());
        c.add(Calendar.DAY_OF_MONTH, 30);
        String end = iso.format(c.getTime());
        try {
            ha.send(new JSONObject().put("type", "call_service").put("domain", "calendar").put("service", "get_events")
                    .put("target", new JSONObject().put("entity_id", new JSONArray(cals)))
                    .put("service_data", new JSONObject().put("start_date_time", start).put("end_date_time", end))
                    .put("return_response", true), (res, err) -> {
                JSONObject r = res == null ? null : res.optJSONObject("response");
                if (r == null) return;
                upcomingLoaded = true;
                List<JSONObject> all = new ArrayList<>();
                for (String cal : cals) {
                    JSONObject mine = r.optJSONObject(cal);
                    JSONArray ev = mine == null ? null : mine.optJSONArray("events");
                    for (int i = 0; ev != null && i < ev.length(); i++) all.add(ev.optJSONObject(i));
                }
                BriefActivity.upcoming(all);
                renderUpcoming(all);
            });
        } catch (Exception ignored) { }
    }

    private void renderUpcoming(List<JSONObject> all) {
        upRows.removeAllViews();
        SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.getDefault()), date = new SimpleDateFormat("MMM d", Locale.getDefault()),
                time = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (int i = 0; i < Math.min(all.size(), 3); i++) {
            JSONObject e = all.get(i);
            long w = BriefActivity.when(e);
            boolean allDay = e.optString("start").length() <= 10;
            Date d = new Date(w);
            String when = (android.text.format.DateUtils.isToday(w) ? "Today" : w - System.currentTimeMillis() < 6L * 86_400_000L ? day.format(d) : date.format(d))
                    + (allDay ? "" : " " + time.format(d));
            LinearLayout r = new LinearLayout(this);
            r.setPadding(0, u(5), 0, u(5));
            r.addView(Style.t(this, when, 15, 600, Style.WHITE80), new LinearLayout.LayoutParams(u(150), -2));
            TextView s = Style.t(this, e.optString("summary"), 17, 400, Style.WHITE);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(s, new LinearLayout.LayoutParams(0, -2, 1));
            upRows.addView(r);
        }
        upCard.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void loadBirthday() {
        String cal = dash.birthdays();
        if (!ha.connected() || cal.isEmpty() || ha.states.get(cal) == null) return;
        try {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            String start = iso.format(c.getTime());
            c.add(Calendar.DAY_OF_MONTH, 1);
            String end = iso.format(c.getTime());
            ha.callService("calendar", "get_events", cal, new JSONObject().put("start_date_time", start).put("end_date_time", end), true, (res, err) -> {
                JSONObject r = res == null ? null : res.optJSONObject("response");
                JSONObject mine = r == null ? null : r.optJSONObject(cal);
                JSONArray ev = mine == null ? null : mine.optJSONArray("events");
                birthday = ev != null && ev.length() > 0 ? ev.optJSONObject(0).optString("summary", "") : "";
                refresh();
            });
        } catch (Exception ignored) { }
    }

    // ---------- clock, night, drift ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickClock();
            refreshTimers();
            long now = SystemClock.uptimeMillis();
            long wall = System.currentTimeMillis();
            // Retry every minute until the forecast arrives (the first ticks can run before HA's states load), then every 30 min.
            if (ha.connected() && wall - lastForecast > (hiLo.isEmpty() ? 60_000L : 30 * 60_000L)) { lastForecast = wall; loadForecast(); }
            if (ha.connected() && wall - lastBirthday > 60 * 60_000L) { lastBirthday = wall; loadBirthday(); }
            if (ha.connected() && dash.room() && wall - lastUpcoming > (upcomingLoaded ? 15 * 60_000L : 60_000L)) { lastUpcoming = wall; loadUpcoming(); }
            if (wall - lastDrift > 60_000L) {
                lastDrift = wall;
                clockGroup.animate().translationX((float) (Math.random() * u(12))).translationY((float) (Math.random() * u(12))).setDuration(1200).start();
                if (alertCard.getVisibility() == View.VISIBLE != dash.trashNight() || dash.trashNight()) refresh();
            }
            boolean nightTime = isNight();
            if (nightTime && !nightShown && now - lastTouch > 60_000L && timers.ringing().isEmpty()) showNight(true);
            if (!nightTime && nightShown) showNight(false);
            applyBrightness();
            h.postDelayed(this, 1000);
        }
    };

    private boolean isNight() {
        if (!prefs.on(Prefs.NIGHT_DIM, true)) return false;
        int from = prefs.num(Prefs.NIGHT_FROM, 22), to = prefs.num(Prefs.NIGHT_TO, 7);
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return from > to ? (hr >= from || hr < to) : (hr >= from && hr < to);
    }

    private void showNight(boolean on) {
        nightShown = on;
        night.setVisibility(on ? View.VISIBLE : View.GONE);
        refreshNightLine();
    }

    /** Step one of waking: from the black night screen to the dim idle screen. A tap there opens the controls. */
    private void wake() {
        lastTouch = SystemClock.uptimeMillis();
        if (nightShown) showNight(false);
    }

    private void applyBrightness() {
        Style.brightness(this, nightShown);
    }

    private void tickClock() {
        Date now = new Date();
        boolean h24 = Ui.h24(this);
        String t = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now);
        if (!t.equals(clock.getText().toString())) {
            clock.setText(t);
            nClock.setText(t);
            ampm.setText(h24 ? "" : new SimpleDateFormat("a", Locale.US).format(now));
            date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        }
    }

    static String condition(String s) {
        switch (s) {
            case "clear-night": return "Clear";
            case "partlycloudy": return "Partly cloudy";
            case "lightning-rainy": return "Storms";
            case "snowy-rainy": return "Sleet";
            case "windy-variant": return "Windy";
            case "exceptional": return "Severe weather";
            default: return s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('-', ' ');
        }
    }

    /** Amber sun or moon with a cream cloud (and rain/snow marks), drawn in code. */
    static final class WeatherIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String cond = "";
        WeatherIcon(Context c) { super(c); }
        void set(String c) { cond = c == null ? "" : c; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float s = getWidth() / 64f;
            c.save(); c.scale(s, s);
            boolean night = cond.contains("night") || isDark();
            boolean cloud = cond.contains("cloud") || cond.contains("rain") || cond.contains("pour") || cond.contains("snow") || cond.contains("fog") || cond.contains("lightning");
            boolean sunny = cond.equals("sunny") || cond.contains("clear") || cond.equals("partlycloudy");
            p.setStyle(Paint.Style.FILL);
            if (sunny || !cloud) {
                p.setColor(Style.AMBER);
                if (night) {
                    Path m = new Path();
                    m.addCircle(26, 24, 16, Path.Direction.CW);
                    Path cut = new Path();
                    cut.addCircle(34, 18, 13, Path.Direction.CW);
                    m.op(cut, Path.Op.DIFFERENCE);
                    c.drawPath(m, p);
                } else {
                    c.drawCircle(24, 24, 11, p);
                    p.setStrokeWidth(3); p.setStrokeCap(Paint.Cap.ROUND);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4;
                        c.drawLine(24 + (float) Math.cos(a) * 16, 24 + (float) Math.sin(a) * 16, 24 + (float) Math.cos(a) * 20, 24 + (float) Math.sin(a) * 20, p);
                    }
                }
            }
            if (cloud || cond.equals("partlycloudy")) {
                p.setColor(Style.CREAM);
                c.drawCircle(30, 40, 11, p); c.drawCircle(42, 36, 13, p); c.drawCircle(50, 44, 9, p);
                c.drawRoundRect(new RectF(18, 40, 58, 54), 7, 7, p);
                if (cond.contains("rain") || cond.contains("pour") || cond.contains("lightning")) {
                    p.setColor(Style.INDIGO_LT); p.setStrokeWidth(3);
                    c.drawLine(28, 58, 25, 63, p); c.drawLine(38, 58, 35, 63, p); c.drawLine(48, 58, 45, 63, p);
                }
                if (cond.contains("snow")) { p.setColor(Style.WHITE); c.drawCircle(28, 60, 2.5f, p); c.drawCircle(38, 61, 2.5f, p); c.drawCircle(48, 60, 2.5f, p); }
            }
            c.restore();
        }
        private static boolean isDark() {
            int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return hr >= 19 || hr < 6;
        }
    }
}
