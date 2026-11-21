package org.goathouse.kiosk;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Shared screen pieces: line icons, the photo backdrop, the timer-done alert, time formatting. */
final class Kit {

    /** Stroke icons drawn on a 24-unit grid, so they look the same on every Android version. */
    static final class Icon extends View {
        private final String name;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        Icon(Context c, String name, int color) { super(c); this.name = name; this.color = color; }
        void color(int c) { color = c; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.translate((getWidth() - 24 * s) / 2, (getHeight() - 24 * s) / 2);
            c.scale(s, s);
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.1f);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            switch (name) {
                case "mic":
                    c.drawRoundRect(new RectF(9, 3, 15, 14), 3, 3, p);
                    path.moveTo(5, 11); path.cubicTo(5, 20, 19, 20, 19, 11); c.drawPath(path, p);
                    c.drawLine(12, 18, 12, 21, p); break;
                case "moon":
                    path.moveTo(20, 14.5f); path.cubicTo(17, 21, 6, 20.5f, 4, 13); path.cubicTo(3, 8, 6, 4.5f, 9.5f, 4);
                    path.cubicTo(7.5f, 9, 12, 16.5f, 20, 14.5f); c.drawPath(path, p); break;
                case "power":
                    c.drawLine(12, 3, 12, 11, p); c.drawArc(new RectF(4, 4.5f, 20, 20.5f), -50, 280, false, p); break;
                case "back":
                    path.moveTo(15, 5); path.lineTo(8, 12); path.lineTo(15, 19); c.drawPath(path, p); break;
                case "x":
                    c.drawLine(6, 6, 18, 18, p); c.drawLine(18, 6, 6, 18, p); break;
                case "pause":
                    p.setStyle(Paint.Style.FILL); c.drawRect(7, 5, 10.5f, 19, p); c.drawRect(13.5f, 5, 17, 19, p); break;
                case "play":
                    p.setStyle(Paint.Style.FILL); path.moveTo(8, 5); path.lineTo(19, 12); path.lineTo(8, 19); path.close(); c.drawPath(path, p); break;
                case "prev":
                    p.setStyle(Paint.Style.FILL); c.drawRect(6, 5, 8, 19, p); path.moveTo(19, 5); path.lineTo(19, 19); path.lineTo(9.5f, 12); path.close(); c.drawPath(path, p); break;
                case "next":
                    p.setStyle(Paint.Style.FILL); c.drawRect(16, 5, 18, 19, p); path.moveTo(5, 5); path.lineTo(5, 19); path.lineTo(14.5f, 12); path.close(); c.drawPath(path, p); break;
                case "plane":
                    p.setStyle(Paint.Style.FILL); c.rotate(45, 12, 12);
                    path.moveTo(21, 16); path.lineTo(21, 14); path.lineTo(13, 9); path.lineTo(13, 3.5f); path.cubicTo(13, 2, 10, 2, 10, 3.5f);
                    path.lineTo(10, 9); path.lineTo(2, 14); path.lineTo(2, 16); path.lineTo(10, 13.5f); path.lineTo(10, 19); path.lineTo(8, 20.5f);
                    path.lineTo(8, 22); path.lineTo(11.5f, 21); path.lineTo(15, 22); path.lineTo(15, 20.5f); path.lineTo(13, 19); path.lineTo(13, 13.5f); path.close();
                    c.drawPath(path, p); break;
                case "volume":
                    path.moveTo(4, 9); path.lineTo(4, 15); path.lineTo(8, 15); path.lineTo(13, 19); path.lineTo(13, 5); path.lineTo(8, 9); path.close(); c.drawPath(path, p);
                    c.drawArc(new RectF(12, 8.5f, 19, 15.5f), -50, 100, false, p); break;
                case "check":
                    path.moveTo(5, 12); path.lineTo(10, 17); path.lineTo(20, 7); c.drawPath(path, p); break;
                default:
                    c.drawCircle(12, 12, 8, p);
            }
            c.restore();
        }
    }

    static Icon icon(Context c, String name, int color) { return new Icon(c, name, color); }

    /** Round icon button: amber fill, or see-through amber, or glass. */
    static FrameLayout iconButton(Context c, String icon, int size, int bg, int fg, String label, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Style.fill(bg, size / 2f));
        f.setContentDescription(label);
        Icon i = icon(c, icon, fg);
        f.addView(i, new FrameLayout.LayoutParams(size * 9 / 20, size * 9 / 20, Gravity.CENTER));
        f.setOnClickListener(l);
        Style.pressable(f);
        f.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return f;
    }

    private static Bitmap cachedBackdrop;

    /** Full-bleed photo. Bundled landscape until an Immich album is linked (next version). */
    static ImageView backdrop(Activity a) {
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (cachedBackdrop == null) {
            try (InputStream in = a.getAssets().open("backdrop.jpg")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                cachedBackdrop = BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) { }
        }
        if (cachedBackdrop != null) iv.setImageBitmap(cachedBackdrop);
        return iv;
    }

    static View scrim(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        return v;
    }

    static String mmss(long ms) {
        long s = (ms + 999) / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Full-screen "timer done" sheet with one big Stop button. Returns the view so callers can remove it. */
    static View timerAlert(Activity a, List<Timers.T> ringing, Runnable stop) {
        FrameLayout f = new FrameLayout(a);
        f.setTag("timer-alert");
        f.setBackgroundColor(0xE6050810);
        f.setClickable(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView top = Style.tcaps(a, "Timer done", 22, Style.AMBER);
        col.addView(top);
        StringBuilder names = new StringBuilder();
        for (Timers.T t : ringing) { if (names.length() > 0) names.append(" · "); names.append(t.label); }
        TextView name = Style.t(a, names.toString(), 96, 300, Style.WHITE);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Style.u(16), 0, Style.u(40));
        col.addView(name);
        TextView btn = Style.t(a, "Stop", 32, 700, Style.INK);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(Style.fill(Style.AMBER, Style.u(44)));
        btn.setOnClickListener(v -> stop.run());
        col.addView(btn, new LinearLayout.LayoutParams(Style.u(340), Style.u(88)));
        f.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return f;
    }
}
