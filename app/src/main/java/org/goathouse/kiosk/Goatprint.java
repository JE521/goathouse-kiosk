package org.goathouse.kiosk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/**
 * goatprint node on the tablet: one thread owns the reader and either identifies (capture → fused NBIS score against
 * every enrolled finger → open that person's brief) or runs an enrollment job. Same behaviour and numbers as the Linux
 * daemon (linux/goatprintd/daemon.py): 5 probes, sum of the top 3 template scores. Thresholds are the upright-enrolment
 * ones (genuine 160–270, worst stranger 67): match 70, approve/learn 100, up to 16 templates. A kiosk identifies 1:N
 * across the household, so the winner must also beat the best other person by 30 or it's a miss, 5 misses in 60 s lock for 60 s. Templates only, never images, encrypted at rest.
 */
final class Goatprint {
    private static final String TAG = "Goatprint";
    static final int PROBES = 5, FUSE_TOP = 3, THRESHOLD = 70, SECURE = 100, APPROVE = 100, ADAPT_AT = 100, MARGIN = 30, MAX_TEMPLATES = 16,
            SAMPLES = 8, MIN_MINUTIAE = 15, LOCK_FAILS = 5;
    static final long LOCK_WINDOW_MS = 60_000, LOCK_MS = 60_000;
    static final String[] FINGERS = {"right_index", "right_middle", "right_thumb", "right_ring", "left_index", "left_middle", "left_thumb", "left_ring"};
    private static final String[] HINTS = {"centre", "centre", "a bit higher, fingertip", "a bit higher, fingertip",
            "rolled a bit left or right", "rolled a bit left or right", "a bit lower, flatter", "a bit lower, flatter"};
    private static final String PERMISSION = "org.goathouse.kiosk.GOATPRINT_USB";

    interface Listener { void onGoatprint(String status); }
    interface Enrollment { void say(String msg, int got, boolean done, boolean failed); }

    static final class Entry {
        String name, finger;
        boolean admin;
        final List<byte[]> templates = new ArrayList<>();
    }

    private static Goatprint instance;
    static synchronized Goatprint get(Context c) {
        if (instance == null) instance = new Goatprint(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Long> failures = new ArrayList<>();
    private volatile List<Entry> gallery = new ArrayList<>();
    private volatile String status = "Starting";
    private volatile boolean running, attached;
    private volatile Job job;
    private long lockedUntil, askedPermission;
    private Thread thread;

    private static final class Job {
        String name, finger;
        Enrollment cb;
        volatile boolean cancelled;
    }

    private Goatprint(Context c) { ctx = c; }

    String status() { return status; }
    boolean attached() { return attached; }
    List<Entry> entries() { return Collections.unmodifiableList(gallery); }
    boolean hasAdmin() { for (Entry e : gallery) if (e.admin) return true; return false; }
    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "goatprint");
        thread.setDaemon(true);
        thread.start();
    }

    /** Release the reader (bring-up screen wants it); start() resumes. */
    void stop() {
        Thread t;
        synchronized (this) { running = false; t = thread; thread = null; }
        if (t != null) try { t.join(3000); } catch (InterruptedException ignored) { }
    }

    /** Queue an enrollment; it runs on the reader thread and reports through cb on the main thread. */
    void enroll(String name, String finger, Enrollment cb) {
        Job j = new Job();
        j.name = name; j.finger = finger; j.cb = cb;
        Job old = job;
        if (old != null) old.cancelled = true;
        job = j;
        start();
    }

    void cancelEnroll() { Job j = job; if (j != null) j.cancelled = true; }

    synchronized void delete(String name, String finger) {
        List<Entry> g = new ArrayList<>(gallery);
        for (int i = g.size() - 1; i >= 0; i--) if (g.get(i).name.equals(name) && g.get(i).finger.equals(finger)) g.remove(i);
        save(g);
    }

    // ---------- reader thread ----------

