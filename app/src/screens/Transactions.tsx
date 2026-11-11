import { useState } from 'preact/hooks';
import { book, mutate, today, ask } from '../store';
import { runningBalances, cmpTx, acct } from '../../../core/src/ledger';
import { deleteTx } from '../../../core/src/rules';
import { money, cls } from '../format';
import { EntrySheet } from './EntrySheet';
import type { Tx } from '../../../core/src/types';
export function Transactions() {
  const b = book.value, [accId, setAcc] = useState(0), [adding, setAdding] = useState(false), [editing, setEditing] = useState<Tx | null>(null);
  const run = accId ? runningBalances(b, accId) : null;
  const list = b.tx.filter(t => !accId || t.acc === accId || t.to === accId).sort((x, y) => -cmpTx(x, y)).slice(0, 300);
  const catName = (t: Tx) => t.type === 'xfer' ? `→ ${acct(b, t.to!).name}` : (b.categories.find(c => c.id === t.cat)?.name ?? 'Uncategorised');
  const dir = (t: Tx): 1 | -1 => t.type === 'inc' ? 1 : t.type === 'exp' ? -1 : (accId && t.to === accId ? 1 : -1);
  let last = '';
  return (<>
    <div style="display:flex;gap:10px;align-items:center;margin-bottom:12px">
      <h2 style="margin:0;flex:1">Transactions</h2>
      <select aria-label="Account" value={accId} onChange={e => setAcc(+(e.target as HTMLSelectElement).value)}>
        <option value={0}>All accounts</option>{b.accounts.map(a => <option value={a.id} key={a.id}>{a.name}</option>)}
      </select>
      <button class="btn pri" disabled={!b.accounts.length} onClick={() => setAdding(true)}>Add</button>
    </div>
    <div class="card">{list.map(t => { const head = t.date !== last ? (last = t.date, <div class="day">{t.date}{t.date > today() ? ' · upcoming' : ''}</div>) : null;
      const a = acct(b, t.acc), sign = dir(t), amt = sign > 0 && t.type === 'xfer' && t.to === accId ? (t.amountTo ?? t.amount) : t.amount, c = (t.type === 'xfer' && t.to === accId ? acct(b, t.to!) : a).cur;
      return (<div key={t.id}>{head}
        <div class="row" style="cursor:pointer" tabIndex={0} role="button" aria-label="Edit transaction" onClick={() => setEditing(t)} onKeyDown={e => e.key === 'Enter' && setEditing(t)}><div><div>{catName(t)}</div><div class="mut">{a.name}{t.note ? ' · ' + t.note : ''}</div></div>
          <div class={`num ${t.type === 'xfer' && !accId ? '' : cls(sign)}`}>{sign > 0 ? '+' : '−'}{money(b, amt, c)}{run && <div class={`mut ${cls(run.get(t.id)!)}`}>{money(b, run.get(t.id)!, a.cur)}</div>}</div>
          <button class="btn del" aria-label="Delete transaction" onClick={async e => { e.stopPropagation(); if (await ask('Delete this transaction?')) mutate(x => deleteTx(x, t.id)); }}>✕</button></div></div>); })}
      {!list.length && <div class="mut">{b.accounts.length ? 'No transactions yet.' : 'Add an account first, then record transactions here.'}</div>}</div>
    {editing && <EntrySheet edit={editing} onClose={() => setEditing(null)} defaultAcc={editing.acc} />}
    {adding && <EntrySheet onClose={() => setAdding(false)} defaultAcc={accId || b.accounts[0]?.id} />}
  </>);
}
