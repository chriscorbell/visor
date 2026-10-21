# visor

A native Android dashboard for the desk display tablet: every fleet machine's stats from the Beszel hub, plus the front-door camera. The README covers what it shows, where the data comes from and how the tablet is set up. Read it first.

- **Plain Android, no AndroidX or Compose.** The tablet is a 4 GB MediaTek MT8786, on all day. Every panel is a custom `View` drawing on a `Canvas`, and each one redraws only itself, about once a second. Keep it that way: no WebView, no continuous animations, no blur.
- **The camera player is camview's** (`FeedPlayer.kt`, `Fmp4Reader.kt`). Fix player bugs in camview first, then copy the change here.
- **The tablet**: `adb connect 10.0.0.199:5555`, rooted (`su -c`). Before installing or launching anything on it, check `dumpsys activity activities | grep topResumedActivity`; Chris may be using it. Test with debug builds (`com.chriscorbell.visor.debug`), which never interfere with the release app's self-updates.
- **Credentials never go into the app or the repo.** The hub account is read from the app's own preferences on the tablet.
- **Beszel realtime:** subscribing to `rt_metrics?options={"query":{"system":"<id>"}}` makes the hub poll that agent every second. The topic must be serialized exactly as PocketBase's JS SDK does (`Beszel.metricsTopic`), because the hub keeps one subscription name per machine and the Beszel web interface subscribes the same way. PocketBase ends every realtime stream after 30 minutes, so `Beszel` reconnects at once after a stream that ran a while, and only shows the link down if that fails.
- **Releases** come only from CI on `main`. Never publish an APK signed with any other key: the tablet would refuse it.
- **Writing style:** plain, short sentences, no filler, in code comments, docs and commit messages. No attribution trailers in commits or PRs.
