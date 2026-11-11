import { useState } from 'preact/hooks';
import { book, mutate, today, ask } from '../store';
import { balance } from '../../../core/src/ledger';
import { blockWhy, deleteRecord, loanBalances } from '../../../core/src/rules';
import { parseAmount } from '../../../core/src/money';
import { money, cls, decOf } from '../format';
import type { AccountKind } from '../../../core/src/types';
const KINDS: AccountKind[] = ['Cash', 'Bank', 'Card', 'Savings', 'Loan given', 'Loan borrowed', 'Other'];
export function Accounts() {
  const b = book.value, t = today();
  const [name, setName] = useState(''), [kind, setKind] = useState<AccountKind>('Cash'), [curr, setCurr] = useState(b.settings.base), [open, setOpen] = useState('0'), [err, setErr] = useState('');
  const add = (e: Event) => { e.preventDefault(); let o = 0; try { o = parseAmount(open || '0', decOf(b, curr)); } catch (x) { return setErr((x as Error).message); }
    if (!name.trim()) return setErr('Give the account a name');
    if (b.accounts.some(a => a.name.toLowerCase() === name.trim().toLowerCase())) return setErr('An account with that name already exists');
    setErr(''); mutate(x => { x.accounts.push({ id: x.accounts.reduce((m, a) => Math.max(m, a.id), 0) + 1, name: name.trim(), kind, cur: curr, opening: o }); }); setName(''); setOpen('0'); };
  return (<>
    <h2>Accounts</h2>
    {b.accounts.map(a => { const v = balance(b, a.id, t), why = blockWhy(b, 'account', a.id), loan = a.kind.startsWith('Loan'), people = loan ? [...loanBalances(b, a.id)].filter(([, n]) => n !== 0) : [];
      return (<div class="card" key={a.id}>
        <div style="display:flex;justify-content:space-between;gap:10px;align-items:center"><div><b>{a.name}</b> <span class="mut">{a.kind} · {a.cur}</span></div><div class={`big num ${cls(v)}`}>{money(b, v, a.cur)}</div></div>
        {loan && <div class="mut" style="margin-top:6px">{people.length ? 'By person:' : 'No open loans.'}{people.map(([id, n]) => <div key={id} class="row" style="grid-template-columns:1fr auto"><span>{b.parties.find(p => p.id === id)?.name}</span><span class={`num ${cls(n)}`}>{money(b, n, a.cur)}</span></div>)}</div>}
        <div style="margin-top:8px;display:flex;gap:10px;align-items:center"><button class="btn del" disabled={!!why} onClick={async () => (await ask(`Delete ${a.name}?`)) && mutate(x => deleteRecord(x, 'account', a.id))}>Delete</button>{why && <span class="mut">In use: {why}</span>}</div></div>); })}
    <form class="card" onSubmit={add} style="display:grid;gap:8px"><b>New account</b>
      <input aria-label="Account name" placeholder="Name" value={name} onInput={e => setName((e.target as HTMLInputElement).value)} />
      <select aria-label="Kind" value={kind} onChange={e => setKind((e.target as HTMLSelectElement).value as AccountKind)}>{KINDS.map(k => <option>{k}</option>)}</select>
      <select aria-label="Currency" value={curr} onChange={e => setCurr((e.target as HTMLSelectElement).value)}>{b.currencies.map(c => <option>{c.code}</option>)}</select>
      <input aria-label="Opening balance" inputMode="decimal" value={open} onInput={e => setOpen((e.target as HTMLInputElement).value)} />
      {err && <div class="err" role="alert">{err}</div>}<button class="btn pri">Add account</button></form>
  </>);
}
