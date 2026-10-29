import { useEffect, useState } from 'react';
import type { Link } from '../shared/types.ts';
import { clock, date } from './format.ts';
import { Dot } from './ui.tsx';
import type { ServerLink } from './useVisor.ts';

const LINKS: Record<Link, [string, string]> = {
  live: ['HUB LINK', 'green'],
  connecting: ['HUB LINKING', 'amber'],
  offline: ['HUB OFFLINE', 'magenta'],
  'no-credentials': ['NO CREDENTIALS', 'magenta'],
};

function useNow(): Date {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    let timer = 0;
    const tick = () => {
      setNow(new Date());
      timer = window.setTimeout(tick, 1000 - (Date.now() % 1000));
    };
    timer = window.setTimeout(tick, 1000 - (Date.now() % 1000));
    return () => window.clearTimeout(timer);
  }, []);
  return now;
}

/** The top strip: the name, how many machines are up, the hub link, and the time. */
export function Header(props: { link: Link | null; server: ServerLink; online: number; total: number }) {
  const now = useNow();
  const [linkText, linkColor] =
    props.server === 'lost' ? ['VISOR OFFLINE', 'magenta'] : props.link ? LINKS[props.link] : LINKS.connecting;
  const two = (n: number) => String(n).padStart(2, '0');
  return (
    <header className="header">
      <span className="logo">VISOR</span>
      <span className="sub">// FLEET TELEMETRY</span>
      <span className="info">
        NODES {two(props.online)}/{two(props.total)}
      </span>
      <span className="info">
        <Dot color={linkColor} />
        {linkText}
      </span>
      <span className="info">{date(now)}</span>
      <span className="clock">{clock(now)}</span>
    </header>
  );
}
