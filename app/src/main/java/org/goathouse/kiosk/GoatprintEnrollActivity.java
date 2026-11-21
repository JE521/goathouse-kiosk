package org.goathouse.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Enrol a finger right on the tablet: who → which finger → 8 touches with a hint each time → done. After the first
 * (admin) finger, an admin finger approves first; the engine asks for it. Opens from Settings → Fingerprints, the
 * personal brief ("Add a finger", person preselected), or by itself when someone touches a reader nobody's enrolled on.
 */
public class GoatprintEnrollActivity extends Activity {
    private static final String[] FINGER_ORDER = {"right_index", "right_middle", "right_thumb", "left_index", "left_middle", "left_thumb", "right_ring", "left_ring"};
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable idle = this::finish;
    private FrameLayout root;
    private String person, finger;
    private boolean running;
    private TextView message, progress, sub;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        person = getIntent().getStringExtra("person");
        if (person != null && person.startsWith("person.")) person = person.substring(7);
    }

    @Override protected void onResume() {
        super.onResume();
        Style.brightness(this, false);
        Style.measure(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        root.addView(Kit.backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        root.addView(Kit.scrim(this, 0xB3060910), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        if (running) showRun(); else if (person == null) showPeople(); else showFingers();
        poke();
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(idle);
        if (isFinishing() && running) Goatprint.get(this).cancelEnroll();
    }

    @Override public void onUserInteraction() { super.onUserInteraction(); poke(); }

    @Override public void onBackPressed() {
        if (running) { Goatprint.get(this).cancelEnroll(); running = false; showFingers(); }
        else if (finger == null && person != null && getIntent().getStringExtra("person") == null) { person = null; showPeople(); }
        else finish();
    }

    private void poke() { h.removeCallbacks(idle); if (!running) h.postDelayed(idle, 180_000); }

    private int u(float v) { return Style.u(v); }

    /** Title bar + a centred column for the step. */
    private LinearLayout page(String eyebrow, String title) {
        if (root.getChildCount() > 2) root.removeViewAt(2);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(u(40), u(28), u(40), u(28));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Kit.iconButton(this, "back", u(52), Style.GLASS, Style.WHITE, "Back", v -> onBackPressed()));
        TextView e = Style.tcaps(this, eyebrow, 15, Style.AMBER);
        e.setPadding(u(18), 0, 0, 0);
        top.addView(e);
        page.addView(top, new LinearLayout.LayoutParams(-1, u(60)));
        TextView t = Style.t(this, title, 40, 300, Style.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, u(24), 0, u(24));
        page.addView(t);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        return page;
    }

    private TextView chip(String label, View.OnClickListener l) {
        TextView c = Style.t(this, label, 22, 500, Style.WHITE);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Style.glassU(26));
        c.setOnClickListener(l);
        Style.pressable(c);
        return c;
    }

    /** Rows of chips, `per` to a row. */
    private void grid(LinearLayout into, List<String> labels, int per, ChipClick click) {
        LinearLayout row = null;
        for (int i = 0; i < labels.size(); i++) {
            if (i % per == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, u(96));
                rp.bottomMargin = u(16);
                into.addView(row, rp);
            }
            final int idx = i;
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1);
            cp.leftMargin = cp.rightMargin = u(8);
            row.addView(chip(labels.get(i), v -> click.on(idx)), cp);
        }
        if (row != null) for (int k = labels.size() % per; k != 0 && k < per; k++) row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1));
    }

    private interface ChipClick { void on(int i); }

    private void showPeople() {
        finger = null;
        LinearLayout p = page("Add a fingerprint", "Who's this for?");
        List<String> ids = new ArrayList<>(Dash.of(this).people());
        Ha ha = Ha.get(this);
        if (ids.isEmpty()) for (Ha.Ent e : ha.states.values()) if (e.id.startsWith("person.")) ids.add(e.id);
        List<String> names = new ArrayList<>();
        for (String id : ids) { Ha.Ent e = ha.states.get(id); names.add(e != null ? e.name() : Tiles.cap(id.substring(id.indexOf('.') + 1))); }
        if (ids.isEmpty()) { message = Style.t(this, "No people found in Home Assistant yet.", 20, 400, Style.WHITE80); message.setGravity(Gravity.CENTER); p.addView(message); return; }
        grid(p, names, 3, i -> { person = ids.get(i).substring(ids.get(i).indexOf('.') + 1); showFingers(); });
    }

    private void showFingers() {
        finger = null;
        Ha.Ent e = Ha.get(this).states.get("person." + person);
        String who = e != null ? e.name() : Tiles.cap(person);
        LinearLayout p = page("Add a fingerprint · " + who, "Which finger?");
        List<String> labels = new ArrayList<>();
        Goatprint gp = Goatprint.get(this);
        for (String f : FINGER_ORDER) {
            boolean have = false;
            for (Goatprint.Entry x : gp.entries()) if (x.name.equals(person) && x.finger.equals(f)) have = true;
            labels.add(Tiles.cap(f.replace('_', ' ')) + (have ? "  ✓" : ""));
        }
        grid(p, labels, 3, i -> { finger = FINGER_ORDER[i]; start(); });
        TextView note = Style.t(this, gp.hasAdmin() ? "An admin will touch the reader first to approve." : "This first finger becomes the admin finger.", 17, 400, Style.WHITE70);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, u(12), 0, 0);
        p.addView(note);
    }

    private void start() {
        running = true;
        h.removeCallbacks(idle);
        showRun();
        Goatprint.get(this).enroll(person, finger, (msg, got, done, failed) -> {
            if (message == null) return;
            message.setText(msg);
            progress.setText(dots(got));
            if (done) {
                running = false;
                if (failed) { sub.setText("Tap to try again"); sub.setOnClickListener(v -> start()); }
                else {
                    progress.setText("✓");
                    sub.setText("All set. Touch the reader any time to check in.");
                    h.postDelayed(this::finish, 3500);
                }
                poke();
            }
        });
    }

    private void showRun() {
        Ha.Ent e = Ha.get(this).states.get("person." + person);
        String who = e != null ? e.name() : Tiles.cap(person);
        LinearLayout p = page("Add a fingerprint · " + who, Tiles.cap(finger.replace('_', ' ')));
        progress = Style.t(this, dots(0), 44, 300, Style.AMBER);
        progress.setGravity(Gravity.CENTER);
        p.addView(progress);
        message = Style.t(this, Goatprint.get(this).status(), 28, 500, Style.WHITE);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, u(28), 0, u(12));
        p.addView(message);
        sub = Style.t(this, "Finger upright and flat, tip toward the top of the reader. Lift between touches.", 18, 400, Style.WHITE70);
        sub.setGravity(Gravity.CENTER);
        p.addView(sub);
    }

    private static String dots(int got) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Goatprint.SAMPLES; i++) s.append(i < got ? "●" : "○").append(i < Goatprint.SAMPLES - 1 ? "  " : "");
        return s.toString();
    }

    static void open(android.content.Context c, String person) {
        Intent i = new Intent(c, GoatprintEnrollActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (person != null) i.putExtra("person", person);
        c.startActivity(i);
    }
}
