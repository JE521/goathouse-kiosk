package org.goathouse.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;
import java.util.UUID;

/** Full-screen player for things cast from a Jellyfin app. Tap to show controls; the phone's remote works too. */
public class CastActivity extends Activity implements Jellyfin.Remote {
    private static final String TAG = "Cast";
    private Jellyfin jf;
    private VideoView video;
    private ImageView poster;
    private LinearLayout controls;
    private TextView title, sub, pos;
    private Widgets.Slider seek;
    private Kit.Icon playIcon;
    private FrameLayout playBtn;
    private final Handler h = new Handler(Looper.getMainLooper());
    private JSONArray ids = new JSONArray();
    private int index;
    private long startTicks;
    /** Video streams are transcoded starting at baseMs (server-side start), so positions = baseMs + player position. */
    private long baseMs;
    private int audioIndex = -1;
    private String itemId, playSession, mediaType = "Video";
    private long runTicks;
    private boolean prepared;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        jf = Jellyfin.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        Style.measure(this);
        build();
        take(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); stopReport(); take(i); }

    @Override protected void onResume() {
        super.onResume();
        Style.brightness(this, false);
        Jellyfin.remote = this;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override protected void onDestroy() {
        if (Jellyfin.remote == this) Jellyfin.remote = null;
        h.removeCallbacksAndMessages(null);
        stopReport();
        super.onDestroy();
    }

    private int u(float v) { return Style.u(v); }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(u(40), u(28), u(40), u(32));
        controls.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000}));
        title = Style.t(this, "", 30, 600, Style.WHITE);
        sub = Style.t(this, "", 17, 400, Style.WHITE80);
        controls.addView(title);
        controls.addView(sub);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, u(18), 0, 0);
        row.addView(Kit.iconButton(this, "x", u(56), Style.GLASS, Style.WHITE, "Stop", v -> stopAndClose()));
        playBtn = Kit.iconButton(this, "pause", u(64), Style.AMBER, Style.INK, "Play or pause", v -> togglePause());
        playIcon = (Kit.Icon) playBtn.getChildAt(0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(u(64), u(64));
        pp.leftMargin = u(14); pp.rightMargin = u(18);
        row.addView(playBtn, pp);
        seek = new Widgets.Slider(this, 0, Style.AMBER, null, null, v -> { long d = durMs(); if (prepared && d > 0) seekTo(d / 100 * v); });
        row.addView(seek, new LinearLayout.LayoutParams(0, u(12), 1));
        pos = Style.t(this, "", 16, 500, Style.WHITE80);
        pos.setPadding(u(18), 0, 0, 0);
        row.addView(pos);
        controls.addView(row);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.setOnClickListener(v -> showControls());
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            prepared = true;
            if (startTicks > 0 && isAudio()) video.seekTo((int) (startTicks / 10_000));      // video already starts there
            video.start();
            setPlayIcon(true);
            report("/Sessions/Playing", false);
            h.post(progress);
            showControls();
        });
        video.setOnCompletionListener(mp -> next());
        video.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "player error " + what + "/" + extra);
            sub.setText("Can't play this here (" + what + ")");
            showControls();
            return true;
        });
    }

    private void take(Intent i) {
        try { ids = new JSONArray(i.getStringExtra("ids")); } catch (Exception e) { finish(); return; }
        index = i.getIntExtra("index", 0);
        startTicks = i.getLongExtra("start", 0);
        audioIndex = i.getIntExtra("audioIndex", -1);
        load();
    }

    private void load() {
        if (index >= ids.length()) { finish(); return; }
        itemId = ids.optString(index);
        playSession = UUID.randomUUID().toString().replace("-", "");
        prepared = false;
        title.setText("Loading…");
        sub.setText("");
        poster.setImageBitmap(null);
        new Thread(() -> {
            try {
                JSONObject it = jf.call("GET", "/Users/" + jf.userId() + "/Items/" + itemId, null);
                Bitmap art = null;
                try (InputStream in = new URL(jf.imageUrl(itemId)).openStream()) { art = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
                Bitmap fa = art;
                h.post(() -> start(it, fa));
            } catch (Exception e) {
                h.post(() -> { title.setText("Couldn't load"); sub.setText(String.valueOf(e.getMessage())); showControls(); });
            }
        }).start();
    }

    private void start(JSONObject it, Bitmap art) {
        mediaType = it.optString("MediaType", "Video");
        runTicks = it.optLong("RunTimeTicks", 0);
        String name = it.optString("Name");
        String series = it.optString("SeriesName", "");
        if (!series.isEmpty()) {
            title.setText(series);
            sub.setText("S" + it.optInt("ParentIndexNumber") + "·E" + it.optInt("IndexNumber") + "  " + name);
        } else if ("Audio".equals(mediaType)) {
            title.setText(name);
            JSONArray artists = it.optJSONArray("Artists");
            sub.setText(artists != null && artists.length() > 0 ? artists.optString(0) : it.optString("Album", ""));
        } else {
            title.setText(name);
            sub.setText(it.has("ProductionYear") ? String.valueOf(it.optInt("ProductionYear")) : "");
        }
        boolean audio = "Audio".equals(mediaType);
        poster.setImageBitmap(art);
        poster.setVisibility(audio ? View.VISIBLE : View.GONE);
        baseMs = audio ? 0 : startTicks / 10_000;
        video.setVideoURI(Uri.parse(audio ? jf.audioUrl(itemId, playSession) : StreamRelay.wrap(jf.videoUrl(itemId, playSession, audioIndex, startTicks))));
    }

    private void next() {
        report("/Sessions/Playing/Stopped", false);
        index++;
        startTicks = 0;
        if (index < ids.length()) load(); else finish();
    }

    private void togglePause() {
        if (!prepared) return;
        if (video.isPlaying()) video.pause(); else video.start();
        setPlayIcon(video.isPlaying());
        report("/Sessions/Playing/Progress", !video.isPlaying());
        showControls();
    }

    private void setPlayIcon(boolean playing) {
        playBtn.removeAllViews();
        playIcon = Kit.icon(this, playing ? "pause" : "play", Style.INK);
        playBtn.addView(playIcon, new FrameLayout.LayoutParams(u(28), u(28), Gravity.CENTER));
    }

    private void stopAndClose() {
        try { video.stopPlayback(); } catch (Exception ignored) { }
        finish();
    }

    private final Runnable hide = () -> controls.animate().alpha(0f).setDuration(400).start();

    private void showControls() {
        controls.animate().cancel();
        controls.setAlpha(1f);
        h.removeCallbacks(hide);
        if (!"Audio".equals(mediaType)) h.postDelayed(hide, 4000);
    }

    private long lastReport;
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (prepared) {
                long d = durMs(), p = posMs();
                if (d > 0) seek.set((int) (p * 100 / d));
                pos.setText(Kit.mmss(p) + (d > 0 ? " / " + Kit.mmss(d) : ""));
                long now = System.currentTimeMillis();
                if (now - lastReport > 10_000) report("/Sessions/Playing/Progress", !video.isPlaying());
            }
            h.postDelayed(this, 1000);
        }
    };

    private void report(String path, boolean paused) {
        if (itemId == null) return;
        lastReport = System.currentTimeMillis();
        try {
            long ticks = prepared ? posMs() * 10_000L : startTicks;
            jf.post(path, new JSONObject().put("ItemId", itemId).put("PlaySessionId", playSession).put("PositionTicks", ticks)
                    .put("IsPaused", paused).put("CanSeek", true).put("PlayMethod", "Transcode").put("MediaSourceId", itemId));
        } catch (Exception ignored) { }
    }

    private void stopReport() {
        if (itemId != null) report("/Sessions/Playing/Stopped", false);
        itemId = null;
    }

    private boolean isAudio() { return "Audio".equals(mediaType); }

    /** Real position in the item (video: the stream starts at baseMs). */
    private long posMs() { return baseMs + Math.max(0, video.getCurrentPosition()); }

    /** Full item length: Jellyfin's runtime (a transcoded stream's own duration is only the part from baseMs). */
    private long durMs() { return runTicks > 0 ? runTicks / 10_000 : baseMs + Math.max(0, video.getDuration()); }

    /** Audio seeks in place; video restarts the transcode at the new point (the server only has what it has converted). */
    private void seekTo(long ms) {
        if (isAudio()) {
            video.seekTo((int) ms);
        } else {
            startTicks = ms * 10_000;
            baseMs = ms;
            prepared = false;
            video.setVideoURI(Uri.parse(StreamRelay.wrap(jf.videoUrl(itemId, playSession, audioIndex, startTicks))));
        }
        report("/Sessions/Playing/Progress", false);
        showControls();
    }

    // ---------- phone remote ----------

    @Override public void onPlaystate(String cmd, long seekTicks) {
        switch (cmd) {
            case "Pause": if (video.isPlaying()) togglePause(); break;
            case "Unpause": if (!video.isPlaying()) togglePause(); break;
            case "PlayPause": togglePause(); break;
            case "Stop": stopAndClose(); break;
            case "Seek": if (prepared && seekTicks >= 0) seekTo(seekTicks / 10_000); break;
            case "NextTrack": next(); break;
            case "PreviousTrack": if (index > 0) { report("/Sessions/Playing/Stopped", false); index--; startTicks = 0; load(); } break;
        }
    }
}
