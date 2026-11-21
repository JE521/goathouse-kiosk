# Architecture

About 9,000 lines of plain Java in one package (`org.goathouse.kiosk`) plus a small C core for fingerprints.

## Principles

- **Code-only UI.** No XML layouts, Compose or AndroidX. Views are built in Java and sized with `Style.u()`, a
  scale unit measured from the screen, so one layout fits 800×1280 and 1920×1200 alike. It's fast on 1 GB tablets
  and easy to restyle.
- **Home Assistant is the source of truth.** One websocket, one `subscribe_entities` stream. Screens re-render the
  entities that changed; nothing polls.
- **Nothing household-specific in code.** Entity ids come from per-tablet prefs (phone setup page), falling back
  to the optional house profile (`assets/house.json`), then generic defaults.
- **Old Android first.** Every API is checked against API 21. Where the platform is missing something (X25519,
  modern TLS, robust HTTP streaming), the app carries its own.

## Code map

| Area | Files | Notes |
|---|---|---|
| Screens | `HomeActivity` (glance/idle), `ControlsActivity` (controls), `BriefActivity` (personal brief), `RadarActivity`, `FlightsActivity`, `SettingsActivity`, `SetupActivity` | `HomeActivity` is the HOME/launcher activity |
| Look | `Style` (tokens, fonts, brightness rule), `Kit` (icons, backdrop), `Ui`, `Widgets` (sliders, wheels), `Tiles`/`TileGrid` (entity tiles), `TaskRows` (to-do lists) | Outfit font, static weights because Android < 8 renders the variable font thin |
| Config | `Prefs` (private SharedPreferences), `Dash` (what this tablet shows), `SetupServer` (phone setup page, QR + PIN, port 8780) | |
| Home Assistant | `Ha` (websocket client: auth, `subscribe_entities` diffs, `call_service`, heartbeat), `MiniWebSocket`, `HaPipeline` (Assist voice) | Message ids must increase in arrival order, so one sender thread per connection, recreated on reconnect. Ping every 30 s; reconnect after 75 s of silence (Wi-Fi drops leave dead sockets). |
| Speaker | `SendspinService` (foreground `mediaPlayback` service, starts on boot), `SendspinClient`, `Noise` (Noise_KKpsk2 + pure-Java X25519, RFC 7748 vectors), `TimeFilter` (Kalman clock sync) | Music Assistant's Sendspin protocol. Some firmwares return null from `startForegroundService` right after install, hence `startService` with retries. |
| Jellyfin | `Jellyfin` (Quick Connect, session websocket, remote control), `CastActivity` (VideoView player), `StreamRelay` (localhost relay), `JellyfinLinkActivity` | Header auth (10.11+ refuses `api_key=`). Continuous `/Videos/{id}/stream.ts` from `StartTimeTicks`; seek = restart at the new point. The relay declares a Content-Length because Android 5's `NuCachedSource2` aborts on unknown-length streams. |
| Radar | `RadarMap` | RainViewer frames at zoom 7 over a darkened XYZ basemap, cached as RGB_565; cross-fade animation |
| Timers | `Timers` | Kitchen timers with alarm tone |
| Fingerprints | `GoatprintReader` (USB driver), `GoatprintCore` + `cpp/` (JNI: normalise, extract, score), `Goatprint` (engine thread, matching, vault), `GoatprintEnrollActivity`, `GoatprintUsbActivity` (USB attach), `GoatprintTestActivity` (bring-up) | See [GOATPRINT.md](GOATPRINT.md) |
| Boot | `BootReceiver` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` |

## Threading

- **Main thread:** all views and listener callbacks (`Ha.Listener`, `Goatprint.Listener`).
- **`ha-loop`:** connects, reads and reconnects with back-off. Sends go through a single-thread executor per
  connection.
- **Sendspin:** its own socket and audio threads inside the foreground service.
- **`goatprint`:** owns the USB reader; every reader and JNI call happens on it.
- Network calls never run on the main thread. `MiniWebSocket.close()` is also kept off it.

## Brightness

One rule (`Style.brightness`), applied by every activity: day 100%, night 45%, night clock 2%. Night hours come from
Settings.