    private void loop() {
        gallery = load();
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        while (running && Thread.currentThread() == thread) {
            UsbDevice d = GoatprintReader.find(um);
            attached = d != null;
            if (d == null) { setStatus("No reader"); failJob("No fingerprint reader attached"); sleep(3000); continue; }
            if (!um.hasPermission(d)) {
                setStatus("Waiting for USB permission");
                if (System.currentTimeMillis() - askedPermission > 60_000) {
                    askedPermission = System.currentTimeMillis();
                    main.post(() -> um.requestPermission(d, PendingIntent.getBroadcast(ctx, 0,
                            new Intent(PERMISSION).setPackage(ctx.getPackageName()), PendingIntent.FLAG_MUTABLE)));
                }
                sleep(2000);
                continue;
            }
            GoatprintReader r = null;
            try {
                r = new GoatprintReader(um, d);
                Log.i(TAG, "reader " + r.serial + " open, " + gallery.size() + " fingers enrolled");
                setStatus(gallery.isEmpty() ? "Ready · nobody enrolled yet" : "Ready");
                r.light(true);
                while (running && Thread.currentThread() == thread) {
                    Job j = job;
                    if (j != null) {
                        try { enrollNow(r, j); } finally { if (job == j) job = null; }
                        setStatus("Ready");
                    } else identifyOnce(r);
                }
            } catch (Exception e) {
                Log.w(TAG, "reader: " + e);
                setStatus("Reader error: " + e.getMessage());
                failJob("Reader error: " + e.getMessage());
                sleep(2000);
            } finally {
                if (r != null) r.close();
            }
        }
    }

