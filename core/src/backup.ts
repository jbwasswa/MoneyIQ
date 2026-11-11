import type { Book } from './types.js';
import { migratePrototype } from './migrate.js';

/** Returns a list of problems; empty means the book is safe to load. */
export function validateBook(b: Book): string[] {
  const p: string[] = [], ids = <T extends { id: number }>(a: T[]) => new Set(a.map(x => x.id));
  const A = ids(b.accounts), C = ids(b.categories), P = ids(b.parties), J = ids(b.projects), cur = new Set(b.currencies.map(c => c.code));
  if (!cur.has(b.settings.base)) p.push('Base currency is missing');
  for (const a of b.accounts) if (!cur.has(a.cur)) p.push(`Account ${a.name}: unknown currency ${a.cur}`);
  for (const c of b.categories) if (c.parent != null && !C.has(c.parent)) p.push(`Category ${c.name}: missing parent`);
  const seen = new Set<number>();
  for (const t of b.tx) {
    if (seen.has(t.id)) p.push(`Transaction ${t.id}: duplicate id`); seen.add(t.id);
    if (!A.has(t.acc)) p.push(`Transaction ${t.id}: unknown account`);
    if (t.to != null && !A.has(t.to)) p.push(`Transaction ${t.id}: unknown destination`);
    if (t.type === 'xfer' && (t.to == null || t.to === t.acc)) p.push(`Transaction ${t.id}: bad transfer`);
    if (t.cat != null && !C.has(t.cat)) p.push(`Transaction ${t.id}: unknown category`);
    if (t.party != null && !P.has(t.party)) p.push(`Transaction ${t.id}: unknown payee`);
    if (t.project != null && !J.has(t.project)) p.push(`Transaction ${t.id}: unknown project`);
    if (!Number.isInteger(t.amount) || t.amount <= 0) p.push(`Transaction ${t.id}: bad amount`);
    if (!/^\d{4}-\d\d-\d\d$/.test(t.date)) p.push(`Transaction ${t.id}: bad date`);
  }
  const byId = new Map(b.tx.map(t => [t.id, t]));
  for (const t of b.tx) if (t.feeOf != null) {
    const par = byId.get(t.feeOf);
    if (!par || par.type !== 'xfer') p.push(`Transaction ${t.id}: charge without its transfer`);
    else if (t.type !== 'exp' || t.acc !== par.acc) p.push(`Transaction ${t.id}: charge must be an expense on the transfer's source account`);
  }
  return p;
}
/** Reads either a prototype (schema 1) or a desktop (schema 2) backup. Throws with a plain message if unusable. */
export function readBackup(text: string): { book: Book; note: string } {
  let o: any; try { o = JSON.parse(text); } catch { throw new Error('That is not a valid backup file.'); }
  if (!o || o.app !== 'pocket-ledger') throw new Error('This file does not look like a Pocket Ledger backup.');
  let book: Book, note = '';
  if (o.schema === 2) book = { ...(o.data as Book), budgets: o.data?.budgets ?? [] };
  else if (o.schema === 1 || o.schema == null) { const r = migratePrototype(o); book = r.book; note = r.warnings.length ? `${r.warnings.length} entries were skipped.` : ''; }
  else throw new Error(`This backup is from a newer version (schema ${o.schema}). Update the app first.`);
  const bad = validateBook(book);
  if (bad.length) throw new Error(`The backup is damaged, so nothing was changed. (${bad[0]}${bad.length > 1 ? ` and ${bad.length - 1} more` : ''})`);
  return { book, note };
}
