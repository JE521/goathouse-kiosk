package org.goathouse.kiosk;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * This tablet as a goatprint node on the house MQTT broker (goatprint docs/ANDROID.md §5–6):
 * <ul>
 * <li>Identity: an Ed25519 key (seed sealed with the Keystore-wrapped vault key, so it survives app updates) and the
 *     X25519 "box" key derived from it. {@link #identity()} is the {@code gpnode1:…} line other nodes trust.</li>
 * <li>Events: match / match_secure / no_match / lockout / lockout_end, signed exactly like the Linux daemon (canonical
 *     JSON, monotonic counter) so Home Assistant's goatprint integration accepts them.</li>
 * <li>Enroll once, use everywhere: retained bundles on {@code goatprint/<node>/inbox/<sender>/<person>} from accepted
 *     peers are verified, decrypted and mirrored into the vault (linux/goatprintd/share.py semantics), then cleared.</li>
 * </ul>
 * Nothing biometric leaves the tablet except as these end-to-end encrypted bundles, and this side only receives them.
 */
final class GoatprintNode implements Mqtt.Callback {
    private static final String TAG = "GoatprintNode";
    static final String HOST = "gp_mqtt_host", PORT = "gp_mqtt_port", USER = "gp_mqtt_user", PASS = "gp_mqtt_pass",
            NODE = "gp_node", ACCEPT = "gp_accept";
    private static final Pattern NODE_RE = Pattern.compile("^[a-z0-9-]{1,32}$"), NAME_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$"),
            FINGER_RE = Pattern.compile("^[a-z_]{1,32}$");
    private static final String[] HDR_FIELDS = {"v", "type", "from", "from_sign", "to", "to_box", "person", "revoke", "ts", "epk"};

    private static GoatprintNode instance;
    static synchronized GoatprintNode get(Context c) {
        if (instance == null) instance = new GoatprintNode(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private Ed25519PrivateKeyParameters sign;
    private X25519PrivateKeyParameters box;
    private volatile Mqtt mqtt;
    private volatile String status = "MQTT not set up";
    private String node = "";

    private GoatprintNode(Context c) { ctx = c; }

    String status() { return status; }

    /** The node id: the configured one, else kiosk-<layout> (lowercase a-z0-9-, what goatprint-add-node.sh accepts). */
    String node() {
        String n = Prefs.of(ctx).get(NODE).trim().toLowerCase(Locale.ROOT);
        if (NODE_RE.matcher(n).matches()) return n;
        Dash d = Dash.of(ctx);
        return "kiosk-" + (d.hallway() ? "hallway" : d.room() ? "room" : "kitchen");
    }

    // ---------- MQTT lifecycle ----------

    /** (Re)connect with the current settings; no host = offline (events only go to the log). */
    synchronized void start() {
        Mqtt old = mqtt;
        mqtt = null;
        if (old != null) old.stop();
        Prefs p = Prefs.of(ctx);
        String host = p.get(HOST).trim();
        node = node();
        if (host.isEmpty()) { status = "MQTT not set up"; return; }
        try { keys(); } catch (Exception e) { status = "Key error: " + e.getMessage(); Log.e(TAG, "keys", e); return; }
        int port = 8883;
        try { port = Integer.parseInt(p.get(PORT, "8883").trim()); } catch (NumberFormatException ignored) { }
        String user = p.get(USER).trim();
        if (user.isEmpty()) user = "goatprint-" + node;
        Mqtt m = new Mqtt(ctx, host, port, port != 1883, user, p.get(PASS), "goatprint-" + node,
                base() + "/status", "offline", this);
        mqtt = m;
        m.start();
    }

    private String base() { return "goatprint/" + node; }

    @Override public void onStatus(String s) { status = s; }

    @Override public void onConnected(Mqtt m) {
        try {
            discovery(m);
            m.publish(base() + "/pubkey", b64(signPub()), 1, true);
            m.publish(base() + "/status", "online", 1, true);
            m.subscribe(base() + "/inbox/+/+");
        } catch (Exception e) { Log.w(TAG, "on connect: " + e); }
    }

    /** Ask for retained bundles again (after trusting a new peer). */
    void resubscribe() {
        Mqtt m = mqtt;
        if (m != null && m.connected()) m.subscribe(base() + "/inbox/+/+");
    }

    private void discovery(Mqtt m) throws JSONException {
        String uid = "goatprint_" + node, p = "homeassistant";
        JSONObject dev = new JSONObject().put("identifiers", new JSONArray().put(uid)).put("name", "goatprint " + node)
                .put("manufacturer", "goatprint (open source)").put("model", "Suprema BioMini Slim (SFU-S20)")
                .put("sw_version", version());
        Map<String, JSONObject> cfg = new LinkedHashMap<>();
        cfg.put(p + "/event/" + uid + "/scan/config", new JSONObject().put("name", "Fingerprint").put("unique_id", uid + "_scan")
                .put("state_topic", base() + "/event").put("event_types", new JSONArray(Arrays.asList("match", "match_secure", "no_match", "lockout", "lockout_end")))
                .put("icon", "mdi:fingerprint"));
        cfg.put(p + "/event/" + uid + "/alert/config", new JSONObject().put("name", "Duress").put("unique_id", uid + "_duress")
                .put("state_topic", base() + "/alert").put("event_types", new JSONArray().put("duress")).put("icon", "mdi:alarm-light"));
        cfg.put(p + "/sensor/" + uid + "/last_person/config", new JSONObject().put("name", "Last person").put("unique_id", uid + "_last_person")
                .put("state_topic", base() + "/last").put("value_template", "{{ value_json.person }}").put("json_attributes_topic", base() + "/last")
                .put("icon", "mdi:account-check"));
        cfg.put(p + "/sensor/" + uid + "/public_key/config", new JSONObject().put("name", "Signing key").put("unique_id", uid + "_pubkey")
                .put("state_topic", base() + "/pubkey").put("entity_category", "diagnostic").put("icon", "mdi:key"));
        for (Map.Entry<String, JSONObject> e : cfg.entrySet()) {
            JSONObject o = e.getValue().put("device", dev).put("availability_topic", base() + "/status");
            m.publish(e.getKey(), o.toString(), 1, true);
        }
    }

    private String version() {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    // ---------- events ----------

    /** Publish a signed event: event(“match”, “person”, “tau”, “finger”, “right_index”, “score”, 161). */
    void event(String type, Object... kv) {
        long when = System.currentTimeMillis();
        OUT.execute(() -> eventNow(type, when, kv));      // never block the reader thread on the network
    }

    private static final java.util.concurrent.ExecutorService OUT = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void eventNow(String type, long when, Object... kv) {
        String msg;
        try { msg = signed(type, when, kv); } catch (Exception e) { Log.e(TAG, "sign " + type, e); return; }
        Mqtt m = mqtt;
        boolean sent = m != null && m.publish(base() + "/event", msg, 1, false);
        if (sent && (type.equals("match") || type.equals("match_secure"))) m.publish(base() + "/last", msg, 1, true);
        Log.i(TAG, "event " + type + (sent ? "" : " (not sent: " + status + ")"));
    }

    void alert(Object... kv) {
        long when = System.currentTimeMillis();
        OUT.execute(() -> {
            String msg;
            try { msg = signed("duress", when, kv); } catch (Exception e) { Log.e(TAG, "sign duress", e); return; }
            Mqtt m = mqtt;
            if (m != null) m.publish(base() + "/alert", msg, 1, false);
        });
    }

    private String signed(String type, long when, Object... kv) throws Exception {
        keys();
        TreeMap<String, Object> f = new TreeMap<>();
        f.put("event_type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) f.put((String) kv[i], kv[i + 1]);
        f.put("node", node.isEmpty() ? node() : node);
        f.put("ts", when / 1000.0);                       // ms → seconds with 3 decimals, like round(time.time(), 3)
        f.put("ctr", nextCounter());
        f.put("v", 1);
        String body = canonical(f);
        String sig = b64(sign(body.getBytes(StandardCharsets.UTF_8)));
        return body.substring(0, body.length() - 1) + ",\"sig\":\"" + sig + "\"}";
    }

    /**
     * Strictly increasing and never reused: max(last + 1, now in ms). Persisted before use; the clock floor means even a
     * lost counter file (app data wiped) can't make it go backwards, as long as the tablet's clock is right.
     */
    private synchronized long nextCounter() throws Exception {
        File f = new File(ctx.getFilesDir(), "goatprint.ctr");
        long last = 0;
        if (f.exists()) try { last = Long.parseLong(new String(read(f), StandardCharsets.UTF_8).trim()); } catch (NumberFormatException ignored) { }
        long next = Math.max(last + 1, System.currentTimeMillis());
        File tmp = new File(ctx.getFilesDir(), "goatprint.ctr.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(Long.toString(next).getBytes(StandardCharsets.UTF_8)); o.getFD().sync(); }
        if (!tmp.renameTo(f)) throw new Exception("counter rename failed");
        return next;
    }

    // ---------- identity ----------

    private synchronized void keys() throws Exception {
        if (sign != null) return;
        File f = new File(ctx.getFilesDir(), "goatprint.node");
        byte[] key = Goatprint.get(ctx).vaultKey();
        byte[] seed;
        if (f.exists()) {
            byte[] all = read(f);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
            seed = c.doFinal(all, 12, all.length - 12);
        } else {
            seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(seed);
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(iv); o.write(ct); o.getFD().sync(); }
            Log.i(TAG, "new node key");
        }
        sign = new Ed25519PrivateKeyParameters(seed, 0);
        box = boxKey(seed);
    }

    /** X25519 box key = HKDF-SHA256(ikm = Ed25519 seed, no salt, info "goatprint box key v1"). */
    static X25519PrivateKeyParameters boxKey(byte[] seed) {
        return new X25519PrivateKeyParameters(hkdf(seed, null, "goatprint box key v1"), 0);
    }

    byte[] signPub() throws Exception { keys(); return sign.generatePublicKey().getEncoded(); }
    byte[] boxPub() throws Exception { keys(); return box.generatePublicKey().getEncoded(); }

    /** gpnode1:<node>:<ed25519 b64>:<x25519 b64> — paste into `sudo goatprintctl trust <line>` on the enrolling node. */
    String identity() {
        try { return "gpnode1:" + node() + ":" + b64(signPub()) + ":" + b64(boxPub()); }
        catch (Exception e) { return "key error: " + e.getMessage(); }
    }

    private byte[] sign(byte[] msg) { return sign(sign, msg); }

    static byte[] sign(Ed25519PrivateKeyParameters key, byte[] msg) {
        Ed25519Signer s = new Ed25519Signer();
        s.init(true, key);
        s.update(msg, 0, msg.length);
        return s.generateSignature();
    }

    // ---------- accept list ----------

    /** {node: ed25519 pub b64} of peers whose enrollments this tablet takes. */
    Map<String, String> accepted() {
        Map<String, String> out = new TreeMap<>();
        try {
            JSONObject o = new JSONObject(Prefs.of(ctx).get(ACCEPT, "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); out.put(k, o.getString(k)); }
        } catch (JSONException ignored) { }
        return out;
    }

    /** Accept enrollments from the node in this `goatprintctl id` line. Returns its node id. */
    String accept(String line) throws Exception {
        String[] parts = line.trim().split(":");
        if (parts.length != 4 || !parts[0].equals("gpnode1")) throw new Exception("not a node identity (gpnode1:<id>:<sign>:<box>, from `goatprintctl id`)");
        if (!NODE_RE.matcher(parts[1]).matches()) throw new Exception("bad node id");
        if (b64d(parts[2]).length != 32 || b64d(parts[3]).length != 32) throw new Exception("bad key in identity");
        if (parts[1].equals(node())) throw new Exception("that's this tablet's own identity");
        Map<String, String> a = accepted();
        a.put(parts[1], parts[2]);
        Prefs.of(ctx).put(ACCEPT, new JSONObject(a).toString());
        resubscribe();
        return parts[1];
    }

    void unaccept(String peer) {
        Map<String, String> a = accepted();
        a.remove(peer);
        Prefs.of(ctx).put(ACCEPT, new JSONObject(a).toString());
    }

    // ---------- inbox ----------

    @Override public void onMessage(Mqtt m, String topic, byte[] payload, boolean retained) {
        if (payload.length == 0 || !topic.startsWith(base() + "/inbox/")) return;
        try {
            Log.i(TAG, importBundle(new JSONObject(new String(payload, StandardCharsets.UTF_8))));
        } catch (BundleException e) {
            if (e.notAccepted) { Log.w(TAG, "inbox: " + e.getMessage() + " (left on the broker)"); return; }
            Log.w(TAG, "inbox: rejected " + topic + ": " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "inbox: malformed " + topic + ": " + e);
        }
        m.publish(topic, new byte[0], 1, true);
    }

    static final class BundleException extends Exception {
        final boolean notAccepted;
        BundleException(String m) { this(m, false); }
        BundleException(String m, boolean notAccepted) { super(m); this.notAccepted = notAccepted; }
    }

    static final class Finger {
        String finger;
        boolean duress;
        final List<byte[]> templates = new ArrayList<>();
    }

    /** Verify, decrypt and apply one bundle (share.import_bundle). */
    String importBundle(JSONObject b) throws Exception {
        keys();
        List<Finger> fingers = openBundle(b, node(), box, accepted());
        String src = b.getString("from"), person = b.getString("person");
        double ts = b.getDouble("ts");
        JSONObject seen = imports();
        if (ts <= seen.optDouble(src + ":" + person, -1)) throw new BundleException("stale bundle for " + person + " from " + src + " (already have a newer one)");
        String res = Goatprint.get(ctx).mirror(person, src, fingers);
        seen.put(src + ":" + person, ts);
        File f = new File(ctx.getFilesDir(), "goatprint.imports");
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(seen.toString().getBytes(StandardCharsets.UTF_8)); }
        return b.getBoolean("revoke") ? "revoked " + person + "'s fingers from " + src + " (" + res + ")"
                : "imported " + person + " from " + src + " (" + res + ")";
    }

    private JSONObject imports() {
        File f = new File(ctx.getFilesDir(), "goatprint.imports");
        try { return f.exists() ? new JSONObject(new String(read(f), StandardCharsets.UTF_8)) : new JSONObject(); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** share.open_bundle: every check, in order. Returns the fingers (empty for a revoke). */
    static List<Finger> openBundle(JSONObject b, String me, X25519PrivateKeyParameters box, Map<String, String> accept) throws Exception {
        JSONObject body;
        try {
            if (!"goatprint-bundle".equals(b.optString("type")) || !(b.opt("v") instanceof Integer) || b.getInt("v") != 1)
                throw new BundleException("not a goatprint bundle (v1)");
            if (!b.getString("to").equals(me) || !b.getString("to_box").equals(b64(box.generatePublicKey().getEncoded())))
                throw new BundleException("bundle is for node " + b.getString("to") + ", this is " + me);
            String want = accept.get(b.getString("from"));
            if (want == null) throw new BundleException("not accepting enrollments from " + b.getString("from"), true);
            if (!want.equals(b.getString("from_sign"))) throw new BundleException(b.getString("from") + " signed with a different key than the one trusted");
            if (!NAME_RE.matcher(b.getString("person")).matches()) throw new BundleException("bad person name");
            TreeMap<String, Object> all = new TreeMap<>();
            for (Iterator<String> it = b.keys(); it.hasNext(); ) { String k = it.next(); if (!k.equals("sig")) all.put(k, b.get(k)); }
            byte[] msg = canonical(all).getBytes(StandardCharsets.UTF_8), sig = b64d(b.getString("sig"));
            Ed25519Signer v = new Ed25519Signer();
            v.init(false, new Ed25519PublicKeyParameters(b64d(want), 0));
            v.update(msg, 0, msg.length);
            if (!v.verifySignature(sig)) throw new BundleException("bad signature (tampered, or not from that node)");
            byte[] epk = b64d(b.getString("epk")), toBox = b64d(b.getString("to_box"));
            byte[] shared = new byte[32];
            X25519Agreement ag = new X25519Agreement();
            ag.init(box);
            ag.calculateAgreement(new X25519PublicKeyParameters(epk, 0), shared, 0);
            byte[] salt = new byte[epk.length + toBox.length];
            System.arraycopy(epk, 0, salt, 0, epk.length);
            System.arraycopy(toBox, 0, salt, epk.length, toBox.length);
            byte[] key = hkdf(shared, salt, "goatprint bundle v1");
            TreeMap<String, Object> hdr = new TreeMap<>();
            for (String k : HDR_FIELDS) hdr.put(k, b.get(k));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, b64d(b.getString("nonce"))));
            c.updateAAD(canonical(hdr).getBytes(StandardCharsets.UTF_8));
            byte[] pt;
            try { pt = c.doFinal(b64d(b.getString("ct"))); }
            catch (javax.crypto.AEADBadTagException e) { throw new BundleException("cannot decrypt (corrupted)"); }
            body = new JSONObject(new String(pt, StandardCharsets.UTF_8));
        } catch (BundleException e) {
            throw e;
        } catch (JSONException | IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new BundleException("malformed bundle: " + e.getMessage());
        }
        List<Finger> out = new ArrayList<>();
        JSONArray fs = body.getJSONArray("fingers");
        for (int i = 0; i < fs.length(); i++) {
            JSONObject o = fs.getJSONObject(i);
            Finger f = new Finger();
            f.finger = o.getString("finger");
            f.duress = o.optBoolean("duress");
            JSONArray t = o.getJSONArray("templates");
            for (int k = 0; k < t.length(); k++) f.templates.add(b64d(t.getString(k)));
            boolean gpt1 = true;
            for (byte[] tp : f.templates) gpt1 &= tp.length >= 4 && tp[0] == 'G' && tp[1] == 'P' && tp[2] == 'T' && tp[3] == '1';
            if (!FINGER_RE.matcher(f.finger).matches() || f.templates.isEmpty() || f.templates.size() > 64 || !gpt1)
                throw new BundleException("bad finger in bundle");
            out.add(f);
        }
        if (b.getBoolean("revoke") && !out.isEmpty()) throw new BundleException("revoke bundle carries fingers");
        return out;
    }

    // ---------- self-test against goatprint's published vectors (GoatprintTestActivity --ez selftest true) ----------

    static String selfTest(Context c) {
        StringBuilder r = new StringBuilder();
        try {
            byte[] seed = new byte[32];
            for (int i = 0; i < 32; i++) seed[i] = (byte) i;
            Ed25519PrivateKeyParameters k = new Ed25519PrivateKeyParameters(seed, 0);
            String pub = b64(k.generatePublicKey().getEncoded());
            TreeMap<String, Object> ev = new TreeMap<>();
            ev.put("ctr", 42); ev.put("event_type", "match"); ev.put("finger", "right_index"); ev.put("node", "kiosk1");
            ev.put("person", "alice"); ev.put("score", 161); ev.put("ts", 1763600000.5); ev.put("v", 1);
            String body = canonical(ev), sig = b64(sign(k, body.getBytes(StandardCharsets.UTF_8)));
            boolean evOk = pub.equals("A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=")
                    && body.equals("{\"ctr\":42,\"event_type\":\"match\",\"finger\":\"right_index\",\"node\":\"kiosk1\",\"person\":\"alice\",\"score\":161,\"ts\":1763600000.5,\"v\":1}")
                    && sig.equals("iBZ73QYVnSyzh9Xg9tM3JsOISTHbi3dxYPTdRptPmn5Zg4dgT4k3VUBciRKvIGxRcDkgBElhObDxj8eaQWHVDA==");
            r.append("event vector: ").append(evOk ? "PASS" : "FAIL " + body + " " + sig).append('\n');
            String box = b64(boxKey(seed).generatePublicKey().getEncoded());
            r.append("box key: ").append(box.equals("CDqpF3CCS4yxQW6xjtfJX3oYSIPOguhxpnY/JJvPOHk=") ? "PASS" : "FAIL " + box).append('\n');
            r.append("float: ").append(pyFloat(1763600000.0).equals("1763600000.0") && pyFloat(1763600123.457).equals("1763600123.457") ? "PASS" : "FAIL").append('\n');
            JSONObject v;
            try (java.io.InputStream in = c.getAssets().open("goatprint-bundle-v1.json")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                v = new JSONObject(bo.toString("UTF-8"));
            }
            Map<String, String> accept = new TreeMap<>();
            accept.put("desk", "Kay64UG8yvCyLhqU000LxzYeUm0L/hLIl5S8kyKWbdc=");
            List<Finger> got = openBundle(v.getJSONObject("bundle"), "kiosk", boxKey(seed), accept);
            JSONObject want = v.getJSONObject("expect").getJSONArray("fingers").getJSONObject(0);
            String hex = want.getJSONArray("templates_hex").getString(0);
            StringBuilder h = new StringBuilder();
            for (byte x : got.get(0).templates.get(0)) h.append(String.format(Locale.ROOT, "%02x", x));
            boolean bOk = got.size() == 1 && got.get(0).finger.equals(want.getString("finger")) && !got.get(0).duress && h.toString().equals(hex);
            r.append("bundle vector: ").append(bOk ? "PASS" : "FAIL " + h).append('\n');
            JSONObject bad = new JSONObject(v.getJSONObject("bundle").toString()).put("person", "mallory");
            try { openBundle(bad, "kiosk", boxKey(seed), accept); r.append("tamper: FAIL (accepted)\n"); }
            catch (BundleException e) { r.append("tamper: PASS (").append(e.getMessage()).append(")\n"); }
            try { openBundle(v.getJSONObject("bundle"), "kiosk", boxKey(seed), new TreeMap<>()); r.append("untrusted: FAIL\n"); }
            catch (BundleException e) { r.append("untrusted: ").append(e.notAccepted ? "PASS" : "FAIL").append(" (").append(e.getMessage()).append(")\n"); }
        } catch (Exception e) {
            r.append("ERROR ").append(e);
        }
        return r.toString();
    }

    // ---------- helpers ----------

    static byte[] hkdf(byte[] ikm, byte[] salt, String info) {
        HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
        g.init(new HKDFParameters(ikm, salt, info.getBytes(StandardCharsets.UTF_8)));
        byte[] out = new byte[32];
        g.generateBytes(out, 0, 32);
        return out;
    }

    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }

    static byte[] b64d(String s) {
        if (!s.matches("^[A-Za-z0-9+/]*={0,2}$") || s.length() % 4 != 0) throw new IllegalArgumentException("bad base64");
        return Base64.decode(s, Base64.NO_WRAP);
    }

    /** Python json.dumps(x, sort_keys=True, separators=(",", ":")) — ensure_ascii, repr() floats. */
    static String canonical(Object x) {
        StringBuilder b = new StringBuilder();
        write(b, x);
        return b.toString();
    }

    private static void write(StringBuilder b, Object x) {
        if (x == null || x == JSONObject.NULL) b.append("null");
        else if (x instanceof Boolean) b.append(((Boolean) x) ? "true" : "false");
        else if (x instanceof Integer || x instanceof Long || x instanceof Short || x instanceof Byte) b.append(x);
        else if (x instanceof Double || x instanceof Float) b.append(pyFloat(((Number) x).doubleValue()));
        else if (x instanceof String) str(b, (String) x);
        else if (x instanceof JSONObject) {
            JSONObject o = (JSONObject) x;
            TreeMap<String, Object> m = new TreeMap<>();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.opt(k)); }
            write(b, m);
        } else if (x instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : new TreeMap<>((Map<?, ?>) x).entrySet()) {
                if (!first) b.append(',');
                first = false;
                str(b, (String) e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (x instanceof JSONArray) {
            JSONArray a = (JSONArray) x;
            b.append('[');
            for (int i = 0; i < a.length(); i++) { if (i > 0) b.append(','); write(b, a.opt(i)); }
            b.append(']');
        } else if (x instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) x) { if (!first) b.append(','); first = false; write(b, o); }
            b.append(']');
        } else throw new IllegalArgumentException("can't serialise " + x.getClass());
    }

    /** Python repr(float) for the values events and bundles carry (timestamps, scores). */
    static String pyFloat(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("non-finite");
        double a = Math.abs(d);
        if (a != 0 && (a < 1e-4 || a >= 1e16)) throw new IllegalArgumentException("float out of range for canonical JSON");
        String s = new BigDecimal(Double.toString(d)).toPlainString();
        if (s.contains(".")) { s = s.replaceAll("0+$", ""); if (s.endsWith(".")) s += "0"; }
        else s += ".0";
        return s;
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20 || c > 0x7E && c != 0x7F) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    private static byte[] read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
        }
        return b;
    }
}