    private void identifyOnce(GoatprintReader r) throws Exception {
        if (lockedUntil != 0) {
            if (System.currentTimeMillis() < lockedUntil) { Thread.sleep(500); return; }
            lockedUntil = 0;
            r.light(true);
            setStatus("Ready");
        }
        List<byte[]> probes = probes(r, 1000, PROBES);
        if (probes.isEmpty()) return;
        if (gallery.isEmpty()) {                 // first touch on a fresh reader: go straight to enrolment
            Log.i(TAG, "touch with nobody enrolled: opening enrolment");
            main.post(() -> GoatprintEnrollActivity.open(ctx, null));
            r.waitLift(5000);
            return;
        }
        Match m = gallery.isEmpty() ? new Match() : bestMatch(probes, gallery);
        if (m.entry != null && m.score >= THRESHOLD && m.score - m.runnerUp >= MARGIN) {
            failures.clear();
            Log.i(TAG, (m.score >= SECURE ? "match_secure " : "match ") + m.entry.name + " " + m.entry.finger + " score " + m.score);
            Entry e = m.entry;
            int score = m.score;
            main.post(() -> {
                for (Listener l : listeners) l.onGoatprint("match " + e.name);
                Intent i = new Intent(ctx, BriefActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("person", "person." + e.name).putExtra("finger", e.finger).putExtra("score", score);
                ctx.startActivity(i);
            });
            adapt(e, m.probe, score);
        } else {
            long now = System.currentTimeMillis();
            List<Long> keep = new ArrayList<>();
            for (long t : failures) if (now - t < LOCK_WINDOW_MS) keep.add(t);
            keep.add(now);
            failures.clear();
            failures.addAll(keep);
            Log.i(TAG, "no_match score " + Math.max(m.score, 0) + " runner-up " + Math.max(m.runnerUp, 0));
            if (failures.size() >= LOCK_FAILS) {
                lockedUntil = now + LOCK_MS;
                failures.clear();
                setStatus("Locked for a minute");
                r.flash(6, 80, true);
                return;
            }
            r.flash(3, 150, false);
        }
        r.waitLift(5000);
    }

    private void enrollNow(GoatprintReader r, Job j) throws Exception {
        boolean first = !hasAdmin();
        if (!first) {
            say(j, "Admin: place YOUR finger to approve", 0, false, false);
            List<byte[]> probes = probes(r, 30000, 3);
            Match m = probes.isEmpty() ? new Match() : bestMatch(probes, gallery);
            if (m.entry == null || m.score < APPROVE || !isAdmin(m.entry.name)) {
                say(j, "Not approved" + (m.score >= 0 ? " (score " + m.score + ")" : ""), 0, true, true);
                return;
            }
            say(j, "Approved by " + m.entry.name + " · lift", 0, false, false);
            r.waitLift(3000);
        }
        List<byte[]> got = new ArrayList<>();
        while (got.size() < SAMPLES) {
            if (j.cancelled) { say(j, "Cancelled", got.size(), true, true); return; }
            say(j, "Sample " + (got.size() + 1) + " of " + SAMPLES + " · place finger, " + HINTS[got.size() % 8], got.size(), false, false);
            List<byte[]> p = probes(r, 30000, 1);
            if (j.cancelled) continue;
            if (p.isEmpty()) { say(j, "Timed out waiting for a finger", got.size(), true, true); return; }
            int n = GoatprintCore.minutiae(p.get(0));
            if (n < MIN_MINUTIAE) say(j, "Only " + n + " points, again · lift", got.size(), false, false);
            else { got.add(p.get(0)); say(j, "Got it (" + n + " points) · lift", got.size(), false, false); }
            r.waitLift(3000);
        }
        synchronized (this) {
            List<Entry> g = new ArrayList<>(gallery);
            for (int i = g.size() - 1; i >= 0; i--) if (g.get(i).name.equals(j.name) && g.get(i).finger.equals(j.finger)) g.remove(i);
            Entry e = new Entry();
            e.name = j.name; e.finger = j.finger; e.admin = first || isAdmin(j.name);
            e.templates.addAll(got);
            g.add(e);
            save(g);
        }
        Log.i(TAG, "enrolled " + j.name + " " + j.finger);
        say(j, "Enrolled " + j.name + " " + j.finger.replace('_', ' ') + (first ? " (admin)" : ""), SAMPLES, true, false);
    }

    /** Capture, then normalise + extract each kept frame into a GPT1 template (frames dropped right after). */
    private static List<byte[]> probes(GoatprintReader r, long timeoutMs, int max) throws Exception {
        List<byte[]> out = new ArrayList<>();
        for (byte[] f : r.capture(timeoutMs, max)) {
            byte[] img = GoatprintCore.normalize(f);
            byte[] t = img == null ? null : GoatprintCore.extract(img);
            if (t != null) out.add(t);
        }
        return out;
    }

    private static final class Match { Entry entry; int score = -1, runnerUp = -1; byte[] probe; }

    /**
     * daemon.py best_match / score_entry: per finger, best probe's sum of its top-3 template scores. runnerUp is the
     * best score of anyone other than the winner (for the 1:N margin).
     */
    private static Match bestMatch(List<byte[]> probes, List<Entry> entries) {
        Match best = new Match();
        java.util.Map<String, Integer> perPerson = new java.util.HashMap<>();
        for (Entry e : entries) {
            for (byte[] p : probes) {
                List<Integer> sc = new ArrayList<>();
                for (byte[] g : e.templates) sc.add(GoatprintCore.score(p, g));
                Collections.sort(sc, Collections.reverseOrder());
                int fused = 0;
                for (int k = 0; k < Math.min(FUSE_TOP, sc.size()); k++) fused += sc.get(k);
                if (fused > best.score) { best.score = fused; best.entry = e; best.probe = p; }
                Integer was = perPerson.get(e.name);
                if (was == null || fused > was) perPerson.put(e.name, fused);
            }
        }
        if (best.entry != null) for (java.util.Map.Entry<String, Integer> pe : perPerson.entrySet())
            if (!pe.getKey().equals(best.entry.name) && pe.getValue() > best.runnerUp) best.runnerUp = pe.getValue();
        return best;
    }

    /** Keep a confident scan as an extra sample (never dropping the 8 enrolled ones). */
    private synchronized void adapt(Entry e, byte[] probe, int score) {
        if (probe == null || score < ADAPT_AT) return;
        for (byte[] g : e.templates) if (GoatprintCore.score(probe, g) >= 150) return;
        List<byte[]> enrolled = new ArrayList<>(e.templates.subList(0, Math.min(SAMPLES, e.templates.size())));
        List<byte[]> learned = new ArrayList<>(e.templates.subList(enrolled.size(), e.templates.size()));
        learned.add(probe);
        while (enrolled.size() + learned.size() > MAX_TEMPLATES) learned.remove(0);
        e.templates.clear();
        e.templates.addAll(enrolled);
        e.templates.addAll(learned);
        save(new ArrayList<>(gallery));
        Log.i(TAG, "learned a sample for " + e.name + " " + e.finger + " (" + e.templates.size() + ")");
    }

    private boolean isAdmin(String name) { for (Entry e : gallery) if (e.admin && e.name.equals(name)) return true; return false; }

    private void say(Job j, String msg, int got, boolean done, boolean failed) {
        setStatus(msg);
        main.post(() -> j.cb.say(msg, got, done, failed));
    }

    private void failJob(String why) {
        Job j = job;
        if (j != null) { job = null; say(j, why, 0, true, true); }
    }

    private void setStatus(String s) {
        status = s;
        main.post(() -> { for (Listener l : listeners) l.onGoatprint(s); });
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    // ---------- vault: AES-256-GCM file, key wrapped by an Android Keystore RSA key ----------

    private File vaultFile() { return new File(ctx.getFilesDir(), "goatprint.vault"); }

    private List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        File f = vaultFile();
        if (!f.exists()) return out;
        try {
            byte[] all = read(f);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, all, 0, 12));
            JSONArray a = new JSONArray(new String(c.doFinal(all, 12, all.length - 12), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.name = o.getString("name"); e.finger = o.getString("finger"); e.admin = o.optBoolean("admin");
                JSONArray t = o.getJSONArray("templates");
                for (int k = 0; k < t.length(); k++) e.templates.add(Base64.decode(t.getString(k), Base64.NO_WRAP));
                out.add(e);
            }
        } catch (Exception e) {
            Log.e(TAG, "vault unreadable: " + e);
        }
        return out;
    }

