# Frame Launcher

A small Android home-screen launcher for wall-mounted photo frames and tablets. It
switches what the screen shows on a weekly schedule: a photo slideshow, a clock,
wireframe animations, a web page (such as a Home Assistant dashboard), or the
screen turned off. Slideshows, clocks and animations are drawn natively, which is
much smoother than a web page on old hardware.

It was built for a Frameo photo frame opened up for ADB access (Android 6, no
root), but it should run on any Android 6+ device where you can set a custom home
app.

## What it does

- **Modes.** You define named modes. Each one is:
  - **NAS photos**: photos from an Immich album, served by the companion photo
    service (`frameo-slideshow`). Set the service URL, e.g. `http://nas:8088`.
  - **Picsum photos**: random photos from picsum.photos.
  - **Split-flap clock**: time, date and current weather (Open-Meteo, no API key)
    for a configurable latitude/longitude.
  - **Wireframe animations**: glowing vector scenes in the style of early-1980s
    TV interstitials.
  - **Web page**: a full-screen WebView showing a URL, with an optional reload
    every N minutes.
  - **Simple clock**: a large full-screen clock.
  - **Screen off**: the display is turned off.

  Photo modes crossfade with a slow zoom, fill the sides with a blurred copy of the
  photo, and show two portrait photos side by side. They keep the last 200 photos
  on the device and cycle through those when the source is unreachable. Photo and
  animation modes advance every N seconds (set per mode).

  Every mode except Screen off has its own brightness level.
- **Schedule.** Rules of the form "on these days, at this time, switch to this
  mode". The frame stays in a mode until the next rule fires. After a reboot or a
  clock or timezone change, it works out which mode should be active right now.
- **Show now.** You can manually pick a mode from settings. It stays until the
  next scheduled switch, or until you tap "Resume schedule".
- **Swipes** in photo and animation modes: left/right for next/previous, up for a
  fresh batch of photos, down to restart the mode.
- **Hidden settings.** There's no visible UI. Hold your finger on the top-left
  corner for 2.5 seconds to open settings. You can protect this with a numeric
  PIN.
- **Screen-off peek.** If someone wakes the screen while it's in Screen off mode,
  a dim clock shows briefly and then the screen goes dark again.
- **Kiosk behaviour.** As the home app it starts on boot, hides the system bars
  and keeps the screen on. Self-signed HTTPS certificates are accepted if you
  allow them in settings, which helps with NAS web interfaces on the local
  network.

All configuration lives on the device itself. Nothing is sent anywhere, and the
app has no server or account.

On first start it creates an example schedule: a "Dashboard" mode at 07:00,
"Photos" (NAS photos) at 18:00, a dim "Night clock" at 22:30 and "Screen off" at
23:30. "Dashboard" and "Photos" have no URL yet, so set them in settings.

## Building

Requires the Android SDK (platform 34, build-tools 34) and JDK 17.

1. Create `local.properties` pointing to your SDK:

   ```
   sdk.dir=C:/path/to/android-sdk
   ```

2. Build:

   ```
   ./gradlew assembleDebug        # gradlew.bat on Windows
   ```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on a device

Connect over ADB (USB, or `adb connect <device-ip>:5555` for ADB over network), then:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Make it the home app: press Home on the device and choose
# "Frame Launcher" with "Always". On newer Android versions you can also run:
#   adb shell cmd package set-home-activity app.framelauncher/.MainActivity

# Let it turn the screen off (needed for "Screen off" mode)
adb shell dpm set-active-admin app.framelauncher/.AdminReceiver
```

Without the device-admin step, Screen off mode only dims the screen to minimum and
relies on the system screen timeout.

## Notes and caveats

- Some vendor ROMs won't start a stopped app for background alarms. If the
  schedule stops firing, make sure the launcher process wasn't force-stopped.
  Pressing Home or rebooting brings it back.
- The WebView caches pages. To force a fresh load of a changed page on a debug
  build without losing settings:

  ```sh
  adb shell am force-stop app.framelauncher
  adb shell "run-as app.framelauncher rm -rf cache/WebView/Default 'app_webview/Default/Service Worker'"
  adb shell am start -n app.framelauncher/.MainActivity
  ```

- To try a mode without changing the schedule (shown for 10 minutes):

  ```sh
  adb shell am start -n app.framelauncher/.MainActivity --es preview WIRE
  adb shell am start -n app.framelauncher/.MainActivity --es preview PHOTOS --es url http://nas:8088 --ei interval 8
  adb shell am start -n app.framelauncher/.MainActivity --es preview none   # back to the schedule
  ```

  Mode type names: `PHOTOS`, `PICSUM`, `FLIP_CLOCK`, `WIRE`, `WEB`, `CLOCK`, `OFF`.
- Cleartext HTTP is allowed on purpose, because local services often don't use
  HTTPS.

## Project layout

```
app/src/main/java/app/framelauncher/
  MainActivity.kt      the full-screen display: hosts the modes, swipes, hidden corner
  PhotoShow.kt         native photo slideshow, photo sources (NAS service, Picsum), disk cache
  FlipClockView.kt     split-flap clock with weather
  WireView.kt          wireframe animations
  Net.kt               HTTP helper and the Show interface shared by native modes
  SettingsActivity.kt  on-device settings: modes, schedule, PIN, show-now
  Engine.kt            alarms, boot handling, screen wake/off, device admin
  Model.kt             modes, schedule rules, storage and schedule calculation
```
