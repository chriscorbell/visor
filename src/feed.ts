// Plays the Camera's stream from /api/feed in a <video>: H.264 in fragmented
// MP4 over HTTP, fed to the browser's hardware decoder through Media Source
// Extensions. Reconnects whenever frames stop arriving.

export type FeedState = 'connecting' | 'live' | 'reconnecting';

// The Camera's keyframe stall is ~0.3 s, so a gap this long is a real outage.
const STALL_MS = 2500;
// A new viewer waits for the next keyframe, which comes every 2 s.
const FIRST_FRAME_MS = 6000;
const RETRY_MS = [500, 1000, 2000, 5000];
const TICK_MS = 250;
// Frames arrive in bursts after every keyframe stall. Playback keeps at least
// BUFFER_S of them in hand at the leanest moment of each window, easing a
// little faster or slower to hold it. Closer to the newest frame, Firefox for
// Android keeps finding its decoded frames late, drops them and flushes the
// decoder, and some decoders then show pictures from a second or two before.
const WINDOW_MS = 4000;
const BUFFER_S = 0.5;
const MAX_SPEEDUP = 0.08;
const MAX_SLOWDOWN = 0.05;
// Further behind than this, as after a hiccup in the decoder, jump ahead to the newest frames.
const JUMP_S = 3;
// Seconds of played video kept in the buffer.
const KEEP_S = 4;

export class Feed {
  readonly #video: HTMLVideoElement;
  readonly #url: string;
  readonly #onState: (state: FeedState) => void;
  #abort: AbortController | null = null;
  #objectUrl = '';
  #state: FeedState = 'connecting';
  #attemptAt = 0;
  #lastFrameAt = 0;
  #lastTime = -1;
  #failures = 0;
  #retryTimer = 0;
  #ticker = 0;
  #windowStart = 0;
  #windowMinLag = Infinity;

