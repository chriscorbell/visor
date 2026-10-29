import { ArrowDown, ArrowUp } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { FleetSummary, Link, Slot } from '../shared/types.ts';
import { capacity, clock, date, rate } from './format.ts';
import { Tile } from './ui.tsx';
import type { ServerLink } from './useVisor.ts';

/** The time, moved on at each minute. */
function useMinute(): Date {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    let timer = 0;
    const tick = () => {
      setNow(new Date());
      timer = window.setTimeout(tick, 60_000 - (Date.now() % 60_000));
    };
    timer = window.setTimeout(tick, 60_000 - (Date.now() % 60_000));
    return () => window.clearTimeout(timer);
  }, []);
  return now;
}

/** What's wrong with the connection, or null while the numbers are current. */
function linkProblem(server: ServerLink, link: Link | null): [string, string] | null {
  if (server === 'lost') return ['Server unreachable', 'crit'];
  if (link === 'offline') return ['Hub offline', 'crit'];
  if (link === 'no-credentials') return ['No hub credentials', 'crit'];
  if (link !== 'live') return ['Connecting to hub', 'warn'];
  return null;
}

/**
 * The clock, how many machines are up, and the fleet's memory and traffic.
 * The connection shows only when it's down.
 */
export function StatusStrip(props: {
  order: number;
  slots: Slot[];
  fleet: FleetSummary;
  link: Link | null;
  server: ServerLink;
}) {
  const now = useMinute();
  const up = props.slots.filter((s) => s.machine?.status === 'up').length;
  const down = props.slots.some((s) => s.machine?.status === 'down');
  const short = props.slots.some((s) => !s.machine?.live);
  const problem = linkProblem(props.server, props.link);
  const { fleet } = props;

  return (
    <Tile order={props.order} className="status">
      <div className="when">
        <span className="clock">{clock(now)}</span>
        <span className="date">{date(now)}</span>
      </div>
      <div className="fleet">
        {props.slots.length > 0 && (
          <span className={`fleet-count ${down ? 'crit' : short ? 'warn' : ''}`}>
            {up} of {props.slots.length} up
          </span>
        )}
        {problem ? (
          <span className={`fleet-detail ${problem[1]}`}>{problem[0]}</span>
        ) : (
          <span className="fleet-detail">
            <span>
              <ArrowUp />
              {rate(fleet.netUpBps)}
            </span>
            <span>
              <ArrowDown />
              {rate(fleet.netDownBps)}
            </span>
            <span>{capacity(fleet.memUsedGb, fleet.memTotalGb)} memory</span>
          </span>
        )}
      </div>
    </Tile>
  );
}
