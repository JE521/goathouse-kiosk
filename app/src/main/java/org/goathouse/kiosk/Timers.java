package org.goathouse.kiosk;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kitchen timers that live on the tablet: any number, each with a label, kept across restarts.
 * When one finishes it rings (alarm sound) until someone taps Stop.
 */
final class Timers {
    static final class T {
        long id;
        String label;
        long totalMs, endAt, pausedLeft = -1;
        boolean ringing;
        boolean paused() { return pausedLeft >= 0; }
        long left() { return paused() ? pausedLeft : Math.max(0, endAt - System.currentTimeMillis()); }
        float progress() { return totalMs <= 0 ? 0 : 1f - (float) left() / totalMs; }
    }

    interface Listener { void onTimersChanged(); }

    private static Timers instance;
    static synchronized Timers get(Context c) {
        if (instance == null) instance = new Timers(c.getApplicationContext());
        return instance;
    }

    final List<T> list = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Ringtone tone;

    private Timers(Context c) {
        ctx = c;
        load();
        h.post(tick);
    }

    void add(Listener l) { listeners.add(l); }
    void remove(Listener l) { listeners.remove(l); }

    void start(String label, long ms) {
        if (ms <= 0) return;
        T t = new T();
        t.id = System.currentTimeMillis();
        t.label = label == null || label.trim().isEmpty() ? defaultLabel(ms) : label.trim();
        t.totalMs = ms;
        t.endAt = System.currentTimeMillis() + ms;
        list.add(t);
        changed();
    }

    void pauseResume(T t) {
        if (t.paused()) { t.endAt = System.currentTimeMillis() + t.pausedLeft; t.pausedLeft = -1; }
        else t.pausedLeft = t.left();
        changed();
    }

    void cancel(T t) {
        list.remove(t);
        if (t.ringing) stopTone();
        changed();
    }

    /** Stop every ringing timer (the alert's Stop button). */
    void stopRinging() {
        for (T t : new ArrayList<>(list)) if (t.ringing) list.remove(t);
        stopTone();
        changed();
    }

    List<T> ringing() {
        List<T> out = new ArrayList<>();
        for (T t : list) if (t.ringing) out.add(t);
        return out;
    }

    /** The timer that matters most on the idle screen: the one finishing soonest. */
    T soonest() {
        T best = null;
        for (T t : list) if (!t.ringing && (best == null || t.left() < best.left())) best = t;
        return best;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean fire = false;
            for (T t : list) if (!t.ringing && !t.paused() && t.left() <= 0) { t.ringing = true; fire = true; }
            if (fire) { startTone(); changed(); }
            h.postDelayed(this, 500);
        }
    };

    private void startTone() {
        if (tone != null && tone.isPlaying()) return;
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            tone = RingtoneManager.getRingtone(ctx, u);
            if (tone == null) return;
            if (Build.VERSION.SDK_INT >= 21) tone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            if (Build.VERSION.SDK_INT >= 28) tone.setLooping(true);
            tone.play();
        } catch (Exception ignored) { }
    }

    private void stopTone() {
        if (ringing().isEmpty() && tone != null) { try { tone.stop(); } catch (Exception ignored) { } }
    }

    private void changed() {
        save();
        h.post(() -> { for (Listener l : listeners) l.onTimersChanged(); });
    }

    private static String defaultLabel(long ms) {
        long m = ms / 60000;
        return m >= 60 ? (m / 60) + " hr" + (m % 60 > 0 ? " " + (m % 60) + " min" : "") : m + " min";
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (T t : list) a.put(new JSONObject().put("id", t.id).put("label", t.label).put("total", t.totalMs)
                    .put("end", t.endAt).put("paused", t.pausedLeft).put("ringing", t.ringing));
            Prefs.of(ctx).raw().edit().putString("timers", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void load() {
        try {
            JSONArray a = new JSONArray(Prefs.of(ctx).raw().getString("timers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                T t = new T();
                t.id = o.optLong("id"); t.label = o.optString("label"); t.totalMs = o.optLong("total");
                t.endAt = o.optLong("end"); t.pausedLeft = o.optLong("paused", -1); t.ringing = o.optBoolean("ringing");
                list.add(t);
            }
        } catch (Exception ignored) { }
    }
}
