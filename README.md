# visor

A fleet dashboard for the always-on Android tablet on Chris's desk: the health and load of every machine in the fleet, with the front-door camera in the top-right corner. It's a native Android app, landscape only, styled like a terminal HUD.

## What it shows

- **One tile per machine** in its fleet list (`Fleet.ORDER`), each in a fixed slot: status and uptime, CPU, memory and disk, plus the GPU (or the biggest ZFS pool, or the temperature), a minute of CPU history, network rates, temperature and load.
- **The camera**, in the top-right 2×2 block of the grid.
- **Fleet summary**: how many machines are up, the fleet's combined CPU and memory, and its total traffic.
- **Event log**, two cells tall, under the camera's right half: machines going offline or coming back, and loads that stay over a threshold for a few seconds.

Machines the hub has that aren't in the fleet list, like the retired `nas-old`, are left out.

Hold the header for a menu that opens the tablet's other apps (camview, Reolink, Firefox, the stock launcher, Settings). visor is the home screen, so that menu is the way out.

## Where the data comes from

- **Machine stats**: the [Beszel](https://beszel.dev) hub on minicore, at `http://10.0.0.20:8090` on the LAN, since the tablet isn't on the tailnet. visor signs in with a read-only account and subscribes to each machine's `rt_metrics` topic, which makes the hub poll that machine's agent every second for as long as visor watches. The hub and its agents are set up in the [fleet repo](https://github.com/chriscorbell/fleet) (`hosts/minicore/stacks/beszel/`, `docs/beszel.md`).
- **Camera**: camview's Relay on minicore, which already makes a 1080p H.264 stream for the desk display (`/api/stream.mp4?src=desk`). The player, `FeedPlayer` and `Fmp4Reader`, is copied from [camview](https://github.com/chriscorbell/camview)'s Android app, minus the sound.

## Setting up the tablet

The tablet runs a LineageOS GSI with Magisk root. Over ADB (`adb connect 10.0.0.199:5555`):

1. Install the latest release APK from this repo's releases. After that, visor updates itself.
2. Give visor the hub account. The credentials stay on the tablet and never go into the app or this repo. Write `shared_prefs/visor.xml` in visor's data directory as root, with `email` and `password` strings. The values are `BESZEL_VISOR_EMAIL` and `BESZEL_VISOR_PASSWORD` in minicore's `~/docker/stacks/beszel/.env`.
3. Make visor the home screen: `cmd package set-home-activity com.chriscorbell.visor/.MainActivity`.
4. Let it be landscape. Android 16 ignores apps' orientation requests on tablets unless the display allows them, and that resets on every boot, so a Magisk boot script, `/data/adb/service.d/landscape-apps.sh`, runs `wm set-ignore-orientation-request false` once the tablet has booted. The app also opts out with `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`, and keeps asking for landscape until it gets it.

## Building

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds install as `com.chriscorbell.visor.debug`, beside the release app, with their own settings, so step 2 above applies to them too.

## Releases

Every push to `main` builds a signed APK in GitHub Actions and publishes it as release `visor-<run number>`. The tablet checks every 15 minutes and installs anything newer by itself. Only the first update after a manual install asks for a tap. The workflow refuses to publish an APK whose signing certificate doesn't match `release-certificate.sha256`.

The release key is in the repo's Actions secrets (`VISOR_ANDROID_KEYSTORE_BASE64`, `VISOR_ANDROID_KEYSTORE_PASSWORD`, `VISOR_ANDROID_KEY_ALIAS`, `VISOR_ANDROID_KEY_PASSWORD`). A copy is kept on mbp in `~/Backups/visor/`. Without it, visor can't be updated in place: it would have to be uninstalled and installed again.

## License

GPL-3.0, like camview, which the player comes from. JetBrains Mono is under the SIL Open Font License, in `licenses/`.
