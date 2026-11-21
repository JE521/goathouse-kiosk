package org.goathouse.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 * Goatprint personal brief (mockup "Hallway · personal brief"): who just checked in, their next events, their tasks,
 * and who is home. A fingerprint scan opens it for that person (extra "person", "finger"); without a reader the
 * avatars at the top pick the person. "House view" goes to the shared controls. Back to idle after 60 s untouched.
 */
public class BriefActivity extends Activity implements Ha.Listener {
    private Ha ha;
    private Dash dash;
    private String person;
    private String finger;
    private LinearLayout avatars, events, tasks, home;
    private TextView source, hello, line, tasksCount;
    private EditText addTask;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable idle = this::finish;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ha = Ha.get(this);
        dash = Dash.of(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        take(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); take(i); }

    private void take(Intent i) {
        person = i.getStringExtra("person");
        finger = i.getStringExtra("finger");
        if (person == null) { List<String> ps = dash.people(); person = ps.isEmpty() ? "" : ps.get(0); }
    }

    @Override protected void onResume() {
        super.onResume();
        Style.brightness(this, false);
        Style.measure(this);
        build();
        ha.addListener(this);
        render();
        poke();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override protected void onPause() { super.onPause(); ha.removeListener(this); h.removeCallbacks(idle); }

    @Override public void onUserInteraction() { super.onUserInteraction(); poke(); }

    private void poke() { h.removeCallbacks(idle); h.postDelayed(idle, 60_000); }

    private int u(float v) { return Style.u(v); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(u(24), u(20), u(24), u(20));
        c.setBackground(Style.glassU(26));
        return c;
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        root.addView(Kit.backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        root.addView(Kit.scrim(this, 0x99060910), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(u(40), u(28), u(40), u(28));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Kit.iconButton(this, "back", u(52), Style.GLASS, Style.WHITE, "Back", v -> finish()));
        TextView when = Style.tcaps(this, new SimpleDateFormat("h:mm a · EEEE d MMMM", Locale.getDefault()).format(new Date()), 15, Style.WHITE80);
        when.setPadding(u(18), 0, 0, 0);
        top.addView(when, new LinearLayout.LayoutParams(0, -2, 1));
        avatars = new LinearLayout(this);
        top.addView(avatars);
        TextView house = Style.t(this, "House view", 16, 600, Style.WHITE);
        house.setGravity(Gravity.CENTER);
        house.setPadding(u(20), 0, u(20), 0);
        house.setBackground(Style.fill(Style.GLASS, u(26)));
        house.setOnClickListener(v -> { startActivity(new Intent(this, ControlsActivity.class)); finish(); });
        Style.pressable(house);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, u(52));
        hp.leftMargin = u(14);
        if (Goatprint.get(this).attached()) {      // fingerprint reader here: enrol this person's finger
            TextView add = Style.t(this, "Add a finger", 16, 600, Style.WHITE);
            add.setGravity(Gravity.CENTER);
            add.setPadding(u(20), 0, u(20), 0);
            add.setBackground(Style.fill(Style.GLASS, u(26)));
            add.setOnClickListener(v -> GoatprintEnrollActivity.open(this, person));
            Style.pressable(add);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, u(52));
            ap.leftMargin = u(14);
            top.addView(add, ap);
        }
        top.addView(house, hp);
        page.addView(top, new LinearLayout.LayoutParams(-1, u(60)));

        LinearLayout grid = new LinearLayout(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1);
        glp.topMargin = u(22);
        page.addView(grid, glp);

        // left: greeting + events
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        source = Style.tcaps(this, "", 14, Style.AMBER);
        hello = Style.t(this, "", 44, 300, Style.WHITE);
        line = Style.t(this, "", 19, 400, Style.WHITE80);
        line.setPadding(0, u(6), 0, u(18));
        left.addView(source); left.addView(hello); left.addView(line);
        ScrollView esv = new ScrollView(this);
        esv.setVerticalScrollBarEnabled(false);
        events = new LinearLayout(this);
        events.setOrientation(LinearLayout.VERTICAL);
        esv.addView(events);
        left.addView(esv, new LinearLayout.LayoutParams(-1, 0, 1));
        grid.addView(left, new LinearLayout.LayoutParams(0, -1, 1.25f));

        // right: tasks + home now
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1);
        rlp.leftMargin = u(24);
        grid.addView(right, rlp);

