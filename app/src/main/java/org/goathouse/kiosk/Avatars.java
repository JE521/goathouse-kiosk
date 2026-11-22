package org.goathouse.kiosk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Round person avatars: the HA person's profile picture (entity_picture, fetched with the tablet's token) over an
 * initial that shows until the picture arrives, or when the person has none. Pictures are cached in memory.
 */
final class Avatars {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(16);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A size×size round avatar for {@code e}: initial on {@code bg} with {@code fg} text, replaced by the photo once loaded. */
    static FrameLayout view(Context c, Ha.Ent e, int size, int bg, int fg, float textPx) {
        FrameLayout f = new FrameLayout(c);
        String name = e == null ? "?" : e.name();
        TextView t = Style.t(c, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.getDefault()), textPx, 700, fg);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Style.fill(bg, size / 2f));
        f.addView(t, new FrameLayout.LayoutParams(size, size));
        ImageView iv = new ImageView(c);
        f.addView(iv, new FrameLayout.LayoutParams(size, size));
        String pic = e == null ? "" : e.attrs.optString("entity_picture", "");
        if (!pic.isEmpty()) load(c, pic, size, iv);
        return f;
    }

    private static void load(Context c, String path, int size, ImageView into) {
        String key = path + "@" + size;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { into.setImageBitmap(hit); return; }
        Prefs p = Prefs.of(c);
        String base = p.get(Prefs.HA_URL).replaceAll("/+$", ""), token = p.get(Prefs.HA_TOKEN);
        if (base.isEmpty()) return;
        String url = path.startsWith("http") ? path : base + path;
        IO.execute(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(5000);
                h.setReadTimeout(10000);
                if (url.startsWith(base)) h.setRequestProperty("Authorization", "Bearer " + token);   // never send the token elsewhere
                Bitmap raw;
                try (InputStream in = h.getInputStream()) { raw = BitmapFactory.decodeStream(in); }
                h.disconnect();
                if (raw == null) return;
                Bitmap round = round(raw, size);
                CACHE.put(key, round);
                MAIN.post(() -> into.setImageBitmap(round));
            } catch (Exception ignored) { /* keep the initial */ }
        });
    }

    private static Bitmap round(Bitmap src, int size) {
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - s) / 2, (src.getHeight() - s) / 2, s, s);
        Bitmap scaled = Bitmap.createScaledBitmap(sq, size, size, true);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p);
        return out;
    }

    private Avatars() { }
}