    private void save(List<Entry> g) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : g) {
                JSONArray t = new JSONArray();
                for (byte[] b : e.templates) t.put(Base64.encodeToString(b, Base64.NO_WRAP));
                a.put(new JSONObject().put("name", e.name).put("finger", e.finger).put("admin", e.admin).put("templates", t));
            }
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(a.toString().getBytes("UTF-8"));
            File tmp = new File(ctx.getFilesDir(), "goatprint.vault.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(iv); o.write(ct); }
            if (!tmp.renameTo(vaultFile())) throw new Exception("rename failed");
            gallery = g;
        } catch (Exception e) {
            Log.e(TAG, "vault save failed: " + e);
        }
    }

    private byte[] aesKey;

    /** The vault key: random AES-256, stored wrapped by a Keystore RSA key (Android 5 has no Keystore AES). */
    private synchronized byte[] key() throws Exception {
        if (aesKey != null) return aesKey;
        File kf = new File(ctx.getFilesDir(), "goatprint.key");
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        String alias = "goatprint-vault";
        if (!ks.containsAlias(alias)) {
            Calendar from = Calendar.getInstance(), to = Calendar.getInstance();
            to.add(Calendar.YEAR, 30);
            @SuppressWarnings("deprecation")
            KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(ctx).setAlias(alias)
                    .setSubject(new X500Principal("CN=goatprint")).setSerialNumber(BigInteger.ONE)
                    .setStartDate(from.getTime()).setEndDate(to.getTime()).build();
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            kpg.initialize(spec);
            kpg.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry pk = (KeyStore.PrivateKeyEntry) ks.getEntry(alias, null);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        if (kf.exists()) {
            rsa.init(Cipher.DECRYPT_MODE, pk.getPrivateKey());
            aesKey = rsa.doFinal(read(kf));
        } else {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            rsa.init(Cipher.ENCRYPT_MODE, pk.getCertificate().getPublicKey());
            try (FileOutputStream o = new FileOutputStream(kf)) { o.write(rsa.doFinal(k)); }
            aesKey = k;
        }
        return aesKey;
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
