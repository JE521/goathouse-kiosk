# Goathouse Kiosk

**A native Android wall-panel app for Home Assistant, built to give old tablets a second life.**

> 🛠️ **Vibe-coded project.** Almost all of this code was written by an AI coding agent (Claude Code), steered and
> tested on real hardware by a human. It works well in one house; treat it as a hobby project, not a product.
> Agents can pick up where we left off: see [`.claude/skills/goathouse-kiosk/`](.claude/skills/goathouse-kiosk/SKILL.md).

Most "Home Assistant on a tablet" setups load the HA web UI in a kiosk browser. On a 2014 Samsung with 1.3 GB of RAM
that means slow, laggy and dimmed-by-the-battery. Goathouse Kiosk is the opposite: a small (~700 KB), code-only
Android app that talks to Home Assistant's websocket API directly and draws everything natively. It runs on
**Android 5.0 and up** (tested on 5.1, 7.1 and 12).

<!-- screenshots: docs/img/ (add yours) -->

## What it does

| | |
|---|---|
| **Glance screen** | Big clock, weather, next event, planes overhead, trash night. Dims at night and shows a near-black night clock. Tap anywhere for controls. |
| **Controls** | Native light, switch, scene, climate, timer, to-do, calendar and media tiles over a live `subscribe_entities` stream, with no polling. Brightness sheet, "last to bed" (all lights off except the ones you keep), whole-house lights. |
| **Layouts per tablet** | `kitchen` (timers, shopping list, room buttons), `room` (bedroom: alarms with an AM/PM wheel picker, to-do tabs with a completed section, "coming up" from your calendars, thermostat) and `hallway` (personal brief per person). |
| **Speaker** | The tablet is a **Music Assistant player** over the Sendspin protocol: synced multi-room audio, no MA app. It includes a pure-Java X25519 so the Noise handshake works on Android 5. |
| **Jellyfin cast target** | Link it with Quick Connect, then cast from any Jellyfin app. Old tablets get a continuous H.264 TS transcode through a local relay that works around Android 5's media player quirks. |
| **Weather radar** | Native animated RainViewer radar with smooth cross-fades over any XYZ basemap (OpenStreetMap by default, or your own tile server). |
| **Fingerprints (goatprint)** | Optional USB fingerprint reader (Suprema BioMini Slim) with an **open-source** driver and NIST NBIS matcher: no vendor SDK. Touch the reader and your personal brief opens. You enrol fingers on the tablet itself. |
| **Phone setup** | No typing on the tablet. Scan a QR code and fill in HA URL, token and dashboard entities on your phone (PIN protected, LAN only). |
| **Voice** | Optional HA Assist push-to-talk. |

## Quick start

1. **Build** (JDK 17, Android SDK 34, NDK 27.3; details in [docs/BUILD.md](docs/BUILD.md)):
   ```sh
   ./gradlew assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
2. **Open the app** on the tablet. It shows a QR code and a PIN. Scan it with your phone and enter your Home
   Assistant LAN address (`http://<ip>:8123`, since old tablets can't do modern TLS) and a long-lived token from a
   **non-admin** HA user.
3. **Pick a layout** and the entities each card uses on the same phone page. Missing entities are simply hidden.
4. Optional: set it as the HOME app, turn on the speaker, link Jellyfin and plug in a fingerprint reader. See
   [docs/SETUP.md](docs/SETUP.md).

To bake your household's defaults into your own builds, copy [`house.example.json`](house.example.json) to
`house/house.json` (gitignored). You can drop a `house/backdrop.jpg` there too for the background photo.

## Docs

| | |
|---|---|
| [docs/SETUP.md](docs/SETUP.md) | Installing on a tablet, phone setup, layouts, speaker, Jellyfin, kiosk mode |
| [docs/BUILD.md](docs/BUILD.md) | Toolchain, reproducible builds, signing, CI |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the code is laid out and why (code-only UI, websocket model, threading) |
| [docs/TABLETS.md](docs/TABLETS.md) | Tested hardware, debloating, rooting notes, old-Android traps |
| [docs/GOATPRINT.md](docs/GOATPRINT.md) | Fingerprint reader: protocol, matching, enrolment, privacy |
| [hardware/battery-eliminator/](hardware/battery-eliminator/) | Run a tablet on USB-C with its battery removed (schematic + parts list) |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to work on it, by hand or with an AI agent |

## Status

Daily use on four tablets: a kitchen panel, a bedroom panel, a hallway panel with a fingerprint reader, and a spare.
Things that are rough or missing are tracked in [docs/ROADMAP.md](docs/ROADMAP.md).

## License

[MIT](LICENSE). Bundled: Outfit font (SIL OFL, `app/src/main/assets/fonts/OFL.txt`), NIST NBIS (public domain,
via libfprint's trimmed copy), ZXing (Apache 2.0). Sendspin/websocket pieces started life in the MIT-licensed
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) launcher fork.
