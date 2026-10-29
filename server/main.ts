import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { extname, join, normalize } from 'node:path';
import { Readable } from 'node:stream';
import { fileURLToPath } from 'node:url';
import type { Hello, Link } from '../shared/types.ts';
import { Fleet } from './fleet.ts';
import type { FleetMachine } from './fleet.ts';
import { Hub } from './hub.ts';

// Addresses, machine names and the hub account come from the environment,
// never the repo. See compose.yaml.
const env = process.env;
const PORT = Number(env.PORT ?? 3150);
const BUILD = env.BUILD ?? 'dev';
const HUB_URL = (env.HUB_URL ?? '').replace(/\/$/, '');
const FEED_URL = env.FEED_URL ?? '';
const CAMERA_NAME = env.CAMERA_NAME || 'Camera';
const STATIC_DIR = fileURLToPath(new URL('../dist/', import.meta.url));
/** After the last page closes, keep the hub polling this long, so a reload doesn't restart it. */
const LINGER_MS = 60_000;

if (!HUB_URL) {
  console.error('HUB_URL is not set');
  process.exit(1);
}

/** `server:Docker host,nas:Storage`: fleet machines in grid order, each with its role. */
function parseMachines(value: string): FleetMachine[] {
  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const colon = entry.indexOf(':');
      return colon < 0
        ? { name: entry, role: '' }
        : { name: entry.slice(0, colon).trim(), role: entry.slice(colon + 1).trim() };
    });
}

const fleet = new Fleet(parseMachines(env.MACHINES ?? ''));
let link: Link = 'connecting';

const hub = new Hub({
  url: HUB_URL,
  email: env.HUB_EMAIL,
  password: env.HUB_PASSWORD,
  follows: (name) => fleet.follows(name),
  listener: {
    link(next) {
      if (next === link) return;
      if (next === 'live') fleet.log('hub', 'Connected', 'good');
      else if (link === 'live') fleet.log('hub', 'Connection lost', 'alert');
      else if (next === 'no-credentials') fleet.log('hub', 'No credentials set', 'alert');
      link = next;
    },
    systems: (systems) => fleet.replace(systems),
    system: (system) => fleet.update(system),
    systemDeleted: (id) => fleet.remove(id),
    metrics: (id, stats, info) => fleet.sample(id, stats, info),
    sampling: (on) => fleet.sampling(on),
  },
});

fleet.log('visor', `Started, build ${BUILD.slice(0, 7)}`, 'info');
hub.start();

// ---- pages watching ------------------------------------------------------------

const pages = new Set<ServerResponse>();
let lingerTimer: NodeJS.Timeout | undefined;

function watchers(): void {
  clearTimeout(lingerTimer);
  if (pages.size > 0) hub.watching = true;
  else lingerTimer = setTimeout(() => (hub.watching = false), LINGER_MS);
}

/**
 * Everything on screen changes on one beat, once a second, so the page draws
 * one frame for it instead of one per machine sample.
 */
function heartbeat(): void {
  const state = `event: state\ndata: ${JSON.stringify(fleet.snapshot(link))}\n\n`;
  for (const page of pages) page.write(state);
  setTimeout(heartbeat, 1000 - (Date.now() % 1000));
}
heartbeat();

function streamState(req: IncomingMessage, res: ServerResponse): void {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-store',
    'X-Accel-Buffering': 'no',
  });
  pages.add(res);
  // Sampling starts here when it was off, so the first snapshot shows machines as live, not stale.
  watchers();
  const hello: Hello = { build: BUILD, camera: FEED_URL ? CAMERA_NAME : null };
  res.write(
    `retry: 2000\nevent: hello\ndata: ${JSON.stringify(hello)}\n\n` +
      `event: state\ndata: ${JSON.stringify(fleet.snapshot(link))}\n\n`,
  );
  req.on('close', () => {
    pages.delete(res);
    watchers();
  });
}

// ---- camera ----------------------------------------------------------------------

/**
 * Passes the Camera's stream through from the Relay, so the page reads it
 * from its own origin.
 */
async function streamFeed(req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (!FEED_URL) return notFound(res);
  const abort = new AbortController();
  req.on('close', () => abort.abort());
  try {
    const upstream = await fetch(FEED_URL, { signal: abort.signal });
    if (!upstream.ok || !upstream.body) {
      res.writeHead(502).end();
      return;
    }
    res.writeHead(200, {
      'Content-Type': upstream.headers.get('content-type') ?? 'video/mp4',
      'Cache-Control': 'no-store',
    });
    Readable.fromWeb(upstream.body).on('error', () => res.destroy()).pipe(res);
  } catch {
    if (!res.headersSent) res.writeHead(502);
    res.end();
  }
}

// ---- page ------------------------------------------------------------------------

const TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.webmanifest': 'application/manifest+json',
};

async function serveStatic(path: string, res: ServerResponse): Promise<void> {
  const file = normalize(join(STATIC_DIR, path === '/' ? 'index.html' : path));
  if (!file.startsWith(STATIC_DIR)) return notFound(res);
  try {
    const info = await stat(file);
    if (!info.isFile()) return notFound(res);
    res.writeHead(200, {
      'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream',
      'Content-Length': info.size,
      // Vite names built assets by their content; everything else is checked each time.
      'Cache-Control': path.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache',
    });
    createReadStream(file).pipe(res);
  } catch {
    notFound(res);
  }
}

function notFound(res: ServerResponse): void {
  res.writeHead(404, { 'Content-Type': 'text/plain' }).end('Not found');
}

createServer((req, res) => {
  const path = new URL(req.url ?? '/', 'http://visor').pathname;
  if (req.method !== 'GET' && req.method !== 'HEAD') return void res.writeHead(405).end();
  if (path === '/api/state') return streamState(req, res);
  if (path === '/api/feed') return void streamFeed(req, res);
  if (path === '/api/health') return void res.writeHead(200, { 'Content-Type': 'text/plain' }).end('ok');
  void serveStatic(path, res);
}).listen(PORT, () => console.log(`visor ${BUILD} on :${PORT}`));

for (const signal of ['SIGINT', 'SIGTERM'] as const) process.on(signal, () => process.exit(0));
