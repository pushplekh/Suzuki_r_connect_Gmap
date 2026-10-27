# REDLINE — Suzuki Ride Connect replacement

An open, local-only Android app that talks to the Bluetooth instrument cluster of
Suzuki scooters, replacing the official Suzuki Ride Connect app. It sends Google Maps
turn-by-turn guidance, incoming calls and notifications to the cluster and reads
vehicle telemetry back to the phone. The BLE protocol was reverse-engineered for
educational purposes and is documented in this repository.

## Compatibility

This version targets the **Suzuki Access and Burgman Street** Bluetooth clusters
(non-TFT), which share one protocol family.

| Vehicle | Detection | Status |
|---|---|---|
| Suzuki Access | Automatic (cluster name `AS01`/`AS02`) | Tested on a vehicle |
| Suzuki Burgman Street | Automatic (cluster name `AS11`/`AS12`) | Same protocol and mapping; not yet tested on a vehicle |
| e-ACCESS, Access-TFT, Burgman Street-TFT | Manual selection in Bike settings | Untested |

Other Suzuki Ride Connect vehicles may connect using the default mapping, but they
are not targeted or tested by this release.

## Features

- Google Maps turn-by-turn navigation on the cluster (arrows, distance, ETA, street text)
- Incoming-call alert with caller name and flashing LED until the call is answered or missed
- Missed-call, SMS and app-notification alerts
- Speed-limit warning (5-120 km/h)
- Calendar and meeting reminders
- Auto-reply SMS on missed calls, with an optional reject-and-reply mode
- Rain alert during navigation
- Spoken alerts for connection, low fuel and low battery
- Live telemetry dashboard, ride logging, statistics and GPX/CSV export
- Crash detection with SOS SMS
- Clock and weather on the cluster when navigation is idle

Local-only: no cloud service and no account. Features marked untested in `NOTES.md`
have not yet been confirmed on a vehicle.

## Install

Download the APK from the GitHub release, install it, then follow the onboarding
wizard to grant permissions and pair the cluster. Close or disable the official
Suzuki app so both apps do not compete for the Bluetooth link.

## Version

Version name and monotonically increasing version code are kept in
`android\gradle.properties`. Current version: **0.1.18 / code 19**.
Increment the code for every distributed build.

## Building

From the `android` directory:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

### Release builds

Create a release key privately with Android Studio's **Generate Signed Bundle / APK**
wizard, or `keytool`. Keep the keystore outside the repository, back it up securely,
and reuse it for future updates. Never commit keys, passwords or signing
configuration containing secrets.

Provide all four environment variables to the build: `REDLINE_KEYSTORE_PATH`
(absolute path), `REDLINE_KEYSTORE_PASSWORD`, `REDLINE_KEY_ALIAS` and
`REDLINE_KEY_PASSWORD`. On GitHub Actions, supply them through repository secrets.
Partial signing configuration fails explicitly.

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

With signing configured, the output is `app\build\outputs\apk\release\app-release.apk`.
**Without signing variables this intentionally produces an unsigned
`app-release-unsigned.apk`; it is not installable or ready to publish.**
Do not publish a debug-signed APK.

Before publishing, verify the APK with `apksigner verify --verbose --print-certs`,
confirm the version in `output-metadata.json`, and test that exact APK on a stationary
vehicle. Attach it as `redline-<version>-code<code>.apk` to release tag `v<version>`,
with a SHA-256 checksum and release notes. Keep
`app\build\outputs\mapping\release\mapping.txt` privately for crash decoding.
The release package is `dev.mrwick.redline`; debug uses `dev.mrwick.redline.debug`,
so switching to release does not migrate debug data.

## Protocol overview

- One vendor GATT service `0xFFF0`: `0xFFF1` (write, phone → cluster) and `0xFFF2`
  (notify, cluster → phone).
- Every frame is 30 bytes: `0xA5` header, ASCII type byte, body, checksum, `0x7F` terminator.
- Checksum: `sum(payload[1:28]) mod 256`.
- Frame types: `a531` navigation display, `a532` call, `a533` phone heartbeat,
  `a534` missed call, `a535` message, `a536` identity, `a537` cluster telemetry.
- The cluster only sends notifications after the phone writes first.
- The OEM app uses the cloud only for map-licence checks; vehicle data flows over BLE.

Full specification: `NOTES.md`. `tools/protocol.py` is the reference encoder/decoder;
the Kotlin port in `android/` mirrors it.

## Repository map

```
├── README.md          # This file
├── NOTES.md           # Protocol specification (current best understanding)
├── DISCOVERIES.md     # Chronological research log, including corrected assumptions
├── SMOKE_TEST.md      # On-vehicle test checklist
├── TROUBLESHOOTING.md # Common problems
├── android/           # REDLINE app (see android/HANDOFF.md)
├── tools/             # Python protocol reference and BLE research tools
├── frida-scripts/     # Runtime hooks used during protocol research
├── tests/             # Python tests
└── docs/              # Design specs, plans and assumption log
```

OEM APKs, decompiled sources and captures are intentionally not included.

## Research method

Facts in `NOTES.md` are backed by captures, decompiled OEM code or on-vehicle tests;
hypotheses are labelled as such. When a claim proves wrong, the correction is logged
in `DISCOVERIES.md` rather than silently rewritten.

## Credits

Maintained by **Pushplekh**. Original protocol research and app by **Arjun KR (mrwick)**.

## Disclaimer

Not affiliated with or endorsed by Suzuki. Educational project; use at your own risk
and never interact with the phone while riding. Before redistribution, review tracked
files and history for private data, OEM material and licensing requirements, and keep
upstream attribution.
