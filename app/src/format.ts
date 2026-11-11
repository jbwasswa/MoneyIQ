import { formatAmount } from '../../core/src/money';
import type { Book } from '../../core/src/types';
export const decOf = (b: Book, code: string) => b.currencies.find(c => c.code === code)?.decimals ?? 2;
export const money = (b: Book, minor: number, code: string) => `${code} ${formatAmount(minor, decOf(b, code))}`;
export const cls = (n: number) => (n < 0 ? 'neg' : n > 0 ? 'pos' : '');
