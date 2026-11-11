/** All money is whole minor units (integers). Dates are 'YYYY-MM-DD'. Rates are decimal text. */
export type Id = number;
export interface Currency { code: string; name?: string; decimals: number; /** base units per 1 unit of this currency, decimal text */ rate: string; }
/** Free text: the user defines account types. 'Loan given' and 'Loan borrowed' drive loan tracking. */
export type AccountKind = string;
export interface Account { id: Id; name: string; kind: AccountKind; cur: string; opening: number; ref?: string; /** free text note about the account */ notes?: string; /** false when the account is left out of the totals; absent means included */ inTotals?: boolean; }
export interface Category { id: Id; name: string; type: 'inc' | 'exp' | 'xfer'; parent: Id | null; icon?: string; color?: string; ref?: string; }
/** `ref` present means the name appears in the user's saved list; absent means it was typed freely on a transaction. */
export interface Party { id: Id; name: string; kind: 'payee' | 'payer' | 'counterparty'; ref?: string; }
export interface Project { id: Id; name: string; ref?: string; }
export type TxType = 'inc' | 'exp' | 'xfer';
export interface Tx {
  id: Id; type: TxType; date: string; acc: Id; amount: number;
  /** transfer destination account and amount received in its currency */
  to?: Id; amountTo?: number;
  cat?: Id; party?: Id; project?: Id; note?: string;
  /** set on an expense that is the charge of a transfer: the transfer's id. Same account as the transfer's source. */
  feeOf?: Id;
  /** amount as typed, when entered in a currency other than the account's, and the rate used (decimal text) */
  txnCur?: string; txnAmount?: number; txnRate?: string;
  /** rate snapshot (base per 1 unit of the account currency) at entry time, decimal text */
  rate?: string;
}
/** Extra repeat rules. Without them a rule means "every 1 <unit>" on the day of `next`. */
export interface RepeatRule {
  /** every n days/weeks/months/years (default 1) */ n?: number;
  /** weekly: days of the week, 0 = Sunday */ weekdays?: number[];
  /** monthly: the same date, the last day, or the nth weekday (nth 1-4, or -1 for the last) */ monthly?: 'day' | 'last' | 'nth'; nth?: number; weekday?: number;
  /** stop after this many occurrences in total, and how many have been made */ count?: number; done?: number;
}
export interface Recurring { id: Id; ref?: string; day?: number; tx: Omit<Tx, 'id' | 'date'>; every: 'day' | 'week' | 'month' | 'year'; next: string; /** last date an occurrence may fall on */ end?: string; rule?: RepeatRule; }
export interface Settings { base: string; countFuture: boolean; /** screen preferences kept with the data: theme, last account, last backup */ ui?: Record<string, string | number | boolean>; /** true while the book still holds the sample data */ sample?: boolean; }
export interface Book {
  settings: Settings; currencies: Currency[]; accounts: Account[]; categories: Category[];
  parties: Party[]; projects: Project[]; tx: Tx[]; recurring: Recurring[];
  /** monthly budget per top-level category, base-currency minor units */
  budgets: { cat: Id; amount: number }[];
}
