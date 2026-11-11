import { useState } from 'preact/hooks';
import { book, today } from '../store';
import { summary, byCategory } from '../../../core/src/rules';
import { money, cls } from '../format';
export function Reports() {
  const b = book.value, m = today().slice(0, 7);
  const [from, setFrom] = useState(m + '-01'), [to, setTo] = useState(m + '-31'), [type, setType] = useState<'exp' | 'inc'>('exp');
  const s = summary(b, { from, to }), rows = [...byCategory(b, { from, to }, type)].sort((x, y) => y[1] - x[1]), max = rows[0]?.[1] || 1;
  return (<>
    <h2>Reports</h2>
    <div class="card" style="display:flex;gap:10px;flex-wrap:wrap">
      <label>From <input type="date" value={from} onInput={e => setFrom((e.target as HTMLInputElement).value)} /></label>
      <label>To <input type="date" value={to} onInput={e => setTo((e.target as HTMLInputElement).value)} /></label>
      <select aria-label="Type" value={type} onChange={e => setType((e.target as HTMLSelectElement).value as 'exp' | 'inc')}><option value="exp">Expenses</option><option value="inc">Income</option></select>
    </div>
    <div class="grid">
      <div class="card"><div class="mut">Income</div><div class="big pos num">{money(b, s.income, b.settings.base)}</div></div>
      <div class="card"><div class="mut">Expenses</div><div class="big neg num">{money(b, s.expense, b.settings.base)}</div></div>
      <div class="card"><div class="mut">Net</div><div class={`big num ${cls(s.net)}`}>{money(b, s.net, b.settings.base)}</div></div>
    </div>
    <div class="card">{rows.map(([id, v]) => <div key={id} style="margin:8px 0"><div style="display:flex;justify-content:space-between"><span>{b.categories.find(c => c.id === id)?.name}</span><span class="num">{money(b, v, b.settings.base)}</span></div><div class="bar" style={{ width: `${(v / max) * 100}%`, background: type === 'inc' ? 'var(--pos)' : 'var(--neg)' }} /></div>)}
      {!rows.length && <div class="mut">Nothing in this period.</div>}</div>
  </>);
}
