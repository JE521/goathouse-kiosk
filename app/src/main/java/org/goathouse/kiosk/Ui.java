package org.goathouse.kiosk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Design tokens from the TV launcher (see goathouse-kiosk-app design.md) and small view helpers. */
final class Ui {
    static final int BG = 0xFF07080B;
    static final int GLASS = 0x26FFFFFF, GLASS_PRESSED = 0x4DFFFFFF, LIT = 0xF2FFFFFF;
    static final int TEXT = 0xFFFFFFFF, TEXT2 = 0xCCFFFFFF, TEXT3 = 0x99FFFFFF, HINT = 0x80FFFFFF, INK = 0xFF0B0D12;
    static final int GOOD = 0xFF7EE2A8, BAD = 0xFFFF8F8F;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView light(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        return t;
    }

    /** A glass pill button with tvOS-style press feedback (scale down + brighten, no ripple). */
    static TextView pill(Context c, String label, View.OnClickListener click) {
        TextView t = text(c, label, 15, TEXT, true);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 12), v = dp(c, 10);
        t.setPadding(h + dp(c, 4), v, h + dp(c, 4), v);
        t.setMinHeight(dp(c, 44));
        t.setMinWidth(dp(c, 56));
        t.setBackground(round(GLASS, dp(c, 22)));
        t.setOnClickListener(click);
        pressable(t, GLASS, GLASS_PRESSED);
        return t;
    }

    static void pressable(View v, int normal, int pressed) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                if (view.getBackground() instanceof GradientDrawable && normal != 0) ((GradientDrawable) view.getBackground()).setColor(pressed);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                if (view.getBackground() instanceof GradientDrawable && normal != 0) ((GradientDrawable) view.getBackground()).setColor(normal);
            }
            return false;
        });
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(Color.alpha(color) * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    // ---------- time ----------

    /** ISO 8601 from HA: 2026-11-19T15:00:00-05:00, ...Z, fractional seconds, or a bare date (all-day). */
    static Date parseIso(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.length() == 10) {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                return f.parse(s);
            }
            String main = s.substring(0, 19);
            String rest = s.substring(19);
            int frac = 0;
            if (rest.startsWith(".")) {
                int i = 1;
                while (i < rest.length() && Character.isDigit(rest.charAt(i))) i++;
                String digits = (rest.substring(1, i) + "000").substring(0, 3);
                frac = Integer.parseInt(digits);
                rest = rest.substring(i);
            }
            TimeZone tz;
            if (rest.isEmpty()) tz = TimeZone.getDefault();
            else if (rest.equals("Z")) tz = TimeZone.getTimeZone("UTC");
            else tz = TimeZone.getTimeZone("GMT" + rest.replace(":", ""));
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(tz);
            Date d = f.parse(main, new ParsePosition(0));
            return d == null ? null : new Date(d.getTime() + frac);
        } catch (Exception e) {
            return null;
        }
    }

    /** Temperature unit for the whole tablet: "F" (default) or "C". A person's brief can override it ("temp_unit_<person>"). */
    static final String TEMP_UNIT = "temp_unit";

    /** 12-hour clock unless turned on in Settings (ignores the tablet's own Android setting). */
    static boolean h24(Context c) { return Prefs.of(c).on("clock_24", false); }

    static String clock(Context c, Date d) {
        boolean h24 = Ui.h24(c);
        return new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(d);
    }

    static String dayLabel(Date d) {
        Calendar now = Calendar.getInstance(), x = Calendar.getInstance();
        x.setTime(d);
        int diff = (int) ((startOfDay(x) - startOfDay(now)) / 86_400_000L);
        if (diff == 0) return "Today";
        if (diff == 1) return "Tomorrow";
        return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d);
    }

    private static long startOfDay(Calendar c) {
        Calendar k = (Calendar) c.clone();
        k.set(Calendar.HOUR_OF_DAY, 0); k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** HA duration attribute "0:05:00" → ms. */
    static long parseHms(String s) {
        try {
            String[] p = s.split(":");
            return ((Long.parseLong(p[0]) * 60 + Long.parseLong(p[1])) * 60 + (long) Double.parseDouble(p[2])) * 1000;
        } catch (Exception e) {
            return 0;
        }
    }
}
