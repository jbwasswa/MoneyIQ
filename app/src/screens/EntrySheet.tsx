import { useState } from 'preact/hooks';
import { book, mutate, today } from '../store';
import { addTx, updateTx, acct, cur } from '../../../core/src/ledger';
import { parseAmount, convert, formatAmount, crossRate } from '../../../core/src/money';
import type { Tx, TxType } from '../../../core/src/types';
export function EntrySheet({ onClose, defaultAcc, edit }: { onClose: () => void; defaultAcc: number; edit?: Tx }) {
  const b = book.value, txt = (n: number, code: string) => formatAmount(n, cur(b, code).decimals).replace(/,/g, '');
  const [type, setType] = useState<TxType>(edit?.type ?? 'exp'), [acc, setAcc] = useState(edit?.acc ?? defaultAcc), [to, setTo] = useState(edit?.to ?? b.accounts.find(a => a.id !== defaultAcc)?.id ?? 0);
  const [amt, setAmt] = useState(edit ? txt(edit.amount, acct(b, edit.acc).cur) : ''), [recv, setRecv] = useState(edit?.amountTo != null && edit.to ? txt(edit.amountTo, acct(b, edit.to).cur) : ''), [cat, setCat] = useState(edit?.cat ?? 0), [party, setParty] = useState(edit?.party ?? 0);
  const [date, setDate] = useState(edit?.date ?? today()), [note, setNote] = useState(edit?.note ?? ''), [err, setErr] = useState('');
  const from = acct(b, acc), dest = type === 'xfer' && to ? acct(b, to) : null, cross = !!dest && dest.cur !== from.cur;
  let auto = '';
  try { if (cross && amt) { const fa = parseAmount(amt, cur(b, from.cur).decimals); auto = formatAmount(convert(fa, cur(b, from.cur).decimals, cur(b, dest!.cur).decimals, crossRate(cur(b, from.cur).rate, cur(b, dest!.cur).rate)), cur(b, dest!.cur).decimals); } } catch { /* shown on save */ }
  const cats = b.categories.filter(c => type !== 'xfer' && c.type === type);
  const parties = b.parties.filter(p => type === 'inc' ? p.kind === 'payer' : type === 'exp' ? p.kind === 'payee' : p.kind === 'counterparty');
  const save = (e: Event) => {
    e.preventDefault();
    let amount: number, amountTo: number | undefined;
    try {
      amount = parseAmount(amt, cur(b, from.cur).decimals);
      if (cross) amountTo = parseAmount(recv || auto, cur(b, dest!.cur).decimals);
    } catch (x) { return setErr((x as Error).message); }
    const body = { type, date, acc, amount, ...(type === 'xfer' ? { to, ...(cross ? { amountTo } : {}) } : { cat: cat || undefined }), party: party || undefined, note: note || undefined };
    const m = mutate(x => { if (edit) updateTx(x, edit.id, body); else addTx(x, body); });
    if (m) setErr(m); else onClose();
  };
  return (
    <div class="sheet" role="dialog" aria-modal="true" aria-label={edit ? 'Edit entry' : 'New entry'}><form onSubmit={save}>
      <div class="seg">{(['exp', 'inc', 'xfer'] as const).map(t => <button type="button" class={`btn ${t === type ? 'pri' : ''}`} onClick={() => { setType(t); setCat(0); setParty(0); }}>{t === 'exp' ? 'Expense' : t === 'inc' ? 'Income' : 'Transfer'}</button>)}</div>
      <label>{type === 'xfer' ? 'From account' : 'Account'}<select value={acc} onChange={e => setAcc(+(e.target as HTMLSelectElement).value)}>{b.accounts.map(a => <option value={a.id}>{a.name} ({a.cur})</option>)}</select></label>
      {type === 'xfer' && <label>To account<select value={to} onChange={e => setTo(+(e.target as HTMLSelectElement).value)}>{b.accounts.filter(a => a.id !== acc).map(a => <option value={a.id}>{a.name} ({a.cur})</option>)}</select></label>}
      <label>Amount ({from.cur})<input inputMode="decimal" value={amt} onInput={e => setAmt((e.target as HTMLInputElement).value)} autofocus required /></label>
      {cross && <label>Amount received ({dest!.cur})<input inputMode="decimal" value={recv} placeholder={auto} onInput={e => setRecv((e.target as HTMLInputElement).value)} /></label>}
      {type !== 'xfer' && <label>Category<select value={cat} onChange={e => setCat(+(e.target as HTMLSelectElement).value)}><option value={0}>None</option>{cats.map(c => <option value={c.id}>{c.parent ? '   ' : ''}{c.name}</option>)}</select></label>}
      <label>{type === 'inc' ? 'Payer' : type === 'exp' ? 'Payee' : 'Person (for loans)'}<select value={party} onChange={e => setParty(+(e.target as HTMLSelectElement).value)}><option value={0}>None</option>{parties.map(p => <option value={p.id}>{p.name}</option>)}</select></label>
      <label>Date<input type="date" value={date} onInput={e => setDate((e.target as HTMLInputElement).value)} required /></label>
      <label>Note<input value={note} onInput={e => setNote((e.target as HTMLInputElement).value)} /></label>
      {err && <div class="err" role="alert">{err}</div>}
      <div class="seg"><button type="button" class="btn" onClick={onClose}>Cancel</button><button class="btn pri">{edit ? 'Save changes' : 'Save'}</button></div>
    </form></div>);
}
