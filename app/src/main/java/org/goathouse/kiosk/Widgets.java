package org.goathouse.kiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small custom views drawn in code (fast on old tablets, identical on Android 5 and 12). */
final class Widgets {

    /** Progress ring: track + amber arc, starting at 12 o'clock. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        Ring(Context c, float strokeDp, int trackColor, int arcColor) {
            super(c);
            float w = Style.dp(c, strokeDp);
            track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(w); track.setColor(trackColor);
            arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(w); arc.setColor(arcColor); arc.setStrokeCap(Paint.Cap.ROUND);
        }
        void set(float p) { progress = Math.max(0, Math.min(1, p)); invalidate(); }
        void colors(int t, int a) { track.setColor(t); arc.setColor(a); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float s = track.getStrokeWidth() / 2 + 1;
            RectF r = new RectF(s, s, getWidth() - s, getHeight() - s);
            c.drawArc(r, 0, 360, false, track);
            c.drawArc(r, -90, 360 * (1 - progress), false, arc);   // remaining time shrinks clockwise
        }
    }

    interface IntListener { void on(int value); }

    /** iOS-style number wheel: drag/fling, snaps to a value; centre row highlighted, edges fade. */
    static final class Wheel extends View {
        private final int max, step;
        private final String unit;
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG), unitP = new Paint(Paint.ANTI_ALIAS_FLAG), band = new Paint();
        private float offset;       // in rows; value = round(offset)
        private float lastY, velocity;
        private long lastT;
        private IntListener listener;
        private final android.widget.OverScroller scroller;

        Wheel(Context c, int max, int step, String unit) {
            super(c);
            this.max = max; this.step = step; this.unit = unit;
            num.setTypeface(Style.font(c, 400)); num.setColor(Style.WHITE); num.setTextAlign(Paint.Align.RIGHT);
            unitP.setTypeface(Style.font(c, 500)); unitP.setColor(Style.WHITE80);
            band.setColor(0x24FFFFFF);
            scroller = new android.widget.OverScroller(c);
        }
        void onValue(IntListener l) { listener = l; }
        private String[] labels;
        /** Custom text per position (e.g. 12, 1 … 11 for a 12-hour clock, or AM / PM). */
        void labels(String... l) { labels = l; invalidate(); }
        int value() { return Math.max(0, Math.min(max, Math.round(offset))) * step; }
        void setValue(int v) { offset = v / (float) step; scroller.forceFinished(true); invalidate(); notifyValue(); }

        private float rowH() { return getHeight() / 3.2f; }

