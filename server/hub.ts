import type { Link } from '../shared/types.ts';
import type { SystemRecord } from './fleet.ts';

type Json = Record<string, any>;

export interface HubListener {
  link(link: Link): void;
  /** The full list of machines, replacing whatever came before. */
  systems(systems: SystemRecord[]): void;
  /** One machine's record changed: its status, name or once-a-minute info. */
  system(system: SystemRecord): void;
  systemDeleted(id: string): void;
  /** A machine's live sample: the agent's `stats` and `info`, once a second. */
  metrics(id: string, stats: Json, info: Json): void;
  /** Whether samples are on their way. */
  sampling(on: boolean): void;
}

export interface HubOptions {
  url: string;
  email: string | undefined;
  password: string | undefined;
  follows: (name: string) => boolean;
  listener: HubListener;
}

const SYSTEMS_TOPIC = 'systems/*';
/** Sign in afresh now and then, long before the hub's tokens expire. */
const RENEW_MS = 6 * 60 * 60_000;
/** Machines report every second; this long without one means the stream is a dud. */
const QUIET_MS = 20_000;
/** Without samples, the stream only carries the once-a-minute systems updates. */
const IDLE_QUIET_MS = 3 * 60_000;
/** A stream that ran this long ended normally, so reconnect without showing the link down. */
const STEADY_MS = 60_000;
const RETRY_MS = [1_000, 2_000, 5_000, 10_000, 30_000];

/**
 * Serialized exactly as PocketBase's JS SDK does it, which Beszel's web
 * interface uses: the hub keeps one subscription name per machine, so a
 * different spelling of the same topic would miss its updates.
 */
export function metricsTopic(systemId: string): string {
  return 'rt_metrics?options=' + encodeURIComponent(JSON.stringify({ query: { system: systemId } }));
}

/**
 * Talks to the Beszel hub, which holds every machine's stats.
 *
 * The hub is PocketBase underneath. Machines are records in its `systems`
 * collection, refreshed once a minute. Subscribing to a machine's
 * `rt_metrics` topic over PocketBase's realtime stream makes the hub poll that
 * machine's agent every second for as long as the subscription lasts. That
 * costs the machines something, so visor subscribes only while someone is
 * watching. Only machines that pass `follows` are listed or subscribed to.
 */
export class Hub {
  readonly #options: HubOptions;
  #watching = false;
  /** Sends the current subscriptions on the open stream, if there is one. */
  #resubscribe: (() => void) | null = null;

  constructor(options: HubOptions) {
    this.#options = options;
  }

  start(): void {
    void this.#run();
  }

