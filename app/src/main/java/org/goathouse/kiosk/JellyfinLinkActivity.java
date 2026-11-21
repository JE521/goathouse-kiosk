package org.goathouse.kiosk;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Link this tablet to Jellyfin as a cast target: finds the server on the LAN (Jellyfin's UDP discovery), then shows a
 * Quick Connect code to approve from any signed-in Jellyfin app (profile → Quick Connect). No password is entered here.
 */
public class JellyfinLinkActivity extends Activity {
    private Jellyfin jf;
    private EditText server;
    private TextView code, state;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        jf = Jellyfin.get(this);
        Style.measure(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Style.u(60), Style.u(40), Style.u(60), Style.u(40));
        col.addView(Style.t(this, "Jellyfin cast", 34, 600, Style.WHITE));
        TextView how = Style.t(this, "Cast movies, shows and music to this tablet from the Jellyfin app.", 17, 400, Style.WHITE80);
        how.setPadding(0, Style.u(8), 0, Style.u(24));
        col.addView(how);
        server = new EditText(this);
        server.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        server.setSingleLine(true);
        server.setTextColor(Style.WHITE);
        server.setHint("http://jellyfin-address:8096");
        server.setHintTextColor(Style.WHITE60);
        server.setText(jf.server());
        col.addView(server, new LinearLayout.LayoutParams(Style.u(560), -2));
        TextView go = Style.t(this, jf.linked() ? "Link again" : "Get a code", 19, 600, Style.INK);
        go.setGravity(Gravity.CENTER);
        go.setBackground(Style.fill(Style.AMBER, Style.u(26)));
        go.setOnClickListener(v -> start());
        Style.pressable(go);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Style.u(260), Style.u(56));
        gp.topMargin = Style.u(16);
        col.addView(go, gp);
        code = Style.t(this, "", 72, 300, Style.AMBER);
        code.setLetterSpacing(0.2f);
        code.setPadding(0, Style.u(24), 0, 0);
        code.setGravity(Gravity.CENTER);
        col.addView(code, new LinearLayout.LayoutParams(-1, -2));
        state = Style.t(this, jf.status, 17, 500, Style.WHITE80);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, Style.u(12), 0, 0);
        col.addView(state);
        TextView done = Style.t(this, "Done", 18, 600, Style.WHITE);
        done.setGravity(Gravity.CENTER);
        done.setBackground(Style.fill(Style.GLASS, Style.u(26)));
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Style.u(200), Style.u(52));
        dp.topMargin = Style.u(28);
        col.addView(done, dp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        col.setFocusableInTouchMode(true);
        col.requestFocus();      // no keyboard until the address field is tapped
        setContentView(root);
        if (jf.server().isEmpty()) discover();
    }

    /** Jellyfin answers "who is JellyfinServer?" on UDP 7359 with its address. */
    private void discover() {
        state.setText("Looking for Jellyfin on the network…");
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                s.setSoTimeout(2500);
                byte[] q = "who is JellyfinServer?".getBytes("UTF-8");
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 7359));
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                JSONObject j = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                String addr = j.optString("Address");
                runOnUiThread(() -> { server.setText(addr); state.setText("Found " + j.optString("Name", "Jellyfin")); });
            } catch (Exception e) {
                runOnUiThread(() -> state.setText("Type the Jellyfin address (http://…:8096)"));
            }
        }).start();
    }

    private void start() {
        String url = server.getText().toString().trim();
        if (url.isEmpty()) { discover(); return; }
        if (!url.matches("^[a-zA-Z]+://.*")) url = "http://" + url;
        code.setText("");
        state.setText("Asking Jellyfin for a code…");
        jf.link(url, new Jellyfin.LinkListener() {
            @Override public void code(String c) {
                code.setText(c);
                state.setText("In the Jellyfin app: your profile → Quick Connect → enter this code");
            }
            @Override public void done(String user, String error) {
                code.setText(error == null ? "✓" : "");
                state.setText(error == null ? "Linked as " + user + ". This tablet now shows up when you cast." : "Couldn't link: " + error);
            }
        });
    }
}
