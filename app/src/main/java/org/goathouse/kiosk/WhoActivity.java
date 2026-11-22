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
import java.util.Locale;

/**
 * Hallway "Who are you?": tapping the idle screen without a fingerprint lands here. Pick a person → their personal
 * brief (where "Add a finger" enrols that person). "House view" goes to the shared controls. Back to idle after 30 s.
 */
public class WhoActivity extends Activity implements Ha.Listener {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable idle = this::finish;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onResume() {
        super.onResume();
        Style.brightness(this, false);
        Style.measure(this);
        build();
        Ha.get(this).addListener(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        poke();
    }

    @Override protected void onPause() { super.onPause(); h.removeCallbacks(idle); Ha.get(this).removeListener(this); }

    // people (names, photos, home/away) arrive from Home Assistant after the screen may already be up
    @Override public void onStatus(boolean connected, String message) { if (connected) build(); }
    @Override public void onChanged(java.util.Set<String> ids) {
        for (String id : ids) if (id.startsWith("person.")) { build(); return; }
    }
    @Override public void onUserInteraction() { super.onUserInteraction(); poke(); }
    private void poke() { h.removeCallbacks(idle); h.postDelayed(idle, 30_000); }
    private int u(float v) { return Style.u(v); }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        root.addView(Kit.backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        root.addView(Kit.scrim(this, 0xB3060910), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(u(40), u(28), u(40), u(28));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Kit.iconButton(this, "back", u(52), Style.GLASS, Style.WHITE, "Back", v -> finish()));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView house = Style.t(this, "House view", 16, 600, Style.WHITE);
        house.setGravity(Gravity.CENTER);
        house.setPadding(u(20), 0, u(20), 0);
        house.setBackground(Style.fill(Style.GLASS, u(26)));
        house.setOnClickListener(v -> { startActivity(new Intent(this, ControlsActivity.class)); finish(); });
        Style.pressable(house);
        top.addView(house, new LinearLayout.LayoutParams(-2, u(52)));
        page.addView(top, new LinearLayout.LayoutParams(-1, u(60)));

        TextView title = Style.t(this, "Who are you?", 44, 300, Style.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, u(30), 0, u(8));
        page.addView(title);
        TextView sub = Style.t(this, "Or touch the fingerprint reader", 17, 400, Style.WHITE70);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, u(36));
        page.addView(sub);

        Ha ha = Ha.get(this);
        List<String> ids = new ArrayList<>(Dash.of(this).people());
        if (ids.isEmpty()) for (Ha.Ent e : ha.states.values()) if (e.id.startsWith("person.")) ids.add(e.id);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (String id : ids) {
            Ha.Ent e = ha.states.get(id);
            String name = e != null ? e.name() : Tiles.cap(id.substring(id.indexOf('.') + 1));
            String first = name.contains(" ") ? name.substring(0, name.indexOf(' ')) : name;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setBackground(Style.glassU(26));
            card.setPadding(u(16), u(22), u(16), u(22));
            card.addView(Avatars.view(this, e, u(88), Style.WHITE, Style.INK, 34), new LinearLayout.LayoutParams(u(88), u(88)));
            TextView nm = Style.t(this, first, 22, 500, Style.WHITE);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, u(14), 0, 0);
            card.addView(nm);
            boolean home = e != null && "home".equals(e.state);
            TextView st = Style.t(this, e == null ? "" : home ? "Home" : Tiles.cap(e.state.replace("not_home", "Away")), 14, 500, home ? Style.AMBER : Style.WHITE60);
            st.setGravity(Gravity.CENTER);
            card.addView(st);
            card.setOnClickListener(v -> {
                startActivity(new Intent(this, BriefActivity.class).putExtra("person", id));
                finish();
            });
            Style.pressable(card);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(u(200), -2);
            cp.leftMargin = cp.rightMargin = u(12);
            row.addView(card, cp);
        }
        page.addView(row, new LinearLayout.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }
}
