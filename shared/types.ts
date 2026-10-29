// What the server sends the page over /api/state: a `hello` when the page
// connects, then a `state` snapshot once a second.

export type Link = 'no-credentials' | 'connecting' | 'live' | 'offline';
export type Status = 'up' | 'down' | 'paused' | 'pending';
export type Tone = 'info' | 'good' | 'warn' | 'alert';

export interface Volume {
  name: string;
  usedGb: number;
  totalGb: number;
  zfs: boolean;
}

export interface Machine {
  name: string;
  status: Status;
  /** Up, and sending samples. */
  live: boolean;
  uptimeSec: number;
  cpu: number;
  mem: number;
  memUsedGb: number;
  memTotalGb: number;
  disk: number;
  /** The busiest GPU's load, or null on a machine without one. */
  gpu: number | null;
  gpuName: string | null;
  /**
   * The biggest ZFS pool, or else the biggest extra filesystem. On the NAS
   * this, not the root disk, is the storage that matters.
   */
  pool: Volume | null;
  /** ZFS pools whose health isn't ONLINE: name and health. */
  unhealthyPools: [string, string][];
  tempC: number | null;
  netUpBps: number;
  netDownBps: number;
  /** The last minute of CPU, oldest first. */
  cpuHistory: number[];
}

/** A grid slot, kept for one fleet machine whether or not it has reported. */
export interface Slot {
  name: string;
  role: string;
  machine: Machine | null;
}

/** The live machines' combined memory and traffic. */
export interface FleetSummary {
  memUsedGb: number;
  memTotalGb: number;
  netUpBps: number;
  netDownBps: number;
}

export interface LogEvent {
  id: number;
  at: number;
  source: string;
  text: string;
  tone: Tone;
}

export interface Snapshot {
  link: Link;
  slots: Slot[];
  fleet: FleetSummary;
  events: LogEvent[];
}

export interface Hello {
  /** The server's build. The page reloads when it changes. */
  build: string;
  /** The camera's label, or null when there is no camera. */
  camera: string | null;
}

/** Seconds of history in every sparkline. */
export const HISTORY = 60;
