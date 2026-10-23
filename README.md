# visor

A fleet dashboard for an always-on Android tablet: the health and load of every machine in a [Beszel](https://beszel.dev) hub, with a live camera feed in the top-right corner. It's a native Android app, landscape only, styled like a terminal HUD.

## ⚠️ This is a personal project ⚠️

visor is built for one tablet, one hub and one camera. The machine names, addresses, camera stream and update source are written into the code. It won't work for anyone else as-is, and there are no plans to make it configurable.

If you want something like it, fork it and change it to fit your own setup. See [Making it yours](#making-it-yours).

## What it shows

- **One tile per machine**, each in a fixed slot: status and uptime, CPU, memory and disk, plus the GPU (or the biggest ZFS pool, or the temperature), a minute of CPU history, network rates, temperature and load.
- **The camera**, in the top-right 2×2 block of the grid.
- **Fleet summary**: how many machines are up, the fleet's combined CPU and memory, and its total traffic.
- **Event log**, two cells tall, under the camera's right half: machines going offline or coming back, and loads that stay over a threshold for a few seconds.

Machines that are registered with the hub but aren't in the fleet list are left out.

Hold the header for a menu that opens the tablet's other apps. visor is meant to be the home screen, so that menu is the way out.

## How it works

- **Machine stats** come from a Beszel hub. visor signs in with a read-only account and subscribes to each machine's `rt_metrics` topic, which makes the hub poll that machine's agent every second for as long as visor watches.
- **The camera** is an H.264 stream in fragmented MP4 over HTTP, the kind [camview](https://github.com/chriscorbell/camview)'s relay serves. The player, `FeedPlayer` and `Fmp4Reader`, is copied from camview's Android app, minus the sound.
- **Every panel** is a custom `View` drawing on a `Canvas`, and each redraws only itself, about once a second. There's no AndroidX, Compose or WebView, so it stays light on a modest tablet that runs all day.
- **Updates**: release builds check the repo's GitHub Releases every 15 minutes and install anything newer by themselves.

## Making it yours

In a fork, change these:

- **Addresses**, in `app/build.gradle.kts`: `HUB_URL` (your Beszel hub), `FEED_URL` (your camera stream) and `RELEASES_URL` (your fork's releases).
- **App ID**, also in `app/build.gradle.kts`, so your build doesn't clash with this one.
- **Machines**: `Fleet.ORDER` in `Fleet.kt`. Names must match the hub's, and the order sets the grid slots.
- **The app menu**: the package list in `Dashboard.kt`.
- **Release signing**: generate your own key, put it in your fork's Actions secrets and replace `release-certificate.sha256` with its certificate's SHA-256.

The grid has a fixed number of machine slots (`MACHINE_SLOTS`) and is sized for one landscape tablet. A different machine count or screen will need changes in `Dashboard.kt` and `Panels.kt`.

## Setting up a tablet

1. Install a release APK. After that, visor updates itself.
2. Give visor the hub account. Credentials never go into the app or the repo. Write `shared_prefs/visor.xml` in visor's data directory as root, with `email` and `password` strings.
3. Make visor the home screen: `cmd package set-home-activity <app id>/.MainActivity`.
4. Let it be landscape. Android 16 ignores apps' orientation requests on tablets unless the display allows them, and that resets on every boot. Run `wm set-ignore-orientation-request false` after each boot, for example from a Magisk boot script in `/data/adb/service.d/`. The app also opts out with `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`, and keeps asking for landscape until it gets it.

Steps 2 and 4 need root.

## Building

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds install beside the release app with a `.debug` suffix and their own settings, so step 2 above applies to them too. They never update themselves.

## Releases

Every push to `main` builds a signed APK in GitHub Actions and publishes it as release `visor-<run number>`. The workflow refuses to publish an APK whose signing certificate doesn't match `release-certificate.sha256`. The key lives in the repo's Actions secrets (`VISOR_ANDROID_KEYSTORE_BASE64`, `VISOR_ANDROID_KEYSTORE_PASSWORD`, `VISOR_ANDROID_KEY_ALIAS`, `VISOR_ANDROID_KEY_PASSWORD`). Keep a backup of it: without it, the app can't be updated in place and has to be reinstalled.

## License

GPL-3.0. JetBrains Mono is under the SIL Open Font License, in `licenses/`.
