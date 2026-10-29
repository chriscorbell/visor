import type { CSSProperties, ReactNode } from 'react';
import { HISTORY } from '../shared/types.ts';
import { level } from './format.ts';

/** A flat panel. `order` staggers its entrance when the page loads. */
export function Tile(props: { order: number; className?: string; children: ReactNode }) {
  return (
    <section className={`tile ${props.className ?? ''}`} style={{ '--i': props.order } as CSSProperties}>
      {props.children}
    </section>
  );
}

/**
 * A labelled bar and its value, colored only once the reading passes `warn`.
 * The fill is a scaled copy of the track, so it moves on the compositor.
 */
export function Meter(props: { label: string; value: number; unit?: string; warn: number; crit: number }) {
  // Whole percents, so a reading that hasn't really moved doesn't restart the transition.
  const fraction = Math.round(Math.min(100, Math.max(0, props.value))) / 100;
  return (
    <div className={`meter ${level(props.value, props.warn, props.crit)}`}>
      <span className="meter-label">{props.label}</span>
      <span className="meter-track">
        <i className="meter-fill" style={{ '--f': fraction } as CSSProperties} />
      </span>
      <span className="meter-value">
        {Math.round(props.value)}
        {props.unit ?? '%'}
      </span>
    </div>
  );
}

/** The last minute of `values`, on a fixed 0–100 scale so machines compare at a glance. */
export function Sparkline({ values }: { values: number[] }) {
  if (values.length < 2) return <svg className="sparkline" />;
  const start = HISTORY - values.length;
  const points = values.map((v, i) => `${start + i},${(100 - Math.min(100, Math.max(0, v))).toFixed(1)}`);
  return (
    <svg className="sparkline" viewBox={`0 0 ${HISTORY - 1} 100`} preserveAspectRatio="none">
      <path className="sparkline-area" d={`M${start},100L${points.join('L')}L${HISTORY - 1},100Z`} />
      <path className="sparkline-line" d={`M${points.join('L')}`} vectorEffect="non-scaling-stroke" />
    </svg>
  );
}
