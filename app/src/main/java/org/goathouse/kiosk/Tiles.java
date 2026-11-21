package org.goathouse.kiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** One view per HA entity. Small tiles toggle on tap; wide cards (climate, timer, lists, media) do more. */
final class Tiles {
    interface Host {
        Ha ha();
        void openBrightness(Ha.Ent e);
        boolean lite();
    }

    static boolean supports(String domain) {
        switch (domain) {
            case "light": case "switch": case "fan": case "input_boolean": case "scene": case "script": case "button":
            case "climate": case "sensor": case "binary_sensor": case "weather": case "person": case "todo":
            case "calendar": case "timer": case "media_player": case "lock": case "cover":
                return true;
            default:
                return false;
        }
    }

    /** Kinds that live in the header rather than the grid. */
    static boolean header(String domain) { return "weather".equals(domain) || "person".equals(domain) || "calendar".equals(domain); }

    static boolean wide(String domain) {
        return "climate".equals(domain) || "timer".equals(domain) || "todo".equals(domain) || "media_player".equals(domain);
    }

    static Tile make(Context c, Host host, String id) {
        String d = id.substring(0, id.indexOf('.'));
        switch (d) {
            case "climate": return new ClimateCard(c, host, id);
            case "timer": return new TimerCard(c, host, id);
            case "todo": return new TodoCard(c, host, id);
            case "media_player": return new MediaCard(c, host, id);
            default: return new Small(c, host, id);
        }
    }

    // ---------- base ----------

    abstract static class Tile extends FrameLayout {
        final String id;
        final Host host;
        Tile(Context c, Host host, String id) {
            super(c);
            this.id = id;
            this.host = host;
            setBackground(Ui.round(Ui.GLASS, Ui.dp(c, 20)));
        }
        abstract void bind(Ha.Ent e);
        void tick() { }
        int dp(float v) { return Ui.dp(getContext(), v); }
        void setLit(boolean lit) {
            ((GradientDrawable) getBackground()).setColor(lit ? Ui.LIT : Ui.GLASS);
        }
    }

    // ---------- small toggle / sensor tile ----------

    static final class Small extends Tile {
        private final Icon icon;
        private final TextView name, state;
        private boolean lit;

        Small(Context c, Host host, String id) {
            super(c, host, id);
            setTag(null);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(16), dp(14), dp(16), dp(14));
            icon = new Icon(c, id.substring(0, id.indexOf('.')));
            col.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            name = Ui.text(c, "", 16, Ui.TEXT, true);
            name.setMaxLines(2);
            name.setPadding(0, dp(12), 0, 0);
            col.addView(name);
            state = Ui.text(c, "", 13, Ui.TEXT3, false);
            state.setPadding(0, dp(4), 0, 0);
            col.addView(state);
            addView(col);
            setMinimumHeight(dp(116));
            setOnClickListener(v -> tap());
            setOnLongClickListener(v -> longPress());
            Ui.pressable(this, 0, 0);
        }

        private String domain() { return id.substring(0, id.indexOf('.')); }

        @Override void bind(Ha.Ent e) {
            if (e == null) { name.setText(id); state.setText("Not found"); setAlpha(0.5f); return; }
            setAlpha(e.unavailable() ? 0.5f : 1f);
            name.setText(e.name());
            String d = domain();
            String s;
            lit = false;
            switch (d) {
                case "light": {
                    lit = e.isOn();
                    int b = e.attrs.optInt("brightness", -1);
                    s = lit ? (b >= 0 ? "On · " + Math.round(b * 100 / 255f) + "%" : "On") : "Off";
                    break;
                }
                case "switch": case "fan": case "input_boolean":
                    lit = e.isOn(); s = lit ? "On" : "Off"; break;
                case "lock":
                    lit = "unlocked".equals(e.state); s = cap(e.state); break;
                case "cover":
                    lit = "open".equals(e.state); s = cap(e.state); break;
                case "scene": case "script": case "button":
                    s = "running".equals(e.state) || e.isOn() ? "Running" : "Tap to run"; break;
                case "binary_sensor":
                    s = binary(e); lit = e.isOn(); break;
                case "sensor": {
                    String unit = e.attrs.optString("unit_of_measurement", "");
                    String val = e.state;
                    if ("°C".equals(unit)) {              // always Fahrenheit
                        try { val = String.valueOf(Double.parseDouble(e.state) * 9 / 5 + 32); unit = "°F"; } catch (Exception ignored) { }
                    }
                    s = num(val) + (unit.isEmpty() ? "" : (unit.startsWith("°") || unit.equals("%") ? "" : " ") + unit);
                    break;
                }
                case "calendar":
                    s = e.attrs.optString("message", "Nothing coming up"); break;
                default:
                    s = cap(e.state);
            }
            if (e.unavailable()) s = "Unavailable";
            state.setText(s);
            setLit(lit);
            int ink = lit ? Ui.INK : Ui.TEXT;
            name.setTextColor(ink);
            state.setTextColor(lit ? 0xB30B0D12 : Ui.TEXT3);
            icon.set(lit, ink);
            if ("sensor".equals(d)) { state.setTextSize(22); state.setTextColor(Ui.TEXT); }
        }

