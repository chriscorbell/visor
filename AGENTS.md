# visor

A web dashboard for an always-on tablet: every fleet machine's stats from a Beszel hub, plus a camera feed. The README covers what it shows, where the data comes from and how it's configured. Read it first.

- **Nothing identifying goes in the repo.** It's public. Machine names, roles, addresses, the camera's label and the hub account all come from environment variables. Examples use placeholders: `192.168.1.x`, `server`, `nas`, `desktop`. Deployment details and how to reach the tablet are in Chris's private notes, not here.
- **Stack:** pnpm, TypeScript, Vite and React for the page (`src/`). The server (`server/`) is plain Node running its TypeScript directly, with no dependencies: keep it that way, and use only erasable TypeScript syntax there. `shared/types.ts` is the wire format between them.
- **Light on the tablet.** It's a 4 GB MediaTek tablet, on all day. The page renders once a second, when the snapshot arrives. Motion is CSS transitions and opacity or transform animations only: no blur or `backdrop-filter`, no JavaScript animation loops, nothing that runs every video frame.
- **The tablet:** before opening or installing anything on it over adb, check `dumpsys activity activities | grep topResumedActivity`; Chris may be using it.
- **Beszel realtime:** subscribing to `rt_metrics?options={"query":{"system":"<id>"}}` makes the hub poll that agent every second, so the server subscribes only while a page is open. The topic must be serialized exactly as PocketBase's JS SDK does (`metricsTopic` in `server/hub.ts`), because the hub keeps one subscription name per machine and the Beszel web interface subscribes the same way. PocketBase ends every realtime stream after 30 minutes, so `Hub` reconnects at once after a stream that ran a while, and only shows the link down if that fails.
- **The camera player** (`src/feed.ts`) is visor's own, built on Media Source Extensions. camview's players are separate: a WebRTC page and a native Android app.
- **Releases:** every push to `main` publishes the image to GHCR. The server picks it up, and open pages reload into the new build when it restarts.
- **Writing style:** plain, short sentences, no filler, in code comments, docs and commit messages. No attribution trailers in commits or PRs.
