---
name: goathouse-kiosk
description: Work on Goathouse Kiosk, a native Android (minSdk 21, code-only UI) Home Assistant wall-panel app for old tablets, with a Music Assistant Sendspin speaker, Jellyfin cast target, RainViewer radar and goatprint USB fingerprint check-in. Use for any change to this repo, for building or installing the APK, for testing on a tablet over adb, or for adding support for another tablet.
---

# Goathouse Kiosk

A ~9k-line Java app (`org.goathouse.kiosk`) plus a small C core. Read the repo docs as you need them:
`docs/ARCHITECTURE.md` (code map, threading), `docs/BUILD.md`, `docs/SETUP.md`, `docs/TABLETS.md`,
`docs/GOATPRINT.md`, `docs/ROADMAP.md` (what's next). House rules: `CONTRIBUTING.md`.

## Build and run

```sh
export JAVA_HOME=<jdk17> ANDROID_HOME=<sdk>          # SDK 34, build-tools 34.0.0, NDK 27.3.13750724
./gradlew assembleRelease lintRelease               # 0 lint errors
adb -s <ip>:5555 install -r app/build/outputs/apk/release/app-release.apk
adb -s <ip>:5555 shell am start -n org.goathouse.kiosk/.HomeActivity
adb -s <ip>:5555 exec-out screencap -p > shot.png   # look at it before claiming a UI change works
adb -s <ip>:5555 logcat -d | grep -E "Ha|Sendspin|Jellyfin|Relay|Goatprint"
```

- Look at the screenshot after every visual change. Most regressions in this app are visual or only happen on
  Android 5.
- `adb shell input tap x y` drives the UI. Coordinates are screen pixels, and layouts scale with `Style.u()`.
- Don't send key presses or start playback on a tablet someone may be using without asking its owner.
- Non-exported activities (`CastActivity`, `GoatprintTestActivity`) need `su -c 'am start …'` on rooted tablets.
  If an `am start` with JSON extras behaves oddly, push a shell script and run that instead of fighting adb quoting.

## Ground rules

- **No household data in git** (entity ids, names, IPs, tokens, photos). Defaults come from per-tablet prefs, then
  `assets/house.json` (built from the gitignored `house/`), then generic values. See `house.example.json`.
- **Secrets** reach a tablet only through the phone setup page (`SetupServer`, port 8780, QR + PIN). Never print
  or commit them.
- **Code-only UI** with `Style`/`Kit`/`Widgets`. No XML layouts, AndroidX, Compose or WebView dashboards.
- **minSdk 21.** Guard newer APIs. Test assumptions against Android 5.1.

## Solved traps (don't undo them)

| Symptom | Cause | Fix in code |
|---|---|---|
| HA error "Identifier values have to increase" | concurrent sends | `Ha`: one sender executor per connection; ids assigned on it |
| Controls empty after a Wi-Fi drop | dead socket, stuck sender | `Ha`: ping 30 s, reconnect after 75 s silence, new executor per connection |
| Speaker never starts on some firmware | `startForegroundService` returns null right after install | `SendspinService.startIfConfigured`: `startService` + retries |
| Noise handshake fails on Android < 12 | no platform X25519 | `Noise.x25519()` pure Java (RFC 7748 vectors) |
| Jellyfin 403 | 10.11+ rejects `api_key=` | `Authorization: MediaBrowser …` header on the websocket, `ApiKey=` on stream URLs |
| Resume fails on HLS | Jellyfin rejects `StartTimeTicks` on HLS | continuous `/Videos/{id}/stream.ts` from `StartTimeTicks`; seek = restart |
| mediaserver crash on Android 5 | `NuCachedSource2` aborts on unknown length | `StreamRelay` on 127.0.0.1 declares a Content-Length |
| Thin text on Android < 8 | variable font | static Outfit 200–700 in `assets/fonts` |
| Fingerprint frames corrupt | `bulkTransfer` 16 KB cap < Android 9, no flow control | `GoatprintReader` queues 24 `UsbRequest`s before `e1/e2` |
| Old semester-long calendar entries on top | multi-month events | `BriefActivity.upcoming()` drops events that started before today |
| `https://` HA URL refused | Android < 7.1.1 lacks ISRG Root X1 | LAN `http://` only, by design |

## Adding things

- **A new card or tile:** read how `ControlsActivity` builds cards and how `Tiles` renders an entity. Subscribe to
  changes through `Ha.Listener.onChanged(ids)` and re-render only what changed.
- **A new per-tablet setting:** add a key to `Dash` (with `def(KEY, generic)`), a row to `SetupServer.DASH_FIELDS`,
  and document it in `docs/SETUP.md` and `house.example.json`.
- **A new tablet:** check `getprop ro.build.version.sdk`, `wm size`, `/proc/meminfo`, and add it to `docs/TABLETS.md`.
- **Goatprint:** matching numbers live in `Goatprint.java` and must stay compatible with the Linux daemon. The
  vendored C core is in `app/src/main/cpp/goatprint` (see `VENDORED.txt`).

## Finishing a change

Build, lint, install on at least one tablet, look at a screenshot, and update the docs the change touches. In the
PR or commit message, say what you verified on hardware and what you didn't.