        @Override protected void onDraw(Canvas c) {
            if (scroller.computeScrollOffset()) { offset = scroller.getCurrY() / 1000f; postInvalidateOnAnimation(); if (scroller.isFinished()) snap(); }
            float rh = rowH(), cy = getHeight() / 2f, cx = getWidth() * 0.52f;
            c.drawRoundRect(new RectF(Style.dp(getContext(), 6), cy - rh / 2, getWidth() - Style.dp(getContext(), 6), cy + rh / 2), rh / 3, rh / 3, band);
            int first = (int) Math.floor(offset) - 2, last = (int) Math.ceil(offset) + 2;
            for (int i = first; i <= last; i++) {
                if (i < 0 || i > max / step) continue;
                float y = cy + (i - offset) * rh;
                float d = Math.abs(i - offset);
                float size = rh * (d < 0.5f ? 0.62f : 0.48f);
                num.setTextSize(size);
                num.setAlpha((int) (255 * Math.max(0.12f, 1 - d * 0.45f)));
                c.drawText(labels != null && i < labels.length ? labels[i] : String.format(Locale.US, step >= 5 || max >= 10 ? "%02d" : "%d", i * step), cx, y + size * 0.36f, num);
            }
            unitP.setTextSize(rh * 0.36f);
            c.drawText(unit, cx + Style.dp(getContext(), 10), cy + rh * 0.13f, unitP);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.forceFinished(true); lastY = e.getY(); lastT = SystemClock.uptimeMillis(); velocity = 0; return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getY() - lastY; long now = SystemClock.uptimeMillis();
                    offset = clamp(offset - dy / rowH());
                    velocity = -dy / rowH() / Math.max(1, now - lastT) * 1000f;
                    lastY = e.getY(); lastT = now; invalidate(); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    if (Math.abs(velocity) > 2) {
                        scroller.fling(0, (int) (offset * 1000), 0, (int) (velocity * 1000), 0, 0, 0, (max / step) * 1000);
                        postInvalidateOnAnimation();
                    } else snap();
                    return true;
                default: return true;
            }
        }
        private float clamp(float v) { return Math.max(0, Math.min(max / step, v)); }
        private void snap() { offset = Math.round(clamp(offset)); invalidate(); notifyValue(); }
        private void notifyValue() { if (listener != null) listener.on(value()); }
    }

    /** Horizontal slider (brightness / warmth). */
    static final class Slider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int value, fillColor;
        private int[] gradient;
        private final IntListener moved, done;
        Slider(Context c, int value, int fillColor, int[] gradient, IntListener moved, IntListener done) {
            super(c); this.value = value; this.fillColor = fillColor; this.gradient = gradient; this.moved = moved; this.done = done;
        }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2, w = getWidth();
            if (gradient != null) {
                p.setShader(new LinearGradient(0, 0, w, 0, gradient, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
                p.setShader(null);
                p.setColor(Style.CREAM);
                float x = r + (w - 2 * r) * value / 100f;
                c.drawCircle(x, r, r - Style.dp(getContext(), 4), p);
            } else {
                p.setColor(0x26FFFFFF);
                c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
                p.setColor(fillColor);
                if (value > 0) c.drawRoundRect(new RectF(0, 0, Math.max(h, w * value / 100f), h), r, r, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            value = Math.max(0, Math.min(100, Math.round(e.getX() * 100f / getWidth())));
            invalidate();
            if (moved != null) moved.on(value);
            if (e.getActionMasked() == MotionEvent.ACTION_UP && done != null) done.on(value);
            return true;
        }

        /** Move the knob from code (no listeners fire). */
        void set(int v) { value = Math.max(0, Math.min(100, v)); invalidate(); }
    }

    /**
     * Planes overhead (flightradar24 sensor) drifting across the top quarter of the screen; the flight text itself
     * trails behind each plane like smoke, fading away from it. Direction follows the real heading (east-bound left to right).
     */
    static final class Planes extends View {
        private static final class P { String label; boolean east; float y; long start; long dur; }
        private final Map<String, P> planes = new HashMap<>();
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG), trail = new Paint(Paint.ANTI_ALIAS_FLAG), body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path plane = new Path();
        private boolean running;

        Planes(Context c) {
            super(c);
            txt.setTypeface(Style.font(c, 500)); txt.setColor(0xA6FFFFFF); txt.setTextSize(Style.dp(c, 13));
            if (android.os.Build.VERSION.SDK_INT >= 21) txt.setLetterSpacing(0.1f);
            body.setColor(Style.CREAM);
            float s = Style.dp(c, 1.25f);   // plane silhouette pointing right, ~24dp long
            plane.moveTo(12 * s, 0); plane.lineTo(4 * s, -1.6f * s); plane.lineTo(-1 * s, -9 * s); plane.lineTo(-4 * s, -9 * s);
            plane.lineTo(-1 * s, -1.6f * s); plane.lineTo(-8 * s, -1.4f * s); plane.lineTo(-10 * s, -4.5f * s); plane.lineTo(-12 * s, -4.5f * s);
            plane.lineTo(-10.5f * s, 0); plane.lineTo(-12 * s, 4.5f * s); plane.lineTo(-10 * s, 4.5f * s); plane.lineTo(-8 * s, 1.4f * s);
            plane.lineTo(-1 * s, 1.6f * s); plane.lineTo(-4 * s, 9 * s); plane.lineTo(-1 * s, 9 * s); plane.lineTo(4 * s, 1.6f * s); plane.close();
        }

        /** Feed the sensor's `flights` attribute. */
        void setFlights(JSONArray flights) {
            List<String> seen = new ArrayList<>();
            int lane = planes.size();
            for (int i = 0; flights != null && i < Math.min(flights.length(), 3); i++) {
                JSONObject f = flights.optJSONObject(i);
                if (f == null) continue;
                String id = f.optString("id", f.optString("callsign"));
                seen.add(id);
                if (planes.containsKey(id)) continue;
                P p = new P();
                String cs = clean(f.optString("callsign"));
                if (cs.isEmpty()) cs = clean(f.optString("flight_number"));
                String from = clean(f.optString("airport_origin_code_iata")), to = clean(f.optString("airport_destination_code_iata"));
                int alt = f.optInt("altitude", 0);
                StringBuilder lb = new StringBuilder(cs);
                if (!from.isEmpty() && !to.isEmpty()) lb.append(lb.length() > 0 ? " · " : "").append(from).append(" → ").append(to);
                if (alt > 0) lb.append(lb.length() > 0 ? " · " : "").append(String.format(Locale.US, "%,d FT", alt));
                p.label = lb.toString().toUpperCase(Locale.US);
                int hd = f.optInt("heading", 90);
                p.east = hd < 180;
                p.y = 0.18f + 0.62f * ((lane++ % 3) / 2f);
                p.start = SystemClock.uptimeMillis() - (long) (Math.random() * 20000);
                p.dur = 38000 + (long) (Math.random() * 14000);
                planes.put(id, p);
            }
            planes.keySet().retainAll(seen);
            if (!planes.isEmpty() && !running) { running = true; postInvalidateOnAnimation(); }
        }

        private static String clean(String s) { return s == null || "null".equalsIgnoreCase(s.trim()) ? "" : s.trim(); }

        @Override protected void onDraw(Canvas c) {
            if (planes.isEmpty()) { running = false; return; }
            long now = SystemClock.uptimeMillis();
            float w = getWidth(), span = w + Style.dp(getContext(), 700);
            for (P p : planes.values()) {
                float t = ((now - p.start) % p.dur) / (float) p.dur;
                float y = getHeight() * p.y;
                float gap = Style.dp(getContext(), 30);
                float labelW = txt.measureText(p.label);
                if (p.east) {
                    float x = -Style.dp(getContext(), 60) + t * span - Style.dp(getContext(), 300);
                    // the flight's own text is the smoke trail: right behind the tail, fading out away from the plane
                    txt.setShader(new LinearGradient(x - gap - labelW, y, x - gap, y, 0x14FFFFFF, 0xD9FFFFFF, Shader.TileMode.CLAMP));
                    c.drawText(p.label, x - gap - labelW, y + txt.getTextSize() * 0.35f, txt);
                    txt.setShader(null);
                    c.save(); c.translate(x, y); c.drawPath(plane, body); c.restore();
                } else {
                    float x = w + Style.dp(getContext(), 60) - t * span + Style.dp(getContext(), 300);
                    txt.setShader(new LinearGradient(x + gap, y, x + gap + labelW, y, 0xD9FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
                    c.drawText(p.label, x + gap, y + txt.getTextSize() * 0.35f, txt);
                    txt.setShader(null);
                    c.save(); c.translate(x, y); c.scale(-1, 1); c.drawPath(plane, body); c.restore();
                }
            }
            postInvalidateOnAnimation();
        }
    }
}
