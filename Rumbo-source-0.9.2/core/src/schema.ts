export const SCHEMA_VERSION = 7;
/** Foreign keys use RESTRICT so the database itself refuses unsafe deletes. */
export const SCHEMA_SQL = `
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
