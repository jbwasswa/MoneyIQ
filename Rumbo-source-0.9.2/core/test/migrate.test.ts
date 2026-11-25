import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { migratePrototype } from '../src/migrate.js';
import { initDb, saveBook, loadBook } from '../src/db.js';
import { balance, totalInBase, runningBalances } from '../src/ledger.js';
import { blockWhy } from '../src/rules.js';

const file = JSON.parse(readFileSync(new URL('./fixtures/prototype-backup.json', import.meta.url), 'utf8'));
const proto = file.data;
const today = '2999-01-01'; // count everything, matching the prototype's default
const protoBalance = (id: string) => {
  let b = proto.accounts.find((a: any) => a.id === id).open;
  for (const t of proto.tx) {
    if (t.type === 'inc' && t.acc === id) b += t.amount; else if (t.type === 'exp' && t.acc === id) b -= t.amount;
    else if (t.type === 'xfer') { if (t.acc === id) b -= t.amount; if (t.to === id) b += t.amountTo == null ? t.amount : t.amountTo; }
  }
  return b;
};

describe('migration from prototype backup', () => {
  const { book, counts, warnings } = migratePrototype(file);
  it('keeps every record and reports no warnings', () => {
    expect(counts.transactions).toBe(proto.tx.length);
    expect(counts.accounts).toBe(proto.accounts.length);
    expect(warnings).toEqual([]);
  });
  it('every account balance equals the prototype to the currency precision', () => {
    for (const a of proto.accounts) {
      const acc = book.accounts.find(x => x.name === a.name)!;
      const dec = book.currencies.find(c => c.code === a.cur)!.decimals;
      expect(balance(book, acc.id, today) / 10 ** dec).toBeCloseTo(protoBalance(a.id), dec);
    }
  });
  it('all amounts are integers and running balance ends at the balance', () => {
    expect(book.tx.every(t => Number.isInteger(t.amount) && (t.amountTo == null || Number.isInteger(t.amountTo)))).toBe(true);
    for (const a of book.accounts) {
      const run = runningBalances(book, a.id), last = [...run.values()].pop();
      if (last != null) expect(last).toBe(balance(book, a.id, today));
    }
  });
  it('payees became shared records', () => {
    const names = new Set(proto.tx.map((t: any) => t.payee).filter(Boolean));
    expect(new Set(book.parties.map(p => p.name))).toEqual(names);
  });
});

describe('SQLite storage', () => {
  const { book } = migratePrototype(file);
  const open = () => { const db = new DatabaseSync(':memory:'); initDb(db as any); return db; };
  it('round-trips the whole book identically', () => {
    const db = open(); saveBook(db as any, book);
    const back = loadBook(db as any);
    expect(back.tx).toEqual(book.tx);
    expect(back.accounts).toEqual(book.accounts);
    expect(back.categories).toEqual(book.categories);
    expect(back.recurring).toEqual(book.recurring);
    expect(back.settings).toEqual(book.settings);
    expect(totalInBase(back, today)).toBe(totalInBase(book, today));
  });
  it('database refuses to delete a referenced account', () => {
    const db = open(); saveBook(db as any, book);
    const used = book.tx[0].acc;
    expect(blockWhy(book, 'account', used)).toBeTruthy();
    expect(() => db.exec(`DELETE FROM account WHERE id=${used}`)).toThrow(/FOREIGN KEY/);
  });
  it('a failed save rolls back and keeps the old data', () => {
    const db = open(); saveBook(db as any, book);
    const bad = structuredClone(book); bad.tx[5].acc = 9999;
    expect(() => saveBook(db as any, bad)).toThrow();
    expect(loadBook(db as any).tx.length).toBe(book.tx.length);
  });
  it('rejects bad data at the database level', () => {
    const db = open(); saveBook(db as any, book);
    expect(() => db.exec("INSERT INTO tx(type,date,acc,amount) VALUES('exp','2026-01-01',1,0)")).toThrow();
    expect(() => db.exec("INSERT INTO tx(type,date,acc,amount) VALUES('exp','tomorrow',1,5)")).toThrow();
  });
});

import { readBackup, validateBook } from '../src/backup.js';
describe('backup reading', () => {
  const text = readFileSync(new URL('./fixtures/prototype-backup.json', import.meta.url), 'utf8');
  it('reads a prototype backup and a desktop backup', () => {
    const { book } = readBackup(text);
    expect(validateBook(book)).toEqual([]);
    const again = readBackup(JSON.stringify({ app: 'pocket-ledger', schema: 2, data: book }));
    expect(again.book.tx.length).toBe(book.tx.length);
  });
  it('rejects damaged, foreign and newer files without a crash', () => {
    expect(() => readBackup('nope')).toThrow(/not a valid/);
    expect(() => readBackup('{"app":"other"}')).toThrow(/does not look/);
    expect(() => readBackup('{"app":"pocket-ledger","schema":9,"data":{}}')).toThrow(/newer/);
    const { book } = readBackup(text); book.tx[0].acc = 9999;
    expect(() => readBackup(JSON.stringify({ app: 'pocket-ledger', schema: 2, data: book }))).toThrow(/damaged/);
  });
});
