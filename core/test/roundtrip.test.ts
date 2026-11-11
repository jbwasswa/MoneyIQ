import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { fromPrototype, toPrototype } from '../src/convert.js';
import { initDb, saveBook, loadBook } from '../src/db.js';
import { validateBook } from '../src/backup.js';

const proto = JSON.parse(readFileSync(new URL('./fixtures/prototype-backup.json', import.meta.url), 'utf8')).data;
describe('prototype document <-> core book', () => {
  it('is lossless: screens get back exactly what they had', () => {
    const { book, warnings } = fromPrototype(proto);
    expect(warnings).toEqual([]);
    expect(validateBook(book)).toEqual([]);
    const back = toPrototype(book);
    for (const k of Object.keys(proto)) expect(back[k], k).toEqual(proto[k]);
    expect(Object.keys(back).sort()).toEqual(Object.keys(proto).sort());
  });
  it('survives the database as well', () => {
    const { book } = fromPrototype(proto);
    const db = new DatabaseSync(':memory:'); initDb(db as any); saveBook(db as any, book);
    const loaded = loadBook(db as any);
    const byCat = (b: typeof book) => ({ ...b, budgets: [...b.budgets].sort((x, y) => x.cat - y.cat) });
    expect(byCat(loaded)).toEqual(byCat(book));
    expect(toPrototype(loaded)).toEqual(toPrototype(byCat(book)));
  });
});

describe('saving many times (regression: every save after the first used to fail)', () => {
  const open = () => { const db = new DatabaseSync(':memory:'); initDb(db as any); return db; };
  it('survives repeated saves with every kind of change', () => {
    const S = structuredClone(proto), db = open();
    const save = () => { const { book } = fromPrototype(S); saveBook(db as any, book); return loadBook(db as any); };
    save(); save();                                                          // unchanged
    S.accounts.push({ id: 'n1', name: 'New wallet', kind: 'Cash', cur: 'UGX', open: 1000 }); expect(save().accounts.length).toBe(5);
    S.accounts.push({ id: 'n2', name: 'Cash', kind: 'Cash', cur: 'UGX', open: 5 }); expect(save().accounts.length).toBe(6);   // same name twice is allowed
    S.accounts.push({ id: 'n3', name: 'Dollars', kind: 'Mobile money', cur: 'USD', open: 12.34 }); save();
    S.projects.push({ id: 'p1', name: 'Reno' }, { id: 'p2', name: 'Reno' }); save();
    S.cats[0].subs.push({ id: 'zz', name: 'Extra', icon: 'food' }); save();
    S.cats.pop(); S.tx = S.tx.filter((t: any) => t.cat !== S.cats.length); save();
    S.tx.push({ id: 9999, date: '2026-11-02', type: 'exp', amount: 5, cat: 'food', sub: 'groc', acc: 'n1', to: '', amountTo: null, rate: 1, project: '', payee: 'Brand new payee', note: '' }); save();
    S.tx = []; const last = save();
    expect(last.tx.length).toBe(0); expect(last.accounts.length).toBe(7);
  });
  it('upgrades an older data file without losing anything', () => {
    const db = new DatabaseSync(':memory:'); initDb(db as any);
    const { book } = fromPrototype(proto); saveBook(db as any, book);
    db.exec("UPDATE meta SET value='2' WHERE key='schema'");
    initDb(db as any);
    expect(loadBook(db as any).tx.length).toBe(book.tx.length);
    expect((db.prepare("SELECT value FROM meta WHERE key='schema'").all()[0] as any).value).toBe('7');
  });
});

import { describe as d2, it as i2, expect as e2 } from 'vitest';
import { fromPrototype as fp2, toPrototype as tp2 } from '../src/convert';
import fx2 from './fixtures/prototype-backup.json';
d2('account types', () => {
  i2('survive the round trip and any free-text kind is accepted', () => {
    const doc: any = JSON.parse(JSON.stringify((fx2 as any).data ?? fx2));
    doc.accountTypes = [{ id: 'at1', name: 'Bitcoin', icon: 'bitcoin' }, { id: 'at2', name: 'Cash', icon: 'cash' }];
    doc.accounts[0].kind = 'Bitcoin';
    const back: any = tp2(fp2(doc).book);
    e2(back.accountTypes).toEqual(doc.accountTypes);
    e2(back.accounts[0].kind).toBe('Bitcoin');
  });
});

d2('transfer charge', () => {
  const mk = () => { const doc: any = JSON.parse(JSON.stringify((fx2 as any).data ?? fx2)); return doc; };
  i2('a charge row stays linked through the round trip', () => {
    const doc = mk();
    const xf = doc.tx.find((t: any) => t.type === 'xfer');
    doc.tx.push({ id: 99999, date: xf.date, type: 'exp', amount: 500, cat: doc.cats[0].id, sub: '', acc: xf.acc, to: '', amountTo: null, rate: 1, project: '', note: 'Transfer charge', feeOf: xf.id });
    const { book } = fp2(doc);
    const fee = book.tx.find(t => t.id === 99999)!;
    e2(fee.feeOf).toBe(Number(xf.id));
    e2(validateBook(book)).toEqual([]);
    const back: any = tp2(book);
    e2(back.tx.find((t: any) => t.id === 99999).feeOf).toBe(Number(xf.id));
  });
  i2('a charge on the wrong account or without its transfer is refused', () => {
    const doc = mk();
    const xf = doc.tx.find((t: any) => t.type === 'xfer');
    const other = doc.accounts.find((a: any) => a.id !== xf.acc).id;
    doc.tx.push({ id: 99998, date: xf.date, type: 'exp', amount: 500, cat: doc.cats[0].id, sub: '', acc: other, to: '', amountTo: null, rate: 1, project: '', note: '', feeOf: xf.id });
    e2(validateBook(fp2(doc).book).join(' ')).toContain('source account');
    const doc2 = mk();
    doc2.tx.push({ id: 99997, date: xf.date, type: 'exp', amount: 500, cat: doc2.cats[0].id, sub: '', acc: xf.acc, to: '', amountTo: null, rate: 1, project: '', note: '', feeOf: 424242 });
    e2(validateBook(fp2(doc2).book).join(' ')).toContain('without its transfer');
  });
});