        private void tap() {
            Ha.Ent e = host.ha().states.get(id);
            if (e == null) return;
            String d = domain();
            switch (d) {
                case "light": case "switch": case "fan": case "input_boolean": case "cover":
                    // optimistic flip so the tile answers instantly; the state stream corrects it
                    lit = !lit; setLit(lit);
                    host.ha().callService("cover".equals(d) ? "cover" : "homeassistant", "toggle", id, null);
                    break;
                case "scene": host.ha().callService("scene", "turn_on", id, null); flash(); break;
                case "script": host.ha().callService("script", "turn_on", id, null); flash(); break;
                case "button": host.ha().callService("button", "press", id, null); flash(); break;
                case "lock":
                    if ("locked".equals(e.state)) Toast.makeText(getContext(), "Hold to unlock", Toast.LENGTH_SHORT).show();
                    else host.ha().callService("lock", "lock", id, null);
                    break;
                default: break;
            }
        }

        private boolean longPress() {
            Ha.Ent e = host.ha().states.get(id);
            if (e == null) return false;
            if ("light".equals(domain()) && e.attrs.has("brightness") || "light".equals(domain()) && supportsDim(e)) { host.openBrightness(e); return true; }
            if ("lock".equals(domain()) && "locked".equals(e.state)) { host.ha().callService("lock", "unlock", id, null); return true; }
            return false;
        }

        private static boolean supportsDim(Ha.Ent e) {
            JSONArray modes = e.attrs.optJSONArray("supported_color_modes");
            if (modes == null) return false;
            for (int i = 0; i < modes.length(); i++) if (!"onoff".equals(modes.optString(i))) return true;
            return false;
        }

        private void flash() {
            setLit(true);
            new Handler(Looper.getMainLooper()).postDelayed(() -> setLit(false), 600);
        }

