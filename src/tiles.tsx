import { ArrowDown, ArrowUp, TriangleAlert } from 'lucide-react';
import type { CSSProperties } from 'react';
import type { FleetSummary, LogEvent, Machine, Slot } from '../shared/types.ts';
import { clock, level, rate, size, uptime } from './format.ts';
import { Bar, Dot, Panel, Sparkline } from './ui.tsx';
import type { Accent } from './ui.tsx';

type Area = { area: CSSProperties; order: number };

function Net({ up, down }: { up: number; down: number }) {
  return (
    <span className="net">
      <ArrowUp className="glyph" />
      {rate(up)} <ArrowDown className="glyph" />
      {rate(down)}
    </span>
  );
}

/** A bar's label and reading, and where it turns amber and red. */
type Reading = [label: string, value: number, warn: number, crit: number];

/**
 * The third bar: the biggest ZFS pool where there is one, since the root
 * disk of a TrueNAS app container says nothing; otherwise the root disk.
 */
function third(m: Machine): Reading {
  return m.pool?.zfs ? ['ZFS', (m.pool.usedGb / m.pool.totalGb) * 100, 85, 90] : ['DSK', m.disk, 85, 90];
}

/** The fourth bar: the GPU, else another volume, else the temperature. */
function fourth(m: Machine): Reading | null {
  if (m.gpu !== null) return ['GPU', m.gpu, 75, 90];
  if (m.pool && !m.pool.zfs) return ['VOL', (m.pool.usedGb / m.pool.totalGb) * 100, 85, 90];
  if (m.tempC !== null) return ['TMP', m.tempC, 75, 85];
  return null;
}

function stateOf(m: Machine): [string, string] {
  if (m.live) return [`UP ${uptime(m.uptimeSec)}`, 'hi'];
  if (m.status === 'up') return ['STALE', 'amber'];
  if (m.status === 'down') return ['OFFLINE', 'red'];
  if (m.status === 'paused') return ['PAUSED', 'muted'];
  return ['PENDING', 'muted'];
}

/** Gray while all is well; amber for a machine gone quiet, red for one that's down or has a sick pool. */
function accentOf(m: Machine): Accent {
  if (m.status === 'down' || (m.live && m.unhealthyPools.length > 0)) return 'red';
  if (m.status === 'up' && !m.live) return 'amber';
  return m.live ? 'hi' : 'muted';
}

/** One machine: its name, health, load bars and a minute of CPU history. */
export function MachineTile({ slot, area, order }: { slot: Slot } & Area) {
  const m = slot.machine;
  if (!m) {
    return (
      <Panel area={area} order={order} accent="faint" className="machine empty">
        <div className="tile-head">
          <span className="name">{slot.name}</span>
        </div>
        <div className="role">{slot.role}</div>
        <div className="awaiting">AWAITING AGENT</div>
      </Panel>
    );
  }

  const [stateText, stateColor] = stateOf(m);
  const zfs = m.pool?.zfs ? m.pool : null;
  const role = slot.role || m.gpuName || '';
  const alert = m.unhealthyPools[0];
  const bars: Reading[] = [['CPU', m.cpu, 75, 90], ['MEM', m.mem, 80, 90], third(m)];
  const four = fourth(m);
  if (four) bars.push(four);
  const peak = Math.max(0, ...m.cpuHistory);
  // The CPU history's scale follows the minute's peak, so a quiet machine isn't a flat line.
  const scale = Math.min(100, Math.max(10, Math.ceil(peak / 10) * 10));
  const temp = four?.[0] !== 'TMP' && m.tempC !== null ? m.tempC : null;

  return (
    <Panel area={area} order={order} accent={accentOf(m)} className={`machine ${m.live ? 'live' : 'lost'}`}>
      <div className="tile-head">
        <span className="name">{m.name}</span>
        <span className="state">
          <Dot color={stateColor} />
          {stateText}
        </span>
      </div>
      {alert && m.live ? (
        <div className="role alert">
          <TriangleAlert className="glyph" /> {alert[0]} {alert[1]}
        </div>
      ) : (
        <div className="role">
          {role}
          {zfs && ` // ${zfs.name} ${size(zfs.usedGb)}/${size(zfs.totalGb)}`}
        </div>
      )}

      {m.live ? (
        <>
          <div className="bars">
            {bars.map(([label, pct, warn, crit]) => (
              <Bar key={label} label={label} pct={pct} warn={warn} crit={crit} unit={label === 'TMP' ? '°' : '%'} />
            ))}
          </div>
          <div className="history">
            <span className="history-label">CPU 60S /{scale}%</span>
            <Sparkline values={m.cpuHistory} scale={scale} />
          </div>
          <div className="foot">
            <Net up={m.netUpBps} down={m.netDownBps} />
            <span>
              {temp !== null && <span className={`level-${level(temp, 75, 85)}`}>{Math.trunc(temp)}°C</span>}
              {temp !== null && m.load1 !== null && '  '}
              {m.load1 !== null && `LA ${m.load1.toFixed(2)}`}
            </span>
          </div>
        </>
      ) : (
        <div className={`signal-lost ${m.status}`}>
          <strong data-text={m.status === 'paused' ? 'PAUSED' : 'SIGNAL LOST'}>
            {m.status === 'paused' ? 'PAUSED' : 'SIGNAL LOST'}
          </strong>
          <span>NO TELEMETRY</span>
        </div>
      )}
    </Panel>
  );
}

/**
 * The whole fleet at once: how many machines are up, and their combined load.
 * The count turns red while a machine is down, and amber while one is quiet.
 */
export function FleetTile({ fleet, slots, area, order }: { fleet: FleetSummary; slots: Slot[] } & Area) {
  const machines = slots.map((s) => s.machine);
  const count = machines.some((m) => m?.status === 'down')
    ? 'level-crit'
    : machines.some((m) => !m || (m.status !== 'paused' && !m.live))
      ? 'level-warn'
      : '';
  const mem = fleet.memTotalGb > 0 ? (fleet.memUsedGb / fleet.memTotalGb) * 100 : 0;
  return (
    <Panel area={area} order={order} className="fleet">
      <div className="title">FLEET // AGGREGATE</div>
      <div className="count">
        <strong className={count}>
          {String(fleet.online).padStart(2, '0')}/{String(fleet.total).padStart(2, '0')}
        </strong>
        <span>NODES ONLINE</span>
      </div>
      <div className="bars">
        <Bar label="CPU" pct={fleet.cpu} />
        <Bar label="MEM" pct={mem} warn={80} />
      </div>
      <div className="history">
        <Sparkline values={fleet.cpuHistory} />
      </div>
      <div className="foot">
        <Net up={fleet.netUpBps} down={fleet.netDownBps} />
        <span>
          {size(fleet.memUsedGb)} / {size(fleet.memTotalGb)} RAM
        </span>
      </div>
    </Panel>
  );
}

/** The newest events, oldest at the top, with a blinking cursor under them. */
export function LogTile({ events, area, order }: { events: LogEvent[] } & Area) {
  return (
    <Panel area={area} order={order} className="log">
      <div className="title">EVENT LOG</div>
      <ol className="events">
        {events.map((e) => (
          <li key={e.id} className={`tone-${e.tone}`}>
            <time>{clock(e.at)}</time>
            <span className="source">{e.source.slice(0, 9)}</span>
            <span className="text">{e.text}</span>
          </li>
        ))}
        <li className="prompt">
          &gt;<i className="cursor" />
        </li>
      </ol>
    </Panel>
  );
}