  /** Whether someone is looking, and so whether the hub should poll the agents. */
  set watching(on: boolean) {
    if (on === this.#watching) return;
    this.#watching = on;
    this.#resubscribe?.();
  }

  async #run(): Promise<void> {
    const { email, password, listener } = this.#options;
    if (!email || !password) {
      listener.link('no-credentials');
      return;
    }
    let failures = 0;
    let reconnecting = false;
    for (;;) {
      if (!reconnecting) listener.link('connecting');
      reconnecting = false;
      let liveAt = 0;
      try {
        const token = await this.#signIn(email, password);
        const systems = await this.#listSystems(token);
        listener.systems(systems);
        await this.#follow(token, new Set(systems.map((s) => s.id)), systems.some((s) => s.status === 'up'), () => {
          failures = 0;
          liveAt = Date.now();
        });
      } catch (e) {
        console.log(`Hub link dropped: ${e instanceof Error ? e.message : e}`);
      } finally {
        this.#resubscribe = null;
        listener.sampling(false);
      }
      // The hub ends every realtime stream after 30 minutes, and visor ends
      // its own to renew the sign-in. After a stream that ran a while,
      // reconnect at once with the link still shown live; it shows down only
      // if that fails.
      if (liveAt > 0 && Date.now() - liveAt > STEADY_MS) {
        reconnecting = true;
        continue;
      }
      listener.link('offline');
      await sleep(RETRY_MS[Math.min(failures++, RETRY_MS.length - 1)]);
    }
  }

  async #signIn(email: string, password: string): Promise<string> {
    const auth = await this.#request('POST', '/api/collections/users/auth-with-password', null, {
      identity: email,
      password,
    });
    return auth.token;
  }

  async #listSystems(token: string): Promise<SystemRecord[]> {
    const page = await this.#request('GET', '/api/collections/systems/records?perPage=200&sort=name', token);
    return (page.items as Json[]).map(parseSystem).filter((s) => this.#options.follows(s.name));
  }

  /**
   * Opens the realtime stream and dispatches its events until it drops, or
   * until it is old enough that the sign-in should be renewed.
   */
  async #follow(token: string, systemIds: Set<string>, anyUp: boolean, onLive: () => void): Promise<void> {
    const { listener, follows } = this.#options;
    const abort = new AbortController();
    const res = await fetch(this.#options.url + '/api/realtime', {
      headers: { Accept: 'text/event-stream' },
      signal: abort.signal,
    });
    if (!res.ok || !res.body) throw new Error(`realtime: HTTP ${res.status}`);

    const openedAt = Date.now();
    let lastByteAt = openedAt;
    let lastSampleAt = openedAt;
    let clientId: string | null = null;
    let topics = new Map<string, string>();
    let sampling = false;

    const subscribe = () => {
      if (!clientId) return;
      topics = new Map();
      if (this.#watching) for (const id of systemIds) topics.set(metricsTopic(id), id);
      if (this.#watching !== sampling) {
        sampling = this.#watching;
        lastSampleAt = Date.now();
        listener.sampling(sampling);
      }
      const subscriptions = [SYSTEMS_TOPIC, ...topics.keys()];
      this.#request('POST', '/api/realtime', token, { clientId, subscriptions }).catch((e) => {
        console.log(`Subscribing failed: ${e instanceof Error ? e.message : e}`);
        abort.abort();
      });
    };
    this.#resubscribe = subscribe;

    // fetch has no read timeout, so a watchdog ends a stream that went quiet.
    let stopReason = '';
    const watchdog = setInterval(() => {
      const now = Date.now();
      if (now - openedAt > RENEW_MS) stopReason = 'renewing sign-in';
      else if (now - lastByteAt > (sampling ? QUIET_MS : IDLE_QUIET_MS)) stopReason = 'stream went quiet';
      // A stream that says it's live but brings no samples is a dud. Seen
      // right after the client restarts, while the hub still holds the
      // connection from before; a fresh connection fixes it.
      else if (sampling && anyUp && now - lastSampleAt > QUIET_MS) stopReason = `no samples for ${QUIET_MS / 1000} s`;
      if (stopReason) abort.abort();
    }, 1_000);

    const dispatch = (event: string, payload: string) => {
      if (event === 'PB_CONNECT') {
        clientId = JSON.parse(payload).clientId;
        subscribe();
        listener.link('live');
        onLive();
      } else if (event === SYSTEMS_TOPIC) {
        const message = JSON.parse(payload);
        const record = parseSystem(message.record);
        anyUp ||= record.status === 'up';
        // A machine renamed out of the fleet list goes like a deleted one.
        if (message.action === 'delete' || !follows(record.name)) {
          if (systemIds.delete(record.id)) {
            listener.systemDeleted(record.id);
            subscribe();
          }
        } else {
          listener.system(record);
          if (!systemIds.has(record.id)) {
            systemIds.add(record.id);
            subscribe();
          }
        }
      } else {
        const id = topics.get(event);
        if (!id) return;
        lastSampleAt = Date.now();
        const sample = JSON.parse(payload);
        if (sample.stats && sample.info) listener.metrics(id, sample.stats, sample.info);
      }
    };

    try {
      const decoder = new TextDecoder();
      let buffered = '';
      let event = '';
      let data: string[] = [];
      for await (const chunk of res.body) {
        lastByteAt = Date.now();
        buffered += decoder.decode(chunk, { stream: true });
        let newline: number;
        while ((newline = buffered.indexOf('\n')) >= 0) {
          const line = buffered.slice(0, newline).replace(/\r$/, '');
          buffered = buffered.slice(newline + 1);
          if (line === '') {
            if (data.length > 0) dispatch(event, data.join('\n'));
            event = '';
            data = [];
          } else if (line.startsWith('event:')) {
            event = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            data.push(line.slice(5).trimStart());
          }
        }
      }
      throw new Error('stream closed');
    } catch (e) {
      throw new Error(stopReason || (e instanceof Error ? e.message : String(e)));
    } finally {
      clearInterval(watchdog);
      abort.abort();
    }
  }

  async #request(method: string, path: string, token: string | null, body?: Json): Promise<Json> {
    const headers: Record<string, string> = {};
    if (token) headers.Authorization = token;
    if (body) headers['Content-Type'] = 'application/json';
    const res = await fetch(this.#options.url + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(15_000),
    });
    const text = await res.text();
    if (!res.ok) throw new Error(`${method} ${path}: HTTP ${res.status} ${text.slice(0, 200)}`);
    return text ? JSON.parse(text) : {};
  }
}

function parseSystem(json: Json): SystemRecord {
  return { id: json.id, name: json.name ?? '', status: json.status ?? '', info: json.info ?? {} };
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
