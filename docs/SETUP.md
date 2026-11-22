# Setting up a tablet

## 1. Install

Enable Developer options → USB debugging on the tablet, then:

```sh
adb install -r app-release.apk
```

Over Wi-Fi: `adb tcpip 5555` once over USB, then `adb connect <tablet-ip>:5555`. Android 11+ also has *Wireless
debugging* (pair with `adb pair`). Close network ADB when you're done if anyone else is on your LAN.

## 2. Connect it to Home Assistant (from your phone)

Open the app. The first screen shows a **QR code and a 6-digit PIN**. The tablet runs a tiny setup server on port
**8780** only while that screen is open, and it only answers requests that carry the PIN.

1. Scan the QR code with a phone on the same Wi-Fi.
2. **HA URL:** use the LAN address, e.g. `http://192.168.1.10:8123`. Android before 7.1.1 doesn't trust current
   Let's Encrypt roots, so the app refuses `https`/`wss` on purpose.
3. **Token:** create a dedicated **non-admin** user in HA (e.g. `kiosk`), sign in as it once, and make a long-lived
   access token under its profile. The token never leaves the tablet; the setup page never shows it again.
4. Save. The tablet connects within a few seconds; the status shows under Settings → Connection.

The same phone page edits everything else (it reopens from Settings → Tiles / Connection).

## 3. Pick a layout and entities

Every card points at Home Assistant entity ids that you set on the phone page. Missing or unavailable entities
are hidden, so start with the defaults and fill in what you have.

| Field | Used by | Example |
|---|---|---|
| Layout | everything | `kitchen`, `room` or `hallway` |
| Main light (big button) | kitchen | `light.kitchen` |
| Room buttons (one per line) | controls | `light.living_room` |
| Shopping list | kitchen | `todo.shopping_list` |
| Speaker for Now Playing | controls | blank = this tablet's own Music Assistant player |
| Weather | glance, radar | `weather.forecast_home` |
| Planes overhead | glance | `sensor.flightradar24_current_in_area` (FlightRadar24 integration) |
| Last to bed keeps on | controls | id prefixes, e.g. `light.kitchen` |
| Birthdays calendar | glance | `calendar.birthdays` |
| Alarm switch / time | room | `switch.bedroom_alarm_1`, `time.bedroom_alarm_1_time` (alarm 2 = same ids with `_2`) |
| To-do lists as tabs | room | `todo.alex` |
| Calendars for "Coming up" | room | `calendar.alex` |
| Thermostat / room sensor | room | `climate.thermostat`, `sensor.bedroom_temperature` |
| People | hallway, goatprint | `person.alex=calendar.alex,calendar.work;todo.alex`, one person per line |
| Radar basemap | radar | blank = OpenStreetMap (darkened), or any `{z}/{x}/{y}` tile URL |

**House profile:** to ship your household's choices as the defaults of your own builds, copy
`house.example.json` to `house/house.json` before building. `house/` is gitignored, so your entity ids, names
and background photo (`house/backdrop.jpg`) stay out of the repo.

## 4. Make it a kiosk

- **HOME app:** press Home and pick Goathouse Kiosk → Always. Or run
  `adb shell cmd package set-home-activity org.goathouse.kiosk/.HomeActivity`.
- **Screen:** the app keeps the screen on and dims itself at night (Settings → Night starts / Morning / Night
  brightness). On battery-less rigs see [hardware/battery-eliminator](../hardware/battery-eliminator/).
- **Clock:** 12-hour by default; Settings → 24-hour clock. Temperatures always show in °F.
- **Starts on boot:** yes. The speaker/cast service starts on `BOOT_COMPLETED` and after every app update.
- **Debloat:** see [TABLETS.md](TABLETS.md). `pm disable-user` / `pm hide` the vendor junk you don't need.

## 5. Speaker (Music Assistant)

Settings → turn the speaker on and give it a name (phone page → *player name*). The tablet connects to
`ws://<HA host>:8927/sendspin`, assuming MA runs next to HA. The first time, approve the new player in Music Assistant.
Now Playing follows this tablet's player, or the group it's playing in.

## 6. Jellyfin cast target

Settings → **Jellyfin cast** → enter your server's LAN URL → **Get a code**. In any signed-in Jellyfin app go to
profile → **Quick Connect** and enter the code. The tablet then shows up in that account's cast menu. No password is stored.

A Jellyfin session belongs to one user, so **link every account that should be able to cast**: tap **Add an account** and
repeat with each person's Jellyfin app. The tablet keeps one session per account (each with its own device id) and plays
whatever any of them casts. Remove an account from the same screen.
Video is transcoded to ≤ 720p H.264 so 2014-era hardware decoders cope.

## 7. Fingerprint reader (optional)

Plug a Suprema BioMini Slim (USB `16d1:0407`) into the tablet through an OTG adapter (powered OTG if the tablet is
on battery). Accept the USB prompt and tick *use by default*. The first touch on a fresh reader opens enrolment.
Details and privacy: [GOATPRINT.md](GOATPRINT.md).
