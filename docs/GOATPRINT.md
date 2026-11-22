# Goatprint: fingerprints on the tablet

A USB fingerprint reader turns a hallway tablet into a personal check-in: touch the reader and *your* brief opens
(greeting, today's events, your tasks, who's home). No vendor SDK is involved.

## Hardware

**Suprema BioMini Slim (SFU-S20)**, USB `16d1:0407`. It's an optical sensor, bus-powered (~100 mA). Plug it in through an
OTG adapter; use a powered OTG hub if the tablet runs on its battery. Sibling ids `0x0408` and `0x1027` are untested.

## Driver (Java, `GoatprintReader.java`)

The open protocol was reverse-engineered in the goatprint project:

- Everything except the image is a **vendor control transfer**. Requests are `0xC0`/`0x40` with
  `wValue/wIndex` `0x0002/0x0008` (IN) and `0x0001/0x0000` (OUT).
- Open: `d0` status (1 byte, `01` = ready), `c5` info (24 bytes), `db` serial (32 bytes ASCII).
- Per frame: `c0` sensor config with the exposure (slot 0 only), `c2 04` sensor on, `e1`/`e2` request a slot,
  then **one 387,072-byte bulk IN read** on `0x82`, then `ef <slot>` to release. Slots alternate; exposures cycle
  160 / 999 / 123.
- The frame is 896×432 8-bit grey, about 15 fps. The sensor streams with no flow control. Android before 9 caps
  `bulkTransfer` at 16 KB, so the driver queues all 24 `UsbRequest`s **before** asking for the frame.
- Finger presence is the std-dev of the centre region (finger ≥ 6, lifted < 5 twice in a row). Don't use the
  device's own finger metric: it lags.
- The light is `c2 04` on and `c2 00` off. It blinks three times on a miss.

## Matching (C over JNI, `app/src/main/cpp/`)

The same code as the Linux goatprint daemon, so templates and scores match:

1. Normalise: rotate 90° counter-clockwise and resample the non-square pixels (×0.698 / ×0.858) to a 352×608
   500 dpi image.
2. Extract minutiae with NIST **MINDTCT** into a "GPT1" template (≤ 150 minutiae, 8 bytes each).
3. Score with NIST **BOZORTH3**.

The decision rule (`Goatprint.java`), tuned for 1:N identification across a household:

- Take the 5 sharpest frames of one touch. Per enrolled finger, score each probe against every template and sum
  the top 3. The best probe gives that finger's score.
- **Match ≥ 70**, and the winner must beat the best *other person* by **≥ 30**, or it counts as a miss. That
  stops one person's tap opening someone else's brief.
- Learning: a match ≥ 100 adds the scan as an extra template, up to 16. The 8 enrolled samples are never dropped.
- Lockout: 5 misses in 60 s lock the reader for 60 s.

Genuine upright scores land around 160–270; the worst stranger seen scored 67.

## Enrolment (on the tablet)

**Settings → Fingerprints → Add a finger**, **Add a finger** on a personal brief, or simply the first touch on a
reader with nobody enrolled. Pick the person and the finger, then give 8 touches with a placement hint for each.
Keep the finger **upright and flat, tip toward the top**; sideways enrolments only match sideways. Samples with
fewer than 15 minutiae are retried.

The **first** finger enrolled becomes the admin. Every later enrolment needs an admin finger (score ≥ 100) to
approve it, so a passer-by can't add themselves.

## Privacy

- Only templates are stored (minutiae, not images), AES-256-GCM encrypted in app-private storage. The key is
  wrapped by an Android Keystore RSA key (Android 5 has no Keystore AES).
- Images never leave the tablet. Templates leave only as end-to-end encrypted share bundles, and this app only
  receives those (it never sends its own enrolments anywhere).
- There's no liveness detection. Treat a match as "someone with this print": fine for greetings and lights, not for
  outside doors.

## On the network (`GoatprintNode.java`, `Mqtt.java`)

The tablet is a goatprint node like the Linux daemon (goatprint `docs/ANDROID.md` §5b and §6):

- **Identity.** An Ed25519 key, its seed sealed with the vault key, so it survives app updates but not an uninstall
  or a data wipe. The X25519 "box" key is derived from it (HKDF-SHA256, info `goatprint box key v1`).
  Settings → Fingerprints → *This tablet's identity* shows the `gpnode1:<node>:<ed25519>:<x25519>` line and a QR code.
- **Events.** `match`, `match_secure`, `no_match`, `lockout`, `lockout_end` and `duress` alerts go to
  `goatprint/<node>/event` (and `/last`, `/alert`). They're signed over canonical JSON exactly like `daemon.py`.
  `ctr` is `max(last + 1, now in ms)`, persisted before use, so it never repeats even after a data wipe.
  `ts` must be within 300 s of Home Assistant's clock, so keep the tablet on NTP.
  HA discovery and the retained `goatprint/<node>/pubkey` are published on connect.
- **Enroll once, use everywhere.** Retained bundles on `goatprint/<node>/inbox/<sender>/<person>` from accepted
  nodes are checked like `share.py` (addressee, sender key, signature, AES-GCM, sanity, newer `ts`), mirrored into
  the vault, and then cleared. Imported fingers show "from <node>". They never overwrite a finger enrolled here,
  never get admin, and can't approve enrolments.
- **MQTT.** A small built-in MQTT 3.1.1 client over TLS. It trusts the system CAs plus the bundled ISRG Root X1/X2
  (`res/raw/isrg_roots.pem`), because Android 5 to 7.0 don't know Let's Encrypt, and it checks the hostname.
- **Setup** (the phone setup page, *Fingerprints* section):
  - broker host (must match the certificate) and port 8883
  - node id (default `kiosk-<layout>`)
  - user (default `goatprint-<node>`) and password, from goatprint's `examples/mosquitto/goatprint-add-node.sh`;
    the ACL needs `topic read goatprint/<node>/inbox/#`
  - the enrolling node's `goatprintctl id` to accept
- **Self-test.** Settings → Fingerprints → *Self-test* checks signing, the box key and bundle import against
  goatprint's published test vectors (`assets/goatprint-bundle-v1.json`).
