/** 1.2M, 340K, 12B: a byte rate in four characters or so. */
export function rate(bytesPerSec: number): string {
  if (bytesPerSec >= 1e9) return `${(bytesPerSec / 1e9).toFixed(1)}G`;
  if (bytesPerSec >= 1e7) return `${(bytesPerSec / 1e6).toFixed(0)}M`;
  if (bytesPerSec >= 1e6) return `${(bytesPerSec / 1e6).toFixed(1)}M`;
  if (bytesPerSec >= 1e3) return `${(bytesPerSec / 1e3).toFixed(0)}K`;
  return `${Math.round(bytesPerSec)}B`;
}

/** 35d 4h, 4h 12m, 12m. */
export function uptime(seconds: number): string {
  const d = Math.floor(seconds / 86_400);
  const h = Math.floor((seconds % 86_400) / 3_600);
  const m = Math.floor((seconds % 3_600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

/** "2.7 of 12.6 TB", "90 of 221 GB": used and total, in the larger total's unit. */
export function capacity(usedGb: number, totalGb: number): string {
  if (totalGb >= 1000) return `${(usedGb / 1024).toFixed(1)} of ${(totalGb / 1024).toFixed(1)} TB`;
  return `${usedGb.toFixed(0)} of ${totalGb.toFixed(0)} GB`;
}

const pad = (n: number) => String(n).padStart(2, '0');

/** 22:12 */
export function clock(at: number | Date): string {
  const d = new Date(at);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Thu 29 Oct */
export function date(d: Date): string {
  return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}`;
}

export type Level = 'ok' | 'warn' | 'crit';

/** Where a reading stands against its warning and critical thresholds. */
export function level(value: number, warn: number, crit: number): Level {
  return value >= crit ? 'crit' : value >= warn ? 'warn' : 'ok';
}
