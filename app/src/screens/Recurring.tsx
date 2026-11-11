import { useState } from 'preact/hooks';
import { book, mutate, today } from '../store';
import { catchUp } from '../../../core/src/rules';
import { parseAmount } from '../../../core/src/money';
import { money, decOf } from '../format';
import type { Recurring as R } from '../../../core/src/types';
export function Recurring() {
  const b = book.value, [acc, setAcc] = useState(b.accounts[0]?.id ?? 0), [type, setType] = useState<'exp' | 'inc'>('exp'), [amt, setAmt] = useState(''), [every, setEvery] = useState<R['every']>('month'), [next, setNext] = useState(today()), [cat, setCat] = useState(0), [note, setNote] = useState(''), [err, setErr] = useState('');
  const add = (e: Event) => { e.preventDefault(); let a = 0; try { a = parseAmount(amt, decOf(b, b.accounts.find(x => x.id === acc)!.cur)); } catch (x) { return setErr((x as Error).message); } if (a <= 0) return setErr('Amount must be greater than zero'); setErr('');
    mutate(x => { x.recurring.push({ id: x.recurring.reduce((m, r) => Math.max(m, r.id), 0) + 1, every, next, tx: { type, acc, amount: a, cat: cat || undefined, note: note || undefined } }); catchUp(x, today()); }); setAmt(''); setNote(''); };
  return (<>
    <h2>Recurring</h2>
    <div class="mut" style="margin-bottom:10px">Due items are created when the app opens, from their due dates.</div>
    <div class="card">{b.recurring.map(r => <div class="row" key={r.id}><div>{r.tx.note || b.categories.find(c => c.id === r.tx.cat)?.name || r.tx.type}<div class="mut">Every {r.every} · next {r.next}</div></div><div class="num">{money(b, r.tx.amount, b.accounts.find(a => a.id === r.tx.acc)!.cur)}</div>
      <button class="btn del" onClick={() => mutate(x => { x.recurring = x.recurring.filter(i => i.id !== r.id); })}>Stop</button></div>)}{!b.recurring.length && <div class="mut">None yet.</div>}</div>
    <form class="card" onSubmit={add} style="display:grid;gap:8px"><b>New recurring item</b>
      <select aria-label="Type" value={type} onChange={e => setType((e.target as HTMLSelectElement).value as 'exp')}><option value="exp">Expense</option><option value="inc">Income</option></select>
      <select aria-label="Account" value={acc} onChange={e => setAcc(+(e.target as HTMLSelectElement).value)}>{b.accounts.map(a => <option value={a.id}>{a.name} ({a.cur})</option>)}</select>
      <input aria-label="Amount" inputMode="decimal" placeholder="Amount" value={amt} onInput={e => setAmt((e.target as HTMLInputElement).value)} />
      <select aria-label="Category" value={cat} onChange={e => setCat(+(e.target as HTMLSelectElement).value)}><option value={0}>No category</option>{b.categories.filter(c => c.type === type).map(c => <option value={c.id}>{c.name}</option>)}</select>
      <select aria-label="Repeat" value={every} onChange={e => setEvery((e.target as HTMLSelectElement).value as R['every'])}>{['day', 'week', 'month', 'year'].map(v => <option>{v}</option>)}</select>
      <input aria-label="First due date" type="date" value={next} onInput={e => setNext((e.target as HTMLInputElement).value)} />
      <input aria-label="Note" placeholder="Note" value={note} onInput={e => setNote((e.target as HTMLInputElement).value)} />
      {err && <div class="err" role="alert">{err}</div>}<button class="btn pri">Add</button></form>
  </>);
}
