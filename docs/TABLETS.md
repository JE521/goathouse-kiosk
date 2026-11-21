# Tablets

## Tested

| Tablet | SoC / RAM | Android | Notes |
|---|---|---|---|
| Samsung Galaxy Tab 4 10.1 (SM-T537A, AT&T) | Snapdragon 400, 1.35 GB | 5.1.1 | Main target. Rooted (see below), debloated, auto "Lite" mode |
| Pivot PVT-R78 (resort tablet) | Allwinner A64, 1 GB | 7.1.1 | Works; slowest of the bunch |
| "T804" kitchen tablet (resort tablet) | Allwinner A133, 2 GB | 12 (32-bit userspace) | Fastest; needs `startService` retries for the speaker |

Anything Android 5.0+ with Wi-Fi should work. Under ~1.6 GB of RAM the app picks **Lite** mode automatically
(fewer animations); Settings → Performance overrides it.

## Old-Android traps (all handled in the code, listed here so you don't undo them)

- **TLS:** Android < 7.1.1 doesn't trust ISRG Root X1, so talk to HA/MA/Jellyfin over LAN `http://`. Cleartext is
  allowed in `res/xml/network.xml`.
- **X25519** isn't in the platform before Android 12/13; `Noise.java` falls back to its own implementation.
- **Media:** Android 5's `NuCachedSource2` aborts on HTTP streams with no `Content-Length`, hence `StreamRelay`.
  Its player also probes far offsets; keep video as a continuous TS transcode, not HLS (Jellyfin rejects
  `StartTimeTicks` on HLS).
- **Fonts:** the variable Outfit renders Thin before Android 8, so static weights 200–700 are bundled.
- **USB bulk:** before Android 9, `bulkTransfer` caps at 16 KB per call; the fingerprint driver queues `UsbRequest`s.
- **Foreground services:** typed `startForeground` is API 29+; API 34 needs `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.

## Debloat

Without root, `pm disable-user --user 0 <pkg>` and `pm hide <pkg>` (needs adb shell) remove most vendor apps. Keep a
list of what you disabled so you can undo it (`pm enable`). Device-admin apps (Find My Device, kiosk browsers) have
to lose their admin first.

## Rooting the SM-T537A (Android 5.1.1, BOJ3)

The Tab 4's bootloader is locked (Qualcomm secure boot, and it rejected a cross-flashed T535 aboot), so custom ROMs
are out. The stock kernel (2015) is vulnerable to Dirty COW. Samsung's MDM build also accepts an unsigned SELinux
policy override (`persist.security.mdm.policy=1` + `/data/security/sepolicy`), which gives a persistent permissive
policy and an `su` daemon reachable from `adb shell`. The full write-up and a one-shot script live in a separate
research repo (not published yet). The kiosk itself **doesn't need root**. Root is only used here for deep debloating
and to edit app prefs from adb.

## Running without a battery

Old tablets often have swollen or dead packs. Remove the pack and run the tablet from USB-C with the
[Goathouse Battery Eliminator](../hardware/battery-eliminator/). Don't run a tablet with a swollen battery on a wall.
