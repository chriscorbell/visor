/** 1.2M, 340K, 12B: a byte rate in four characters or so. */
export function rate(bytesPerSec: number): string {
  if (bytesPerSec >= 1e9) return `${(bytesPerSec / 1e9).toFixed(1)}G`;
  if (bytesPerSec >= 1e7) return `${(bytesPerSec / 1e6).toFixed(0)}M`;
  if (bytesPerSec >= 1e6) return `${(bytesPerSec / 1e6).toFixed(1)}M`;
  if (bytesPerSec >= 1e3) return `${(bytesPerSec / 1e3).toFixed(0)}K`;
  return `${Math.round(bytesPerSec)}B`;
}

const pad = (n: number) => String(n).padStart(2, '0');

/** 35D 04H, 4H 12M, 12M. */
export function uptime(seconds: number): string {
  const d = Math.floor(seconds / 86_400);
  const h = Math.floor((seconds % 86_400) / 3_600);
  const m = Math.floor((seconds % 3_600) / 60);
  if (d > 0) return `${d}D ${pad(h)}H`;
  if (h > 0) return `${h}H ${pad(m)}M`;
  return `${m}M`;
}

/** 1.2T, 512G: sizes in gigabytes. */
export function size(gb: number): string {
  return gb >= 1000 ? `${(gb / 1024).toFixed(1)}T` : `${gb.toFixed(0)}G`;
}

export function clock(at: number | Date): string {
  const d = new Date(at);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

const DAYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

/** THU 29 OCT */
export function date(d: Date): string {
  return `${DAYS[d.getDay()]} ${pad(d.getDate())} ${MONTHS[d.getMonth()]}`;
}

export type Level = 'ok' | 'warn' | 'crit';

/** Whether a reading needs a look: amber from `warn`, red from `crit`. */
export function level(value: number, warn = 75, crit = 90): Level {
  return value >= crit ? 'crit' : value >= warn ? 'warn' : 'ok';
}
