package org.goathouse.kiosk;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Controls (after a tap): timers, shopping list, lights, Last to bed, what's playing. Back to idle after 90 s. */
public class ControlsActivity extends Activity implements Ha.Listener, Timers.Listener {
    private final Handler h = new Handler(Looper.getMainLooper());
    private Ha ha;
    private Dash dash;
    private Timers timers;
    private FrameLayout root;
    private TextView clock, topLine, shopCount, npTitle, npArtist, npPos, npDur;
    private Widgets.Wheel wh, wm, ws;
    private EditText label, shopAdd;
    private TextView startBtn;
    private LinearLayout running, shopList, rooms, npBar, alarmRows, listTabs;
    private String listId = "";
    private View bedBtn, mainBtn, npFill, alertView;
    private TextView mainState, mainSub;
    private Kit.Icon playIcon;
    private Widgets.Slider volume;
    private long lastTouch = SystemClock.uptimeMillis();
    private String lastShopState;
    private HaPipeline voice;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ha = Ha.get(this);
        dash = Dash.of(this);
        timers = Timers.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }

    @Override protected void onResume() {
        super.onResume();
        Style.brightness(this, false);
        String o = Prefs.of(this).get(Prefs.ORIENTATION, "landscape");
        setRequestedOrientation("portrait".equals(o) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : "auto".equals(o) ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        Style.measure(this);
        build();
        ha.addListener(this);
        timers.add(this);
        lastTouch = SystemClock.uptimeMillis();
        lastShopState = null;
        refreshAll();
        h.post(tick);
        immersive();
    }

    @Override protected void onPause() {
        super.onPause();
        ha.removeListener(this);
        timers.remove(this);
        h.removeCallbacks(tick);
        if (voice != null) { voice.cancel(); voice = null; }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        lastTouch = SystemClock.uptimeMillis();
        return super.dispatchTouchEvent(e);
    }

    @Override public void onBackPressed() {
        View sheet = root.findViewWithTag("sheet");
        if (sheet != null) { root.removeView(sheet); return; }
        finish();
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private int u(float v) { return Style.u(v); }

    private TextView header(String s) { return Style.tcaps(this, s, 15, Style.WHITE80); }

    // ---------- layout (mockup "Kitchen · controls") ----------

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        root.addView(Kit.backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        root.addView(Kit.scrim(this, 0x7A060910), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(u(40), u(28), u(40), u(28));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View back = Kit.iconButton(this, "back", u(52), Style.GLASS, Style.WHITE, "Back", v -> finish());
        top.addView(back);
        clock = Style.t(this, "", 40, 300, Style.WHITE);
        clock.setPadding(u(18), 0, u(12), 0);
        top.addView(clock);
        topLine = Style.tcaps(this, "", 15, Style.WHITE80);
        top.addView(topLine, new LinearLayout.LayoutParams(0, -2, 1));
        View mic = Kit.iconButton(this, "mic", u(56), Style.AMBER, Style.INK, "Talk to the house", v -> listen(false));
        top.addView(mic);
        page.addView(top, new LinearLayout.LayoutParams(-1, u(64)));

        LinearLayout grid = new LinearLayout(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1);
        glp.topMargin = u(24); glp.bottomMargin = u(20);
        page.addView(grid, glp);
        List<String> lists = dash.lists();
        listId = dash.room() && !lists.isEmpty() ? lists.get(0) : dash.shop();
        View timers = timerCard();                 // built either way: local timers keep ringing in the room layout too
        grid.addView(dash.room() ? alarmCard() : timers, new LinearLayout.LayoutParams(u(580), -1));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(u(310), -1);
        slp.leftMargin = u(20);
        grid.addView(shopCard(), slp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1);
        rlp.leftMargin = u(20);
        grid.addView(rightColumn(), rlp);

        npBar = nowPlayingBar();
        page.addView(npBar, new LinearLayout.LayoutParams(-1, u(84)));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private View timerCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(u(24), u(22), u(24), u(22));
        c.setBackground(Style.glassU(26));
        c.addView(header("Timers"));

        LinearLayout wheels = new LinearLayout(this);
        wheels.setBackground(Style.fill(0x33000000, u(20)));
        wh = new Widgets.Wheel(this, 9, 1, "hours");
        wm = new Widgets.Wheel(this, 59, 1, "min");
        ws = new Widgets.Wheel(this, 55, 5, "sec");
        Widgets.IntListener upd = v -> updateStart();
        wh.onValue(upd); wm.onValue(upd); ws.onValue(upd);
        wheels.addView(wh, new LinearLayout.LayoutParams(0, -1, 1));
        wheels.addView(wm, new LinearLayout.LayoutParams(0, -1, 1));
        wheels.addView(ws, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, u(150));
        wlp.topMargin = u(14);
        c.addView(wheels, wlp);

        LinearLayout lrow = new LinearLayout(this);
        label = new EditText(this);
        label.setHint("Label");
        label.setHintTextColor(0x8CFFFFFF);
        label.setTextColor(Style.WHITE);
        label.setTypeface(Style.font(this, 400));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(19));
        label.setSingleLine(true);
        label.setImeOptions(EditorInfo.IME_ACTION_DONE);
        label.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        label.setPadding(u(18), 0, u(18), 0);
        label.setBackground(Style.fill(0x1FFFFFFF, u(16)));
        lrow.addView(label, new LinearLayout.LayoutParams(0, u(58), 1));
        startBtn = Style.t(this, "Start", 19, 700, Style.INK);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setBackground(Style.fill(Style.AMBER, u(16)));
        startBtn.setOnClickListener(v -> startFromWheels());
        Style.pressable(startBtn);
        LinearLayout.LayoutParams sb = new LinearLayout.LayoutParams(u(170), u(58));
        sb.leftMargin = u(12);
        lrow.addView(startBtn, sb);
        LinearLayout.LayoutParams lrp = new LinearLayout.LayoutParams(-1, -2);
        lrp.topMargin = u(14);
        c.addView(lrow, lrp);

        LinearLayout quick = new LinearLayout(this);
        for (int m : new int[]{15, 30, 40, 60}) {
            TextView q = Style.t(this, m + " min", 18, 600, Style.WHITE);
            q.setGravity(Gravity.CENTER);
            q.setBackground(Style.fill(Style.CHIP, u(16)));
            q.setOnClickListener(v -> { timers.start(labelText(), m * 60_000L); clearLabel(); });
            Style.pressable(q);
            LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(0, u(52), 1);
            if (m != 15) qp.leftMargin = u(10);
            quick.addView(q, qp);
        }
        LinearLayout.LayoutParams qrp = new LinearLayout.LayoutParams(-1, -2);
        qrp.topMargin = u(14);
        c.addView(quick, qrp);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        running = new LinearLayout(this);
        running.setOrientation(LinearLayout.VERTICAL);
        sv.addView(running);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, 0, 1);
        svp.topMargin = u(14);
        c.addView(sv, svp);
        updateStart();
        return c;
    }

    private String labelText() { return label.getText().toString().trim(); }

    private void clearLabel() {
        label.setText("");
        label.clearFocus();
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(label.getWindowToken(), 0);
    }

    private long wheelMs() { return ((wh.value() * 60L + wm.value()) * 60L + ws.value()) * 1000L; }

    private void updateStart() {
        long ms = wheelMs();
        startBtn.setText(ms > 0 ? "Start " + Kit.mmss(ms) : "Start");
        startBtn.setAlpha(ms > 0 ? 1f : 0.5f);
    }

    private void startFromWheels() {
        long ms = wheelMs();
        if (ms <= 0) return;
        timers.start(labelText(), ms);
        clearLabel();
    }

    private void renderTimers() {
        running.removeAllViews();
        for (Timers.T t : timers.list) {
            if (t.ringing) continue;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(u(14), 0, u(8), 0);
            r.setBackground(Style.fill(0x14FFFFFF, u(16)));
            Widgets.Ring ring = new Widgets.Ring(this, 3.5f, 0x33FFFFFF, Style.AMBER);
            ring.set(t.progress());
            ring.setTag("ring");
            r.addView(ring, new LinearLayout.LayoutParams(u(36), u(36)));
            TextView name = Style.t(this, t.label, 19, 500, Style.WHITE);
            name.setPadding(u(14), 0, 0, 0);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView left = Style.t(this, Kit.mmss(t.left()), 26, 300, t.paused() ? Style.WHITE60 : Style.WHITE);
            left.setTag("left");
            left.setPadding(0, 0, u(10), 0);
            r.addView(left);
            View pr = Kit.iconButton(this, t.paused() ? "play" : "pause", u(44), Style.CHIP, Style.WHITE, t.paused() ? "Resume " + t.label : "Pause " + t.label, v -> timers.pauseResume(t));
            r.addView(pr);
            View x = Kit.iconButton(this, "x", u(44), Style.CHIP, Style.WHITE, "Cancel " + t.label, v -> timers.cancel(t));
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(u(44), u(44));
            xp.leftMargin = u(8);
            r.addView(x, xp);
            r.setTag(t);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, u(58));
            rp.bottomMargin = u(8);
            running.addView(r, rp);
        }
    }

    private void tickTimers() {
        for (int i = 0; i < running.getChildCount(); i++) {
            View r = running.getChildAt(i);
            if (!(r.getTag() instanceof Timers.T)) continue;
            Timers.T t = (Timers.T) r.getTag();
            TextView left = r.findViewWithTag("left");
            Widgets.Ring ring = r.findViewWithTag("ring");
            if (left != null) left.setText(Kit.mmss(t.left()));
            if (ring != null) ring.set(t.progress());
        }
    }

    private View shopCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(u(22), u(22), u(22), u(22));
        c.setBackground(Style.glassU(26));
        LinearLayout hrow = new LinearLayout(this);
        hrow.addView(header(dash.room() ? "Lists" : "Shopping"), new LinearLayout.LayoutParams(0, -2, 1));
        shopCount = Style.t(this, "", 15, 400, Style.WHITE70);
        hrow.addView(shopCount);
        c.addView(hrow);
        if (dash.room()) {
            listTabs = new LinearLayout(this);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, u(40));
            tp.topMargin = u(12);
            c.addView(listTabs, tp);
            renderListTabs();
        }

        LinearLayout add = new LinearLayout(this);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(u(18), 0, u(5), 0);
        add.setBackground(Style.fill(0x1AFFFFFF, u(30)));
        shopAdd = new EditText(this);
        shopAdd.setHint("Add an item");
        shopAdd.setHintTextColor(0x8CFFFFFF);
        shopAdd.setTextColor(Style.WHITE);
        shopAdd.setTypeface(Style.font(this, 400));
        shopAdd.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(18));
        shopAdd.setSingleLine(true);
        shopAdd.setBackground(null);
        shopAdd.setPadding(0, 0, 0, 0);
        shopAdd.setImeOptions(EditorInfo.IME_ACTION_DONE);
        shopAdd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        shopAdd.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                addShop(shopAdd.getText().toString());
                shopAdd.setText("");
                return true;
            }
            return false;
        });
        add.addView(shopAdd, new LinearLayout.LayoutParams(0, -1, 1));
        View smic = Kit.iconButton(this, "mic", u(50), Style.AMBER_SOFT, Style.AMBER, "Say an item", v -> listen(true));
        add.addView(smic);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, u(60));
        ap.topMargin = u(14);
        c.addView(add, ap);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        shopList = new LinearLayout(this);
        shopList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(shopList);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, 0, 1);
        svp.topMargin = u(8);
        c.addView(sv, svp);
        return c;
    }

    private View rightColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bed = new LinearLayout(this);
        bed.setGravity(Gravity.CENTER_VERTICAL);
        bed.setPadding(u(18), u(16), u(18), u(16));
        bed.setBackground(Style.toggleU(22, true));
        bed.addView(Kit.icon(this, "moon", Style.AMBER), new LinearLayout.LayoutParams(u(28), u(28)));
        LinearLayout bt = new LinearLayout(this);
        bt.setOrientation(LinearLayout.VERTICAL);
        bt.setPadding(u(12), 0, 0, 0);
        bt.addView(Style.t(this, "Last to bed", 20, 700, Style.WHITE));
        TextView bs = Style.t(this, keepsLabel(), 13, 500, Style.WHITE80);
        bs.setPadding(0, u(3), 0, 0);
        bt.addView(bs);
        bed.addView(bt);
        bed.setOnClickListener(v -> lastToBed());
        Style.pressable(bed);
        bedBtn = bed;
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.bottomMargin = u(12);
        col.addView(bed, bp);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(u(18), u(14), u(18), u(14));
        LinearLayout mt = new LinearLayout(this);
        TextView mn = Style.t(this, "Kitchen", 22, 700, Style.WHITE);
        mt.addView(mn, new LinearLayout.LayoutParams(0, -2, 1));
        mainState = Style.t(this, "", 14, 700, Style.AMBER);
        mt.addView(mainState);
        main.addView(mt);
        View sp = new View(this);
        main.addView(sp, new LinearLayout.LayoutParams(1, 0, 1));
        mainSub = Style.t(this, "", 15, 500, Style.WHITE80);
        main.addView(mainSub);
        main.setTag(mn);
        main.setOnClickListener(v -> toggle(dash.main()));
        main.setOnLongClickListener(v -> { openSheet(dash.main()); return true; });
        Style.pressable(main);
        mainBtn = main;
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, u(92));
        mp.bottomMargin = u(12);
        col.addView(main, mp);

        rooms = new LinearLayout(this);
        rooms.setOrientation(LinearLayout.VERTICAL);
        rooms.setBackground(Style.glassU(18));
        col.addView(rooms, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private LinearLayout nowPlayingBar() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(u(12), 0, u(16), 0);
        c.setBackground(Style.glassU(24));
        FrameLayout art = new FrameLayout(this);
        art.setBackground(Style.fill(0xFF1E2A44, u(12)));
        art.addView(Kit.icon(this, "play", Style.INDIGO_LT), new FrameLayout.LayoutParams(u(26), u(26), Gravity.CENTER));
        c.addView(art, new LinearLayout.LayoutParams(u(60), u(60)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(u(16), 0, u(16), 0);
        npTitle = Style.t(this, "", 19, 600, Style.WHITE);
        npTitle.setSingleLine(true);
        npArtist = Style.t(this, "", 15, 400, Style.WHITE80);
        npArtist.setSingleLine(true);
        npArtist.setPadding(0, u(3), 0, 0);
        t.addView(npTitle); t.addView(npArtist);
        c.addView(t, new LinearLayout.LayoutParams(u(280), -2));
        LinearLayout prog = new LinearLayout(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        npPos = Style.t(this, "", 14, 400, Style.WHITE70);
        prog.addView(npPos);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(Style.fill(0x38FFFFFF, u(3)));
        npFill = new View(this);
        npFill.setBackground(Style.fill(Style.AMBER, u(3)));
        track.addView(npFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, u(5), 1);
        tp.leftMargin = u(12); tp.rightMargin = u(12);
        prog.addView(track, tp);
        npDur = Style.t(this, "", 14, 400, Style.WHITE70);
        prog.addView(npDur);
        c.addView(prog, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(Kit.iconButton(this, "prev", u(52), 0x00000000, Style.WHITE, "Previous", v -> media("media_previous_track")));
        FrameLayout play = Kit.iconButton(this, "pause", u(60), Style.AMBER, Style.INK, "Play or pause", v -> media("media_play_pause"));
        playIcon = (Kit.Icon) play.getChildAt(0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(u(60), u(60));
        pp.leftMargin = u(6); pp.rightMargin = u(6);
        c.addView(play, pp);
        c.addView(Kit.iconButton(this, "next", u(52), 0x00000000, Style.WHITE, "Next", v -> media("media_next_track")));
        c.addView(Kit.icon(this, "volume", Style.WHITE), new LinearLayout.LayoutParams(u(40), u(22)));
        volume = new Widgets.Slider(this, 50, Style.CREAM, null, null, v -> {
            try { ha.callService("media_player", "volume_set", dash.media(), new JSONObject().put("volume_level", v / 100.0)); } catch (Exception ignored) { }
        });
        c.addView(volume, new LinearLayout.LayoutParams(u(130), u(10)));
        return c;
    }

    // ---------- data ----------

    @Override public void onStatus(boolean connected, String message) {
        if (connected) { lastShopState = null; refreshAll(); }
    }

    @Override public void onChanged(Set<String> ids) {
        boolean lights = ids.contains(dash.main());
        for (String id : ids) if (id.startsWith("light.")) lights = true;   // keeps the Whole house count live
        for (String r : dash.rooms()) if (ids.contains(r)) lights = true;
        if (lights) renderLights();
        if (ids.contains(dash.media())) renderMedia();
        if (ids.contains(listId)) loadShop();
        if (dash.room()) {
            if (ids.contains(dash.alarmSwitch()) || ids.contains(dash.alarmTime()) || ids.contains(dash.alarm2Switch()) || ids.contains(dash.alarm2Time())) renderAlarms();
            if (ids.contains(dash.climate())) renderLights();
            if (ids.contains(dash.roomSensor())) tickClock();
        }
    }

    @Override public void onTimersChanged() { renderTimers(); checkRinging(); }

    private void refreshAll() {
        if (dash.room()) { renderAlarms(); renderListTabs(); lastEvents = 0; loadEvents(); }
        tickClock();
        renderTimers();
        renderLights();
        renderMedia();
        loadShop();
        checkRinging();
    }

    private void renderLights() {
        Ha.Ent m = ha.states.get(dash.main());
        boolean on = m != null && m.isOn();
        mainBtn.setBackground(Style.toggleU(22, on));
        mainState.setText(m == null ? "" : on ? "ON" : "OFF");
        mainState.setTextColor(on ? Style.AMBER : Style.WHITE60);
        ((TextView) mainBtn.getTag()).setText(m == null ? "Kitchen" : shortName(m.name()));
        int b = m == null ? -1 : m.attrs.optInt("brightness", -1);
        mainSub.setText(m == null ? "Not found" : on ? (b >= 0 ? Math.round(b * 100 / 255f) + "% · hold for more" : "hold for more") : "Tap to turn on");
        mainBtn.setVisibility(m == null ? View.GONE : View.VISIBLE);

        rooms.removeAllViews();
        int houseOn = houseLights(true).length();
        LinearLayout wh = new LinearLayout(this);
        wh.setGravity(Gravity.CENTER_VERTICAL);
        wh.setPadding(u(16), 0, u(16), 0);
        wh.addView(Style.t(this, "Whole house", 17, 700, Style.WHITE), new LinearLayout.LayoutParams(0, -2, 1));
        wh.addView(Style.t(this, houseOn == 0 ? "All off" : houseOn + " on", 14, houseOn > 0 ? 700 : 400, houseOn > 0 ? Style.AMBER : Style.WHITE60));
        wh.setOnClickListener(v -> wholeHouse());
        Style.pressable(wh);
        rooms.addView(wh, new LinearLayout.LayoutParams(-1, u(52)));
        List<String> ids = dash.rooms();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Ha.Ent e = ha.states.get(id);
            if (e == null) continue;
            boolean eon = e.isOn();
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(u(16), 0, u(16), 0);
            if (id.startsWith("switch.")) {
                Kit.Icon ic = Kit.icon(this, "power", eon ? Style.AMBER : Style.WHITE60);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(u(20), u(20));
                ip.rightMargin = u(10);
                r.addView(ic, ip);
            }
            r.addView(Style.t(this, shortName(e.name()), 17, 600, Style.WHITE), new LinearLayout.LayoutParams(0, -2, 1));
            TextView st = Style.t(this, eon ? "ON" : "Off", 14, eon ? 700 : 400, eon ? Style.AMBER : Style.WHITE60);
            r.addView(st);
            r.setOnClickListener(v -> toggle(id));
            if (id.startsWith("light.")) r.setOnLongClickListener(v -> { openSheet(id); return true; });
            if (rooms.getChildCount() > 0) {
                View div = new View(this);
                div.setBackgroundColor(Style.LINE);
                rooms.addView(div, new LinearLayout.LayoutParams(-1, 1));
            }
            rooms.addView(r, new LinearLayout.LayoutParams(-1, u(52)));
        }
        rooms.setVisibility(rooms.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        Ha.Ent cl = dash.climate().isEmpty() ? null : ha.states.get(dash.climate());
        if (cl != null) rooms.addView(climateRow(cl));
        bedBtn.setVisibility(dash.bedtime() && !dash.room() ? View.VISIBLE : View.GONE);
    }

    /** "Living Room Lights" → "Living room"; "Back Porch Power" → "Back porch". */
    static String shortName(String n) {
        String s = n.replaceAll("(?i)\\s+(lights?|power|switch|lamps?)$", "").trim();
        if (s.isEmpty()) s = n;
        return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1).toLowerCase(Locale.getDefault());
    }

    private void toggle(String id) {
        Ha.Ent e = ha.states.get(id);
        if (e == null) return;
        String d = id.substring(0, id.indexOf('.'));
        ha.callService(d, e.isOn() ? "turn_off" : "turn_on", id, null);
    }

    /** House lights (no groups, never Argus / LED rings / backlights); onlyOn = just the ones that are on. */
    private JSONArray houseLights(boolean onlyOn) {
        JSONArray out = new JSONArray();
        for (Ha.Ent e : ha.states.values()) {
            if (!"light".equals(e.domain()) || e.unavailable() || e.attrs.has("entity_id")) continue;
            if (e.id.contains("argus") || e.id.contains("led_ring") || e.id.contains("backlight")) continue;
            if (!onlyOn || e.isOn()) out.put(e.id);
        }
        return out;
    }

    /** Whole house: anything on → everything off; all off → everything on. */
    private void wholeHouse() {
        JSONArray on = houseLights(true);
        boolean off = on.length() > 0;
        JSONArray target = off ? on : houseLights(false);
        if (target.length() == 0) return;
        try {
            ha.send(new JSONObject().put("type", "call_service").put("domain", "light").put("service", off ? "turn_off" : "turn_on")
                    .put("target", new JSONObject().put("entity_id", target)), (res, err) -> {
                if (err != null) Toast.makeText(this, "Couldn't: " + err, Toast.LENGTH_SHORT).show();
            });
        } catch (Exception ignored) { }
    }

    /** "Keeps Kitchen, Bedroom" from the keep list (id prefixes → the first matching light's name). */
    private String keepsLabel() {
        List<String> names = new ArrayList<>();
        for (String prefix : dash.keep()) {
            for (Ha.Ent e : ha.states.values())
                if (e.id.startsWith(prefix) && "light".equals(e.domain())) { names.add(e.name().replaceAll("(?i)\\s*lights?$", "")); break; }
        }
        return names.isEmpty() ? "All lights off · switches stay" : "Keeps " + android.text.TextUtils.join(", ", names) + " · switches stay";
    }

    /** All house lights off except the kept ones (keep list); switches stay as they are. */
    private void lastToBed() {
        JSONArray off = new JSONArray();
        for (Ha.Ent e : ha.states.values()) {
            if (!"light".equals(e.domain()) || !e.isOn() || e.attrs.has("entity_id")) continue;   // skip groups
            String id = e.id;
            if (id.contains("argus") || id.contains("led_ring") || id.contains("backlight")) continue;  // never touch these
            boolean keep = false;
            for (String k : dash.keep()) if (id.startsWith(k)) keep = true;
            if (!keep) off.put(id);
        }
        if (off.length() == 0) { Toast.makeText(this, "Already done", Toast.LENGTH_SHORT).show(); return; }
        try {
            ha.send(new JSONObject().put("type", "call_service").put("domain", "light").put("service", "turn_off")
                    .put("target", new JSONObject().put("entity_id", off)), (res, err) ->
                    Toast.makeText(this, err == null ? "Good night" : "Couldn't: " + err, Toast.LENGTH_SHORT).show());
        } catch (Exception ignored) { }
    }

    private void media(String service) { ha.callService("media_player", service, dash.media(), null); }

    private void renderMedia() {
        // Always shown (the mockup's music bar): idle it names this speaker and Play resumes the queue.
        Ha.Ent m = ha.states.get(dash.media());
        String title = m == null ? "" : m.attrs.optString("media_title", "");
        boolean active = m != null && ("playing".equals(m.state) || "paused".equals(m.state)) && !title.isEmpty();
        npBar.setVisibility(View.VISIBLE);
        if (active) {
            npTitle.setText(title);
            npArtist.setText(m.attrs.optString("media_artist", m.attrs.optString("media_album_name", "")));
        } else {
            npTitle.setText(m == null ? SendspinService.playerName(this) : shortName(m.name()));
            npArtist.setText(m == null ? "Speaker connecting…" : "Nothing playing · press play to resume");
        }
        FrameLayout pb = (FrameLayout) playIcon.getParent();
        pb.removeAllViews();
        playIcon = Kit.icon(this, m != null && "playing".equals(m.state) ? "pause" : "play", Style.INK);
        pb.addView(playIcon, new FrameLayout.LayoutParams(u(27), u(27), Gravity.CENTER));
        tickMedia();
    }

    private void tickMedia() {
        Ha.Ent m = ha.states.get(dash.media());
        if (m == null) return;
        double dur = m.attrs.optDouble("media_duration", 0), pos = m.attrs.optDouble("media_position", 0);
        Date upd = Ui.parseIso(m.attrs.optString("media_position_updated_at", ""));
        if ("playing".equals(m.state) && upd != null) pos += (System.currentTimeMillis() - upd.getTime()) / 1000.0;
        if (dur > 0) pos = Math.min(pos, dur);
        npPos.setText(dur > 0 ? Kit.mmss((long) (pos * 1000)) : "");
        npDur.setText(dur > 0 ? Kit.mmss((long) (dur * 1000)) : "");
        View track = (View) npFill.getParent();
        int w = track.getWidth();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) npFill.getLayoutParams();
        lp.width = dur > 0 ? (int) (w * pos / dur) : 0;
        npFill.setLayoutParams(lp);
    }

    private void loadShop() {
        Ha.Ent e = ha.states.get(listId);
        if (e == null) { shopCount.setText(""); return; }
        if (e.state.equals(lastShopState)) return;
        lastShopState = e.state;
        TaskRows.load(this, ha, listId, shopList, shopCount, dash.room());
    }

    private void renderShop(JSONArray items) {
        shopList.removeAllViews();
        int n = items == null ? 0 : items.length();
        shopCount.setText(n == 1 ? "1 item" : n + " items");
        for (int i = 0; i < n; i++) {
            JSONObject it = items.optJSONObject(i);
            String summary = it.optString("summary"), uid = it.optString("uid", summary);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            View box = new View(this);
            android.graphics.drawable.GradientDrawable g = Style.fill(0, u(11));
            g.setStroke(u(2), 0xBFFFFFFF);
            box.setBackground(g);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(u(22), u(22));
            bl.rightMargin = u(14);
            r.addView(box, bl);
            r.addView(Style.t(this, summary, 19, 400, Style.WHITE), new LinearLayout.LayoutParams(0, -2, 1));
            r.setOnClickListener(v -> {
                g.setColor(Style.CREAM);
                r.animate().alpha(0.3f).setDuration(220).start();
                try { ha.callService("todo", "update_item", listId, new JSONObject().put("item", uid).put("status", "completed")); } catch (Exception ignored) { }
            });
            r.setMinimumHeight(u(52));
            r.setPadding(0, u(8), 0, u(8));
            shopList.addView(r, new LinearLayout.LayoutParams(-1, -2));
            if (i < n - 1) {
                View div = new View(this);
                div.setBackgroundColor(Style.LINE);
                shopList.addView(div, new LinearLayout.LayoutParams(-1, 1));
            }
        }
    }

    private void addShop(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return;
        s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        try { ha.callService("todo", "add_item", listId, new JSONObject().put("item", s)); } catch (Exception ignored) { }
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(shopAdd.getWindowToken(), 0);
    }

    // ---------- voice ----------

    /** Shopping mic = dictation into the list ("eggs and milk" → two items). Top mic = ask the house (Assist). */
    private void listen(boolean shopping) {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        if (voice != null) { voice.endSpeech(); return; }
        View old = root.findViewWithTag("voice");
        if (old != null) root.removeView(old);
        TextView bubble = Style.t(this, shopping ? "Listening… say what to add" : "Listening…", 26, 500, Style.WHITE);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(u(36), u(24), u(36), u(24));
        bubble.setBackground(Style.fill(0xE6161A22, u(30)));
        bubble.setTag("voice");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(bubble, lp);
        bubble.setOnClickListener(v -> { if (voice != null) voice.endSpeech(); });
        voice = new HaPipeline(HaPipeline.Settings.load(this), shopping, new HaPipeline.Events() {
            @Override public void onListening() { }
            @Override public void onLevel(float level) { bubble.setAlpha(0.75f + level * 0.25f); }
            @Override public void onThinking() { bubble.setText("…"); }
            @Override public void onHeard(String text) {
                bubble.setText("“" + text + "”");
                if (shopping) for (String part : text.split("(?i)\\s*(,| and | then )\\s*")) addShop(part.replaceAll("[.!?]+$", ""));
            }
            @Override public void onReply(String text) { if (!shopping) bubble.setText(text); }
            @Override public void onSpeech(String url) { }
            @Override public void onError(String message) { bubble.setText(message); }
            @Override public void onDone() {
                voice = null;
                h.postDelayed(() -> { if (bubble.getParent() != null && voice == null) root.removeView(bubble); }, shopping ? 1500 : 4000);
            }
        });
        voice.start();
    }

    // ---------- light sheet (hold a light) ----------

    private void openSheet(String id) {
        Ha.Ent e = ha.states.get(id);
        if (e == null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setTag("sheet");
        overlay.setBackgroundColor(0xA8040610);
        overlay.setOnClickListener(v -> root.removeView(overlay));
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setPadding(u(36), u(32), u(36), u(32));
        sheet.setBackground(Style.fill(0xF0181A20, u(34)));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Style.t(this, shortName(e.name()), 38, 500, Style.WHITE);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView onoff = Style.pill(this, e.isOn() ? "On" : "Off", e.isOn() ? Style.AMBER_SOFT : Style.CHIP, Style.WHITE, v -> toggle(id));
        top.addView(onoff);
        View close = Kit.iconButton(this, "x", u(52), Style.CHIP, Style.WHITE, "Close", v -> root.removeView(overlay));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(u(52), u(52));
        cl.leftMargin = u(12);
        top.addView(close, cl);
        sheet.addView(top);

        int pct = e.isOn() ? Math.round(e.attrs.optInt("brightness", 255) * 100 / 255f) : 0;
        TextView bLabel = Style.tcaps(this, "Brightness  " + pct + "%", 15, Style.WHITE70);
        bLabel.setPadding(0, u(26), 0, u(10));
        sheet.addView(bLabel);
        Widgets.Slider bright = new Widgets.Slider(this, pct, Style.AMBER, null, v -> bLabel.setText(("Brightness  " + v + "%").toUpperCase(Locale.getDefault())), v -> {
            try {
                if (v <= 0) ha.callService("light", "turn_off", id, null);
                else ha.callService("light", "turn_on", id, new JSONObject().put("brightness_pct", v));
            } catch (Exception ignored) { }
        });
        sheet.addView(bright, new LinearLayout.LayoutParams(-1, u(64)));

        JSONArray modes = e.attrs.optJSONArray("supported_color_modes");
        String ms = modes == null ? "" : modes.toString();
        if (ms.contains("color_temp")) {
            int minK = e.attrs.optInt("min_color_temp_kelvin", 2000), maxK = e.attrs.optInt("max_color_temp_kelvin", 6500);
            int curK = e.attrs.optInt("color_temp_kelvin", (minK + maxK) / 2);
            TextView wLabel = Style.tcaps(this, "Warmth  " + curK + " K", 15, Style.WHITE70);
            wLabel.setPadding(0, u(24), 0, u(10));
            sheet.addView(wLabel);
            int start = Math.round((curK - minK) * 100f / Math.max(1, maxK - minK));
            Widgets.Slider warm = new Widgets.Slider(this, start, 0, new int[]{0xFFFFB45E, 0xFFFFE6C7, 0xFFDCE8FF},
                    v -> wLabel.setText(("Warmth  " + (minK + (maxK - minK) * v / 100) + " K").toUpperCase(Locale.getDefault())),
                    v -> { try { ha.callService("light", "turn_on", id, new JSONObject().put("color_temp_kelvin", minK + (maxK - minK) * v / 100)); } catch (Exception ignored) { } });
            sheet.addView(warm, new LinearLayout.LayoutParams(-1, u(48)));
        }
        if (ms.contains("hs") || ms.contains("rgb") || ms.contains("xy")) {
            TextView cLabel = Style.tcaps(this, "Colour", 15, Style.WHITE70);
            cLabel.setPadding(0, u(24), 0, u(10));
            sheet.addView(cLabel);
            LinearLayout sw = new LinearLayout(this);
            int[][] colours = {{255, 217, 160}, {242, 169, 59}, {233, 113, 154}, {229, 72, 77}, {155, 107, 255}, {79, 107, 237}, {43, 181, 168}};
            for (int i = 0; i < colours.length; i++) {
                int[] c = colours[i];
                View s = new View(this);
                s.setBackground(Style.fill(0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2], u(28)));
                s.setOnClickListener(v -> {
                    try { ha.callService("light", "turn_on", id, new JSONObject().put("rgb_color", new JSONArray().put(c[0]).put(c[1]).put(c[2]))); } catch (Exception ignored) { }
                });
                Style.pressable(s);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, u(56), 1);
                if (i > 0) lp.leftMargin = u(10);
                sw.addView(s, lp);
            }
            sheet.addView(sw);
        }
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(u(880), -2, Gravity.CENTER);
        overlay.addView(sheet, sl);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void checkRinging() {
        List<Timers.T> r = timers.ringing();
        if (!r.isEmpty() && alertView == null) {
            alertView = Kit.timerAlert(this, r, () -> timers.stopRinging());
            root.addView(alertView, new FrameLayout.LayoutParams(-1, -1));
        } else if (r.isEmpty() && alertView != null) {
            root.removeView(alertView);
            alertView = null;
        }
    }

    // ---------- tick ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickClock();
            tickTimers();
            tickMedia();
            if (SystemClock.uptimeMillis() - lastTouch > 90_000L && timers.ringing().isEmpty() && voice == null) { finish(); return; }
            h.postDelayed(this, 1000);
        }
    };

    private void tickClock() {
        Date now = new Date();
        boolean h24 = Ui.h24(this);
        clock.setText(new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now));
        StringBuilder s = new StringBuilder(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now));
        Ha.Ent w = ha.states.get(dash.weather());
        if (w != null) s.append(" · ").append(Tiles.deg(w.attrs.optString("temperature", ""))).append("° ").append(HomeActivity.condition(w.state));
        if (dash.room()) {
            Ha.Ent rs = dash.roomSensor().isEmpty() ? null : ha.states.get(dash.roomSensor());
            if (rs != null && !rs.unavailable()) s.append(" · room ").append(rs.state);
            String next = nextAlarm();
            if (!next.isEmpty()) s.append(" · alarm ").append(next);
        }
        topLine.setText(s.toString().toUpperCase(Locale.getDefault()));
    }

    // ---------- room layout: alarms, list tabs, thermostat ----------

    private View alarmCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(u(24), u(22), u(24), u(22));
        c.setBackground(Style.glassU(26));
        c.addView(header("Alarms"));
        alarmRows = new LinearLayout(this);
        alarmRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = u(12);
        c.addView(alarmRows, lp);
        TextView up = header("Coming up");
        up.setPadding(0, u(10), 0, u(6));
        c.addView(up);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        events = new LinearLayout(this);
        events.setOrientation(LinearLayout.VERTICAL);
        sv.addView(events);
        c.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return c;
    }

    private LinearLayout events;
    private long lastEvents;

    /** Next events across the room's calendars (30 days), soonest first. */
    private void loadEvents() {
        if (events == null || System.currentTimeMillis() - lastEvents < 10 * 60_000L) return;
        lastEvents = System.currentTimeMillis();
        java.util.List<String> cals = new java.util.ArrayList<>();
        for (String c : dash.calendars()) if (ha.states.get(c) != null) cals.add(c);
        if (cals.isEmpty()) return;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        Calendar now = Calendar.getInstance();
        String start = iso.format(now.getTime());
        now.add(Calendar.DAY_OF_MONTH, 30);
        String end = iso.format(now.getTime());
        try {
            ha.send(new JSONObject().put("type", "call_service").put("domain", "calendar").put("service", "get_events")
                    .put("target", new JSONObject().put("entity_id", new JSONArray(cals)))
                    .put("service_data", new JSONObject().put("start_date_time", start).put("end_date_time", end))
                    .put("return_response", true), (res, err) -> {
                JSONObject r = res == null ? null : res.optJSONObject("response");
                java.util.List<JSONObject> all = new java.util.ArrayList<>();
                for (String c : cals) {
                    JSONObject mine = r == null ? null : r.optJSONObject(c);
                    JSONArray ev = mine == null ? null : mine.optJSONArray("events");
                    for (int i = 0; ev != null && i < ev.length(); i++) all.add(ev.optJSONObject(i));
                }
                BriefActivity.upcoming(all);
                renderEvents(all);
            });
        } catch (Exception ignored) { }
    }

    private void renderEvents(java.util.List<JSONObject> all) {
        events.removeAllViews();
        if (all.isEmpty()) { events.addView(Style.t(this, "Nothing in the next month", 17, 400, Style.WHITE70)); return; }
        SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.getDefault()), time = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (int i = 0; i < Math.min(all.size(), 5); i++) {
            JSONObject e = all.get(i);
            String s = e.optString("start");
            Date d = Ui.parseIso(s);
            boolean allDay = s.length() <= 10;
            if (allDay) try { d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s); } catch (Exception ignored) { }
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, u(8), 0, u(8));
            boolean soon = d != null && d.getTime() - System.currentTimeMillis() < 6L * 86_400_000L;
            String when = d == null ? "" : (android.text.format.DateUtils.isToday(d.getTime()) ? "Today" : soon ? day.format(d) : new SimpleDateFormat("MMM d", Locale.getDefault()).format(d))
                    + (allDay ? "" : " " + time.format(d));
            TextView w = Style.t(this, when, 14, 600, Style.AMBER);
            r.addView(w, new LinearLayout.LayoutParams(u(140), -2));
            TextView sm = Style.t(this, e.optString("summary"), 17, 400, Style.WHITE);
            sm.setSingleLine(true);
            sm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(sm, new LinearLayout.LayoutParams(0, -2, 1));
            events.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
    }


    private void renderAlarms() {
        if (alarmRows == null) return;
        alarmRows.removeAllViews();
        String[][] alarms = {{dash.alarmSwitch(), dash.alarmTime(), "Alarm 1"}, {dash.alarm2Switch(), dash.alarm2Time(), "Alarm 2"}};
        for (String[] a : alarms) {
            Ha.Ent sw = ha.states.get(a[0]), tm = ha.states.get(a[1]);
            if (sw == null && tm == null) continue;
            boolean on = sw != null && sw.isOn();
            String hhmm = tm == null || tm.unavailable() ? "" : tm.state;      // "07:00:00"
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(u(20), u(8), u(14), u(8));
            row.setBackground(Style.toggleU(20, on));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(Style.tcaps(this, a[2], 12, on ? Style.AMBER : Style.WHITE70));
            txt.addView(Style.t(this, hhmm.isEmpty() ? "–" : twelve(hhmm), 38, 300, on ? Style.WHITE : Style.WHITE70));
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            TextView pill = Style.t(this, on ? "ON" : "OFF", 15, 700, on ? Style.INK : Style.WHITE);
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(Style.fill(on ? Style.AMBER : 0x26FFFFFF, u(22)));
            if (sw != null) pill.setOnClickListener(v -> ha.callService("switch", on ? "turn_off" : "turn_on", a[0], null));
            Style.pressable(pill);
            row.addView(pill, new LinearLayout.LayoutParams(u(84), u(44)));
            row.setOnClickListener(v -> openAlarmSheet(a[0], a[1], a[2]));     // tap the alarm → window to set it
            Style.pressable(row);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = u(10);
            alarmRows.addView(row, rp);
        }
        if (alarmRows.getChildCount() == 0) alarmRows.addView(Style.t(this, "No alarms set up for this tablet", 17, 400, Style.WHITE70));
        tickClock();
    }

    /** Window to set an alarm: hour/minute wheels, AM/PM read-out, on/off, Save (sets the time and turns it on). */
    private void openAlarmSheet(String switchId, String timeId, String title) {
        Ha.Ent tm = ha.states.get(timeId), sw = ha.states.get(switchId);
        if (tm == null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setTag("sheet");
        overlay.setBackgroundColor(0xA8040610);
        overlay.setOnClickListener(v -> root.removeView(overlay));
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setPadding(u(36), u(30), u(36), u(30));
        sheet.setBackground(Style.fill(0xF0181A20, u(34)));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Style.t(this, title, 34, 500, Style.WHITE), new LinearLayout.LayoutParams(0, -2, 1));
        final boolean[] enabled = {sw == null || sw.isOn()};
        TextView onoff = Style.pill(this, enabled[0] ? "On" : "Off", enabled[0] ? Style.AMBER_SOFT : Style.CHIP, Style.WHITE, null);
        onoff.setOnClickListener(v -> {
            enabled[0] = !enabled[0];
            onoff.setText(enabled[0] ? "On" : "Off");
            onoff.setBackground(Style.fill(enabled[0] ? Style.AMBER_SOFT : Style.CHIP, u(22)));
        });
        top.addView(onoff);
        View close = Kit.iconButton(this, "x", u(52), Style.CHIP, Style.WHITE, "Close", v -> root.removeView(overlay));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(u(52), u(52));
        cl.leftMargin = u(12);
        top.addView(close, cl);
        sheet.addView(top);

        TextView readout = Style.t(this, twelve(tm.state), 64, 200, Style.AMBER);
        readout.setGravity(Gravity.CENTER);
        readout.setPadding(0, u(14), 0, u(6));
        sheet.addView(readout, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout wheels = new LinearLayout(this);
        wheels.setBackground(Style.fill(0x33000000, u(22)));
        // 12-hour clock: hour (12, 1 … 11), minute, AM/PM
        Widgets.Wheel wh = new Widgets.Wheel(this, 11, 1, "");
        wh.labels("12", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        Widgets.Wheel wm = new Widgets.Wheel(this, 59, 1, "min");
        Widgets.Wheel wap = new Widgets.Wheel(this, 1, 1, "");
        wap.labels("AM", "PM");
        final int[] h24 = {0};
        Widgets.IntListener upd = v -> {
            h24[0] = wh.value() + (wap.value() == 1 ? 12 : 0);
            readout.setText(twelve(String.format(Locale.US, "%02d:%02d:00", h24[0], wm.value())));
        };
        wh.onValue(upd); wm.onValue(upd); wap.onValue(upd);
        wheels.addView(wh, new LinearLayout.LayoutParams(0, -1, 1));
        wheels.addView(wm, new LinearLayout.LayoutParams(0, -1, 1.2f));
        wheels.addView(wap, new LinearLayout.LayoutParams(0, -1, 1));
        sheet.addView(wheels, new LinearLayout.LayoutParams(-1, u(190)));
        try {
            String[] p = tm.state.split(":");
            int hh = Integer.parseInt(p[0]), mm = Integer.parseInt(p[1]);
            wh.post(() -> { wh.setValue(hh % 12); wm.setValue(mm); wap.setValue(hh >= 12 ? 1 : 0); });
        } catch (Exception ignored) { }

        LinearLayout quick = new LinearLayout(this);
        quick.setGravity(Gravity.CENTER);
        for (String q : new String[]{"6:30", "7:00", "7:30", "8:00", "9:00"}) {
            TextView b = Style.pill(this, q, Style.CHIP, Style.WHITE, v -> {
                String[] p = q.split(":");
                wh.setValue(Integer.parseInt(p[0]) % 12); wap.setValue(0);       // quick picks are morning times
                wm.setValue(Integer.parseInt(p[1]));
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, u(48));
            bp.leftMargin = u(5); bp.rightMargin = u(5);
            quick.addView(b, bp);
        }
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.topMargin = u(16);
        sheet.addView(quick, qp);

        TextView save = Style.t(this, "Save", 20, 700, Style.INK);
        save.setGravity(Gravity.CENTER);
        save.setBackground(Style.fill(Style.AMBER, u(28)));
        save.setOnClickListener(v -> {
            try {
                ha.callService("time", "set_value", timeId, new JSONObject().put("time",
                        String.format(Locale.US, "%02d:%02d:00", wh.value() + (wap.value() == 1 ? 12 : 0), wm.value())));
            } catch (Exception ignored) { }
            if (sw != null && sw.isOn() != enabled[0]) ha.callService("switch", enabled[0] ? "turn_on" : "turn_off", switchId, null);
            root.removeView(overlay);
        });
        Style.pressable(save);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, u(60));
        svp.topMargin = u(20);
        sheet.addView(save, svp);

        overlay.addView(sheet, new FrameLayout.LayoutParams(u(640), -2, Gravity.CENTER));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    /** "07:05:00" → "7:05 AM". */
    private static String twelve(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            int h = Integer.parseInt(p[0]);
            return (h % 12 == 0 ? 12 : h % 12) + ":" + p[1] + (h < 12 ? " AM" : " PM");
        } catch (Exception e) { return hhmm; }
    }

    /** Soonest enabled alarm, e.g. "7:00 AM", or "". */
    private String nextAlarm() {
        String best = "";
        int bestMin = Integer.MAX_VALUE;
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (String[] a : new String[][]{{dash.alarmSwitch(), dash.alarmTime()}, {dash.alarm2Switch(), dash.alarm2Time()}}) {
            Ha.Ent sw = ha.states.get(a[0]), tm = ha.states.get(a[1]);
            if (sw == null || !sw.isOn() || tm == null || tm.unavailable()) continue;
            try {
                String[] p = tm.state.split(":");
                int m = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
                int until = (m - nowMin + 1440) % 1440;
                if (until < bestMin) { bestMin = until; best = twelve(tm.state); }
            } catch (Exception ignored) { }
        }
        return best;
    }

    private void renderListTabs() {
        if (listTabs == null) return;
        listTabs.removeAllViews();
        for (String id : dash.lists()) {
            Ha.Ent e = ha.states.get(id);
            if (e == null) continue;
            boolean sel = id.equals(listId);
            TextView t = Style.t(this, shortName(e.name()).replaceAll("(?i)\\s+list$", ""), 15, sel ? 700 : 500, sel ? Style.INK : Style.WHITE);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(u(16), 0, u(16), 0);
            t.setBackground(Style.fill(sel ? Style.CREAM : 0x1AFFFFFF, u(20)));
            t.setOnClickListener(v -> { listId = id; lastShopState = null; renderListTabs(); loadShop(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
            lp.rightMargin = u(8);
            listTabs.addView(t, lp);
        }
    }

    private View climateRow(Ha.Ent cl) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(u(16), 0, u(8), 0);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Style.t(this, "Thermostat", 17, 600, Style.WHITE));
        String cur = Tiles.num(cl.attrs.optString("current_temperature", ""));
        String mode = cl.attrs.optString("hvac_action", cl.state);
        t.addView(Style.t(this, (cur.isEmpty() ? "" : cur + "° now · ") + mode.replace('_', ' '), 13, 400, Style.WHITE70));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        double target = cl.attrs.optDouble("temperature", Double.NaN);
        if (!Double.isNaN(target)) {
            double stepD = cl.attrs.optDouble("target_temp_step", 1);
            r.addView(Kit.iconButton(this, "prev", u(40), 0x1AFFFFFF, Style.WHITE, "Cooler", v -> setTemp(cl.id, target - stepD)));
            TextView tv = Style.t(this, Tiles.num(String.valueOf(target)) + "°", 20, 600, Style.AMBER);
            tv.setGravity(Gravity.CENTER);
            r.addView(tv, new LinearLayout.LayoutParams(u(64), -2));
            r.addView(Kit.iconButton(this, "next", u(40), 0x1AFFFFFF, Style.WHITE, "Warmer", v -> setTemp(cl.id, target + stepD)));
        }
        View div = new View(this);
        div.setBackgroundColor(Style.LINE);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(div, new LinearLayout.LayoutParams(-1, 1));
        wrap.addView(r, new LinearLayout.LayoutParams(-1, u(64)));
        return wrap;
    }

    private void setTemp(String id, double t) {
        try { ha.callService("climate", "set_temperature", id, new JSONObject().put("temperature", t)); } catch (Exception ignored) { }
    }
}
