import { Book, Recurring, Tx } from './types.js';
import { acct, addTx, cmpTx, toBase } from './ledger.js';

/** Why a record cannot be deleted, or null when it can. Mirrors the RESTRICT foreign keys. */
export function blockWhy(b: Book, kind: 'account' | 'category' | 'party' | 'project' | 'currency', id: number | string): string | null {
  const n = (c: number, what: string) => (c ? `Used by ${c} ${what}${c > 1 ? 's' : ''}` : null);
  switch (kind) {
    case 'account': return n(b.tx.filter(t => t.acc === id || t.to === id).length, 'transaction') ?? n(b.recurring.filter(r => r.tx.acc === id || r.tx.to === id).length, 'recurring item');
    case 'category': return n(b.tx.filter(t => t.cat === id).length, 'transaction') ?? n(b.categories.filter(c => c.parent === id).length, 'subcategory') ?? n(b.recurring.filter(r => r.tx.cat === id).length, 'recurring item');
    case 'party': return n(b.tx.filter(t => t.party === id).length, 'transaction') ?? n(b.recurring.filter(r => r.tx.party === id).length, 'recurring item');
    case 'project': return n(b.tx.filter(t => t.project === id).length, 'transaction');
    case 'currency': return id === b.settings.base ? 'This is the base currency' : n(b.accounts.filter(a => a.cur === id).length, 'account');
  }
}
export function deleteRecord(b: Book, kind: Parameters<typeof blockWhy>[1], id: number | string): void {
  const why = blockWhy(b, kind, id);
  if (why) throw new Error(why);
  const rm = <T extends { id?: unknown; code?: unknown }>(arr: T[], key: 'id' | 'code') => { const i = arr.findIndex(x => x[key] === id); if (i >= 0) arr.splice(i, 1); };
  if (kind === 'account') rm(b.accounts, 'id'); else if (kind === 'category') rm(b.categories, 'id');
  else if (kind === 'party') rm(b.parties, 'id'); else if (kind === 'project') rm(b.projects, 'id'); else rm(b.currencies, 'code');
}
export const deleteTx = (b: Book, id: number) => { b.tx = b.tx.filter(t => t.id !== id); };

/* ---- Loans: one account for what I owe, one for what is owed to me; per-person balance via transfers ---- */
export const isLoan = (b: Book, accId: number) => /^Loan/.test(acct(b, accId).kind);
/** Net per counterparty on a loan account, in that account's minor units (signed as the account moves). */
export function loanBalances(b: Book, accId: number): Map<number, number> {
  const out = new Map<number, number>();
  for (const t of b.tx) {
    if (t.party == null || (t.acc !== accId && t.to !== accId) || t.type !== 'xfer') continue;
    const delta = (t.acc === accId ? -t.amount : 0) + (t.to === accId ? (t.amountTo ?? t.amount) : 0);
    out.set(t.party, (out.get(t.party) ?? 0) + delta);
  }
  return out;
}

