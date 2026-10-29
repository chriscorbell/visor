# visor

A fleet dashboard for an always-on screen: the health and load of every machine in a [Beszel](https://beszel.dev) hub, with a live camera feed. It's a dark, quiet web app, laid out for a landscape tablet that shows it all day. Color shows up only where something needs a look.

## ⚠️ This is a personal project ⚠️

visor is built for one screen, one hub and one camera, and the layout assumes it: a landscape screen with up to twelve machines on the left and the camera on the right. Everything that names a machine or an address comes from environment variables, so it can run elsewhere, but there are no plans to make it more configurable than that. Fork it and change it to fit your setup.

## What it shows

- **One tile per machine**, in the configured order: status and uptime, CPU with a minute of history, memory, disk (or the biggest ZFS pool), one more reading (the GPU, another volume or the temperature), network rates and temperature. A machine that's offline, quiet or has an unhealthy pool gets a red or amber tile.
- **The clock and the fleet**, top right: how many machines are up, the fleet's total traffic and memory, and the hub link when it's down.
- **The camera**, under the clock.
- **Event log**, under the camera, newest first: machines going offline or coming back, ZFS pools that aren't healthy, and loads that stay over a threshold for a few seconds.

Machines that are registered with the hub but aren't in the machine list are left out.

## How it works

- **The server** is a small Node app with no dependencies. It signs in to the hub with a read-only account and keeps one realtime connection to it. While at least one page is open, and for a minute after, it subscribes to each machine's `rt_metrics` topic, which makes the hub poll that machine's agent every second. It keeps the minute of history and the event log, and sends every open page one snapshot a second over server-sent events. Pages never see the hub account.
- **The camera** is an H.264 stream in fragmented MP4 over HTTP, the kind go2rtc's `stream.mp4` serves. The server passes it through, and the page plays it with Media Source Extensions, so the browser's hardware decoder does the work. Playback keeps at least half a second of video in hand. Any closer to the newest frame, Firefox for Android drops frames and flushes the decoder, and the picture jumps back and forth.
- **The page** is React, and it redraws once a second, when the snapshot arrives. Motion is CSS transitions and fades, with no blur and nothing animating every frame, so it stays light on a modest tablet.
- **Updates**: when the server restarts with a new build, open pages reload into it on their own.

## Configuration

| Variable | Example | What it is |
| --- | --- | --- |
| `HUB_URL` | `http://192.168.1.20:8090` | The Beszel hub. Required. |
| `HUB_EMAIL`, `HUB_PASSWORD` | | A read-only account on the hub. Keep these in a `.env`, never in the repo. |
| `MACHINES` | `server:Docker host,nas:Storage` | Machines in grid order, each as `name:role`. Names must match the hub's. The first twelve get a tile. |
| `FEED_URL` | `http://192.168.1.20:1984/api/stream.mp4?src=camera&video` | The camera stream. `&video` asks go2rtc for the picture only. Leave it out for no camera. |
| `CAMERA_NAME` | `Front door` | The camera tile's label. |
| `PORT` | `3150` | Where the server listens. |

## Running it

[`compose.yaml`](compose.yaml) is an example deployment. Put `HUB_EMAIL` and `HUB_PASSWORD` in a `.env` beside it, set the rest, then `docker compose up -d` and open `http://<server>:3150`.

Every push to `main` publishes `ghcr.io/chriscorbell/visor:latest` and `:sha-<commit>`.

## On a tablet

Either way works:

- **A kiosk browser set as the home app**, such as Fully Kiosk Browser. It starts on boot, comes back after a crash, stays full screen, and is fine with plain HTTP on the LAN.
- **Installed as an app.** The manifest asks for full screen and landscape. Browsers only offer to install a site served over HTTPS, though, and an installed app doesn't start on boot.

In an ordinary browser, tap the page once to go full screen. Pages can only hold the screen on over HTTPS, so on plain HTTP set the tablet's screen timeout to never.

## Developing

```sh
pnpm install
pnpm server    # the server, reading .env (see Configuration)
pnpm dev       # the page, with /api sent to the server
pnpm build     # type-check and build
```

## License

GPL-3.0. IBM Plex Sans is under the SIL Open Font License, in `licenses/`.
