"use strict";
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// src/native.ts
var native_exports = {};
__export(native_exports, {
  fromPrototype: () => fromPrototype,
  initDb: () => initDb,
  loadBook: () => loadBook,
  saveBook: () => saveBook,
  toPrototype: () => toPrototype,
  validateBook: () => validateBook
});
module.exports = __toCommonJS(native_exports);

// src/schema.ts
var SCHEMA_VERSION = 7;
var SCHEMA_SQL = `
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS currency (code TEXT PRIMARY KEY, name TEXT, decimals INTEGER NOT NULL CHECK (decimals BETWEEN 0 AND 8), rate TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS account (id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, cur TEXT NOT NULL REFERENCES currency(code) ON DELETE RESTRICT, opening INTEGER NOT NULL, ref TEXT, notes TEXT, no_totals INTEGER);
CREATE TABLE IF NOT EXISTS category (id INTEGER PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL CHECK (type IN ('inc','exp','xfer')), parent INTEGER REFERENCES category(id) ON DELETE RESTRICT, icon TEXT, color TEXT, ref TEXT);
CREATE TABLE IF NOT EXISTS party (id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('payee','payer','counterparty')), ref TEXT, UNIQUE(name, kind));
CREATE TABLE IF NOT EXISTS project (id INTEGER PRIMARY KEY, name TEXT NOT NULL, ref TEXT);
CREATE TABLE IF NOT EXISTS budget (cat INTEGER PRIMARY KEY REFERENCES category(id) ON DELETE RESTRICT, amount INTEGER NOT NULL CHECK (amount >= 0));
CREATE TABLE IF NOT EXISTS tx (
  id INTEGER PRIMARY KEY, type TEXT NOT NULL CHECK (type IN ('inc','exp','xfer')), date TEXT NOT NULL CHECK (date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
  acc INTEGER NOT NULL REFERENCES account(id) ON DELETE RESTRICT, amount INTEGER NOT NULL CHECK (amount > 0),
  to_acc INTEGER REFERENCES account(id) ON DELETE RESTRICT, amount_to INTEGER,
  cat INTEGER REFERENCES category(id) ON DELETE RESTRICT, party INTEGER REFERENCES party(id) ON DELETE RESTRICT,
  project INTEGER REFERENCES project(id) ON DELETE RESTRICT, note TEXT, rate TEXT,
  txn_cur TEXT REFERENCES currency(code) ON DELETE RESTRICT, txn_amount INTEGER, txn_rate TEXT, fee_of INTEGER,
  CHECK (type <> 'xfer' OR (to_acc IS NOT NULL AND to_acc <> acc)));
CREATE INDEX IF NOT EXISTS tx_date ON tx(date, id);
CREATE INDEX IF NOT EXISTS tx_acc ON tx(acc); CREATE INDEX IF NOT EXISTS tx_to ON tx(to_acc);
CREATE TABLE IF NOT EXISTS recurring (id INTEGER PRIMARY KEY, every TEXT NOT NULL, next TEXT NOT NULL, end TEXT, body TEXT NOT NULL, ref TEXT, day INTEGER, rule TEXT);
`;

