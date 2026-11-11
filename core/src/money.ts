/** Decimal text <-> integer minor units, and exact currency conversion using BigInt. */
export function parseAmount(text: string, decimals: number): number {
  const m = /^(-)?(\d+)(?:\.(\d*))?$/.exec(text.trim().replace(/,/g, ''));
  if (!m) throw new Error(`Invalid amount: ${text}`);
  const frac = m[3] ?? '';
  if (frac.length > decimals && /[1-9]/.test(frac.slice(decimals))) throw new Error(`Too many decimals for this currency: ${text}`);
  const n = Number(m[2] + frac.slice(0, decimals).padEnd(decimals, '0'));
  if (!Number.isSafeInteger(n)) throw new Error('Amount too large');
  return m[1] ? -n : n;
}
export function formatAmount(minor: number, decimals: number): string {
  const neg = minor < 0, s = String(Math.abs(minor)).padStart(decimals + 1, '0');
  const int = decimals ? s.slice(0, -decimals) : s, frac = decimals ? '.' + s.slice(-decimals) : '';
  return (neg ? '-' : '') + int.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + frac;
}
function rateParts(rate: string): { num: bigint; scale: bigint } {
  const m = /^(\d+)(?:\.(\d+))?$/.exec(rate.trim());
  if (!m) throw new Error(`Invalid rate: ${rate}`);
  const f = m[2] ?? '';
  return { num: BigInt(m[1] + f), scale: 10n ** BigInt(f.length) };
}
/** Divide, rounding half away from zero. */
function divRound(a: bigint, b: bigint): bigint {
  const neg = (a < 0n) !== (b < 0n), x = a < 0n ? -a : a, y = b < 0n ? -b : b;
  const q = (2n * x + y) / (2n * y);
  return neg ? -q : q;
}
/** Convert minor units of a currency (decDe digits) into another (decTo digits) at `rate` = to-units per 1 from-unit. */
export function convert(minor: number, decFrom: number, decTo: number, rate: string): number {
  const { num, scale } = rateParts(rate);
  const top = BigInt(minor) * num * 10n ** BigInt(decTo);
  const bottom = scale * 10n ** BigInt(decFrom);
  return Number(divRound(top, bottom));
}
/** Cross rate: how many `to` units per 1 `from` unit, given base-per-unit rates of each. Returned with 12 decimals. */
export function crossRate(fromRate: string, toRate: string): string {
  const f = rateParts(fromRate), t = rateParts(toRate);
  const q = divRound(f.num * t.scale * 10n ** 12n, f.scale * t.num);
  const s = q.toString().padStart(13, '0');
  return (s.slice(0, -12) + '.' + s.slice(-12)).replace(/0+$/, '').replace(/\.$/, '');
}
