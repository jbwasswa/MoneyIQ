import { useState } from 'preact/hooks';
import { book, mutate } from '../store';
import { blockWhy, deleteRecord } from '../../../core/src/rules';
type Kind = 'party' | 'project' | 'category';
const nextId = (a: { id: number }[]) => a.reduce((m, x) => Math.max(m, x.id), 0) + 1;
export function Lists() {
  const b = book.value, [name, setName] = useState(''), [sec, setSec] = useState<'payee' | 'payer' | 'counterparty' | 'project' | 'exp' | 'inc'>('payee'), [parent, setParent] = useState(0), [err, setErr] = useState('');
  const isCat = sec === 'exp' || sec === 'inc';
  const items = sec === 'project' ? b.projects : isCat ? b.categories.filter(c => c.type === sec).sort((a, c) => (a.parent ?? a.id) - (c.parent ?? c.id) || (a.parent ? 1 : 0) - (c.parent ? 1 : 0)) : b.parties.filter(p => p.kind === sec);
  const kind: Kind = sec === 'project' ? 'project' : isCat ? 'category' : 'party';
  const dupe = (n: string) => items.some(i => i.name.toLowerCase() === n.toLowerCase());
  const add = (e: Event) => { e.preventDefault(); const n = name.trim(); if (!n) return setErr('Enter a name'); if (dupe(n)) return setErr('That name already exists here'); setErr('');
    mutate(x => { if (kind === 'project') x.projects.push({ id: nextId(x.projects), name: n }); else if (kind === 'party') x.parties.push({ id: nextId(x.parties), name: n, kind: sec as 'payee' }); else x.categories.push({ id: nextId(x.categories), name: n, type: sec as 'exp', parent: parent || null }); }); setName(''); };
  const rename = (id: number, v: string) => { v = v.trim(); if (!v) return; mutate(x => { const arr: { id: number; name: string }[] = kind === 'project' ? x.projects : kind === 'party' ? x.parties : x.categories; const r = arr.find(i => i.id === id); if (r) r.name = v; }); };
  const SECS: [typeof sec, string][] = [['payee', 'Payees'], ['payer', 'Payers'], ['counterparty', 'Loan people'], ['project', 'Projects'], ['exp', 'Expense categories'], ['inc', 'Income categories']];
  return (<>
    <h2>Lists</h2>
    <div class="seg" style="flex-wrap:wrap;margin-bottom:12px">{SECS.map(([k, l]) => <button class={`btn ${k === sec ? 'pri' : ''}`} onClick={() => { setSec(k); setErr(''); }}>{l}</button>)}</div>
    <div class="card">{items.map((i: any) => { const why = blockWhy(b, kind, i.id); return (
      <div class="row" key={i.id} style="grid-template-columns:1fr auto"><div style={i.parent ? 'padding-left:20px' : ''}><input aria-label="Name" defaultValue={i.name} onChange={e => rename(i.id, (e.target as HTMLInputElement).value)} />{why && <span class="mut"> In use: {why}</span>}</div>
        <button class="btn del" disabled={!!why} onClick={() => mutate(x => deleteRecord(x, kind, i.id))}>Delete</button></div>); })}{!items.length && <div class="mut">Nothing here yet.</div>}</div>
    <form class="card" onSubmit={add} style="display:grid;gap:8px"><input aria-label="New name" placeholder="New name" value={name} onInput={e => setName((e.target as HTMLInputElement).value)} />
      {isCat && <select aria-label="Parent category" value={parent} onChange={e => setParent(+(e.target as HTMLSelectElement).value)}><option value={0}>Top level</option>{b.categories.filter(c => c.type === sec && !c.parent).map(c => <option value={c.id}>{c.name}</option>)}</select>}
      {err && <div class="err" role="alert">{err}</div>}<button class="btn pri">Add</button></form>
  </>);
}
