import type { Book, Category, Party, Tx, Recurring, RepeatRule } from './types.js';

/** Lossless two-way conversion between the prototype's document (decimals, text ids) and the Book (whole minor units). */
const dec = (n: number) => String(Number(Number(n).toFixed(12)));
const minor = (x: unknown, d: number) => Math.round((Number(x) || 0) * 10 ** d);
const major = (m: number, d: number) => Number((m / 10 ** d).toFixed(d));
export interface MigrationReport { book: Book; counts: Record<string, number>; warnings: string[]; }
const EVERY: Record<string, Recurring['every']> = { daily: 'day', weekly: 'week', monthly: 'month', yearly: 'year' };
const FREQ: Record<Recurring['every'], string> = { day: 'daily', week: 'weekly', month: 'monthly', year: 'yearly' };

export function fromPrototype(file: any): MigrationReport {
  const d = file && file.app === 'pocket-ledger' ? file.data : file;
  if (!d || !d.base || !Array.isArray(d.tx) || !Array.isArray(d.accounts)) throw new Error('Not a Pocket Ledger backup');
  const warnings: string[] = [];
  const decOf = new Map<string, number>(d.currencies.map((c: any) => [c.code, c.dec ?? 2]));
  const dc = (c: string) => decOf.get(c) ?? 2;
  const ui: Record<string, string | number | boolean> = {};
  for (const k of ['theme', 'palette', 'dateFmt', 'startPage', 'showInOut', 'ovOrder', 'ovHide', 'ovCols', 'catPicker', 'lastAcc', 'sbOrder', 'sbHide', 'recPaused', 'lastBackup', 'v', 'nextId', 'tipOnLaunch', 'tipPos', 'tipShow', 'feeCat', 'feeSub', 'loanGivenType', 'loanBorrowedType']) if (d[k] != null) ui[k] = d[k];
  if (Array.isArray(d.accountTypes)) ui.accountTypes = JSON.stringify(d.accountTypes);
  if (Array.isArray(d.tipOrder)) ui.tipOrder = JSON.stringify(d.tipOrder);
  if (d.budgetMode && typeof d.budgetMode === 'object' && !Array.isArray(d.budgetMode)) ui.budgetMode = JSON.stringify(d.budgetMode);
  if (d.lastCat && typeof d.lastCat === 'object' && !Array.isArray(d.lastCat)) ui.lastCat = JSON.stringify(d.lastCat);
  if (Array.isArray(d.goals)) ui.goals = JSON.stringify(d.goals);
  if (Array.isArray(d.goalTypes)) ui.goalTypes = JSON.stringify(d.goalTypes);
  const book: Book = {
    settings: { base: d.base, countFuture: d.countFuture !== false, ...(d.sample ? { sample: true } : {}), ui },
    currencies: d.currencies.map((c: any) => ({ code: c.code, ...(c.name ? { name: c.name } : {}), decimals: dc(c.code), rate: dec(Number(c.rate) || 1) })),
    accounts: [], categories: [], parties: [], projects: [], tx: [], recurring: [], budgets: [],
  };
  const accId = new Map<string, number>(), catId = new Map<string, number>(), prjId = new Map<string, number>();
  d.accounts.forEach((a: any, i: number) => { accId.set(String(a.id), i + 1); book.accounts.push({ id: i + 1, name: a.name, kind: a.kind, cur: a.cur, opening: minor(a.open, dc(a.cur)), ref: String(a.id), ...(typeof a.notes === 'string' && a.notes.trim() ? { notes: a.notes.trim() } : {}), ...(a.inTotals === false ? { inTotals: false } : {}) }); });
  let cn = 0;
  for (const c of [...d.cats, ...(d.transferCats ?? [])]) {
    const type = c.type === 'inc' ? 'inc' : c.type === 'xfer' ? 'xfer' : 'exp';
    const top: Category = { id: ++cn, name: c.name, type, parent: null, ...(c.icon ? { icon: c.icon } : {}), ...(c.color ? { color: c.color } : {}), ref: String(c.id) };
    book.categories.push(top); catId.set(String(c.id), top.id);
    for (const s of c.subs ?? []) { const sub: Category = { id: ++cn, name: s.name, type, parent: top.id, ...(s.icon ? { icon: s.icon } : {}), ref: String(s.id) }; book.categories.push(sub); catId.set(String(s.id), sub.id); }
  }
  (d.projects ?? []).forEach((p: any, i: number) => { prjId.set(String(p.id), i + 1); book.projects.push({ id: i + 1, name: p.name, ref: String(p.id) }); });
  const partyKey = new Map<string, number>();
  const addParty = (name: string, kind: Party['kind'], ref?: string) => {
    const k = kind + '|' + name;
    if (!partyKey.has(k)) { partyKey.set(k, book.parties.length + 1); book.parties.push({ id: book.parties.length + 1, name, kind, ...(ref != null ? { ref } : {}) }); }
    return partyKey.get(k)!;
  };
  for (const p of d.payees ?? []) addParty(p.name, 'payee', String(p.id));
  for (const p of d.payers ?? []) addParty(p.name, 'payer', String(p.id));
  const partyOf = (name: string, type: string) => (name ? addParty(name, type === 'inc' ? 'payer' : type === 'exp' ? 'payee' : 'counterparty') : undefined);
  for (const t of d.tx) {
    const acc = accId.get(String(t.acc));
    if (acc == null) { warnings.push(`Transaction ${t.id}: unknown account, skipped`); continue; }
    const from = book.accounts[acc - 1], fd = dc(from.cur);
    const tx: Tx = { id: Number(t.id), type: t.type, date: t.date, acc, amount: minor(t.amount, fd) };
    if (t.type === 'xfer') {
      const to = accId.get(String(t.to));
      if (to == null) { warnings.push(`Transfer ${t.id}: unknown destination, skipped`); continue; }
      tx.to = to; const td = dc(book.accounts[to - 1].cur);
      tx.amountTo = t.amountTo == null ? (td === fd ? tx.amount : minor(t.amount, td)) : minor(t.amountTo, td);
    }
    const cid = catId.get(String(t.sub || t.cat));
    if (cid != null) tx.cat = cid; else if (t.cat) warnings.push(`Transaction ${t.id}: category "${t.cat}" not found`);
    const pid = partyOf(t.payee, t.type); if (pid) tx.party = pid;
    if (t.project && prjId.has(String(t.project))) tx.project = prjId.get(String(t.project));
    if (t.note) tx.note = t.note;
    if (t.feeOf != null && t.feeOf !== '') tx.feeOf = Number(t.feeOf);
    if (t.rate != null) tx.rate = dec(Number(t.rate));
    if (t.txnCur && t.txnAmount != null && decOf.has(t.txnCur)) { tx.txnCur = t.txnCur; tx.txnAmount = minor(t.txnAmount, dc(t.txnCur)); if (t.txnRate != null) tx.txnRate = dec(Number(t.txnRate)); }
    if (tx.amount <= 0) { warnings.push(`Transaction ${t.id}: zero amount, skipped`); continue; }
    book.tx.push(tx);
  }
  (d.recurring ?? []).forEach((r: any, i: number) => {
    const a = accId.get(String(r.tpl?.acc)); if (a == null) { warnings.push(`Recurring ${r.id}: unknown account, skipped`); return; }
    const ad = dc(book.accounts[a - 1].cur), tpl = r.tpl;
    const rec: Recurring = { id: i + 1, ref: String(r.id), day: r.day, every: EVERY[r.freq] ?? 'month', next: r.next, tx: { type: tpl.type, acc: a, amount: minor(tpl.amount, ad) } };
    const cid = catId.get(String(tpl.sub || tpl.cat)); if (cid != null) rec.tx.cat = cid;
    const pid = partyOf(tpl.payee, tpl.type); if (pid) rec.tx.party = pid;
    if (tpl.project && prjId.has(String(tpl.project))) rec.tx.project = prjId.get(String(tpl.project));
    if (tpl.note) rec.tx.note = tpl.note;
    if (tpl.type === 'xfer') { const to = accId.get(String(tpl.to)); if (to != null) { rec.tx.to = to; rec.tx.amountTo = minor(tpl.amountTo ?? tpl.amount, dc(book.accounts[to - 1].cur)); } }
    if (r.end) rec.end = r.end;
    const rule: RepeatRule = {};
    if (r.n > 1) rule.n = Number(r.n);
    if (Array.isArray(r.wd) && r.wd.length) rule.weekdays = r.wd.map(Number);
    if (r.mm === 'last' || r.mm === 'nth') { rule.monthly = r.mm; if (r.mm === 'nth') { rule.nth = Number(r.nth); rule.weekday = Number(r.wdn); } }
    if (r.cnt > 0) { rule.count = Number(r.cnt); rule.done = Number(r.done ?? 0); }
    if (Object.keys(rule).length) rec.rule = rule;
    book.recurring.push(rec);
  });
  const bd = dc(d.base);
  for (const [k, v] of Object.entries(d.budgets ?? {})) { const c = catId.get(k); if (c != null) book.budgets.push({ cat: c, amount: minor(v, bd) }); }
  const counts = { currencies: book.currencies.length, accounts: book.accounts.length, categories: book.categories.length, parties: book.parties.length, projects: book.projects.length, transactions: book.tx.length, recurring: book.recurring.length };
  return { book, counts, warnings };
}

