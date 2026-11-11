import type { Book } from './types.js';
import { SCHEMA_SQL, SCHEMA_VERSION } from './schema.js';

/** Minimal surface shared by node:sqlite (tests) and the Tauri SQL adapter. */
export interface Db { exec(sql: string): void; prepare(sql: string): { run(...a: any[]): unknown; all(...a: any[]): any[] }; }

const TABLES = ['tx', 'recurring', 'budget', 'category', 'party', 'project', 'account', 'currency'];
/** Creates the tables, or upgrades an older file by reading everything out, rebuilding the tables, and writing it back. */
export function initDb(db: Db) {
  let old = 0;
  try { old = Number((db.prepare("SELECT value FROM meta WHERE key='schema'").all()[0] as any)?.value ?? 0); } catch { old = 0; }
  if (old > SCHEMA_VERSION) throw new Error(`This data file is from a newer version (schema ${old}). Update the app.`);
  let keep: Book | null = null;
  if (old > 0 && old < SCHEMA_VERSION) { keep = loadBook(db); db.exec('PRAGMA foreign_keys = OFF'); for (const t of TABLES) db.exec(`DROP TABLE IF EXISTS ${t}`); }
  db.exec(SCHEMA_SQL);
  if (keep) saveBook(db, keep);
  db.prepare("INSERT OR REPLACE INTO meta(key,value) VALUES('schema',?)").run(String(SCHEMA_VERSION));
}
const ins = (db: Db, sql: string, rows: any[][]) => { const s = db.prepare(sql); for (const r of rows) s.run(...r); };

/** Replaces the whole database content atomically. A failure rolls back and leaves the old data untouched. */
export function saveBook(db: Db, b: Book) {
  db.exec('BEGIN');
  try {
    for (const t of TABLES) { if (t === 'category') db.exec('DELETE FROM category WHERE parent IS NOT NULL'); db.exec(`DELETE FROM ${t}`); }
    ins(db, 'INSERT INTO currency VALUES(?,?,?,?)', b.currencies.map(c => [c.code, c.name ?? null, c.decimals, c.rate]));
    ins(db, 'INSERT INTO account VALUES(?,?,?,?,?,?,?,?)', b.accounts.map(a => [a.id, a.name, a.kind, a.cur, a.opening, a.ref ?? null, a.notes ?? null, a.inTotals === false ? 1 : null]));
    const cat = (c: Book['categories'][number]) => [c.id, c.name, c.type, c.parent ?? null, c.icon ?? null, c.color ?? null, c.ref ?? null];
    ins(db, 'INSERT INTO category VALUES(?,?,?,?,?,?,?)', b.categories.filter(c => c.parent == null).map(cat));
    ins(db, 'INSERT INTO category VALUES(?,?,?,?,?,?,?)', b.categories.filter(c => c.parent != null).map(cat));
    ins(db, 'INSERT INTO party VALUES(?,?,?,?)', b.parties.map(p => [p.id, p.name, p.kind, p.ref ?? null]));
    ins(db, 'INSERT INTO project VALUES(?,?,?)', b.projects.map(p => [p.id, p.name, p.ref ?? null]));
    ins(db, 'INSERT INTO budget VALUES(?,?)', b.budgets.map(x => [x.cat, x.amount]));
    ins(db, 'INSERT INTO tx VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', b.tx.map(t => [t.id, t.type, t.date, t.acc, t.amount, t.to ?? null, t.amountTo ?? null, t.cat ?? null, t.party ?? null, t.project ?? null, t.note ?? null, t.rate ?? null, t.txnCur ?? null, t.txnAmount ?? null, t.txnRate ?? null, t.feeOf ?? null]));
    ins(db, 'INSERT INTO recurring VALUES(?,?,?,?,?,?,?,?)', b.recurring.map(r => [r.id, r.every, r.next, r.end ?? null, JSON.stringify(r.tx), r.ref ?? null, r.day ?? null, r.rule ? JSON.stringify(r.rule) : null]));
    db.prepare("INSERT OR REPLACE INTO meta(key,value) VALUES('base',?)").run(b.settings.base);
    db.prepare("INSERT OR REPLACE INTO meta(key,value) VALUES('countFuture',?)").run(b.settings.countFuture ? '1' : '0');
    db.prepare("INSERT OR REPLACE INTO meta(key,value) VALUES('extras',?)").run(JSON.stringify({ sample: !!b.settings.sample, ui: b.settings.ui ?? {} }));
    db.exec('COMMIT');
  } catch (e) { db.exec('ROLLBACK'); throw e; }
}
export function loadBook(db: Db): Book {
  const meta = Object.fromEntries(db.prepare('SELECT key,value FROM meta').all().map((r: any) => [r.key, r.value]));
  const opt = <T>(v: T | null) => (v === null ? undefined : v);
  const rows = (sql: string) => db.prepare(sql).all() as any[];
  const clean = (r: any, keep: string[] = []) => Object.fromEntries(Object.entries(r).filter(([k, v]) => v !== null || keep.includes(k)));
  const extras = JSON.parse(meta.extras ?? '{}');
  return {
    settings: { base: meta.base, countFuture: meta.countFuture !== '0', ...(extras.sample ? { sample: true } : {}), ui: extras.ui ?? {} },
    currencies: rows('SELECT code,name,decimals,rate FROM currency ORDER BY rowid').map(r => clean(r)) as any,
    accounts: rows('SELECT * FROM account ORDER BY id').map(r => { const a: any = clean(r); if (a.no_totals === 1) a.inTotals = false; delete a.no_totals; return a; }) as any,
    categories: rows('SELECT id,name,type,parent,icon,color,ref FROM category ORDER BY id').map(r => clean(r, ['parent'])) as any,
    parties: rows('SELECT id,name,kind,ref FROM party ORDER BY id').map(r => clean(r)) as any,
    projects: rows('SELECT id,name,ref FROM project ORDER BY id').map(r => clean(r)) as any,
    budgets: rows('SELECT cat,amount FROM budget ORDER BY cat') as any,
    tx: db.prepare('SELECT * FROM tx ORDER BY id').all().map((r: any) => {
      const t: any = { id: r.id, type: r.type, date: r.date, acc: r.acc, amount: r.amount };
      for (const [k, c] of [['to', 'to_acc'], ['amountTo', 'amount_to'], ['cat', 'cat'], ['party', 'party'], ['project', 'project'], ['note', 'note'], ['rate', 'rate'], ['txnCur', 'txn_cur'], ['txnAmount', 'txn_amount'], ['txnRate', 'txn_rate'], ['feeOf', 'fee_of']] as const) if (opt(r[c]) !== undefined) t[k] = r[c];
      return t;
    }),
    recurring: db.prepare('SELECT * FROM recurring ORDER BY id').all().map((r: any) => ({ id: r.id, ...(r.ref ? { ref: r.ref } : {}), ...(r.day != null ? { day: r.day } : {}), every: r.every, next: r.next, ...(r.end ? { end: r.end } : {}), ...(r.rule ? { rule: JSON.parse(r.rule) } : {}), tx: JSON.parse(r.body) })),
  };
}
