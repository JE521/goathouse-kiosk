package org.goathouse.kiosk;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import java.nio.ByteBuffer;

/**
 * Suprema BioMini Slim (16d1:0407) driver, ported from goatprint's core/src/biomini.c (open protocol, no vendor SDK).
 * Everything but the frame is a vendor control transfer; frames arrive on bulk IN 0x82 as one 387072-byte read.
 * Android before 9 caps one bulk call at 16 KB and the sensor has no flow control, so the whole frame is queued as
 * 16 KB UsbRequests before the frame is requested (what libusb does on Linux). Not thread-safe: one thread only.
 */
final class GoatprintReader {
    static final int VID = 0x16d1, PID = 0x0407;
    static final int W = 896, H = 432, FRAME = W * H;
    private static final int CHUNK = 16384, CHUNKS = (FRAME + CHUNK - 1) / CHUNK;
    private static final int TIMEOUT = 2000;
    private static final int[] EXPOSURE = {160, 999, 123};   // one value per two frames (slot 0 + slot 1)

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint in;
    private final UsbRequest[] reqs = new UsbRequest[CHUNKS];
    private final ByteBuffer[] bufs = new ByteBuffer[CHUNKS];
    private int slot, frameNo;
    /** Last time a transfer finished; the engine's watchdog closes a reader that stops moving (requestWait has no timeout before API 26). */
    volatile long lastActivity = System.currentTimeMillis();
    private volatile boolean closed;
    private boolean lightOn;
    String serial = "", info = "";

    static UsbDevice find(UsbManager um) {
        for (UsbDevice d : um.getDeviceList().values())
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        return null;
    }

