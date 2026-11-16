# Turntable RPM

An Android app that measures turntable speed. Lay the phone flat on the spinning platter and read the speed.

- **Fully offline and private.** The app asks for **no permissions at all**: no internet, no storage, no ads, no analytics, no accounts. Its manifest declares no `<uses-permission>` entries, and the app uses no third-party libraries.
- **Works on Android 12 and newer.** The phone needs a gyroscope, which almost every phone has.

## Features
- Live speed in large numbers on a dark screen
- Presets for 33⅓, 45 and 78 RPM (Auto picks the nearest one), with % deviation colour-coded green, amber or red
- Measures automatically: it waits for the speed to settle, records 10 full turns, beeps, and saves the result
- Wow & flutter (unweighted ±2σ), split into wow (below 6 Hz) and flutter, plus speed drift
- Live speed graph covering the last 30 seconds
- Measurement log kept on the phone, which you can export as CSV through Android's own "save as" screen
- Calibration: zero the gyro, plus an optional speed correction factor you can set against a strobe disc

## How to install
1. Open the **Releases** page of this repo on your phone and download `TurntableRPM.apk`.
   (Every push also builds an APK under **Actions → latest run → Artifacts**. That copy comes as a zip and you have to be signed in to GitHub to download it.)
2. Open the file. Android will ask you to allow installs from your browser or file manager the first time.

## How to measure
1. Optional, once: open **Settings → Zero the gyroscope** with the phone lying still on a table.
2. Put the phone flat on the record, clear of the tonearm. A mat or cloth underneath protects it.
3. Start the platter. The status line reads *Waiting for the speed to settle…*, then *Measuring…*, and the phone beeps when the result is ready.
4. Stop the platter and pick up the phone. The last result stays on screen and is saved in **Log**.

## Accuracy notes
- The app reads speed straight from the gyroscope, so it doesn't matter where on the platter the phone sits.
- Phone gyros can read about 1% high or low. If you have a strobe disc or another trusted reference, set **Settings → Speed correction factor** once.
- Wow & flutter is **unweighted**. It will read higher than DIN/IEC *weighted* figures on spec sheets, so use it to compare your own decks against each other or before and after a repair.
- Sampling runs at 200 Hz, the maximum Android allows without a permission.

## Building
GitHub Actions builds the APK on every push (`.github/workflows/build.yml`). Pushing a tag such as `v1.0.1` also publishes a Release.

**Signing:** unless you add a signing key, each build is signed with a temporary key. That means installing a new version over an old one fails, and you have to uninstall first, which deletes the log, so export it beforehand. To fix this for good, add these repository secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

To build locally: `gradle assembleRelease` (needs Gradle 8.10+, JDK 17 and the Android SDK).
