# Roadmap

Rough edges and next steps, roughly in priority order. PRs welcome.

## Next
- [ ] **Goatprint: first hardware test** of the Java USB driver on Android 5 (does the queued-`UsbRequest` frame
      read arrive intact?). Bring-up screen: `adb shell am start -n org.goathouse.kiosk/.GoatprintTestActivity`
      (needs root because it isn't exported) saves raw frames as PGM.
- [x] **Goatprint → MQTT:** signed events, HA discovery and imported share bundles (docs/GOATPRINT.md). It still
      needs a live run against Home Assistant's goatprint integration once that's installed.
- [ ] **Release signing + GitHub Releases** so Obtainium can update tablets. That means a shared keystore kept out
      of git, with CI signing from a secret.
- [ ] **Jellyfin relay range support:** some players probe far offsets (MPEG-TS duration). Serve a window of
      recent bytes and filler for far probes.

## Later
- [ ] Map tablet: household people on a self-hosted map with movement history.
- [ ] Mix personal-brief pieces (greeting, who's home) into the room layout.
- [ ] Photo backdrop from Immich instead of a static `backdrop.jpg`.
- [ ] Auto power-on after a power cut for battery-less rigs (eliminator rev B).
- [ ] Portrait layouts.
- [ ] Screenshots in the README.
