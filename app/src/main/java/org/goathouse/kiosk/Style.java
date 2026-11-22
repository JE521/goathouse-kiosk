package org.goathouse.kiosk;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * The kiosk's design language (2026-11-21): ambient glass over a photo, Outfit everywhere,
 * amber (or a light's own colour) for anything that is on, see-through amber for toggles.
 */
final class Style {
    static final int AMBER = 0xFFF2A93B, CREAM = 0xFFF4EEE2, INK = 0xFF1A1F2B, INDIGO_LT = 0xFF8EA0FF;
    static final int WHITE = 0xFFFFFFFF, WHITE80 = 0xCCFFFFFF, WHITE70 = 0xB3FFFFFF, WHITE60 = 0x99FFFFFF;
    static final int GLASS = 0x8C141416, CHIP = 0x1FFFFFFF, AWAY = 0xFF2A2F3A, LINE = 0x1AFFFFFF;
    static final int AMBER_SOFT = 0x38F2A93B, AMBER_LINE = 0x99F2A93B;
    static final int NIGHT_TEXT = 0xFF7A746A, NIGHT_DIM = 0xFF5E5A53, NIGHT_AMBER = 0xFF8A6A3A;

    /** Screens are laid out in mockup pixels (1280×800 landscape); k scales them to the real screen. */
    static float k = 1f;

    /**
     * One brightness rule for every kiosk screen: full by day, 45% at night once someone is using it, 2% for the black
     * night clock. (Before, only the idle screen set it, so controls/radar at night used Android's ~100% setting.)
     */
    static void brightness(android.app.Activity a, boolean nightClock) {
        Prefs p = Prefs.of(a);
        Tiles.showCelsius = "C".equals(p.get(Ui.TEMP_UNIT, "F"));
        boolean night = false;
        if (p.on(Prefs.NIGHT_DIM, true)) {
            int from = p.num(Prefs.NIGHT_FROM, 22), to = p.num(Prefs.NIGHT_TO, 7);
            int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            night = from > to ? (hr >= from || hr < to) : (hr >= from && hr < to);
        }
        float want = night ? (nightClock ? 0.02f : 0.45f) : 1f;
        android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        if (lp.screenBrightness != want) { lp.screenBrightness = want; a.getWindow().setAttributes(lp); }
    }

    static void measure(android.app.Activity a) {
        android.util.DisplayMetrics m = a.getResources().getDisplayMetrics();
        boolean land = m.widthPixels >= m.heightPixels;
        k = land ? Math.min(m.widthPixels / 1280f, m.heightPixels / 800f) : Math.min(m.widthPixels / 800f, m.heightPixels / 1280f);
    }

    static int u(float v) { return Math.round(v * k); }

    /** Text sized in mockup pixels. */
    static TextView t(Context c, String s, float px, int weight, int color) {
        TextView v = text(c, s, 10, weight, color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px * k);
        return v;
    }

    static TextView tcaps(Context c, String s, float px, int color) {
        TextView v = t(c, s.toUpperCase(java.util.Locale.getDefault()), px, 600, color);
        if (Build.VERSION.SDK_INT >= 21) v.setLetterSpacing(0.22f);
        return v;
    }

    static GradientDrawable glassU(float radius) {
        GradientDrawable g = fill(GLASS, radius * k);
        g.setStroke(1, 0x24FFFFFF);
        return g;
    }

    static GradientDrawable toggleU(float radius, boolean on) {
        if (!on) return glassU(radius);
        GradientDrawable g = fill(AMBER_SOFT, radius * k);
        g.setStroke(Math.max(1, u(1.5f)), AMBER_LINE);
        return g;
    }

    private static final SparseArray<Typeface> FACES = new SparseArray<>();
    private static Typeface base;

    /** Outfit at a weight (100–900). Variable-font weights need Android 8+; older tablets get the regular cut. */
    static Typeface font(Context c, int weight) {
        Typeface t = FACES.get(weight);
        if (t != null) return t;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                t = new Typeface.Builder(c.getAssets(), "fonts/Outfit.ttf").setFontVariationSettings("'wght' " + weight).build();
            } else {
                // Before Android 8 the variable font renders at its default (Thin 100): use static instances
                // (fonts/Outfit-<w>.ttf, cut from the variable font with fontTools' instancer), nearest weight.
                int[] have = {200, 300, 400, 500, 600, 700};
                int best = 400;
                for (int w : have) if (Math.abs(w - weight) < Math.abs(best - weight)) best = w;
                t = Typeface.createFromAsset(c.getAssets(), "fonts/Outfit-" + best + ".ttf");
            }
        } catch (Exception e) {
            t = Typeface.create(weight >= 600 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
        }
        FACES.put(weight, t);
        return t;
    }

    static TextView text(Context c, String s, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(c, weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Spaced-out capitals, the TV launcher's label style ("GOOD EVENING"). */
    static TextView caps(Context c, String s, float sp, int color) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.getDefault()), sp, 600, color);
        if (Build.VERSION.SDK_INT >= 21) t.setLetterSpacing(0.22f);
        return t;
    }

    static void shade(TextView t) { t.setShadowLayer(14f, 0, 1.5f, 0x8C000000); }

    static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(GLASS);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(1, 0x24FFFFFF);
        return g;
    }

    static GradientDrawable fill(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** See-through amber: the on-state of toggles (Last to bed, Kitchen, Back porch). Off = plain glass. */
    static GradientDrawable toggle(Context c, float radiusDp, boolean on) {
        if (!on) return glass(c, radiusDp);
        GradientDrawable g = fill(AMBER_SOFT, dp(c, radiusDp));
        g.setStroke(dp(c, 1), AMBER_LINE);
        return g;
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Press feedback: scale down a touch, no ripple. */
    static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
            else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            return false;
        });
    }

    static TextView pill(Context c, String label, int bg, int fg, View.OnClickListener l) {
        TextView t = text(c, label, 17, 600, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        t.setMinHeight(dp(c, 50));
        t.setBackground(fill(bg, dp(c, 16)));
        t.setOnClickListener(l);
        pressable(t);
        return t;
    }
}