    GoatprintReader(UsbManager um, UsbDevice d) throws Exception {
        intf = d.getInterface(0);
        UsbEndpoint ep = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == UsbConstants.USB_DIR_IN) ep = e;
        }
        if (ep == null) throw new Exception("no bulk IN endpoint");
        in = ep;
        conn = um.openDevice(d);
        if (conn == null) throw new Exception("open failed (no USB permission?)");
        if (!conn.claimInterface(intf, true)) { conn.close(); throw new Exception("claim failed (busy)"); }
        for (int i = 0; i < CHUNKS; i++) {
            reqs[i] = new UsbRequest();
            if (!reqs[i].initialize(conn, in)) throw new Exception("request init failed");
            reqs[i].setClientData(i);
            bufs[i] = ByteBuffer.allocate(Math.min(CHUNK, FRAME - i * CHUNK));
        }
        byte[] st = ctrlIn(0xd0, 1);
        if (st[0] != 1) throw new Exception("sensor not ready (status " + st[0] + ")");
        byte[] c5 = ctrlIn(0xc5, 24);
        info = hex(c5);
        byte[] sn = ctrlIn(0xdb, 32);
        StringBuilder s = new StringBuilder();
        for (byte b : sn) if (b >= 0x20 && b < 0x7f) s.append((char) b);
        serial = s.toString().trim();
    }

    /** One raw frame (896x432 grey, row-major), cycling the exposures. The light is on while grabbing. */
    byte[] grab() throws Exception { return grab(EXPOSURE[(frameNo / 2) % EXPOSURE.length]); }

    /** One raw frame at {@code ex} (applied on slot 0, like the C driver). */
    byte[] grab(int ex) throws Exception {
        if (slot == 0) {
            ctrlOut(0xc0, new byte[]{0x02, 0x66, 0x00, (byte) (ex >> 8), (byte) ex, 0x00, 0x04, 0x00, 0x20, 0x04, 0x00, 0x08, 0x03, (byte) 0xff, 0x00});
        }
        ctrlOut(0xc2, new byte[]{0x04});
        lightOn = true;
        // queue the whole frame first: the sensor streams without flow control
        for (int i = 0; i < CHUNKS; i++) {
            bufs[i].clear();
            if (!reqs[i].queue(bufs[i], bufs[i].capacity())) { cancelAll(); throw new Exception("queue failed"); }
        }
        byte[] frame = new byte[FRAME];
        try {
            ctrlIn(slot == 0 ? 0xe1 : 0xe2, 6);
            for (int done = 0; done < CHUNKS; done++) {
                UsbRequest r = conn.requestWait();
                if (r == null || closed) throw new Exception("frame read failed");
                int i = (Integer) r.getClientData();
                ByteBuffer b = bufs[i];
                b.rewind();
                b.get(frame, i * CHUNK, b.capacity());
            }
        } catch (Exception e) {
            cancelAll();
            throw e;
        } finally {
            try { ctrlOut(0xef, new byte[]{(byte) slot}); } catch (Exception ignored) { }
            slot ^= 1;
            frameNo++;
        }
        lastActivity = System.currentTimeMillis();
        return frame;
    }

    void light(boolean on) throws Exception {
        if (on == lightOn) return;
        ctrlOut(0xc2, new byte[]{(byte) (on ? 0x04 : 0x00)});
        lightOn = on;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try { ctrlOut(0xc2, new byte[]{0x00}); } catch (Exception ignored) { }   // light off, whatever we think its state is
        cancelAll();
        for (UsbRequest r : reqs) if (r != null) r.close();
        conn.releaseInterface(intf);
        conn.close();
    }

    static final int CONTRAST_ON = 6, CONTRAST_OFF = 5;

    /** Ridge contrast: stddev of the centre region, truncated like frame_contrast() in biomini.c. Empty ≤ 3, finger ≥ 7. */
    static int contrast(byte[] f) {
        long n = 0; double sum = 0, sq = 0;
        for (int y = H / 5; y < H * 4 / 5; y += 2) for (int x = W / 4; x < W * 3 / 4; x += 2) {
            int v = f[y * W + x] & 0xff;
            sum += v; sq += (double) v * v; n++;
        }
        double m = sum / n;
        return (int) Math.sqrt(Math.max(0, sq / n - m * m));
    }

    /**
     * gp_biomini_capture_n: wait up to timeoutMs for a finger, skip the first finger frame, keep the best {@code max}
     * frames by contrast, stop after 10 finger frames or when the finger leaves. Best first; empty list on timeout.
     */
    java.util.List<byte[]> capture(long timeoutMs, int max) throws Exception {
        java.util.List<byte[]> keep = new java.util.ArrayList<>();
        java.util.List<Integer> kc = new java.util.ArrayList<>();
        long end = System.currentTimeMillis() + timeoutMs;
        int on = 0, i = 0;
        light(true);
        while (System.currentTimeMillis() < end) {
            byte[] f = grab(EXPOSURE[(i++ / 2) % EXPOSURE.length]);
            int c = contrast(f);
            if (c < CONTRAST_ON) {
                if (on > 0 && !keep.isEmpty()) break;
                on = 0;
                continue;
            }
            if (++on < 2) continue;
            if (keep.size() < max) { keep.add(f); kc.add(c); }
            else {
                int worst = 0;
                for (int k = 1; k < kc.size(); k++) if (kc.get(k) < kc.get(worst)) worst = k;
                if (c > kc.get(worst)) { keep.set(worst, f); kc.set(worst, c); }
            }
            if (on >= 10) break;
        }
        // best first (stable selection sort, same order as the C code)
        for (int a = 0; a < keep.size(); a++) for (int b = a + 1; b < keep.size(); b++)
            if (kc.get(b) > kc.get(a)) {
                int t = kc.get(a); kc.set(a, kc.get(b)); kc.set(b, t);
                byte[] tf = keep.get(a); keep.set(a, keep.get(b)); keep.set(b, tf);
            }
        return keep;
    }

    /** Lifted = two blank frames in a row. False on timeout. */
    boolean waitLift(long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        int empty = 0, i = 0;
        while (System.currentTimeMillis() < end) {
            empty = contrast(grab(EXPOSURE[(i++ / 2) % EXPOSURE.length])) < CONTRAST_OFF ? empty + 1 : 0;
            if (empty >= 2) return true;
        }
        return false;
    }

    /** Blink: off for halfMs, then on (grabbing so it's really lit) for halfMs; optionally end dark. */
    void flash(int times, long halfMs, boolean endOff) throws Exception {
        for (int i = 0; i < times; i++) {
            light(false);
            Thread.sleep(halfMs);
            long until = System.currentTimeMillis() + halfMs;
            do { grab(EXPOSURE[0]); } while (System.currentTimeMillis() < until);
        }
        if (endOff) light(false);
    }

    private void cancelAll() {
        for (UsbRequest r : reqs) if (r != null) try { r.cancel(); } catch (Exception ignored) { }
    }

    private byte[] ctrlIn(int req, int len) throws Exception {
        byte[] b = new byte[len];
        int r = conn.controlTransfer(0xC0, req, 0x0002, 0x0008, b, len, TIMEOUT);
        if (r < 0) throw new Exception(String.format("control IN %02x failed", req));
        return b;
    }

    private void ctrlOut(int req, byte[] data) throws Exception {
        int r = conn.controlTransfer(0x40, req, 0x0001, 0x0000, data, data.length, TIMEOUT);
        if (r < 0) throw new Exception(String.format("control OUT %02x failed", req));
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
}