/** Rebuilds the prototype document the screens use. Amounts become decimals again. */
export function toPrototype(b: Book): any {
  const dc = (c: string) => b.currencies.find(x => x.code === c)?.decimals ?? 2;
  const accRef = new Map(b.accounts.map(a => [a.id, a])), cat = new Map(b.categories.map(c => [c.id, c])), prj = new Map(b.projects.map(p => [p.id, p])), party = new Map(b.parties.map(p => [p.id, p]));
  const ref = (o: { id: number; ref?: string }) => o.ref ?? String(o.id);
  const catOut = (c: Category) => ({ id: ref(c), name: c.name, type: c.type, ...(c.color ? { color: c.color } : {}), ...(c.icon ? { icon: c.icon } : {}), subs: b.categories.filter(s => s.parent === c.id).map(s => ({ id: ref(s), name: s.name, ...(s.icon ? { icon: s.icon } : {}) })) });
  const tops = b.categories.filter(c => c.parent == null);
  const out: any = {
    ...(b.settings.ui ?? {}), ...(typeof b.settings.ui?.accountTypes === 'string' ? { accountTypes: JSON.parse(b.settings.ui.accountTypes as string) } : {}), ...(typeof b.settings.ui?.budgetMode === 'string' ? { budgetMode: JSON.parse(b.settings.ui.budgetMode as string) } : {}), ...(typeof b.settings.ui?.lastCat === 'string' ? { lastCat: JSON.parse(b.settings.ui.lastCat as string) } : {}), ...(typeof b.settings.ui?.goals === 'string' ? { goals: JSON.parse(b.settings.ui.goals as string) } : {}), ...(typeof b.settings.ui?.goalTypes === 'string' ? { goalTypes: JSON.parse(b.settings.ui.goalTypes as string) } : {}), ...(typeof b.settings.ui?.tipOrder === 'string' ? { tipOrder: JSON.parse(b.settings.ui.tipOrder as string) } : {}), base: b.settings.base, countFuture: b.settings.countFuture, ...(b.settings.sample ? { sample: true } : {}),
    currencies: b.currencies.map(c => ({ code: c.code, ...(c.name ? { name: c.name } : {}), rate: Number(c.rate), dec: c.decimals })),
    accounts: b.accounts.map(a => ({ id: ref(a), name: a.name, kind: a.kind, cur: a.cur, open: major(a.opening, dc(a.cur)), ...(a.notes ? { notes: a.notes } : {}), ...(a.inTotals === false ? { inTotals: false } : {}) })),
    cats: tops.filter(c => c.type !== 'xfer').map(catOut), transferCats: tops.filter(c => c.type === 'xfer').map(catOut),
    projects: b.projects.map(p => ({ id: ref(p), name: p.name })),
    payees: b.parties.filter(p => p.kind === 'payee' && p.ref != null).map(p => ({ id: p.ref, name: p.name })),
    payers: b.parties.filter(p => p.kind === 'payer' && p.ref != null).map(p => ({ id: p.ref, name: p.name })),
    budgets: Object.fromEntries(b.budgets.map(x => [ref(cat.get(x.cat)!), major(x.amount, dc(b.settings.base))])),
  };
  out.tx = b.tx.map(t => {
    const a = accRef.get(t.acc)!, c = t.cat != null ? cat.get(t.cat) : undefined, top = c?.parent != null ? cat.get(c.parent) : c;
    const o: any = { id: t.id, date: t.date, type: t.type, amount: major(t.amount, dc(a.cur)), cat: top ? ref(top) : '', sub: c?.parent != null ? ref(c) : '', acc: ref(a), to: '', amountTo: null, rate: t.rate != null ? Number(t.rate) : 1, project: t.project != null ? ref(prj.get(t.project)!) : '', payee: t.party != null ? party.get(t.party)!.name : '', note: t.note ?? '' };
    if (t.type === 'xfer') { const to = accRef.get(t.to!)!; o.to = ref(to); o.amountTo = major(t.amountTo ?? t.amount, dc(to.cur)); }
    if (t.feeOf != null) o.feeOf = t.feeOf;
    if (t.txnCur) { o.txnCur = t.txnCur; o.txnAmount = major(t.txnAmount ?? 0, dc(t.txnCur)); if (t.txnRate != null) o.txnRate = Number(t.txnRate); }
    return o;
  });
  const EV = FREQ;
  out.recurring = b.recurring.map(r => { const a = accRef.get(r.tx.acc)!, c = r.tx.cat != null ? cat.get(r.tx.cat) : undefined, top = c?.parent != null ? cat.get(c.parent) : c;
    const tpl: any = { type: r.tx.type, amount: major(r.tx.amount, dc(a.cur)), cat: top ? ref(top) : '', sub: c?.parent != null ? ref(c) : '', acc: ref(a), note: r.tx.note ?? '', payee: r.tx.party != null ? party.get(r.tx.party)!.name : '', project: r.tx.project != null ? ref(prj.get(r.tx.project)!) : '' };
    if (r.tx.type === 'xfer' && r.tx.to != null) { const to = accRef.get(r.tx.to)!; tpl.to = ref(to); tpl.amountTo = major(r.tx.amountTo ?? r.tx.amount, dc(to.cur)); }
    return { id: r.ref ?? 'r' + r.id, freq: EV[r.every], day: r.day ?? Number(r.next.slice(8, 10)), next: r.next, tpl, ...(r.end ? { end: r.end } : {}), ...(r.rule?.n ? { n: r.rule.n } : {}), ...(r.rule?.weekdays ? { wd: r.rule.weekdays } : {}), ...(r.rule?.monthly ? { mm: r.rule.monthly, ...(r.rule.monthly === 'nth' ? { nth: r.rule.nth, wdn: r.rule.weekday } : {}) } : {}), ...(r.rule?.count ? { cnt: r.rule.count, done: r.rule.done ?? 0 } : {}) }; });
  return out;
}