  constructor(video: HTMLVideoElement, url: string, onState: (state: FeedState) => void) {
    this.#video = video;
    this.#url = url;
    this.#onState = onState;
    video.addEventListener('waiting', () => (video.playbackRate = 1));
    video.addEventListener('error', () => {
      if (this.#abort) this.#fail();
    });
  }

  start(): void {
    if (this.#ticker) return;
    this.#ticker = window.setInterval(() => this.#tick(), TICK_MS);
    void this.#connect();
  }

  stop(): void {
    window.clearInterval(this.#ticker);
    window.clearTimeout(this.#retryTimer);
    this.#ticker = 0;
    if (this.#state === 'live') this.#setState('reconnecting');
    this.#close();
    // Lets go of the decoder.
    this.#video.removeAttribute('src');
    this.#video.load();
  }

  async #connect(): Promise<void> {
    this.#close();
    const abort = new AbortController();
    this.#abort = abort;
    this.#attemptAt = performance.now();
    this.#lastTime = -1;
    this.#windowStart = 0;
    const video = this.#video;
    const source = new MediaSource();
    this.#objectUrl = URL.createObjectURL(source);
    video.src = this.#objectUrl;
    video.playbackRate = 1;

    try {
      await once(source, 'sourceopen', abort.signal);
      const res = await fetch(this.#url, { signal: abort.signal, cache: 'no-store' });
      if (!res.ok || !res.body) throw new Error(`feed answered ${res.status}`);
      const type = res.headers.get('content-type') ?? '';
      if (!MediaSource.isTypeSupported(type)) throw new Error(`can't play ${type}`);
      const buffer = source.addSourceBuffer(type);
      const reader = res.body.getReader();
      let started = false;
      let trimmedAt = 0;
      for (;;) {
        const { value, done } = await reader.read();
        if (done) throw new Error('feed ended');
        // How much was left to play when new frames came: the slack the buffer had.
        if (started) this.#windowMinLag = Math.min(this.#windowMinLag, this.#lag());
        await append(buffer, value, abort.signal);
        // The stream starts on a keyframe; playing from there, once there's a
        // buffer in hand, needs no seek.
        if (!started && video.buffered.length > 0 && video.buffered.end(0) - video.buffered.start(0) >= BUFFER_S) {
          started = true;
          video.currentTime = video.buffered.start(0);
          video.play().catch(() => {});
        }
        const now = performance.now();
        if (started && now - trimmedAt > 2000 && video.buffered.length > 0) {
          trimmedAt = now;
          const keepFrom = video.currentTime - KEEP_S;
          if (keepFrom > video.buffered.start(0)) {
            buffer.remove(0, keepFrom);
            await once(buffer, 'updateend', abort.signal);
          }
        }
      }
    } catch {
      if (this.#abort === abort) this.#fail();
    }
  }

  /** Seconds between what's on screen and the newest frame. */
  #lag(): number {
    const b = this.#video.buffered;
    return b.length > 0 ? b.end(b.length - 1) - this.#video.currentTime : 0;
  }

  #tick(): void {
    if (!this.#abort) return;
    const video = this.#video;
    const now = performance.now();
    if (this.#lastTime >= 0 && video.currentTime > this.#lastTime) {
      this.#lastFrameAt = now;
      this.#failures = 0;
      this.#setState('live');
    }
    this.#lastTime = video.currentTime;
    const stalled =
      this.#state === 'live' ? now - this.#lastFrameAt > STALL_MS : now - this.#attemptAt > FIRST_FRAME_MS;
    if (stalled) return this.#fail();

    const b = video.buffered;
    if (b.length === 0) return;
    const end = b.end(b.length - 1);
    // A hole in the stream leaves a gap playback won't cross by itself.
    const atGap = b.length > 1 && video.currentTime >= b.end(0) - 0.1;
    if (atGap || end - video.currentTime > JUMP_S) {
      video.currentTime = Math.max(b.start(b.length - 1), end - BUFFER_S);
      this.#windowStart = 0;
      return;
    }
    if (!this.#windowStart) {
      this.#windowStart = now;
      this.#windowMinLag = Infinity;
    } else if (now - this.#windowStart >= WINDOW_MS) {
      const spare = this.#windowMinLag - BUFFER_S;
      const rate = Number.isFinite(spare)
        ? 1 + Math.max(-MAX_SLOWDOWN, Math.min(MAX_SPEEDUP, spare / (WINDOW_MS / 1000)))
        : 1;
      if (Math.abs(video.playbackRate - rate) > 0.001) video.playbackRate = rate;
      this.#windowStart = now;
      this.#windowMinLag = Infinity;
    }
  }

  #fail(): void {
    // The last frame stays on screen, shaded, until the next one arrives.
    if (this.#state === 'live') this.#setState('reconnecting');
    this.#close();
    const delay = RETRY_MS[Math.min(this.#failures++, RETRY_MS.length - 1)];
    window.clearTimeout(this.#retryTimer);
    this.#retryTimer = window.setTimeout(() => void this.#connect(), delay);
  }

  #close(): void {
    this.#abort?.abort();
    this.#abort = null;
    if (this.#objectUrl) URL.revokeObjectURL(this.#objectUrl);
    this.#objectUrl = '';
  }

  #setState(state: FeedState): void {
    if (state === this.#state) return;
    this.#state = state;
    this.#onState(state);
  }
}

async function append(buffer: SourceBuffer, data: Uint8Array<ArrayBuffer>, signal: AbortSignal): Promise<void> {
  if (buffer.updating) await once(buffer, 'updateend', signal);
  buffer.appendBuffer(data);
  await once(buffer, 'updateend', signal);
}

/** Waits for `event`, failing on an `error` event or an abort. */
function once(target: EventTarget, event: string, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(signal.reason);
    const done = (fn: () => void) => () => {
      target.removeEventListener(event, ok);
      target.removeEventListener('error', fail);
      signal.removeEventListener('abort', fail);
      fn();
    };
    const ok = done(resolve);
    const fail = done(() => reject(new Error(`${event} failed`)));
    target.addEventListener(event, ok);
    target.addEventListener('error', fail);
    signal.addEventListener('abort', fail);
  });
}