d2('repeat rules', () => {
  i2('survive the round trip through the book and the data file', () => {
    const doc: any = JSON.parse(JSON.stringify((fx2 as any).data ?? fx2));
    const tpl = { type: 'exp', amount: 100, cat: doc.cats[0].id, sub: '', acc: doc.accounts[0].id, note: '', payee: '', project: '' };
    doc.recurring = [
      { id: 'rA', freq: 'weekly', day: 3, next: '2026-11-04', tpl, n: 2, wd: [1, 3], end: '2027-01-01' },
      { id: 'rB', freq: 'monthly', day: 12, next: '2026-12-11', tpl, mm: 'nth', nth: 2, wdn: 5, cnt: 6, done: 1 },
    ];
    const book = fp2(doc).book;
    const db = new DatabaseSync(':memory:'); initDb(db as any); saveBook(db as any, book);
    const back: any = tp2(loadBook(db as any));
    const pick = (r: any) => ({ freq: r.freq, n: r.n, wd: r.wd, end: r.end, mm: r.mm, nth: r.nth, wdn: r.wdn, cnt: r.cnt, done: r.done });
    expect(pick(back.recurring[0])).toEqual({ freq: 'weekly', n: 2, wd: [1, 3], end: '2027-01-01' });
    expect(pick(back.recurring[1])).toEqual({ freq: 'monthly', mm: 'nth', nth: 2, wdn: 5, cnt: 6, done: 1 });
  });
});

d2('account notes', () => {
  i2('survive the round trip, and an older data file upgrades with its accounts intact', () => {
    const doc: any = JSON.parse(JSON.stringify((fx2 as any).data ?? fx2));
    doc.accounts[0].notes = '  Salary account\nBranch: Kampala Road  ';
    const book = fp2(doc).book;
    const db = new DatabaseSync(':memory:'); initDb(db as any); saveBook(db as any, book);
    const back: any = tp2(loadBook(db as any));
    e2(back.accounts[0].notes).toBe('Salary account\nBranch: Kampala Road');
    e2(back.accounts[1].notes).toBeUndefined();

    // a file written before notes existed (schema 5, no notes column) still opens
    const old = new DatabaseSync(':memory:');
    old.exec('CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)');
    initDb(old as any); saveBook(old as any, book);
    old.exec("UPDATE meta SET value='5' WHERE key='schema'");
    old.exec('ALTER TABLE account DROP COLUMN notes');
    initDb(old as any);
    const up: any = loadBook(old as any);
    e2(up.accounts.length).toBe(book.accounts.length);
    e2((old.prepare("SELECT value FROM meta WHERE key='schema'").all()[0] as any).value).toBe('7');
  });
});

d2('account included-in-totals flag', () => {
  i2('is kept only when an account is left out, and survives the round trip', () => {
    const doc: any = JSON.parse(JSON.stringify((fx2 as any).data ?? fx2));
    doc.accounts[0].inTotals = false;
    const book = fp2(doc).book;
    const db = new DatabaseSync(':memory:'); initDb(db as any); saveBook(db as any, book);
    const back: any = tp2(loadBook(db as any));
    e2(back.accounts[0].inTotals).toBe(false);
    e2(back.accounts[1].inTotals).toBeUndefined();
  });
});

describe('goals and goal types', () => {
  it('survive the round trip', () => {
    const d = { ...proto, goalTypes: [{ id: 'gt1', name: 'Emergency fund', icon: 'shield', beh: 'top' }], goals: [{ id: 'g1', name: 'Rainy day', type: 'gt1', acc: proto.accounts[0].id, target: 5000000, by: '2027-06-30', from: '2026-11-08' }] };
    const { book } = fromPrototype(d);
    const db = new DatabaseSync(':memory:'); initDb(db as any); saveBook(db as any, book);
    const back = toPrototype(loadBook(db as any));
    expect(back.goals).toEqual(d.goals);
    expect(back.goalTypes).toEqual(d.goalTypes);
  });
});

describe('budget enforcement levels', () => {
  it('survive the round trip', () => {
    const d = { ...proto, budgetMode: { '1': 'stop', '2': 'warn' } };
    const { book } = fromPrototype(d);
    const db = new DatabaseSync(':memory:'); initDb(db as any); saveBook(db as any, book);
    expect(toPrototype(loadBook(db as any)).budgetMode).toEqual(d.budgetMode);
  });
});

describe('last used category', () => {
  it('survives the round trip', () => {
    const d = { ...proto, lastCat: { exp: { cat: 'food', sub: 'groc' } } };
    const { book } = fromPrototype(d);
    const db = new DatabaseSync(':memory:'); initDb(db as any); saveBook(db as any, book);
    expect(toPrototype(loadBook(db as any)).lastCat).toEqual(d.lastCat);
  });
});

describe('sidebar layout', () => {
  it('survives the round trip', () => {
    const d = { ...proto, sbOrder: 'total,attn', sbHide: 'goal,month' };
    const { book } = fromPrototype(d);
    const db = new DatabaseSync(':memory:'); initDb(db as any); saveBook(db as any, book);
    const back = toPrototype(loadBook(db as any));
    expect(back.sbOrder).toBe('total,attn'); expect(back.sbHide).toBe('goal,month');
  });
});
