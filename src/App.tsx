import type { CSSProperties } from 'react';
import type { FleetSummary } from '../shared/types.ts';
import { CameraTile } from './camera.tsx';
import { Header } from './header.tsx';
import { FleetTile, LogTile, MachineTile } from './tiles.tsx';
import { useVisor } from './useVisor.ts';

/**
 * A header over a 4×4 grid. The Camera takes the top-right 2×2 block, and the
 * event log the two cells under its right half. Machines fill the other
 * cells in order, and the fleet summary takes what's left of the bottom row:
 * two cells, or one when there's a ninth machine.
 */
const MACHINE_CELLS: [number, number][] = [
  [1, 1], [2, 1], [1, 2], [2, 2], [1, 3], [2, 3], [3, 3], [1, 4], [2, 4],
];
const CAMERA: CSSProperties = { gridColumn: '3 / span 2', gridRow: '1 / span 2' };
const LOG: CSSProperties = { gridColumn: '4', gridRow: '3 / span 2' };

const NO_FLEET: FleetSummary = {
  online: 0, total: 0, cpu: 0, memUsedGb: 0, memTotalGb: 0, netUpBps: 0, netDownBps: 0, cpuHistory: [],
};

/** A tap goes full screen, for a browser that isn't running visor as an installed app. */
function fullScreen(): void {
  if (document.fullscreenElement || !document.documentElement.requestFullscreen) return;
  document.documentElement
    .requestFullscreen({ navigationUI: 'hide' })
    .then(() => (screen.orientation as ScreenOrientation & { lock?: (o: string) => Promise<void> }).lock?.('landscape'))
    .catch(() => {});
}

export function App() {
  const { snapshot, server, hello } = useVisor();
  const slots = snapshot?.slots.slice(0, MACHINE_CELLS.length) ?? [];
  const fleet = snapshot?.fleet ?? NO_FLEET;
  return (
    <main className="screen" onClick={fullScreen}>
      <Header link={snapshot?.link ?? null} server={server} online={fleet.online} total={fleet.total} />
      <div className="grid">
        {slots.map((slot, i) => (
          <MachineTile
            key={slot.name}
            slot={slot}
            order={i}
            area={{ gridColumn: MACHINE_CELLS[i][0], gridRow: MACHINE_CELLS[i][1] }}
          />
        ))}
        {hello?.camera && <CameraTile name={hello.camera} area={CAMERA} order={2} />}
        <LogTile events={snapshot?.events ?? []} area={LOG} order={7} />
        <FleetTile
          fleet={fleet}
          slots={slots}
          area={{ gridColumn: slots.length > 8 ? '3' : '2 / span 2', gridRow: '4' }}
          order={8}
        />
      </div>
    </main>
  );
}