/* ---- Recurring: catch up from due dates when the app opens; no scheduler needed ---- */
export function addPeriod(date: string, every: Recurring['every'], anchorDay: number): string {
  const [y, m, d] = date.split('-').map(Number);
  if (every === 'day' || every === 'week') {
    const dt = new Date(Date.UTC(y, m - 1, d + (every === 'day' ? 1 : 7)));
    return dt.toISOString().slice(0, 10);
  }
  const months = every === 'month' ? 1 : 12;
  const total = y * 12 + (m - 1) + months, ny = Math.floor(total / 12), nm = total % 12;
  const last = new Date(Date.UTC(ny, nm + 1, 0)).getUTCDate();
  return `${ny}-${String(nm + 1).padStart(2, '0')}-${String(Math.min(anchorDay, last)).padStart(2, '0')}`;
}
const DAY = 864e5, ymd = (t: number) => new Date(t).toISOString().slice(0, 10);
/** The date after `from` that the rule produces next. Handles every-n, several weekdays, last day of month and "2nd Friday". */
export function nextOccurrence(r: Pick<Recurring, 'every' | 'rule'>, from: string, anchorDay: number): string {
  const rule = r.rule ?? {}, n = Math.max(1, Math.floor(rule.n ?? 1));
  const [y, m, d] = from.split('-').map(Number);
  if (r.every === 'day') return ymd(Date.UTC(y, m - 1, d + n));
  if (r.every === 'week') {
    const wds = rule.weekdays?.length ? rule.weekdays : null;
    if (!wds) return ymd(Date.UTC(y, m - 1, d + 7 * n));
    const start = Date.UTC(y, m - 1, d), weekStart = start - ((new Date(start).getUTCDay() + 6) % 7) * DAY;
    for (let i = 1; i <= 7 * n + 7; i++) {
      const t = start + i * DAY;
      if (wds.includes(new Date(t).getUTCDay()) && Math.floor((t - weekStart) / (7 * DAY)) % n === 0) return ymd(t);
    }
  }
  const total = y * 12 + (m - 1) + (r.every === 'month' ? n : 12 * n), ny = Math.floor(total / 12), nm = total % 12;
  const last = new Date(Date.UTC(ny, nm + 1, 0)).getUTCDate();
  let day = Math.min(anchorDay, last);
  if (r.every === 'month' && rule.monthly === 'last') day = last;
  else if (r.every === 'month' && rule.monthly === 'nth' && rule.nth && rule.weekday != null) {
    if (rule.nth === -1) day = last - ((new Date(Date.UTC(ny, nm, last)).getUTCDay() - rule.weekday + 7) % 7);
    else day = Math.min(last, 1 + ((rule.weekday - new Date(Date.UTC(ny, nm, 1)).getUTCDay() + 7) % 7) + 7 * (rule.nth - 1));
  }
  return `${ny}-${String(nm + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}
/** True once a repeat has run its course: the count is reached or the next date falls after the end date. */
export const finished = (r: Recurring) => (r.rule?.count != null && (r.rule.done ?? 0) >= r.rule.count) || (!!r.end && r.next > r.end);
/** Creates every due occurrence up to and including `today`, then drops repeats that have ended. Returns the created transactions. Guard stops runaway loops. */
export function catchUp(b: Book, today: string, guard = 600): Tx[] {
  const made: Tx[] = [];
  for (const r of b.recurring) {
    const anchor = r.day ?? Number(r.next.slice(8, 10));
    let n = 0;
    while (r.next <= today && !finished(r) && n++ < guard) {
      made.push(addTx(b, { ...r.tx, date: r.next }));
      if (r.rule?.count != null) r.rule.done = (r.rule.done ?? 0) + 1;
      r.next = nextOccurrence(r, r.next, anchor);
    }
  }
  b.recurring = b.recurring.filter(r => !finished(r));
  return made;
}

/* ---- Reports ---- */
export interface Filter { from?: string; to?: string; accounts?: number[]; projects?: number[]; cat?: number; party?: number; }
export function filterTx(b: Book, f: Filter): Tx[] {
  return b.tx.filter(t => (!f.from || t.date >= f.from) && (!f.to || t.date <= f.to) &&
    (!f.accounts?.length || f.accounts.includes(t.acc)) && (!f.projects?.length || (t.project != null && f.projects.includes(t.project))) &&
    (f.cat == null || t.cat === f.cat || b.categories.find(c => c.id === t.cat)?.parent === f.cat) && (f.party == null || t.party === f.party)).sort(cmpTx);
}
/** Income, expense and net in base currency. Each line is converted and rounded on its own, then summed. */
export function summary(b: Book, f: Filter) {
  let income = 0, expense = 0;
  for (const t of filterTx(b, f)) {
    const base = toBase(b, t.amount, acct(b, t.acc).cur);
    if (t.type === 'inc') income += base; else if (t.type === 'exp') expense += base;
  }
  return { income, expense, net: income - expense };
}
export function byCategory(b: Book, f: Filter, type: 'inc' | 'exp'): Map<number, number> {
  const m = new Map<number, number>();
  for (const t of filterTx(b, f)) if (t.type === type && t.cat != null) {
    const top = b.categories.find(c => c.id === t.cat)?.parent ?? t.cat;
    m.set(top, (m.get(top) ?? 0) + toBase(b, t.amount, acct(b, t.acc).cur));
  }
  return m;
}
