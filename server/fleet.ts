import { HISTORY } from '../shared/types.ts';
import type { FleetSummary, LogEvent, Machine, Slot, Snapshot, Status, Tone, Volume } from '../shared/types.ts';

type Json = Record<string, any>;

/** A machine as the hub's `systems` collection describes it. */
export interface SystemRecord {
  id: string;
  name: string;
  status: string;
  info: Json;
}

export interface FleetMachine {
  name: string;
  role: string;
}

/** A machine that sent nothing for this long is shown as stale. */
const STALE_MS = 10_000;
const MAX_EVENTS = 40;
/** A load must stay over its threshold this long to be logged. */
const SUSTAIN_MS = 5_000;

function parseStatus(value: string): Status {
  return value === 'up' || value === 'down' || value === 'paused' ? value : 'pending';
}

function num(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** What visor knows about one machine. */
class Node {
  readonly id: string;
  name = '';
  status: Status = 'pending';
  uptimeSec = 0;
  cpu = 0;
  mem = 0;
  memUsedGb = 0;
  memTotalGb = 0;
  disk = 0;
  gpu: number | null = null;
  gpuName: string | null = null;
  pool: Volume | null = null;
  unhealthyPools = new Map<string, string>();
  tempC: number | null = null;
  netUpBps = 0;
  netDownBps = 0;
  load1: number | null = null;
  lastSampleAt = 0;
  cpuHistory: number[] = [];

  constructor(id: string) {
    this.id = id;
  }

  /** The once-a-minute summary in the hub's systems record. */
  applyInfo(info: Json, now: number): void {
    this.uptimeSec = num(info.u) ?? this.uptimeSec;
    if (now - this.lastSampleAt < STALE_MS) return;
    this.cpu = num(info.cpu) ?? this.cpu;
    this.mem = num(info.mp) ?? this.mem;
    this.disk = num(info.dp) ?? this.disk;
    if ('g' in info) this.gpu = num(info.g);
    const temp = num(info.dt);
    if (temp !== null && temp > 0) this.tempC = temp;
    if (Array.isArray(info.la)) this.load1 = num(info.la[0]);
  }

  /** A live sample from the agent, once a second. */
  applySample(stats: Json, info: Json, now: number): void {
    this.lastSampleAt = now;
    this.uptimeSec = num(info.u) ?? this.uptimeSec;
    this.cpu = num(stats.cpu) ?? 0;
    this.mem = num(stats.mp) ?? 0;
    this.memUsedGb = num(stats.mu) ?? 0;
    this.memTotalGb = num(stats.m) ?? 0;
    this.disk = num(stats.dp) ?? 0;
    if (Array.isArray(stats.b)) {
      this.netUpBps = num(stats.b[0]) ?? 0;
      this.netDownBps = num(stats.b[1]) ?? 0;
    }
    if (Array.isArray(stats.la)) this.load1 = num(stats.la[0]);
    const temp = num(info.dt);
    this.tempC = temp !== null && temp > 0 ? temp : null;

    const gpus: Json[] = Object.values(stats.g ?? {});
    const busiest = gpus.reduce<Json | null>((best, g) => (!best || (g.u ?? 0) > (best.u ?? 0) ? g : best), null);
    this.gpu = busiest ? (num(busiest.u) ?? 0) : null;
    this.gpuName = busiest?.n || null;

    const unhealthy = new Map<string, string>();
    const zfs = Object.entries<Json>(stats.z ?? {});
    let volumes: Volume[];
    if (zfs.length > 0) {
      volumes = zfs.map(([name, z]) => {
        if (z.h && z.h !== 'ONLINE') unhealthy.set(name, z.h);
        return { name, usedGb: num(z.du) ?? 0, totalGb: num(z.d) ?? 0, zfs: true };
      });
    } else {
      volumes = Object.entries<Json>(stats.efs ?? {}).map(([name, v]) => ({
        name,
        usedGb: num(v.du) ?? 0,
        totalGb: num(v.d) ?? 0,
        zfs: false,
      }));
    }
    this.pool = volumes.filter((v) => v.totalGb > 0).reduce<Volume | null>((a, v) => (!a || v.totalGb > a.totalGb ? v : a), null);
    this.unhealthyPools = unhealthy;

    this.cpuHistory.push(this.cpu);
    if (this.cpuHistory.length > HISTORY) this.cpuHistory.shift();
  }
}

/**
 * Every fleet machine the hub knows, plus the event log: status changes, and
 * loads that stay over a threshold for a few seconds.
 */
export class Fleet {
  readonly order: FleetMachine[];
  readonly #nodes = new Map<string, Node>();
  readonly #events: LogEvent[] = [];
  readonly #alarms = new Map<string, number>();
  readonly #cpuHistory: number[] = [];
  #nextEventId = 1;
  /** When the hub started sending samples. Until they arrive, machines that are up count as live. */
  #samplingSince = 0;

  constructor(order: FleetMachine[]) {
    this.order = order;
  }

  /** Only fleet machines are shown, so one the hub keeps for its history stays off the screen. */
  follows(name: string): boolean {
    return this.order.some((m) => m.name === name);
  }

  sampling(on: boolean): void {
    this.#samplingSince = on ? Date.now() : 0;
  }

  replace(systems: SystemRecord[]): void {
    const keep = new Set(systems.map((s) => s.id));
    for (const id of this.#nodes.keys()) if (!keep.has(id)) this.#nodes.delete(id);
    for (const system of systems) this.update(system, false);
  }

  update(system: SystemRecord, announce = true): void {
    let node = this.#nodes.get(system.id);
    if (!node) {
      node = new Node(system.id);
      this.#nodes.set(system.id, node);
    }
    node.name = system.name;
    const next = parseStatus(system.status);
    if (announce && next !== node.status) {
      if (next === 'up') this.log(node.name, 'ONLINE', 'good');
      else if (next === 'down') this.log(node.name, 'SIGNAL LOST', 'alert');
      else if (next === 'paused') this.log(node.name, 'PAUSED', 'info');
    }
    node.status = next;
    node.applyInfo(system.info, Date.now());
  }

  remove(id: string): void {
    this.#nodes.delete(id);
  }

  sample(id: string, stats: Json, info: Json): void {
    const node = this.#nodes.get(id);
    if (!node) return;
    const was = node.unhealthyPools;
    node.applySample(stats, info, Date.now());
    for (const [name, health] of node.unhealthyPools) {
      if (was.get(name) !== health) this.log(node.name, `${name} ${health}`, 'alert');
    }
    for (const name of was.keys()) {
      if (!node.unhealthyPools.has(name)) this.log(node.name, `${name} ONLINE`, 'good');
    }
    this.#watch(node, 'CPU', node.cpu, 90, 70);
    this.#watch(node, 'MEM', node.mem, 90, 80);
    this.#watch(node, 'DSK', node.disk, 90, 85);
    if (node.tempC !== null) this.#watch(node, 'TEMP', node.tempC, 85, 75, '°C');
  }

  log(source: string, text: string, tone: Tone): void {
    this.#events.push({ id: this.#nextEventId++, at: Date.now(), source, text, tone });
    if (this.#events.length > MAX_EVENTS) this.#events.shift();
  }

  #live(node: Node, now: number): boolean {
    if (node.status !== 'up') return false;
    return now - node.lastSampleAt < STALE_MS || (this.#samplingSince > 0 && now - this.#samplingSince < STALE_MS);
  }

  /**
   * Logs a reading that has stayed over `high` for a few seconds, and again
   * once it falls back under `clear`, so a brief spike stays quiet. A
   * negative alarm is one that started but hasn't been logged yet.
   */
  #watch(node: Node, metric: string, value: number, high: number, clear: number, unit = '%'): void {
    const key = node.id + metric;
    const now = Date.now();
    const since = this.#alarms.get(key);
    if (value >= high && since === undefined) {
      this.#alarms.set(key, -now);
    } else if (value >= high && since !== undefined && since < 0 && now + since >= SUSTAIN_MS) {
      this.#alarms.set(key, now);
      this.log(node.name, `${metric} HIGH ${Math.trunc(value)}${unit}`, 'warn');
    } else if (value < clear && since !== undefined) {
      this.#alarms.delete(key);
      if (since > 0) this.log(node.name, `${metric} NOMINAL ${Math.trunc(value)}${unit}`, 'info');
    } else if (value < high && since !== undefined && since < 0) {
      this.#alarms.delete(key);
    }
  }

  #machine(node: Node, now: number): Machine {
    return {
      name: node.name,
      status: node.status,
      live: this.#live(node, now),
      uptimeSec: node.uptimeSec,
      cpu: node.cpu,
      mem: node.mem,
      memUsedGb: node.memUsedGb,
      memTotalGb: node.memTotalGb,
      disk: node.disk,
      gpu: node.gpu,
      gpuName: node.gpuName,
      pool: node.pool,
      unhealthyPools: [...node.unhealthyPools],
      tempC: node.tempC,
      netUpBps: node.netUpBps,
      netDownBps: node.netDownBps,
      load1: node.load1,
      cpuHistory: node.cpuHistory,
    };
  }

  /** The whole screen's data. Called once a second, which also moves the fleet's CPU history on. */
  snapshot(link: Snapshot['link']): Snapshot {
    const now = Date.now();
    const byName = new Map([...this.#nodes.values()].map((n) => [n.name, n]));
    const slots: Slot[] = this.order.map(({ name, role }) => {
      const node = byName.get(name);
      return { name, role, machine: node ? this.#machine(node, now) : null };
    });
    return { link, slots, fleet: this.#summary(now), events: this.#events };
  }

  #summary(now: number): FleetSummary {
    const nodes = [...this.#nodes.values()];
    const live = nodes.filter((n) => this.#live(n, now));
    const sum = (f: (n: Node) => number) => live.reduce((total, n) => total + f(n), 0);
    const cpu = live.length > 0 ? sum((n) => n.cpu) / live.length : 0;
    this.#cpuHistory.push(cpu);
    if (this.#cpuHistory.length > HISTORY) this.#cpuHistory.shift();
    return {
      online: nodes.filter((n) => n.status === 'up').length,
      total: nodes.length,
      cpu,
      memUsedGb: sum((n) => n.memUsedGb),
      memTotalGb: sum((n) => n.memTotalGb),
      netUpBps: sum((n) => n.netUpBps),
      netDownBps: sum((n) => n.netDownBps),
      cpuHistory: this.#cpuHistory,
    };
  }
}