        private static String binary(Ha.Ent e) {
            String dc = e.attrs.optString("device_class", "");
            boolean on = e.isOn();
            switch (dc) {
                case "door": case "window": case "garage_door": case "opening": return on ? "Open" : "Closed";
                case "motion": case "occupancy": case "presence": return on ? "Detected" : "Clear";
                case "moisture": return on ? "Wet" : "Dry";
                default: return on ? "On" : "Off";
            }
        }
    }

    static String cap(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' '); }

    /** Home Assistant reports temperatures in °C (set from get_config); the kiosk always shows °F. */
    static volatile boolean haCelsius;

    /** A temperature from HA, shown in °F. */
    static String deg(String s) {
        if (!haCelsius) return num(s);
        try { return num(String.valueOf(Double.parseDouble(s) * 9 / 5 + 32)); } catch (Exception e) { return num(s); }
    }

    static String num(String s) {
        try {
            double v = Double.parseDouble(s);
            return Math.abs(v) >= 100 || v == Math.rint(v) ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v);
        } catch (Exception e) {
            return cap(s);
        }
    }

    // ---------- climate ----------

    static final class ClimateCard extends Tile {
        private final TextView title, current, target, mode;
        private double pendingTarget = Double.NaN;
        private final Handler h = new Handler(Looper.getMainLooper());
        private final Runnable send = this::sendTarget;

        ClimateCard(Context c, Host host, String id) {
            super(c, host, id);
            setTag(TileGrid.WIDE);
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(16), dp(16), dp(16));
            LinearLayout left = new LinearLayout(c);
            left.setOrientation(LinearLayout.VERTICAL);
            title = Ui.text(c, "", 16, Ui.TEXT, true);
            current = Ui.light(c, "", 40, Ui.TEXT);
            current.setPadding(0, dp(6), 0, dp(2));
            mode = Ui.text(c, "", 13, Ui.TEXT3, false);
            left.addView(title); left.addView(current); left.addView(mode);
            row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Ui.pill(c, "−", v -> nudge(-1)));
            target = Ui.text(c, "", 26, Ui.TEXT, true);
            target.setGravity(Gravity.CENTER);
            target.setMinWidth(dp(84));
            row.addView(target);
            row.addView(Ui.pill(c, "+", v -> nudge(1)));
            addView(row);
        }

        @Override void bind(Ha.Ent e) {
            if (e == null) { title.setText(id); return; }
            title.setText(e.name());
            current.setText(num(e.attrs.optString("current_temperature", "–")) + "°");
            String action = e.attrs.optString("hvac_action", "");
            mode.setText(cap(e.state) + (action.isEmpty() || "idle".equals(action) ? "" : " · " + cap(action)));
            if (Double.isNaN(pendingTarget)) {
                String t = e.attrs.optString("temperature", "");
                target.setText(t.isEmpty() || "null".equals(t) ? "–" : num(t) + "°");
            }
        }

        private void nudge(int dir) {
            Ha.Ent e = host.ha().states.get(id);
            if (e == null) return;
            double step = e.attrs.optDouble("target_temp_step", 1);
            double base = Double.isNaN(pendingTarget) ? e.attrs.optDouble("temperature", Double.NaN) : pendingTarget;
            if (Double.isNaN(base)) return;
            pendingTarget = base + dir * step;
            target.setText(num(String.valueOf(pendingTarget)) + "°");
            h.removeCallbacks(send);
            h.postDelayed(send, 900);    // let taps add up, then send once
        }

        private void sendTarget() {
            try {
                host.ha().callService("climate", "set_temperature", id, new JSONObject().put("temperature", pendingTarget));
            } catch (Exception ignored) { }
            h.postDelayed(() -> pendingTarget = Double.NaN, 3000);
        }
    }

    // ---------- timer ----------

    static final class TimerCard extends Tile {
        private final TextView title, big, sub;
        private final LinearLayout buttons;
        private long finishesAt;
        private String state = "idle";
        private String remaining = "";

        TimerCard(Context c, Host host, String id) {
            super(c, host, id);
            setTag(TileGrid.WIDE);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(20), dp(16), dp(16), dp(16));
            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout left = new LinearLayout(c);
            left.setOrientation(LinearLayout.VERTICAL);
            title = Ui.text(c, "", 16, Ui.TEXT, true);
            sub = Ui.text(c, "", 13, Ui.TEXT3, false);
            sub.setPadding(0, dp(4), 0, 0);
            left.addView(title); left.addView(sub);
            top.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            big = Ui.light(c, "", 44, Ui.TEXT);
            top.addView(big);
            col.addView(top);
            buttons = new LinearLayout(c);
            buttons.setPadding(0, dp(12), 0, 0);
            col.addView(buttons);
            addView(col);
        }

        private void rebuildButtons() {
            buttons.removeAllViews();
            if ("idle".equals(state)) {
                for (int m : new int[]{1, 5, 10, 15, 30}) {
                    TextView b = Ui.pill(getContext(), m + " min", v -> start(m));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                    lp.rightMargin = dp(8);
                    buttons.addView(b, lp);
                }
            } else {
                boolean active = "active".equals(state);
                addBtn(active ? "Pause" : "Resume", v -> host.ha().callService("timer", active ? "pause" : "start", id, null));
                addBtn("+1 min", v -> {
                    try { host.ha().callService("timer", "change", id, new JSONObject().put("duration", "00:01:00")); } catch (Exception ignored) { }
                });
                addBtn("Cancel", v -> host.ha().callService("timer", "cancel", id, null));
            }
        }

        private void addBtn(String label, View.OnClickListener l) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = dp(8);
            buttons.addView(Ui.pill(getContext(), label, l), lp);
        }

        private void start(int minutes) {
            try {
                host.ha().callService("timer", "start", id, new JSONObject().put("duration", String.format(Locale.US, "00:%02d:00", minutes)));
            } catch (Exception ignored) { }
        }

        @Override void bind(Ha.Ent e) {
            if (e == null) { title.setText(id); return; }
            title.setText(e.name());
            boolean changed = !e.state.equals(state);
            state = e.state;
            Date f = Ui.parseIso(e.attrs.optString("finishes_at", ""));
            finishesAt = f == null ? 0 : f.getTime();
            remaining = e.attrs.optString("remaining", e.attrs.optString("duration", "0:00:00"));
            sub.setText("active".equals(state) ? "Running" : "paused".equals(state) ? "Paused" : "Start a timer");
            setLit("active".equals(state));
            int ink = "active".equals(state) ? Ui.INK : Ui.TEXT;
            title.setTextColor(ink); big.setTextColor(ink); sub.setTextColor("active".equals(state) ? 0xB30B0D12 : Ui.TEXT3);
            if (changed || buttons.getChildCount() == 0) rebuildButtons();
            tick();
        }

        @Override void tick() {
            if ("active".equals(state) && finishesAt > 0) big.setText(Ui.duration(finishesAt - System.currentTimeMillis() + 999));
            else if ("paused".equals(state)) big.setText(Ui.duration(Ui.parseHms(remaining)));
            else big.setText("");
        }
    }

    // ---------- to-do list ----------

    static final class TodoCard extends Tile {
        private final TextView title, empty;
        private final LinearLayout items;
        private String lastState;

        TodoCard(Context c, Host host, String id) {
            super(c, host, id);
            setTag(TileGrid.WIDE);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(20), dp(16), dp(16), dp(12));
            title = Ui.text(c, "", 16, Ui.TEXT, true);
            col.addView(title);
            items = new LinearLayout(c);
            items.setOrientation(LinearLayout.VERTICAL);
            items.setPadding(0, dp(6), 0, 0);
            col.addView(items);
            empty = Ui.text(c, "All done", 14, Ui.TEXT3, false);
            empty.setPadding(0, dp(10), 0, dp(4));
            col.addView(empty);
            addView(col);
        }

        @Override void bind(Ha.Ent e) {
            if (e == null) { title.setText(id); return; }
            title.setText(e.name() + (e.state.matches("\\d+") && !"0".equals(e.state) ? "  ·  " + e.state : ""));
            if (!e.state.equals(lastState)) { lastState = e.state; load(); }
        }

        private void load() {
            try {
                host.ha().callService("todo", "get_items", id, new JSONObject().put("status", new JSONArray().put("needs_action")), true, (res, err) -> {
                    if (res == null) return;
                    JSONObject resp = res.optJSONObject("response");
                    JSONObject mine = resp == null ? null : resp.optJSONObject(id);
                    JSONArray list = mine == null ? null : mine.optJSONArray("items");
                    show(list == null ? new JSONArray() : list);
                });
            } catch (Exception ignored) { }
        }

        private void show(JSONArray list) {
            items.removeAllViews();
            int n = Math.min(list.length(), host.lite() ? 6 : 10);
            for (int i = 0; i < n; i++) {
                JSONObject it = list.optJSONObject(i);
                String summary = it.optString("summary"), uid = it.optString("uid", summary);
                LinearLayout row = new LinearLayout(getContext());
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(44));
                View box = new View(getContext());
                GradientDrawable g = Ui.round(0, dp(11));
                g.setStroke(dp(2), Ui.TEXT2);
                box.setBackground(g);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(22), dp(22));
                blp.rightMargin = dp(14);
                row.addView(box, blp);
                row.addView(Ui.text(getContext(), summary, 16, Ui.TEXT, false));
                row.setOnClickListener(v -> {
                    g.setColor(Ui.TEXT);
                    row.animate().alpha(0.3f).setDuration(220).start();
                    try {
                        host.ha().callService("todo", "update_item", id, new JSONObject().put("item", uid).put("status", "completed"));
                    } catch (Exception ignored) { }
                });
                items.addView(row);
            }
            if (list.length() > n) items.addView(Ui.text(getContext(), "+" + (list.length() - n) + " more", 13, Ui.TEXT3, false));
            empty.setVisibility(list.length() == 0 ? VISIBLE : GONE);
        }
    }

    // ---------- media ----------

    static final class MediaCard extends Tile {
        private final TextView title, line1, line2;
        private final TextView play;

        MediaCard(Context c, Host host, String id) {
            super(c, host, id);
            setTag(TileGrid.WIDE);
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(16), dp(16), dp(16));
            LinearLayout left = new LinearLayout(c);
            left.setOrientation(LinearLayout.VERTICAL);
            title = Ui.text(c, "", 13, Ui.TEXT3, false);
            line1 = Ui.text(c, "", 18, Ui.TEXT, true);
            line1.setSingleLine(true);
            line1.setPadding(0, dp(6), 0, dp(3));
            line2 = Ui.text(c, "", 14, Ui.TEXT2, false);
            line2.setSingleLine(true);
            left.addView(title); left.addView(line1); left.addView(line2);
            row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Ui.pill(c, "\u25C0\uFE0E\u25C0\uFE0E", v -> host.ha().callService("media_player", "media_previous_track", id, null)));
            play = Ui.pill(c, "\u25B6\uFE0E", v -> host.ha().callService("media_player", "media_play_pause", id, null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8); lp.rightMargin = dp(8);
            row.addView(play, lp);
            row.addView(Ui.pill(c, "\u25B6\uFE0E\u25B6\uFE0E", v -> host.ha().callService("media_player", "media_next_track", id, null)));
            addView(row);
        }

        @Override void bind(Ha.Ent e) {
            if (e == null) { title.setText(id); return; }
            title.setText(e.name());
            String t = e.attrs.optString("media_title", "");
            String a = e.attrs.optString("media_artist", e.attrs.optString("media_album_name", ""));
            line1.setText(t.isEmpty() ? cap(e.state) : t);
            line2.setText(a);
            play.setText("playing".equals(e.state) ? "\u275A\u275A" : "\u25B6\uFE0E");
        }
    }

    // ---------- calendar (fetched separately; header card) ----------

    static List<String[]> mergeEvents(JSONObject response) {
        List<String[]> out = new ArrayList<>();   // {startIso, summary}
        if (response == null) return out;
        for (Iterator<String> it = response.keys(); it.hasNext(); ) {
            JSONObject cal = response.optJSONObject(it.next());
            JSONArray ev = cal == null ? null : cal.optJSONArray("events");
            if (ev == null) continue;
            for (int i = 0; i < ev.length(); i++) {
                JSONObject o = ev.optJSONObject(i);
                out.add(new String[]{o.optString("start"), o.optString("summary")});
            }
        }
        Collections.sort(out, (x, y) -> {
            Date a = Ui.parseIso(x[0]), b = Ui.parseIso(y[0]);
            return a == null || b == null ? 0 : a.compareTo(b);
        });
        return out;
    }

    // ---------- icons, drawn so they look the same on Android 5 and 14 ----------

    static final class Icon extends View {
        private final String kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;
        private int ink = Ui.TEXT;

        Icon(Context c, String kind) { super(c); this.kind = kind; }

        void set(boolean on, int ink) { this.on = on; this.ink = ink; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? 0x1A0B0D12 : 0x1FFFFFFF);
            c.drawCircle(cx, cy, r, p);
            p.setColor(ink);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float s = r * 0.5f;
            switch (kind) {
                case "light": {
                    if (on) p.setStyle(Paint.Style.FILL_AND_STROKE);
                    c.drawCircle(cx, cy - s * 0.25f, s * 0.62f, p);
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(cx - s * 0.32f, cy + s * 0.65f, cx + s * 0.32f, cy + s * 0.65f, p);
                    c.drawLine(cx - s * 0.22f, cy + s * 0.92f, cx + s * 0.22f, cy + s * 0.92f, p);
                    break;
                }
                case "scene": case "script": case "button": {
                    Path tri = new Path();
                    tri.moveTo(cx - s * 0.45f, cy - s * 0.7f); tri.lineTo(cx + s * 0.75f, cy); tri.lineTo(cx - s * 0.45f, cy + s * 0.7f); tri.close();
                    p.setStyle(Paint.Style.FILL_AND_STROKE);
                    c.drawPath(tri, p);
                    break;
                }
                case "sensor": case "binary_sensor": case "calendar": {
                    c.drawCircle(cx, cy, s * 0.7f, p);
                    c.drawLine(cx, cy, cx, cy - s * 0.45f, p);
                    c.drawLine(cx, cy, cx + s * 0.35f, cy + s * 0.2f, p);
                    break;
                }
                case "lock": {
                    c.drawRoundRect(new RectF(cx - s * 0.65f, cy - s * 0.1f, cx + s * 0.65f, cy + s * 0.8f), s * 0.15f, s * 0.15f, p);
                    c.drawArc(new RectF(cx - s * 0.4f, cy - s * 0.85f, cx + s * 0.4f, cy + s * 0.05f), 180, 180, false, p);
                    break;
                }
                default: {   // power symbol: switch, fan, input_boolean, cover
                    c.drawArc(new RectF(cx - s * 0.75f, cy - s * 0.65f, cx + s * 0.75f, cy + s * 0.85f), -60, 300, false, p);
                    c.drawLine(cx, cy - s * 0.85f, cx, cy + s * 0.05f, p);
                }
            }
        }
    }
}