// src/db.ts
var TABLES = ["tx", "recurring", "budget", "category", "party", "project", "account", "currency"];
function initDb(db) {
  let old = 0;
  try {
    old = Number(db.prepare("SELECT value FROM meta WHERE key='schema'").all()[0]?.value ?? 0);
  } catch {
    old = 0;
  }
  if (old > SCHEMA_VERSION) throw new Error(`This data file is from a newer version (schema ${old}). Update the app.`);
  let keep = null;
  if (old > 0 && old < SCHEMA_VERSION) {
    keep = loadBook(db);
    db.exec("PRAGMA foreign_keys = OFF");
    for (const t of TABLES) db.exec(`DROP TABLE IF EXISTS ${t}`);
  }
  db.exec(SCHEMA_SQL);
  if (keep) saveBook(db, keep);
  db.prepare("INSERT OR REPLACE INTO meta(key,value) VALUES('schema',?)").run(String(SCHEMA_VERSION));
}
var ins = (db, sql, rows) => {
  const s = db.prepare(sql);
  for (const r of rows) s.run(...r);
};
function saveBook(db, b) {
  db.exec("BEGIN");
  try {
    for (const t of TABLES) {
      if (t === "category") db.exec("DELETE FROM category WHERE parent IS NOT NULL");
      db.exec(`DELETE FROM ${t}`);
    }
    ins(db, "INSERT INTO currency VALUES(?,?,?,?)", b.currencies.map((c) => [c.code, c.name ?? null, c.decimals, c.rate]));
    ins(db, "INSERT INTO account VALUES(?,?,?,?,?,?,?,?)", b.accounts.map((a) => [a.id, a.name, a.kind, a.cur, a.opening, a.ref ?? null, a.notes ?? null, a.inTotals === false ? 1 : null]));
    const cat = (c) => [c.id, c.name, c.type, c.parent ?? null, c.icon ?? null, c.color ?? null, c.ref ?? null];
    ins(db, "INSERT INTO category VALUES(?,?,?,?,?,?,?)", b.categories.filter((c) => c.parent == null).map(cat));
    ins(db, "INSERT INTO category VALUES(?,?,?,?,?,?,?)", b.categories.filter((c) => c.parent != null).map(cat));
    ins(db, "INSERT INTO party VALUES(?,?,?,?)", b.parties.map((p) => [p.id, p.name, p.kind, p.ref ?? null]));
    ins(db, "INSERT INTO project VALUES(?,?,?)", b.projects.map((p) => [p.id, p.name, p.ref ?? null]));
    ins(db, "INSERT INTO budget VALUES(?,?)", b.budgets.map((x) => [x.cat, x.amount]));
    ins(db, "INSERT INTO tx VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", b.tx.map((t) => [t.id, t.type, t.date, t.acc, t.amount, t.to ?? null, t.amountTo ?? null, t.cat ?? null, t.party ?? null, t.project ?? null, t.note ?? null, t.rate ?? null, t.txnCur ?? null, t.txnAmount ?? null, t.txnRate ?? null, t.feeOf ?? null]));
    ins(db, "INSERT INTO recurring VALUES(?,?,?,?,?,?,?,?)", b.recurring.map((r) => [r.id, r.every, r.next, r.end ?? null, JSON.stringify(r.tx), r.ref ?? null, r.day ?? null, r.rule ? JSON.stringify(r.rule) : null]));
    db.prepare("INSERT OR REPLACE INTO meta(key,value) VALUES('base',?)").run(b.settings.base);
    db.prepare("INSERT OR REPLACE INTO meta(key,value) VALUES('countFuture',?)").run(b.settings.countFuture ? "1" : "0");
    db.prepare("INSERT OR REPLACE INTO meta(key,value) VALUES('extras',?)").run(JSON.stringify({ sample: !!b.settings.sample, ui: b.settings.ui ?? {} }));
    db.exec("COMMIT");
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
}
function loadBook(db) {
  const meta = Object.fromEntries(db.prepare("SELECT key,value FROM meta").all().map((r) => [r.key, r.value]));
  const opt = (v) => v === null ? void 0 : v;
  const rows = (sql) => db.prepare(sql).all();
  const clean = (r, keep = []) => Object.fromEntries(Object.entries(r).filter(([k, v]) => v !== null || keep.includes(k)));
  const extras = JSON.parse(meta.extras ?? "{}");
  return {
    settings: { base: meta.base, countFuture: meta.countFuture !== "0", ...extras.sample ? { sample: true } : {}, ui: extras.ui ?? {} },
    currencies: rows("SELECT code,name,decimals,rate FROM currency ORDER BY rowid").map((r) => clean(r)),
    accounts: rows("SELECT * FROM account ORDER BY id").map((r) => {
      const a = clean(r);
      if (a.no_totals === 1) a.inTotals = false;
      delete a.no_totals;
      return a;
    }),
    categories: rows("SELECT id,name,type,parent,icon,color,ref FROM category ORDER BY id").map((r) => clean(r, ["parent"])),
    parties: rows("SELECT id,name,kind,ref FROM party ORDER BY id").map((r) => clean(r)),
    projects: rows("SELECT id,name,ref FROM project ORDER BY id").map((r) => clean(r)),
    budgets: rows("SELECT cat,amount FROM budget ORDER BY cat"),
    tx: db.prepare("SELECT * FROM tx ORDER BY id").all().map((r) => {
      const t = { id: r.id, type: r.type, date: r.date, acc: r.acc, amount: r.amount };
      for (const [k, c] of [["to", "to_acc"], ["amountTo", "amount_to"], ["cat", "cat"], ["party", "party"], ["project", "project"], ["note", "note"], ["rate", "rate"], ["txnCur", "txn_cur"], ["txnAmount", "txn_amount"], ["txnRate", "txn_rate"], ["feeOf", "fee_of"]]) if (opt(r[c]) !== void 0) t[k] = r[c];
      return t;
    }),
    recurring: db.prepare("SELECT * FROM recurring ORDER BY id").all().map((r) => ({ id: r.id, ...r.ref ? { ref: r.ref } : {}, ...r.day != null ? { day: r.day } : {}, every: r.every, next: r.next, ...r.end ? { end: r.end } : {}, ...r.rule ? { rule: JSON.parse(r.rule) } : {}, tx: JSON.parse(r.body) }))
  };
}

// src/convert.ts
var dec = (n) => String(Number(Number(n).toFixed(12)));
var minor = (x, d) => Math.round((Number(x) || 0) * 10 ** d);
var major = (m, d) => Number((m / 10 ** d).toFixed(d));
var EVERY = { daily: "day", weekly: "week", monthly: "month", yearly: "year" };
var FREQ = { day: "daily", week: "weekly", month: "monthly", year: "yearly" };
function fromPrototype(file) {
  const d = file && file.app === "pocket-ledger" ? file.data : file;
  if (!d || !d.base || !Array.isArray(d.tx) || !Array.isArray(d.accounts)) throw new Error("Not a Pocket Ledger backup");
  const warnings = [];
  const decOf = new Map(d.currencies.map((c) => [c.code, c.dec ?? 2]));
  const dc = (c) => decOf.get(c) ?? 2;
  const ui = {};
  for (const k of ["theme", "palette", "dateFmt", "startPage", "showInOut", "ovOrder", "ovHide", "ovCols", "catPicker", "lastAcc", "sbOrder", "sbHide", "recPaused", "lastBackup", "v", "nextId", "tipOnLaunch", "tipPos", "tipShow", "feeCat", "feeSub", "loanGivenType", "loanBorrowedType"]) if (d[k] != null) ui[k] = d[k];
  if (Array.isArray(d.accountTypes)) ui.accountTypes = JSON.stringify(d.accountTypes);
  if (Array.isArray(d.tipOrder)) ui.tipOrder = JSON.stringify(d.tipOrder);
  if (d.budgetMode && typeof d.budgetMode === "object" && !Array.isArray(d.budgetMode)) ui.budgetMode = JSON.stringify(d.budgetMode);
  if (d.lastCat && typeof d.lastCat === "object" && !Array.isArray(d.lastCat)) ui.lastCat = JSON.stringify(d.lastCat);
  if (Array.isArray(d.goals)) ui.goals = JSON.stringify(d.goals);
  if (Array.isArray(d.goalTypes)) ui.goalTypes = JSON.stringify(d.goalTypes);
  const book = {
    settings: { base: d.base, countFuture: d.countFuture !== false, ...d.sample ? { sample: true } : {}, ui },
    currencies: d.currencies.map((c) => ({ code: c.code, ...c.name ? { name: c.name } : {}, decimals: dc(c.code), rate: dec(Number(c.rate) || 1) })),
    accounts: [],
    categories: [],
    parties: [],
    projects: [],
    tx: [],
    recurring: [],
    budgets: []
  };
  const accId = /* @__PURE__ */ new Map(), catId = /* @__PURE__ */ new Map(), prjId = /* @__PURE__ */ new Map();
  d.accounts.forEach((a, i) => {
    accId.set(String(a.id), i + 1);
    book.accounts.push({ id: i + 1, name: a.name, kind: a.kind, cur: a.cur, opening: minor(a.open, dc(a.cur)), ref: String(a.id), ...typeof a.notes === "string" && a.notes.trim() ? { notes: a.notes.trim() } : {}, ...a.inTotals === false ? { inTotals: false } : {} });
  });
  let cn = 0;
  for (const c of [...d.cats, ...d.transferCats ?? []]) {
    const type = c.type === "inc" ? "inc" : c.type === "xfer" ? "xfer" : "exp";
    const top = { id: ++cn, name: c.name, type, parent: null, ...c.icon ? { icon: c.icon } : {}, ...c.color ? { color: c.color } : {}, ref: String(c.id) };
    book.categories.push(top);
    catId.set(String(c.id), top.id);
    for (const s of c.subs ?? []) {
      const sub = { id: ++cn, name: s.name, type, parent: top.id, ...s.icon ? { icon: s.icon } : {}, ref: String(s.id) };
      book.categories.push(sub);
      catId.set(String(s.id), sub.id);
    }
  }
  (d.projects ?? []).forEach((p, i) => {
    prjId.set(String(p.id), i + 1);
    book.projects.push({ id: i + 1, name: p.name, ref: String(p.id) });
  });
  const partyKey = /* @__PURE__ */ new Map();
  const addParty = (name, kind, ref) => {
    const k = kind + "|" + name;
    if (!partyKey.has(k)) {
      partyKey.set(k, book.parties.length + 1);
      book.parties.push({ id: book.parties.length + 1, name, kind, ...ref != null ? { ref } : {} });
    }
    return partyKey.get(k);
  };
  for (const p of d.payees ?? []) addParty(p.name, "payee", String(p.id));
  for (const p of d.payers ?? []) addParty(p.name, "payer", String(p.id));
  const partyOf = (name, type) => name ? addParty(name, type === "inc" ? "payer" : type === "exp" ? "payee" : "counterparty") : void 0;
  for (const t of d.tx) {
    const acc = accId.get(String(t.acc));
    if (acc == null) {
      warnings.push(`Transaction ${t.id}: unknown account, skipped`);
      continue;
    }
    const from = book.accounts[acc - 1], fd = dc(from.cur);
    const tx = { id: Number(t.id), type: t.type, date: t.date, acc, amount: minor(t.amount, fd) };
    if (t.type === "xfer") {
      const to = accId.get(String(t.to));
      if (to == null) {
        warnings.push(`Transfer ${t.id}: unknown destination, skipped`);
        continue;
      }
      tx.to = to;
      const td = dc(book.accounts[to - 1].cur);
      tx.amountTo = t.amountTo == null ? td === fd ? tx.amount : minor(t.amount, td) : minor(t.amountTo, td);
    }
    const cid = catId.get(String(t.sub || t.cat));
    if (cid != null) tx.cat = cid;
    else if (t.cat) warnings.push(`Transaction ${t.id}: category "${t.cat}" not found`);
    const pid = partyOf(t.payee, t.type);
    if (pid) tx.party = pid;
    if (t.project && prjId.has(String(t.project))) tx.project = prjId.get(String(t.project));
    if (t.note) tx.note = t.note;
    if (t.feeOf != null && t.feeOf !== "") tx.feeOf = Number(t.feeOf);
    if (t.rate != null) tx.rate = dec(Number(t.rate));
    if (t.txnCur && t.txnAmount != null && decOf.has(t.txnCur)) {
      tx.txnCur = t.txnCur;
      tx.txnAmount = minor(t.txnAmount, dc(t.txnCur));
      if (t.txnRate != null) tx.txnRate = dec(Number(t.txnRate));
    }
    if (tx.amount <= 0) {
      warnings.push(`Transaction ${t.id}: zero amount, skipped`);
      continue;
    }
    book.tx.push(tx);
  }
  (d.recurring ?? []).forEach((r, i) => {
    const a = accId.get(String(r.tpl?.acc));
    if (a == null) {
      warnings.push(`Recurring ${r.id}: unknown account, skipped`);
      return;
    }
    const ad = dc(book.accounts[a - 1].cur), tpl = r.tpl;
    const rec = { id: i + 1, ref: String(r.id), day: r.day, every: EVERY[r.freq] ?? "month", next: r.next, tx: { type: tpl.type, acc: a, amount: minor(tpl.amount, ad) } };
    const cid = catId.get(String(tpl.sub || tpl.cat));
    if (cid != null) rec.tx.cat = cid;
    const pid = partyOf(tpl.payee, tpl.type);
    if (pid) rec.tx.party = pid;
    if (tpl.project && prjId.has(String(tpl.project))) rec.tx.project = prjId.get(String(tpl.project));
    if (tpl.note) rec.tx.note = tpl.note;
    if (tpl.type === "xfer") {
      const to = accId.get(String(tpl.to));
      if (to != null) {
        rec.tx.to = to;
        rec.tx.amountTo = minor(tpl.amountTo ?? tpl.amount, dc(book.accounts[to - 1].cur));
      }
    }
    if (r.end) rec.end = r.end;
    const rule = {};
    if (r.n > 1) rule.n = Number(r.n);
    if (Array.isArray(r.wd) && r.wd.length) rule.weekdays = r.wd.map(Number);
    if (r.mm === "last" || r.mm === "nth") {
      rule.monthly = r.mm;
      if (r.mm === "nth") {
        rule.nth = Number(r.nth);
        rule.weekday = Number(r.wdn);
      }
    }
    if (r.cnt > 0) {
      rule.count = Number(r.cnt);
      rule.done = Number(r.done ?? 0);
    }
    if (Object.keys(rule).length) rec.rule = rule;
    book.recurring.push(rec);
  });
  const bd = dc(d.base);
  for (const [k, v] of Object.entries(d.budgets ?? {})) {
    const c = catId.get(k);
    if (c != null) book.budgets.push({ cat: c, amount: minor(v, bd) });
  }
  const counts = { currencies: book.currencies.length, accounts: book.accounts.length, categories: book.categories.length, parties: book.parties.length, projects: book.projects.length, transactions: book.tx.length, recurring: book.recurring.length };
  return { book, counts, warnings };
}
function toPrototype(b) {
  const dc = (c) => b.currencies.find((x) => x.code === c)?.decimals ?? 2;
  const accRef = new Map(b.accounts.map((a) => [a.id, a])), cat = new Map(b.categories.map((c) => [c.id, c])), prj = new Map(b.projects.map((p) => [p.id, p])), party = new Map(b.parties.map((p) => [p.id, p]));
  const ref = (o) => o.ref ?? String(o.id);
  const catOut = (c) => ({ id: ref(c), name: c.name, type: c.type, ...c.color ? { color: c.color } : {}, ...c.icon ? { icon: c.icon } : {}, subs: b.categories.filter((s) => s.parent === c.id).map((s) => ({ id: ref(s), name: s.name, ...s.icon ? { icon: s.icon } : {} })) });
  const tops = b.categories.filter((c) => c.parent == null);
  const out = {
    ...b.settings.ui ?? {},
    ...typeof b.settings.ui?.accountTypes === "string" ? { accountTypes: JSON.parse(b.settings.ui.accountTypes) } : {},
    ...typeof b.settings.ui?.budgetMode === "string" ? { budgetMode: JSON.parse(b.settings.ui.budgetMode) } : {},
    ...typeof b.settings.ui?.lastCat === "string" ? { lastCat: JSON.parse(b.settings.ui.lastCat) } : {},
    ...typeof b.settings.ui?.goals === "string" ? { goals: JSON.parse(b.settings.ui.goals) } : {},
    ...typeof b.settings.ui?.goalTypes === "string" ? { goalTypes: JSON.parse(b.settings.ui.goalTypes) } : {},
    ...typeof b.settings.ui?.tipOrder === "string" ? { tipOrder: JSON.parse(b.settings.ui.tipOrder) } : {},
    base: b.settings.base,
    countFuture: b.settings.countFuture,
    ...b.settings.sample ? { sample: true } : {},
    currencies: b.currencies.map((c) => ({ code: c.code, ...c.name ? { name: c.name } : {}, rate: Number(c.rate), dec: c.decimals })),
    accounts: b.accounts.map((a) => ({ id: ref(a), name: a.name, kind: a.kind, cur: a.cur, open: major(a.opening, dc(a.cur)), ...a.notes ? { notes: a.notes } : {}, ...a.inTotals === false ? { inTotals: false } : {} })),
    cats: tops.filter((c) => c.type !== "xfer").map(catOut),
    transferCats: tops.filter((c) => c.type === "xfer").map(catOut),
    projects: b.projects.map((p) => ({ id: ref(p), name: p.name })),
    payees: b.parties.filter((p) => p.kind === "payee" && p.ref != null).map((p) => ({ id: p.ref, name: p.name })),
    payers: b.parties.filter((p) => p.kind === "payer" && p.ref != null).map((p) => ({ id: p.ref, name: p.name })),
    budgets: Object.fromEntries(b.budgets.map((x) => [ref(cat.get(x.cat)), major(x.amount, dc(b.settings.base))]))
  };
  out.tx = b.tx.map((t) => {
    const a = accRef.get(t.acc), c = t.cat != null ? cat.get(t.cat) : void 0, top = c?.parent != null ? cat.get(c.parent) : c;
    const o = { id: t.id, date: t.date, type: t.type, amount: major(t.amount, dc(a.cur)), cat: top ? ref(top) : "", sub: c?.parent != null ? ref(c) : "", acc: ref(a), to: "", amountTo: null, rate: t.rate != null ? Number(t.rate) : 1, project: t.project != null ? ref(prj.get(t.project)) : "", payee: t.party != null ? party.get(t.party).name : "", note: t.note ?? "" };
    if (t.type === "xfer") {
      const to = accRef.get(t.to);
      o.to = ref(to);
      o.amountTo = major(t.amountTo ?? t.amount, dc(to.cur));
    }
    if (t.feeOf != null) o.feeOf = t.feeOf;
    if (t.txnCur) {
      o.txnCur = t.txnCur;
      o.txnAmount = major(t.txnAmount ?? 0, dc(t.txnCur));
      if (t.txnRate != null) o.txnRate = Number(t.txnRate);
    }
    return o;
  });
  const EV = FREQ;
  out.recurring = b.recurring.map((r) => {
    const a = accRef.get(r.tx.acc), c = r.tx.cat != null ? cat.get(r.tx.cat) : void 0, top = c?.parent != null ? cat.get(c.parent) : c;
    const tpl = { type: r.tx.type, amount: major(r.tx.amount, dc(a.cur)), cat: top ? ref(top) : "", sub: c?.parent != null ? ref(c) : "", acc: ref(a), note: r.tx.note ?? "", payee: r.tx.party != null ? party.get(r.tx.party).name : "", project: r.tx.project != null ? ref(prj.get(r.tx.project)) : "" };
    if (r.tx.type === "xfer" && r.tx.to != null) {
      const to = accRef.get(r.tx.to);
      tpl.to = ref(to);
      tpl.amountTo = major(r.tx.amountTo ?? r.tx.amount, dc(to.cur));
    }
    return { id: r.ref ?? "r" + r.id, freq: EV[r.every], day: r.day ?? Number(r.next.slice(8, 10)), next: r.next, tpl, ...r.end ? { end: r.end } : {}, ...r.rule?.n ? { n: r.rule.n } : {}, ...r.rule?.weekdays ? { wd: r.rule.weekdays } : {}, ...r.rule?.monthly ? { mm: r.rule.monthly, ...r.rule.monthly === "nth" ? { nth: r.rule.nth, wdn: r.rule.weekday } : {} } : {}, ...r.rule?.count ? { cnt: r.rule.count, done: r.rule.done ?? 0 } : {} };
  });
  return out;
}

// src/backup.ts
function validateBook(b) {
  const p = [], ids = (a) => new Set(a.map((x) => x.id));
  const A = ids(b.accounts), C = ids(b.categories), P = ids(b.parties), J = ids(b.projects), cur = new Set(b.currencies.map((c) => c.code));
  if (!cur.has(b.settings.base)) p.push("Base currency is missing");
  for (const a of b.accounts) if (!cur.has(a.cur)) p.push(`Account ${a.name}: unknown currency ${a.cur}`);
  for (const c of b.categories) if (c.parent != null && !C.has(c.parent)) p.push(`Category ${c.name}: missing parent`);
  const seen = /* @__PURE__ */ new Set();
  for (const t of b.tx) {
    if (seen.has(t.id)) p.push(`Transaction ${t.id}: duplicate id`);
    seen.add(t.id);
    if (!A.has(t.acc)) p.push(`Transaction ${t.id}: unknown account`);
    if (t.to != null && !A.has(t.to)) p.push(`Transaction ${t.id}: unknown destination`);
    if (t.type === "xfer" && (t.to == null || t.to === t.acc)) p.push(`Transaction ${t.id}: bad transfer`);
    if (t.cat != null && !C.has(t.cat)) p.push(`Transaction ${t.id}: unknown category`);
    if (t.party != null && !P.has(t.party)) p.push(`Transaction ${t.id}: unknown payee`);
    if (t.project != null && !J.has(t.project)) p.push(`Transaction ${t.id}: unknown project`);
    if (!Number.isInteger(t.amount) || t.amount <= 0) p.push(`Transaction ${t.id}: bad amount`);
    if (!/^\d{4}-\d\d-\d\d$/.test(t.date)) p.push(`Transaction ${t.id}: bad date`);
  }
  const byId = new Map(b.tx.map((t) => [t.id, t]));
  for (const t of b.tx) if (t.feeOf != null) {
    const par = byId.get(t.feeOf);
    if (!par || par.type !== "xfer") p.push(`Transaction ${t.id}: charge without its transfer`);
    else if (t.type !== "exp" || t.acc !== par.acc) p.push(`Transaction ${t.id}: charge must be an expense on the transfer's source account`);
  }
  return p;
}
// Annotate the CommonJS export names for ESM import in node:
0 && (module.exports = {
  fromPrototype,
  initDb,
  loadBook,
  saveBook,
  toPrototype,
  validateBook
});
