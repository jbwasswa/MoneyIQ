import { book, today, clearSample, ask } from '../store';
import { balance, totalInBase } from '../../../core/src/ledger';
import { summary } from '../../../core/src/rules';
import { money, cls } from '../format';
export function Overview() {
  const b = book.value, t = today(), m = t.slice(0, 7);
  const total = totalInBase(b, t), s = summary(b, { from: m + '-01', to: m + '-31' });
  return (<>
    <h2>Overview</h2>
    {b.settings.sample && <div class="card" style="display:flex;gap:12px;align-items:center;flex-wrap:wrap"><div style="flex:1;min-width:200px">You are looking at sample data, so you can try everything. When you are ready, start with your own.</div><button class="btn pri" onClick={async () => (await ask('Remove the sample accounts and transactions and start empty?', 'Start empty')) && clearSample()}>Start with my own data</button></div>}
    <div class="card"><div class="mut">Net worth</div><div class={`big num ${cls(total)}`}>{money(b, total, b.settings.base)}</div></div>
    <div class="grid">
      <div class="card"><div class="mut">Income this month</div><div class="big pos num">{money(b, s.income, b.settings.base)}</div></div>
      <div class="card"><div class="mut">Spent this month</div><div class="big neg num">{money(b, s.expense, b.settings.base)}</div></div>
      <div class="card"><div class="mut">Net this month</div><div class={`big num ${cls(s.net)}`}>{money(b, s.net, b.settings.base)}</div></div>
    </div>
    <h2 style="margin-top:18px">Accounts</h2>
    <div class="grid">{b.accounts.map(a => { const v = balance(b, a.id, t); return (
      <div class="card" key={a.id}><div>{a.name}</div><div class="mut">{a.kind}</div><div class={`big num ${cls(v)}`}>{money(b, v, a.cur)}</div></div>); })}</div>
  </>);
}
