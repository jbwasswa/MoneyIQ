import { describe, it, expect } from 'vitest';
import { parseAmount, formatAmount, convert, crossRate } from '../src/money.js';
import { addTx, balance, runningBalances, totalInBase } from '../src/ledger.js';
import { blockWhy, deleteRecord, deleteTx, catchUp, loanBalances, summary } from '../src/rules.js';
import type { Book } from '../src/types.js';

const mk = (countFuture = true): Book => ({
  settings: { base: 'UGX', countFuture },
  currencies: [{ code: 'UGX', decimals: 0, rate: '1' }, { code: 'USD', decimals: 2, rate: '3700' }],
  accounts: [
    { id: 1, name: 'Cash', kind: 'Cash', cur: 'UGX', opening: 100000 },
    { id: 2, name: 'Dollar', kind: 'Bank', cur: 'USD', opening: 10000 },
    { id: 3, name: 'I owe', kind: 'Loan borrowed', cur: 'UGX', opening: 0 },
    { id: 4, name: 'Owed to me', kind: 'Loan given', cur: 'UGX', opening: 0 }],
  categories: [{ id: 1, name: 'Food', type: 'exp', parent: null }, { id: 2, name: 'Lunch', type: 'exp', parent: 1 }, { id: 3, name: 'Salary', type: 'inc', parent: null }],
  parties: [{ id: 1, name: 'Peter', kind: 'counterparty' }], projects: [], tx: [], recurring: [], budgets: [],
});

describe('money', () => {
  it('parses and formats per currency precision', () => {
    expect(parseAmount('1,234.50', 2)).toBe(123450);
    expect(parseAmount('55000000', 0)).toBe(55000000);
    expect(() => parseAmount('1.5', 0)).toThrow();
    expect(formatAmount(123450, 2)).toBe('1,234.50');
    expect(formatAmount(-5, 2)).toBe('-0.05');
  });
  it('has no float drift', () => {
    let s = 0; for (let i = 0; i < 1000; i++) s += parseAmount('0.10', 2);
    expect(formatAmount(s, 2)).toBe('100.00');
  });
  it('converts exactly and rounds half away from zero', () => {
    expect(convert(10000, 2, 0, '3700')).toBe(370000);
    expect(convert(1, 2, 0, '50')).toBe(1);   // 0.01*50 = 0.5 -> 1
    expect(convert(-1, 2, 0, '50')).toBe(-1);
    expect(crossRate('3700', '1')).toBe('3700');
  });
});

describe('ledger', () => {
  it('back-dated entry changes later running balances, not the final one', () => {
    const b = mk();
    addTx(b, { type: 'exp', date: '2026-03-10', acc: 1, amount: 1000 });
    addTx(b, { type: 'exp', date: '2026-03-20', acc: 1, amount: 2000 });
    const before = runningBalances(b, 1), finalBefore = balance(b, 1, '2026-12-31');
    const late = addTx(b, { type: 'inc', date: '2026-03-05', acc: 1, amount: 5000 });
    const after = runningBalances(b, 1);
    expect(after.get(late.id)).toBe(105000);
    expect(after.get(1)).toBe(before.get(1)! + 5000);
    expect(after.get(2)).toBe(before.get(2)! + 5000);
    expect(balance(b, 1, '2026-12-31')).toBe(finalBefore + 5000);
  });
  it('same-date entries keep entry order', () => {
    const b = mk();
    addTx(b, { type: 'exp', date: '2026-01-01', acc: 1, amount: 100 });
    addTx(b, { type: 'exp', date: '2026-01-01', acc: 1, amount: 200 });
    expect([...runningBalances(b, 1).values()]).toEqual([99900, 99700]);
  });
  it('future-dated setting', () => {
    const on = mk(true), off = mk(false);
    for (const b of [on, off]) addTx(b, { type: 'exp', date: '2026-12-01', acc: 1, amount: 1000 });
    expect(balance(on, 1, '2026-11-02')).toBe(99000);
    expect(balance(off, 1, '2026-11-02')).toBe(100000);
    expect(balance(off, 1, '2026-12-01')).toBe(99000);
  });
  it('cross-currency transfer moves both sides', () => {
    const b = mk();
    addTx(b, { type: 'xfer', date: '2026-01-01', acc: 1, to: 2, amount: 370000, amountTo: 10000 });
    expect(balance(b, 1, '2027-01-01')).toBe(100000 - 370000);
    expect(balance(b, 2, '2027-01-01')).toBe(20000);
    expect(() => addTx(b, { type: 'xfer', date: '2026-01-01', acc: 1, to: 2, amount: 5 })).toThrow();
    expect(() => addTx(b, { type: 'xfer', date: '2026-01-01', acc: 1, to: 1, amount: 5 })).toThrow();
  });
  it('transfers do not change the total net worth (same currency)', () => {
    const b = mk(); const t0 = totalInBase(b, '2026-01-01');
    addTx(b, { type: 'xfer', date: '2026-01-01', acc: 1, to: 4, amount: 30000, party: 1 });
    expect(totalInBase(b, '2026-01-01')).toBe(t0);
  });
});

