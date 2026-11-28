import { Account, Book, Currency, Tx } from './types.js';
import { convert } from './money.js';

export const cmpTx = (a: Tx, b: Tx) => a.date.localeCompare(b.date) || a.id - b.id;
export const cur = (b: Book, code: string): Currency => {
  const c = b.currencies.find(x => x.code === code);
  if (!c) throw new Error(`Unknown currency ${code}`);
  return c;
};
export const acct = (b: Book, id: number): Account => {
  const a = b.accounts.find(x => x.id === id);
  if (!a) throw new Error(`Unknown account ${id}`);
  return a;
};

/** Signed effect of a transaction on one account, in that account's minor units. */
export function effect(t: Tx, accountId: number): number {
  if (t.type === 'inc') return t.acc === accountId ? t.amount : 0;
  if (t.type === 'exp') return t.acc === accountId ? -t.amount : 0;
  return (t.acc === accountId ? -t.amount : 0) + (t.to === accountId ? (t.amountTo ?? t.amount) : 0);
}

/** Balance as of `today`; future-dated entries are excluded when settings.countFuture is false. */
export function balance(b: Book, accountId: number, today: string): number {
  let sum = acct(b, accountId).opening;
  for (const t of b.tx) {
    if (!b.settings.countFuture && t.date > today) continue;
    sum += effect(t, accountId);
  }
  return sum;
}

/** Running balance after each transaction. Always derived from the sorted list, never stored. */
export function runningBalances(b: Book, accountId: number): Map<number, number> {
  const map = new Map<number, number>();
  let run = acct(b, accountId).opening;
  for (const t of [...b.tx].sort(cmpTx)) {
    if (t.acc !== accountId && t.to !== accountId) continue;
    run += effect(t, accountId);
    map.set(t.id, run);
  }
  return map;
}

/** Value of an amount of `code` in the base currency, using that currency's current rate. */
export function toBase(b: Book, minor: number, code: string): number {
  if (code === b.settings.base) return minor;
  return convert(minor, cur(b, code).decimals, cur(b, b.settings.base).decimals, cur(b, code).rate);
}
export function totalInBase(b: Book, today: string): number {
  return b.accounts.reduce((s, a) => s + toBase(b, balance(b, a.id, today), a.cur), 0);
}

/** Validates a transaction before it is added. Throws with a plain message. */
export function validateTx(b: Book, t: Omit<Tx, 'id'>): void {
  if (!Number.isInteger(t.amount) || t.amount <= 0) throw new Error('Amount must be greater than zero');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(t.date)) throw new Error('Bad date');
  const from = acct(b, t.acc);
  if (t.type === 'xfer') {
    if (t.to == null) throw new Error('Transfer needs a destination');
    if (t.to === t.acc) throw new Error('Transfer needs two different accounts');
    const to = acct(b, t.to);
    if (from.cur !== to.cur && (t.amountTo == null || t.amountTo <= 0)) throw new Error('Cross-currency transfer needs the amount received');
  }
}
export function addTx(b: Book, t: Omit<Tx, 'id'>): Tx {
  validateTx(b, t);
  const id = b.tx.reduce((m, x) => Math.max(m, x.id), 0) + 1;
  const tx: Tx = { ...t, id, rate: t.rate ?? cur(b, acct(b, t.acc).cur).rate };
  b.tx.push(tx);
  return tx;
}

/** Replaces a transaction in place, keeping its id. Nothing changes if validation fails. */
export function updateTx(b: Book, id: number, t: Omit<Tx, 'id'>): Tx {
  const i = b.tx.findIndex(x => x.id === id);
  if (i < 0) throw new Error('Transaction not found');
  validateTx(b, t);
  const tx: Tx = { ...t, id, rate: t.rate ?? b.tx[i].rate ?? cur(b, acct(b, t.acc).cur).rate };
  b.tx[i] = tx;
  return tx;
}
