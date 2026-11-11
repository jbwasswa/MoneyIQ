/** Glue between the prototype screens (which hold a decimal document) and the core (whole minor units + checks). */
import { fromPrototype, toPrototype } from './convert.js';
import { validateBook, readBackup } from './backup.js';
import { balance } from './ledger.js';
import type { Book } from './types.js';

export interface CheckResult { ok: boolean; problems: string[]; }
const isoToday = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };

/** Converts the screens' document to a Book and checks it. Balances must agree to the currency's precision. */
export function check(S: any, screensBal?: (a: any) => number): { book: Book | null; result: CheckResult } {
  const problems: string[] = [];
  let book: Book | null = null;
  try {
    const r = fromPrototype(S); book = r.book; problems.push(...r.warnings, ...validateBook(book));
    const today = isoToday();
    for (const a of S.accounts) {
      const acc = book.accounts.find(x => x.ref === String(a.id))!, d = book.currencies.find(c => c.code === a.cur)!.decimals;
      const screens = screensBal ? screensBal(a) : null;
      if (screens != null && Math.abs(balance(book, acc.id, today) / 10 ** d - screens) > 0.5 / 10 ** d) problems.push(`Balance mismatch on ${a.name}`);
    }
  } catch (e) { problems.push((e as Error).message); }
  return { book: problems.length ? null : book, result: { ok: !problems.length, problems } };
}
export { fromPrototype, toPrototype, validateBook, readBackup };
