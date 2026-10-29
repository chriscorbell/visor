import { ArrowDown, ArrowUp, Hourglass, Pause, Thermometer, TriangleAlert, Unplug } from 'lucide-react';
import type { ComponentProps } from 'react';
import type { LogEvent, Machine, Slot } from '../shared/types.ts';
import { clock, level, rate, uptime } from './format.ts';
import { Meter, Sparkline, Tile } from './ui.tsx';

type MeterProps = ComponentProps<typeof Meter>;

/**
 * Memory, storage, and then the GPU, another volume or the temperature.
 * Storage is the biggest ZFS pool where there is one, since the root disk of
 * a TrueNAS app container says nothing.
 */
function meters(m: Machine): MeterProps[] {
  const list: MeterProps[] = [{ label: 'Mem', value: m.mem, warn: 80, crit: 90 }];
  const pct = (v: { usedGb: number; totalGb: number }) => (v.usedGb / v.totalGb) * 100;
  if (m.pool?.zfs) list.push({ label: 'Pool', value: pct(m.pool), warn: 85, crit: 90 });
  else list.push({ label: 'Disk', value: m.disk, warn: 85, crit: 90 });
  if (m.gpu !== null) list.push({ label: 'GPU', value: m.gpu, warn: 75, crit: 90 });
  else if (m.pool && !m.pool.zfs) list.push({ label: 'Vol', value: pct(m.pool), warn: 85, crit: 90 });
  else if (m.tempC !== null) list.push({ label: 'Temp', value: m.tempC, unit: '°', warn: 75, crit: 85 });
  return list;
}

/** A machine that isn't sending numbers: an icon and a word in place of its readings. */
function Placeholder({ machine }: { machine: Machine | null }) {
  if (machine?.status === 'down') {
    return (
      <div className="placeholder">
        <Unplug />
        Offline
      </div>
    );
  }
  if (machine?.status === 'paused') {
    return (
      <div className="placeholder">
        <Pause />
        Paused
      </div>
    );
  }
  return (
    <div className="placeholder">
      <Hourglass />
      {machine ? 'Waiting' : 'Not reporting'}
    </div>
  );
}

/**
 * One machine: its name and state, CPU as a number and a minute of history,
 * then memory, storage and one more reading as bars.
 */
export function MachineTile({ slot, order }: { slot: Slot; order: number }) {
  const m = slot.machine;
  // Up but quiet for a while: the last readings stay, dimmed.
  const stale = m?.status === 'up' && !m.live;
  const state = !m ? 'absent' : m.live ? 'live' : stale ? 'stale' : m.status;
  const alert = m?.live ? m.unhealthyPools[0] : undefined;
  const list = m ? meters(m) : [];
  const temp = m?.tempC != null && !list.some((x) => x.label === 'Temp') ? m.tempC : null;

  return (
    <Tile order={order} className={`machine ${state}${alert ? ' alert' : ''}`}>
      <div className="machine-head">
        <span className="name">{slot.name}</span>
        {m?.live && (
          <span className="state">
            <span className="uptime">{uptime(m.uptimeSec)}</span>
            <i className="dot" />
          </span>
        )}
        {stale && <span className="state">No data</span>}
        {m?.status === 'down' && <i className="dot" />}
      </div>
      {alert ? (
        <div className="role">
          <TriangleAlert />
          {alert[0]} {alert[1].toLowerCase()}
        </div>
      ) : (
        <div className="role">{slot.role || m?.gpuName}</div>
      )}

      {m && (m.live || stale) ? (
        <div className="readings">
          <div className="cpu">
            <div className={`cpu-reading ${level(m.cpu, 75, 90)}`}>
              <span className="cpu-label">CPU</span>
              <span className="cpu-value">
                {Math.round(m.cpu)}
                <small>%</small>
              </span>
            </div>
            <div className="history">
              <Sparkline values={m.cpuHistory} />
            </div>
          </div>
          <div className="meters">
            {list.map((x) => (
              <Meter key={x.label} {...x} />
            ))}
          </div>
          <div className="machine-foot">
            <span>
              <ArrowUp />
              {rate(m.netUpBps)}
            </span>
            <span>
              <ArrowDown />
              {rate(m.netDownBps)}
            </span>
            {temp !== null && (
              <span className="temp">
                <Thermometer />
                {Math.round(temp)}°
              </span>
            )}
          </div>
        </div>
      ) : (
        <Placeholder machine={m} />
      )}
    </Tile>
  );
}

/** The newest events first. Only rows that are new animate in. */
export function EventLog({ events, order }: { events: LogEvent[]; order: number }) {
  return (
    <Tile order={order} className="events">
      <h2 className="tile-title">Events</h2>
      <ol>
        {events
          .slice(-20)
          .reverse()
          .map((e) => (
            <li key={e.id} className={e.tone}>
              <time>{clock(e.at)}</time>
              <span className="source">{e.source}</span>
              <span className="text">{e.text}</span>
            </li>
          ))}
      </ol>
    </Tile>
  );
}
