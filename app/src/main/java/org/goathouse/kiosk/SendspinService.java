package org.goathouse.kiosk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

/**
 * Makes this tablet a Music Assistant speaker (Sendspin, ported from the TV launcher). A quiet foreground service so
 * Android keeps the connection and audio alive behind the dashboard. Now Playing on the dashboard comes from this
 * player's Home Assistant entity (see Dash.media()), so no extra UI lives here.
 */
public final class SendspinService extends Service implements SendspinClient.Listener {
    private static final String TAG = "Sendspin";
    private static final String CHANNEL = "sendspin";
    static final String PLAYER_NAME = "player_name", ENABLED = "ma_player";

    static volatile SendspinService live;
    private SendspinClient client;
    volatile boolean connected;

    private static int retries;

    static void startIfConfigured(Context ctx) {
        Log.i(TAG, "speaker " + url(ctx) + (Prefs.of(ctx).on(ENABLED, true) ? "" : " (off)"));
        if (!Prefs.of(ctx).on(ENABLED, true) || url(ctx) == null) return;
        try {
            Intent i = new Intent(ctx, SendspinService.class);
            // startService while the dashboard is in front (the service promotes itself to foreground); from the
            // background (boot, update) Android refuses that, so use the foreground-service call instead.
            android.content.ComponentName cn;
            try { cn = ctx.startService(i); } catch (IllegalStateException bg) { cn = null; }
            if (cn == null && Build.VERSION.SDK_INT >= 26) cn = ctx.startForegroundService(i);
            Log.i(TAG, "start requested: " + cn);
            // Right after an install/update the firmware can't find the service for a little while: try again.
            if (cn == null && retries++ < 12) {
                Context app = ctx.getApplicationContext();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { if (live == null) startIfConfigured(app); }, 10_000);
            } else if (cn != null) retries = 0;
        } catch (Exception e) {
            Log.w(TAG, "start: " + e);
        } catch (Throwable t) {
            Log.w(TAG, "start failed: " + t);
        }
    }

    static void restart(Context ctx) {
        ctx.stopService(new Intent(ctx, SendspinService.class));
        startIfConfigured(ctx);
    }

    /** ws://<Music Assistant host>:8927/sendspin; MA runs next to Home Assistant here, so the HA host is used. */
    static String url(Context ctx) {
        String ha = Prefs.of(ctx).get(Prefs.HA_URL);
        if (ha.isEmpty()) return null;
        try {
            String host = Uri.parse(ha).getHost();
            return host == null ? null : "ws://" + host + ":8927/sendspin";
        } catch (Exception e) {
            return null;
        }
    }

    static String playerName(Context ctx) {
        String n = Prefs.of(ctx).get(PLAYER_NAME, "Kitchen panel");
        return n.trim().isEmpty() ? "Kitchen panel" : n.trim();
    }

    @Override public void onCreate() {
        super.onCreate();
        live = this;
        Log.i(TAG, "service created");
        Jellyfin.get(this).start();      // Jellyfin cast target rides on this always-on service
        Goatprint.get(this).start();     // fingerprint reader (if one is plugged in) is read on its own thread
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Music Assistant speaker", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else b = new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_kiosk).setContentTitle(playerName(this))
                .setContentText("Music Assistant speaker").setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(41, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String u = url(this);
        if (u == null) { stopSelf(); return START_NOT_STICKY; }
        if (client == null) {
            SharedPreferences p = getSharedPreferences("sendspin", MODE_PRIVATE);
            try {
                client = new SendspinClient(u, playerName(this), staticKey(p), p.getInt("volume", 100), p.getBoolean("muted", false), this);
                client.start();
            } catch (Exception e) {
                Log.w(TAG, "client: " + e);
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (client != null) {
            getSharedPreferences("sendspin", MODE_PRIVATE).edit().putInt("volume", client.volume()).putBoolean("muted", client.muted()).apply();
            client.stop();
        }
        live = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** This tablet's Sendspin identity: a Curve25519 key made once and kept (its public key is the client id). */
    private static byte[] staticKey(SharedPreferences p) throws Exception {
        String k = p.getString("noise_priv", "");
        if (k.isEmpty()) {
            byte[][] kp = Noise.generateKeyPair();
            k = Base64.encodeToString(kp[0], Base64.NO_WRAP);
            p.edit().putString("noise_priv", k).apply();
        }
        return Base64.decode(k, Base64.NO_WRAP);
    }

    @Override public void onConnection(boolean c) { connected = c; }
    @Override public void onMetadata(JSONObject md) { }
    @Override public void onPlaybackState(String state) { }
    @Override public void onAudioStarted() { }
    @Override public void onStreamEnded() { }
}
