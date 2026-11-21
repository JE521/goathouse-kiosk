package org.goathouse.kiosk;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Bring-up screen for the fingerprint reader: opens it, streams frames, shows contrast/fps and saves finger frames as
 * PGM (app files dir, goatprint/) so they can be checked against goatprint's C normaliser offline.
 * Also the target of USB_DEVICE_ATTACHED, which gives the app standing permission for the reader.
 */
public class GoatprintTestActivity extends Activity {
    private static final String TAG = "Goatprint";
    private static final String ACTION = "org.goathouse.kiosk.USB_PERMISSION";
    private TextView text;
    private volatile boolean running;
    private Thread worker;
    private int lastMin; private long lastMs;

    private final BroadcastReceiver perm = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (ACTION.equals(i.getAction())) begin(); }
    };

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")   // guarded: flag only exists from API 33
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.BLACK);
        text.setTextSize(22);
        text.setPadding(40, 40, 40, 40);
        setContentView(text);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(perm, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(perm, new IntentFilter(ACTION));
    }

    @Override protected void onResume() {
        super.onResume();
        begin();
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        Thread w = worker;
        if (w != null) new Thread(() -> {
            try { w.join(3000); } catch (InterruptedException ignored) { }
            Goatprint.get(this).start();
        }).start();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(perm);
    }

    private void begin() {
        if (running) return;
        Goatprint.get(this).stop();
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice d = GoatprintReader.find(um);
        if (d == null) { show("No fingerprint reader attached"); return; }
        if (!um.hasPermission(d)) {
            show("Asking for USB permission…");
            um.requestPermission(d, PendingIntent.getBroadcast(this, 0, new Intent(ACTION).setPackage(getPackageName()), PendingIntent.FLAG_MUTABLE));
            return;
        }
        running = true;
        worker = new Thread(() -> loop(um, d), "goatprint");
        worker.start();
    }

    private void loop(UsbManager um, UsbDevice d) {
        GoatprintReader r = null;
        File dir = new File(getExternalFilesDir(null), "goatprint");
        dir.mkdirs();
        try {
            r = new GoatprintReader(um, d);
            show("Opened " + r.serial + "\ninfo " + r.info);
            Log.i(TAG, "opened serial=" + r.serial + " info=" + r.info);
            long t0 = System.currentTimeMillis();
            int frames = 0, saved = 0, fingerRun = 0;
            double best = 0;
            while (running) {
                byte[] f = r.grab();
                frames++;
                double c = GoatprintReader.contrast(f);
                fingerRun = c >= 6 ? fingerRun + 1 : 0;
                if (fingerRun >= 2 && saved < 20) {            // skip the first finger frame
                    save(new File(dir, String.format("frame-%02d-c%.0f.pgm", saved++, c)), f);
                    long t1 = System.currentTimeMillis();
                    byte[] t = GoatprintCore.extract(GoatprintCore.normalize(f));
                    lastMin = t == null ? 0 : GoatprintCore.minutiae(t);
                    lastMs = System.currentTimeMillis() - t1;
                    Log.i(TAG, "frame " + saved + " contrast " + c + " minutiae " + lastMin + " in " + lastMs + " ms");
                }
                if (c > best) best = c;
                if (frames == 1) save(new File(dir, "empty.pgm"), f);
                double fps = frames * 1000.0 / Math.max(1, System.currentTimeMillis() - t0);
                show(String.format("Reader %s\nframes %d  %.1f fps\ncontrast %.1f  %s\nbest %.1f   saved %d → %s\nlast template: %d minutiae (%d ms)",
                        r.serial, frames, fps, c, c >= 6 ? "FINGER" : c < 5 ? "empty" : "…", best, saved, dir, lastMin, lastMs));
                if (frames % 15 == 0) Log.i(TAG, String.format("frames=%d fps=%.1f contrast=%.1f saved=%d", frames, fps, c, saved));
            }
        } catch (Exception e) {
            Log.w(TAG, "reader: " + e);
            show("Reader error: " + e.getMessage());
        } finally {
            if (r != null) r.close();
            running = false;
        }
    }

    private static void save(File f, byte[] frame) {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(("P5\n" + GoatprintReader.W + " " + GoatprintReader.H + "\n255\n").getBytes("US-ASCII"));
            o.write(frame);
        } catch (Exception e) { Log.w(TAG, "save: " + e); }
    }

    private void show(String s) { runOnUiThread(() -> text.setText(s)); }
}
