import { useState, useEffect } from 'preact/hooks';
import { Overview } from './screens/Overview';
import { Transactions } from './screens/Transactions';
import { Reports } from './screens/Reports';
import { Settings } from './screens/Settings';
import { Accounts } from './screens/Accounts';
import { Lists } from './screens/Lists';
import { Recurring } from './screens/Recurring';
import { mutate, today, dialog } from './store';
import { catchUp } from '../../core/src/rules';
const TABS = ['Overview', 'Transactions', 'Accounts', 'Reports', 'Lists', 'Recurring', 'Settings'] as const;
export function App() {
  const [tab, setTab] = useState<(typeof TABS)[number]>('Overview');
  useEffect(() => { const run = () => { mutate(b => { catchUp(b, today()); }); }; run(); const v = () => document.visibilityState === 'visible' && run(); document.addEventListener('visibilitychange', v); const i = setInterval(run, 3600000); return () => { document.removeEventListener('visibilitychange', v); clearInterval(i); }; }, []);
  return (
    <div class="shell">
      <nav aria-label="Main"><h1>Pocket Ledger</h1>
        {TABS.map(t => <button key={t} class={t === tab ? 'on' : ''} aria-current={t === tab ? 'page' : undefined} onClick={() => setTab(t)}>{t}</button>)}
      </nav>
      <main>
        {tab === 'Overview' && <Overview />}
        {tab === 'Transactions' && <Transactions />}
        {tab === 'Reports' && <Reports />}
        {tab === 'Accounts' && <Accounts />}
        {tab === 'Lists' && <Lists />}
        {tab === 'Recurring' && <Recurring />}
        {tab === 'Settings' && <Settings />}
      </main>
      {dialog.value && <div class="sheet" role="alertdialog" aria-modal="true"><div class="card" style="width:min(380px,100%);display:grid;gap:12px"><div>{dialog.value.msg}</div><div class="seg"><button class="btn" autofocus onClick={() => { dialog.value!.resolve(false); dialog.value = null; }}>Cancel</button><button class="btn pri" onClick={() => { dialog.value!.resolve(true); dialog.value = null; }}>{dialog.value.ok}</button></div></div></div>}
    </div>
  );
}
