import type { CSSProperties } from 'react';
import type { FleetSummary } from '../shared/types.ts';
import { CameraTile } from './camera.tsx';
import { StatusStrip } from './status.tsx';
import { EventLog, MachineTile } from './tiles.tsx';
import { useVisor } from './useVisor.ts';

/** The most machines that get a tile: four columns of three. */
const MAX_MACHINES = 12;

const NO_FLEET: FleetSummary = { memUsedGb: 0, memTotalGb: 0, netUpBps: 0, netDownBps: 0 };

/** Columns and rows for `n` machine tiles: two columns up to four, three up to nine, then four. */
function gridFor(n: number): CSSProperties {
  const cols = n <= 4 ? 2 : n <= 9 ? 3 : 4;
  return { '--cols': cols, '--rows': Math.max(2, Math.ceil(n / cols)) } as CSSProperties;
}

/** A tap goes full screen, for a browser that isn't running visor as an installed app. */
function fullScreen(): void {
  if (document.fullscreenElement || !document.documentElement.requestFullscreen) return;
  document.documentElement
    .requestFullscreen({ navigationUI: 'hide' })
    .then(() => (screen.orientation as ScreenOrientation & { lock?: (o: string) => Promise<void> }).lock?.('landscape'))
    .catch(() => {});
}

/**
 * The machines on the left, in the configured order. On the right, the clock
 * and fleet status, the camera, and the event log under it.
 */
export function App() {
  const { snapshot, server, hello } = useVisor();
  const slots = snapshot?.slots ?? [];
  const shown = slots.slice(0, MAX_MACHINES);
  return (
    // When the server is gone, the last numbers stay up, dimmed, rather than looking current.
    <main className={`screen${server === 'lost' ? ' frozen' : ''}`} onClick={fullScreen}>
      <div className="machines" style={gridFor(shown.length)}>
        {shown.map((slot, i) => (
          <MachineTile key={slot.name} slot={slot} order={i} />
        ))}
      </div>
      <div className={`side${hello?.camera ? '' : ' no-camera'}`}>
        <StatusStrip
          order={0}
          slots={slots}
          fleet={snapshot?.fleet ?? NO_FLEET}
          link={snapshot?.link ?? null}
          server={server}
        />
        {hello?.camera && <CameraTile name={hello.camera} order={1} />}
        <EventLog events={snapshot?.events ?? []} order={2} />
      </div>
    </main>
  );
}
