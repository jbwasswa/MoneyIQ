import { book, mutate, ask, saveFile, clearSample } from '../store';
import { cur } from '../../../core/src/ledger';
import { useState } from 'preact/hooks';
import { readBackup } from '../../../core/src/backup';
export function Settings() {
  const b = book.value, [msg, setMsg] = useState('');
  const restore = async (e: Event) => { const f = (e.target as HTMLInputElement).files?.[0]; if (!f) return; try { const r = readBackup(await f.text()); if (!(await ask(`Replace everything with this backup (${r.book.tx.length} transactions)?`, 'Replace'))) return; mutate(x => { Object.assign(x, r.book); }); setMsg('Restored. ' + r.note); } catch (x) { setMsg((x as Error).message); } (e.target as HTMLInputElement).value = ''; };
  const backup = () => { const blob = new Blob([JSON.stringify({ app: 'pocket-ledger', schema: 2, exported: new Date().toISOString(), data: b }, null, 1)], { type: 'application/json' }); const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = `pocket-ledger-${new Date().toISOString().slice(0, 10)}.json`; a.click(); };
  return (<>
    <h2>Settings</h2>
    <div class="card"><label><input type="checkbox" checked={b.settings.countFuture} onChange={e => mutate(x => { x.settings.countFuture = (e.target as HTMLInputElement).checked; })} /> Future-dated transactions affect the current balance</label></div>
    <div class="card"><h3 style="margin-top:0">Currencies</h3>{b.currencies.map(c => <div class="row" key={c.code}><div>{c.code} <span class="mut">{c.decimals} decimals</span></div>
      <div class="mut">{c.code === b.settings.base ? 'Base currency' : `1 ${c.code} =`}</div>
      {c.code !== b.settings.base && <input aria-label={`Rate for ${c.code}`} style="width:110px" value={c.rate} onChange={e => { const v = (e.target as HTMLInputElement).value; if (/^\d+(\.\d+)?$/.test(v) && +v > 0) mutate(x => { cur(x, c.code).rate = v; }); }} />}</div>)}</div>
    <div class="card" style="display:grid;gap:8px"><div><button class="btn" onClick={backup}>Save backup</button></div><label class="mut">Restore from a backup file <input type="file" accept=".json,application/json" aria-label="Restore backup" onChange={restore} /></label>{msg && <div role="status">{msg}</div>}</div>
  </>);
}