describe('rules', () => {
  it('blocks deleting referenced records and allows unused ones', () => {
    const b = mk(); addTx(b, { type: 'exp', date: '2026-01-01', acc: 1, amount: 5, cat: 2, party: 1 });
    expect(blockWhy(b, 'account', 1)).toMatch(/transaction/);
    expect(blockWhy(b, 'category', 1)).toMatch(/subcategory/);
    expect(blockWhy(b, 'category', 2)).toMatch(/transaction/);
    expect(blockWhy(b, 'currency', 'USD')).toMatch(/account/);
    expect(blockWhy(b, 'currency', 'UGX')).toMatch(/base/);
    expect(() => deleteRecord(b, 'party', 1)).toThrow();
    deleteTx(b, 1);
    deleteRecord(b, 'party', 1); expect(b.parties.length).toBe(0);
  });
  it('loan balances per person, repaid to zero', () => {
    const b = mk();
    addTx(b, { type: 'xfer', date: '2026-01-01', acc: 3, to: 1, amount: 50000, party: 1 }); // borrow
    expect(loanBalances(b, 3).get(1)).toBe(-50000);
    addTx(b, { type: 'xfer', date: '2026-02-01', acc: 1, to: 3, amount: 50000, party: 1 }); // repay
    expect(loanBalances(b, 3).get(1)).toBe(0);
  });
  it('recurring catches up from due dates and keeps the day of month', () => {
    const b = mk();
    b.recurring.push({ id: 1, tx: { type: 'exp', acc: 1, amount: 100 }, every: 'month', next: '2026-01-31' });
    const made = catchUp(b, '2026-04-30');
    expect(made.map(t => t.date)).toEqual(['2026-01-31', '2026-02-28', '2026-03-31', '2026-04-30']);
    expect(b.recurring[0].next).toBe('2026-05-31');
    expect(catchUp(b, '2026-04-30').length).toBe(0);
  });
  it('repeat rules: every n days, weekdays, last day, nth weekday, and stopping', () => {
    const rr = (every: any, rule: any, next: string, end?: string) => ({ id: 1, tx: { type: 'exp' as const, acc: 1, amount: 1 }, every, next, rule, ...(end ? { end } : {}) });
    const dates = (r: any, today: string) => { const b = mk(); b.recurring.push(r); return catchUp(b, today).map(t => t.date); };
    expect(dates(rr('day', { n: 3 }, '2026-01-01'), '2026-01-10')).toEqual(['2026-01-01', '2026-01-04', '2026-01-07', '2026-01-10']);
    expect(dates(rr('week', { weekdays: [1, 3] }, '2026-11-02'), '2026-11-11')).toEqual(['2026-11-02', '2026-11-04', '2026-11-09', '2026-11-11']);
    expect(dates(rr('week', { n: 2, weekdays: [1, 3] }, '2026-11-02'), '2026-11-20')).toEqual(['2026-11-02', '2026-11-04', '2026-11-16', '2026-11-18']);
    expect(dates(rr('month', { monthly: 'last' }, '2026-01-31'), '2026-04-30')).toEqual(['2026-01-31', '2026-02-28', '2026-03-31', '2026-04-30']);
    expect(dates(rr('month', { monthly: 'nth', nth: 2, weekday: 5 }, '2026-11-13'), '2027-01-31')).toEqual(['2026-11-13', '2026-12-11', '2027-01-08']);
    expect(dates(rr('month', { monthly: 'nth', nth: -1, weekday: 5 }, '2026-10-30'), '2027-01-31')).toEqual(['2026-10-30', '2026-11-27', '2026-12-25', '2027-01-29']);
    expect(dates(rr('month', { n: 3 }, '2026-01-15'), '2026-12-31')).toEqual(['2026-01-15', '2026-04-15', '2026-07-15', '2026-10-15']);
    expect(dates(rr('month', { count: 3, done: 1 }, '2026-01-15'), '2026-12-31')).toEqual(['2026-01-15', '2026-02-15']);
    const b = mk(); b.recurring.push(rr('week', {}, '2026-01-01', '2026-01-15'));
    expect(catchUp(b, '2026-12-31').map(t => t.date)).toEqual(['2026-01-01', '2026-01-08', '2026-01-15']);
    expect(b.recurring.length).toBe(0);
  });
  it('report sums convert each line to base', () => {
    const b = mk();
    addTx(b, { type: 'inc', date: '2026-01-01', acc: 2, amount: 10000, cat: 3 });
    addTx(b, { type: 'exp', date: '2026-01-02', acc: 1, amount: 20000, cat: 2 });
    addTx(b, { type: 'xfer', date: '2026-01-03', acc: 1, to: 4, amount: 999 });
    expect(summary(b, {})).toEqual({ income: 370000, expense: 20000, net: 350000 });
    expect(summary(b, { cat: 1 }).expense).toBe(20000); // parent filter includes subcategory
  });
});

import { updateTx } from '../src/ledger.js';
describe('editing', () => {
  it('editing the date or amount re-derives balances and keeps the id', () => {
    const b = mk();
    const a = addTx(b, { type: 'exp', date: '2026-03-10', acc: 1, amount: 1000 });
    addTx(b, { type: 'exp', date: '2026-03-20', acc: 1, amount: 2000 });
    updateTx(b, a.id, { type: 'exp', date: '2026-03-25', acc: 1, amount: 1500 });
    expect([...runningBalances(b, 1).entries()]).toEqual([[2, 98000], [1, 96500]]);
    expect(() => updateTx(b, a.id, { type: 'exp', date: 'bad', acc: 1, amount: 5 })).toThrow();
    expect(b.tx.find(t => t.id === a.id)!.amount).toBe(1500);
  });
});
