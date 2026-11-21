package org.goathouse.kiosk;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Shows a QR code; the phone opens the setup form served by SetupServer while this screen is up. */
public class SetupActivity extends Activity {
    private SetupServer server;
    private TextView state;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 32);
        col.setPadding(pad, pad, pad, pad);
        setContentView(col);

        server = new SetupServer(this, () -> runOnUiThread(() -> {
            Ha.get(this).restart();
            state.setText("Saved ✓  The tablet is reconnecting.");
            state.setTextColor(Ui.GOOD);
        }));
        server.start();
        String ip = lanIp();
        String url = "http://" + (ip == null ? "?" : ip) + ":" + SetupServer.PORT + "/?pin=" + server.pin;

        TextView title = Ui.text(this, "Set up from your phone", 28, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        col.addView(title);
        TextView sub = Ui.text(this, "Scan with the phone camera (same Wi-Fi). The page lets you connect Home Assistant and pick what shows here.", 16, Ui.TEXT2, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 24));
        sub.setMaxWidth(Ui.dp(this, 520));
        col.addView(sub);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qr(url, Ui.dp(this, 240)));
        qr.setBackgroundColor(Color.WHITE);
        int q = Ui.dp(this, 12);
        qr.setPadding(q, q, q, q);
        col.addView(qr, new LinearLayout.LayoutParams(Ui.dp(this, 264), Ui.dp(this, 264)));
        TextView link = Ui.text(this, url.replace("/?pin=" + server.pin, "") + "    PIN " + server.pin, 16, Ui.TEXT3, false);
        link.setPadding(0, Ui.dp(this, 18), 0, 0);
        link.setGravity(Gravity.CENTER);
        col.addView(link);
        state = Ui.text(this, Ha.get(this).connected() ? "Home Assistant: connected" : "Home Assistant: " + Ha.get(this).status(), 15, Ui.TEXT3, false);
        state.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 24));
        state.setGravity(Gravity.CENTER);
        col.addView(state);
        col.addView(Ui.pill(this, "Done", v -> finish()), new LinearLayout.LayoutParams(Ui.dp(this, 200), -2));
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        server.stop();
    }

    static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.RGB_565);
            for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            return bmp;
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
        }
    }

    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }
}
