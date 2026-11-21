package org.goathouse.kiosk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native rain radar: a raster basemap with RainViewer's free radar tiles (no key) on top, animating the last hour.
 * Replaces the RainViewer web page, which took ages to load on the tablets. Tiles are plain PNGs drawn on a canvas.
 */
final class RadarMap extends View {
    private static final String TAG = "Radar";
    private static final int Z = 7;              // RainViewer's free tiles stop at zoom 7
    private static final int FRAMES = 13;        // last two hours, 10 minutes apart
    // e.g. a self-hosted tile server: https://maps.example.org/<key>/raster/{z}/{x}/{y}@2x.png (any key stays in prefs)
    static final String DEFAULT_BASE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

    private final double lat, lon;
    private final String base;
    private final Map<String, Bitmap> tiles = new ConcurrentHashMap<>();
    private final List<String> framePaths = new ArrayList<>();
    private final List<Long> frameTimes = new ArrayList<>();
    private String host = "";
    private int frame = -1;
    private ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG), radar = new Paint(Paint.FILTER_BITMAP_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), label, small, bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float tileSize;
    private volatile boolean stopped;
    private final SimpleDateFormat hm = new SimpleDateFormat("h:mm a", Locale.US);

    RadarMap(Context c, double lat, double lon, String baseTemplate) {
        super(c);
        this.lat = lat;
        this.lon = lon;
        this.base = baseTemplate == null || baseTemplate.trim().isEmpty() ? DEFAULT_BASE : baseTemplate.trim();
        tileSize = Style.u(330);
        if (!this.base.contains("dark")) {
            // light map → night map: invert, desaturate, cool it a little, so the radar colours stand out
            android.graphics.ColorMatrix m = new android.graphics.ColorMatrix(new float[]{-1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
            android.graphics.ColorMatrix sat = new android.graphics.ColorMatrix();
            sat.setSaturation(0.15f);
            m.postConcat(sat);
            m.postConcat(new android.graphics.ColorMatrix(new float[]{0.75f, 0, 0, 0, 4, 0, 0.8f, 0, 0, 8, 0, 0, 0.9f, 0, 16, 0, 0, 0, 1, 0}));
            bmp.setColorFilter(new android.graphics.ColorMatrixColorFilter(m));
        }
        dot.setColor(Style.AMBER);
        ring.setColor(0x66F2A93B);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Style.u(3));
        label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Style.WHITE);
        label.setTypeface(Style.font(c, 500));
        label.setTextSize(Style.u(22));
        label.setShadowLayer(Style.u(4), 0, 0, 0xCC000000);
        small = new Paint(label);
        small.setTextSize(Style.u(12));
        small.setColor(0xB3FFFFFF);
        setBackgroundColor(0xFF121826);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        stopped = false;
        if (pool.isShutdown()) pool = Executors.newFixedThreadPool(4);
        pool.execute(this::loadFrames);
        h.post(step);
    }

    @Override protected void onDetachedFromWindow() {
        stopped = true;
        h.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) { if (host.length() > 0) pool.execute(this::loadTiles); else pool.execute(this::loadBase); }

    // ---------- geometry (web mercator, world pixels at Z scaled to tileSize) ----------

    private double worldX() { return (lon + 180) / 360 * (1 << Z); }

    private double worldY() {
        double r = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << Z);
    }

    /** Tiles {x, y} covering the view, plus their screen offsets. */
    private List<int[]> visible() {
        List<int[]> out = new ArrayList<>();
        if (getWidth() == 0) return out;
        double cx = worldX(), cy = worldY();
        double halfW = getWidth() / 2.0 / tileSize, halfH = getHeight() / 2.0 / tileSize;
        int x0 = (int) Math.floor(cx - halfW), x1 = (int) Math.floor(cx + halfW), y0 = (int) Math.floor(cy - halfH), y1 = (int) Math.floor(cy + halfH);
        int n = 1 << Z;
        for (int y = Math.max(0, y0); y <= Math.min(n - 1, y1); y++)
            for (int x = x0; x <= x1; x++) out.add(new int[]{((x % n) + n) % n, y, x});
        return out;
    }

    // ---------- loading ----------

    private void loadBase() {
        for (int[] t : visible()) fetch("b/" + t[0] + "/" + t[1], base.replace("{z}", "" + Z).replace("{x}", "" + t[0]).replace("{y}", "" + t[1]));
    }

    private void loadFrames() {
        try {
            for (int w = 0; w < 50 && getWidth() == 0 && !stopped; w++) Thread.sleep(100);
            JSONObject j = new JSONObject(new String(get("https://api.rainviewer.com/public/weather-maps.json"), "UTF-8"));
            JSONArray past = j.getJSONObject("radar").getJSONArray("past");
            List<String> paths = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            for (int i = Math.max(0, past.length() - FRAMES); i < past.length(); i++) {
                paths.add(past.getJSONObject(i).getString("path"));
                times.add(past.getJSONObject(i).getLong("time") * 1000);
            }
            String hst = j.getString("host");
            h.post(() -> {
                host = hst;
                framePaths.clear(); framePaths.addAll(paths);
                frameTimes.clear(); frameTimes.addAll(times);
                frame = framePaths.size() - 1;      // show "now" first, then animate
                invalidate();
            });
            loadBase();
            // newest frame first so the current radar shows up at once, then the older ones for the loop
            for (int f = paths.size() - 1; f >= 0 && !stopped; f--)
                for (int[] t : visible()) fetch("r/" + paths.get(f) + "/" + t[0] + "/" + t[1], hst + paths.get(f) + "/256/" + Z + "/" + t[0] + "/" + t[1] + "/2/1_1.png");
        } catch (Exception e) {
            Log.w(TAG, "frames: " + e);
        }
    }

    private void loadTiles() {
        loadBase();
        for (int f = framePaths.size() - 1; f >= 0 && !stopped; f--)
            for (int[] t : visible()) fetch("r/" + framePaths.get(f) + "/" + t[0] + "/" + t[1], host + framePaths.get(f) + "/256/" + Z + "/" + t[0] + "/" + t[1] + "/2/1_1.png");
    }

    private void fetch(String key, String url) {
        if (stopped || tiles.containsKey(key)) return;
        try {
            byte[] b = get(url);
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bm != null) { tiles.put(key, bm); postInvalidate(); }
        } catch (Exception e) {
            Log.w(TAG, key + ": " + e);
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "goathouse-kiosk");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    // ---------- animation ----------

    // Like TV weather: frames dissolve into each other continuously instead of jumping every 10 minutes.
    private static final long TICK = 33, FADE_MS = 520, HOLD_MS = 90, HOLD_NOW_MS = 1800;
    private float fade;                // 0..1 from `frame` toward the next frame
    private long holdUntil;

    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            int n = framePaths.size();
            long now = android.os.SystemClock.uptimeMillis();
            if (n > 1 && now >= holdUntil) {
                int next = (frame + 1) % n;
                if (fade > 0 || frameReady(next)) {
                    fade += (float) TICK / FADE_MS;
                    if (fade >= 1f) {
                        fade = 0f;
                        frame = next;
                        holdUntil = now + (frame == n - 1 ? HOLD_NOW_MS : HOLD_MS);
                    }
                    invalidate();
                }
            }
            h.postDelayed(this, TICK);
        }
    };

    private boolean frameReady(int f) {
        for (int[] t : visible()) if (!tiles.containsKey("r/" + framePaths.get(f) + "/" + t[0] + "/" + t[1])) return false;
        return true;
    }

    // ---------- drawing ----------

    private static final int RADAR_A = 215;
    private Bitmap baseCache;
    private int baseCount = -1;

    /** The basemap is composed once into a bitmap (redrawn only when a tile arrives) so the 30 fps fade stays cheap. */
    private void drawBase(Canvas c, float ox, float oy) {
        int have = 0;
        for (int[] t : visible()) if (tiles.containsKey("b/" + t[0] + "/" + t[1])) have++;
        if (baseCache == null || baseCache.getWidth() != getWidth() || baseCache.getHeight() != getHeight() || have != baseCount) {
            if (baseCache == null || baseCache.getWidth() != getWidth() || baseCache.getHeight() != getHeight())
                baseCache = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()), Bitmap.Config.RGB_565);
            Canvas bc = new Canvas(baseCache);
            bc.drawColor(0xFF121826);
            RectF d = new RectF();
            for (int[] t : visible()) {
                Bitmap b = tiles.get("b/" + t[0] + "/" + t[1]);
                if (b == null) continue;
                d.set(ox + t[2] * tileSize, oy + t[1] * tileSize, ox + (t[2] + 1) * tileSize, oy + (t[1] + 1) * tileSize);
                bc.drawBitmap(b, null, d, bmp);
            }
            baseCount = have;
        }
        c.drawBitmap(baseCache, 0, 0, null);
    }

    @Override protected void onDraw(Canvas c) {
        double cx = worldX(), cy = worldY();
        float ox = (float) (getWidth() / 2.0 - cx * tileSize), oy = (float) (getHeight() / 2.0 - cy * tileSize);
        String fp = frame >= 0 && frame < framePaths.size() ? framePaths.get(frame) : null;
        String np = fp != null && fade > 0 ? framePaths.get((frame + 1) % framePaths.size()) : null;
        float e = fade * fade * (3 - 2 * fade);                 // smoothstep
        RectF dst = new RectF();
        drawBase(c, ox, oy);
        for (int[] t : visible()) {
            dst.set(ox + t[2] * tileSize, oy + t[1] * tileSize, ox + (t[2] + 1) * tileSize, oy + (t[1] + 1) * tileSize);
            Bitmap r = fp == null ? null : tiles.get("r/" + fp + "/" + t[0] + "/" + t[1]);
            Bitmap rn = np == null ? null : tiles.get("r/" + np + "/" + t[0] + "/" + t[1]);
            if (r != null) { radar.setAlpha(Math.round(RADAR_A * (rn == null ? 1 : 1 - e))); c.drawBitmap(r, null, dst, radar); }
            if (rn != null) { radar.setAlpha(Math.round(RADAR_A * e)); c.drawBitmap(rn, null, dst, radar); }
        }
        float mx = getWidth() / 2f, my = getHeight() / 2f;
        c.drawCircle(mx, my, Style.u(16), ring);
        c.drawCircle(mx, my, Style.u(7), dot);

        float pad = Style.u(24);
        if (fp != null) {
            int shown = fade > 0.5f ? (frame + 1) % framePaths.size() : frame;
            boolean now = shown == framePaths.size() - 1;
            String when = now ? "Now · " + hm.format(new Date(frameTimes.get(shown))) : hm.format(new Date(frameTimes.get(shown)));
            c.drawText(when, pad, getHeight() - pad - Style.u(18), label);
            // progress dots
            float x = pad, y = getHeight() - pad;
            for (int i = 0; i < framePaths.size(); i++) {
                bar.setColor(i == (fade > 0.5f ? (frame + 1) % framePaths.size() : frame) ? Style.AMBER : 0x59FFFFFF);
                c.drawRoundRect(new RectF(x, y - Style.u(5), x + Style.u(16), y), Style.u(3), Style.u(3), bar);
                x += Style.u(21);
            }
        } else {
            c.drawText("Loading radar…", pad, getHeight() - pad, label);
        }
        String credit = "© OpenStreetMap contributors · RainViewer";
        c.drawText(credit, getWidth() - pad - small.measureText(credit), getHeight() - Style.u(12), small);
    }
}