        LinearLayout tc = card();
        LinearLayout th = new LinearLayout(this);
        th.addView(Style.tcaps(this, "Your tasks", 15, Style.WHITE80), new LinearLayout.LayoutParams(0, -2, 1));
        tasksCount = Style.t(this, "", 15, 400, Style.WHITE70);
        th.addView(tasksCount);
        tc.addView(th);
        addTask = new EditText(this);
        addTask.setHint("Add a task");
        addTask.setHintTextColor(0x8CFFFFFF);
        addTask.setTextColor(Style.WHITE);
        addTask.setTypeface(Style.font(this, 400));
        addTask.setSingleLine(true);
        addTask.setImeOptions(EditorInfo.IME_ACTION_DONE);
        addTask.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        addTask.setBackground(Style.fill(0x1AFFFFFF, u(26)));
        addTask.setPadding(u(18), 0, u(18), 0);
        addTask.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String s = addTask.getText().toString().trim();
                String todo = dash.personTodo(person);
                if (!s.isEmpty() && !todo.isEmpty()) try { ha.callService("todo", "add_item", todo, new JSONObject().put("item", s)); } catch (Exception ignored) { }
                addTask.setText("");
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, u(52));
        ap.topMargin = u(12);
        tc.addView(addTask, ap);
        ScrollView tsv = new ScrollView(this);
        tsv.setVerticalScrollBarEnabled(false);
        tasks = new LinearLayout(this);
        tasks.setOrientation(LinearLayout.VERTICAL);
        tsv.addView(tasks);
        LinearLayout.LayoutParams tsp = new LinearLayout.LayoutParams(-1, 0, 1);
        tsp.topMargin = u(6);
        tc.addView(tsv, tsp);
        right.addView(tc, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout hc = card();
        hc.addView(Style.tcaps(this, "Home now", 15, Style.WHITE80));
        home = new LinearLayout(this);
        home.setPadding(0, u(12), 0, 0);
        hc.addView(home);
        LinearLayout.LayoutParams hcp = new LinearLayout.LayoutParams(-1, -2);
        hcp.topMargin = u(16);
        right.addView(hc, hcp);

        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    // ---------- data ----------

    @Override public void onStatus(boolean connected, String message) { if (connected) render(); }

    @Override public void onChanged(Set<String> ids) {
        boolean people = false;
        for (String id : ids) if (id.startsWith("person.")) people = true;
        if (people) renderHome();
        if (ids.contains(dash.personTodo(person))) loadTasks();
    }

    private void render() {
        renderAvatars();
        Ha.Ent p = ha.states.get(person);
        String name = p == null ? "" : first(p.name());
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean arrived = p != null && "home".equals(p.state) && p.lastChanged * 1000 > System.currentTimeMillis() - 20 * 60_000L;
        hello.setText((arrived ? "Welcome home" : hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 18 ? "Good afternoon" : "Good evening")
                + (name.isEmpty() ? "" : ", " + name) + ".");
        source.setText(finger == null ? "" : (finger + " · " + name).toUpperCase(Locale.getDefault()));
        line.setText("");
        loadEvents();
        loadTasks();
        renderHome();
    }

    private static String first(String n) { int sp = n.indexOf(' '); return sp > 0 ? n.substring(0, sp) : n; }

    private void renderAvatars() {
        avatars.removeAllViews();
        for (String id : dash.people()) {
            Ha.Ent e = ha.states.get(id);
            if (e == null) continue;
            boolean sel = id.equals(person);
            TextView a = Style.t(this, first(e.name()).substring(0, 1).toUpperCase(Locale.getDefault()), 18, 700, sel ? Style.INK : Style.WHITE);
            a.setGravity(Gravity.CENTER);
            a.setBackground(Style.fill(sel ? Style.CREAM : Style.GLASS, u(26)));
            a.setContentDescription(e.name());
            a.setOnClickListener(v -> { person = id; finger = null; render(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(52), u(52));
            lp.leftMargin = u(10);
            avatars.addView(a, lp);
        }
    }

    private void loadEvents() {
        events.removeAllViews();
        List<String> cals = new ArrayList<>();
        for (String c : dash.personCalendars(person)) if (ha.states.get(c) != null) cals.add(c);
        if (cals.isEmpty()) { line.setText("No calendar linked for you yet."); return; }
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        Calendar c = Calendar.getInstance();
        String start = iso.format(c.getTime());
        c.add(Calendar.DAY_OF_MONTH, 2);
        c.set(Calendar.HOUR_OF_DAY, 0);
        String end = iso.format(c.getTime());
        try {
            ha.send(new JSONObject().put("type", "call_service").put("domain", "calendar").put("service", "get_events")
                    .put("target", new JSONObject().put("entity_id", new JSONArray(cals)))
                    .put("service_data", new JSONObject().put("start_date_time", start).put("end_date_time", end))
                    .put("return_response", true), (res, err) -> {
                JSONObject r = res == null ? null : res.optJSONObject("response");
                List<JSONObject> all = new ArrayList<>();
                for (String cal : cals) {
                    JSONObject mine = r == null ? null : r.optJSONObject(cal);
                    JSONArray ev = mine == null ? null : mine.optJSONArray("events");
                    for (int i = 0; ev != null && i < ev.length(); i++) all.add(ev.optJSONObject(i));
                }
                upcoming(all);
                renderEvents(all);
            });
        } catch (Exception ignored) { }
    }

    /**
     * Sort soonest-first and drop events that began before today: HA returns semester-long school entries (started in
     * September) because they overlap the window, and they would always sit on top.
     */
    static void upcoming(List<JSONObject> all) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis();
        java.util.Iterator<JSONObject> it = all.iterator();
        while (it.hasNext()) if (when(it.next()) < today) it.remove();
        Collections.sort(all, (a, b) -> Long.compare(when(a), when(b)));
    }

    static long when(JSONObject e) {
        String s = e.optString("start");
        Date d = s.length() <= 10 ? null : Ui.parseIso(s);
        if (d == null) try { d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.substring(0, Math.min(10, s.length()))); } catch (Exception ignored) { }
        return d == null ? Long.MAX_VALUE : d.getTime();
    }

    private void renderEvents(List<JSONObject> all) {
        events.removeAllViews();
        int today = 0, tomorrow = 0;
        SimpleDateFormat t = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (JSONObject e : all) {
            long w = when(e);
            boolean allDay = e.optString("start").length() <= 10;
            boolean isToday = android.text.format.DateUtils.isToday(w);
            if (isToday) today++; else tomorrow++;
            if (events.getChildCount() >= 4) continue;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(u(22), u(16), u(22), u(16));
            r.setBackground(Style.glassU(22));
            TextView tm = Style.t(this, allDay ? "All day" : t.format(new Date(w)), 22, 300, Style.WHITE);
            r.addView(tm, new LinearLayout.LayoutParams(u(150), -2));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Style.t(this, e.optString("summary"), 20, 600, Style.WHITE));
            String where = e.optString("location", "");
            col.addView(Style.t(this, (isToday ? "Today" : "Tomorrow") + (where.isEmpty() ? "" : " · " + where), 15, 400, Style.WHITE70));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = u(12);
            events.addView(r, lp);
        }
        line.setText(all.isEmpty() ? "Nothing on your calendar today or tomorrow."
                : (today == 0 ? "Free the rest of today" : today == 1 ? "One more thing today" : today + " things today")
                + (tomorrow == 0 ? "." : ", " + tomorrow + " tomorrow."));
    }

    private void loadTasks() {
        String todo = dash.personTodo(person);
        addTask.setEnabled(!todo.isEmpty() && ha.states.get(todo) != null);
        TaskRows.load(this, ha, todo, tasks, tasksCount);
    }

    /** White chip = home, dark grey = away (mockup). */
    private void renderHome() {
        home.removeAllViews();
        for (String id : dash.people()) {
            Ha.Ent e = ha.states.get(id);
            if (e == null) continue;
            boolean in = "home".equals(e.state);
            TextView chip = Style.t(this, first(e.name()), 16, 600, in ? Style.INK : Style.WHITE70);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(u(18), 0, u(18), 0);
            chip.setBackground(Style.fill(in ? 0xF2FFFFFF : 0x40000000, u(22)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, u(44));
            lp.rightMargin = u(10);
            home.addView(chip, lp);
        }
    }
}
