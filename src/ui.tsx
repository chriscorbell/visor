import { useId } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { HISTORY } from '../shared/types.ts';
import { level } from './format.ts';

export type Accent = 'hi' | 'amber' | 'red' | 'muted' | 'faint';

/**
 * A panel on the grid: a dark plate with its top-right corner cut off, a
 * hairline edge, and bright brackets on two corners.
 */
export function Panel(props: {
  area: CSSProperties;
  accent?: Accent;
  order: number;
  className?: string;
  children: ReactNode;
}) {
  const style = { ...props.area, '--i': props.order } as CSSProperties;
  return (
    <section className={`panel accent-${props.accent ?? 'hi'} ${props.className ?? ''}`} style={style}>
      {props.children}
    </section>
  );
}

/** A load bar of lit segments, with its label and value. It turns amber from `warn` and red from `crit`. */
export function Bar(props: { label: string; pct: number; unit?: string; warn?: number; crit?: number }) {
  const { label, pct, unit = '%' } = props;
  const shown = Math.min(100, Math.max(0, pct));
  return (
    <div className={`bar-row level-${level(pct, props.warn, props.crit)}`}>
      <span className="bar-label">{label}</span>
      <div className="bar" style={{ '--pct': `${shown}%` } as CSSProperties}>
        <div className="bar-fill" />
      </div>
      <span className="bar-value">
        {Math.trunc(pct)}
        {unit}
      </span>
    </div>
  );
}

/** The last minute of `values`, scaled 0–`scale`, with a fading fill under it. */
export function Sparkline({ values, scale = 100 }: { values: number[]; scale?: number }) {
  // React's ids hold characters a url(#…) reference can't.
  const gradient = 'spark' + useId().replace(/[^\w-]/g, '');
  if (values.length < 2) return <svg className="sparkline" />;
  const start = HISTORY - values.length;
  const points = values.map((v, i) => `${start + i},${(100 - (Math.min(scale, Math.max(0, v)) / scale) * 100).toFixed(1)}`);
  return (
    <svg className="sparkline" viewBox={`0 0 ${HISTORY - 1} 100`} preserveAspectRatio="none">
      <defs>
        <linearGradient id={gradient} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="currentColor" stopOpacity="0.28" />
          <stop offset="1" stopColor="currentColor" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`M${start},100L${points.join('L')}L${HISTORY - 1},100Z`} fill={`url(#${gradient})`} />
      <path
        d={`M${points.join('L')}`}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.2"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}

/** A status light. */
export function Dot({ color }: { color: string }) {
  return <i className="dot" style={{ '--dot': `var(--${color})` } as CSSProperties} />;
}
