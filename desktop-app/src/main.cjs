const { app, BrowserWindow, Menu, dialog, ipcMain, shell } = require('electron');
const fs = require('node:fs'), path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const core = require('./core-native.cjs');

const userData = app.getPath('userData');
const DB_FILE = path.join(userData, 'pocket-ledger.db');
const BACKUP_DIR = path.join(app.getPath('documents'), 'Pocket Ledger Backups');
const KEEP = 10;
let db, win, lastBackup = 0, pending = null, timer = null;

function openDb() {
  fs.mkdirSync(userData, { recursive: true });
  db = new DatabaseSync(DB_FILE);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;');
  core.initDb(db);
}
const hasData = () => { try { return db.prepare('SELECT COUNT(*) n FROM account').all()[0].n > 0 || db.prepare("SELECT value FROM meta WHERE key='base'").all().length > 0; } catch { return false; } };

/** Writes a backup the screens can restore (their own backup format) and keeps only the newest KEEP files. */
function writeBackup(reason) {
  try {
    if (!hasData()) return;
    const S = core.toPrototype(core.loadBook(db));
    fs.mkdirSync(BACKUP_DIR, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
    fs.writeFileSync(path.join(BACKUP_DIR, `pocket-ledger-${stamp}${reason ? '-' + reason : ''}.json`), JSON.stringify({ app: 'pocket-ledger', schema: 1, exported: new Date().toISOString(), data: S }, null, 1));
    const files = fs.readdirSync(BACKUP_DIR).filter(f => /^pocket-ledger-.*\.json$/.test(f)).sort();
    for (const f of files.slice(0, Math.max(0, files.length - KEEP))) fs.rmSync(path.join(BACKUP_DIR, f));
    lastBackup = Date.now();
  } catch (e) { console.error('backup failed', e); }
}

ipcMain.on('pl-load', e => { try { e.returnValue = hasData() ? JSON.stringify(core.loadBook(db)) : null; } catch (x) { console.error(x); e.returnValue = null; } });
ipcMain.on('pl-save', (e, json) => {
  try {
    const book = JSON.parse(json);
    const bad = core.validateBook(book);
    if (bad.length) { e.returnValue = { ok: false, error: bad[0] }; return; }   // never write a book that fails its own checks
    core.saveBook(db, book);
    e.returnValue = { ok: true };
    if (Date.now() - lastBackup > 3600e3) { clearTimeout(timer); timer = setTimeout(() => writeBackup('auto'), 5000); }
  } catch (x) { console.error('save failed, previous data kept', x); e.returnValue = { ok: false, error: String(x && x.message || x) }; }
});
ipcMain.handle('pl-save-file', async (_e, filename, text) => {
  const r = await dialog.showSaveDialog(win, { defaultPath: path.join(app.getPath('documents'), filename), filters: [{ name: 'Backup', extensions: ['json'] }, { name: 'CSV', extensions: ['csv'] }] });
  if (r.canceled || !r.filePath) { throw new Error('declined'); }
  fs.writeFileSync(r.filePath, text); return 'saved';
});

function stateFile() { return path.join(userData, 'window.json'); }
function createWindow() {
  let st = { width: 1280, height: 860 };
  try { st = { ...st, ...JSON.parse(fs.readFileSync(stateFile(), 'utf8')) }; } catch {}
  win = new BrowserWindow({ ...st, minWidth: 380, minHeight: 560, backgroundColor: '#eef3f0', title: 'Pocket Ledger', show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: false } });
  if (st.max) win.maximize();
  win.once('ready-to-show', () => win.show());
  win.loadFile(path.join(__dirname, 'app.html'));
  if (process.env.PL_SMOKE) win.webContents.once('did-finish-load', async () => {
    try { const out = await win.webContents.executeJavaScript(require('node:fs').readFileSync(process.env.PL_SMOKE, 'utf8')); console.log('SMOKE ' + JSON.stringify(out)); } catch (e) { console.log('SMOKE-ERR ' + e); }
    if (process.env.PL_SHOT) { const img = await win.webContents.capturePage(); require('node:fs').writeFileSync(process.env.PL_SHOT, img.toPNG()); }
    app.quit();
  });
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.on('close', () => { try { const b = win.getNormalBounds(); fs.writeFileSync(stateFile(), JSON.stringify({ ...b, max: win.isMaximized() })); } catch {} });
}
let guideWin = null;
function openGuide() {
  if (guideWin && !guideWin.isDestroyed()) { guideWin.focus(); return; }
  guideWin = new BrowserWindow({ width: 920, height: 800, minWidth: 420, minHeight: 400, title: 'Pocket Ledger User Guide', autoHideMenuBar: true, parent: undefined,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true } });
  guideWin.loadFile(path.join(__dirname, 'guide.html'));
  guideWin.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  guideWin.on('closed', () => { guideWin = null; });
}
function menu() {
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    { label: '&File', submenu: [
      { label: 'Back up now', click: () => { writeBackup('manual'); dialog.showMessageBox(win, { message: 'Backup saved', detail: BACKUP_DIR }); } },
      { label: 'Open backup folder', click: () => { fs.mkdirSync(BACKUP_DIR, { recursive: true }); shell.openPath(BACKUP_DIR); } },
      { label: 'Open data folder', click: () => shell.openPath(userData) }, { type: 'separator' }, { role: 'quit' }] },
    { label: '&Edit', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }] },
    { label: '&View', submenu: [{ role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { type: 'separator' }, { role: 'togglefullscreen' }] },
    { label: '&Help', submenu: [
      { label: 'User guide', accelerator: 'F1', click: openGuide },
      { type: 'separator' },
      { label: 'About Pocket Ledger', click: () => dialog.showMessageBox(win, { type: 'info', title: 'About Pocket Ledger', message: 'Pocket Ledger ' + app.getVersion(), detail: 'Your data is stored on this computer only:\n' + DB_FILE }) }] },
  ]));
}
const lock = app.requestSingleInstanceLock();
if (!lock) app.quit();
else {
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => {
    try { openDb(); } catch (x) { dialog.showErrorBox('Pocket Ledger cannot open its data file', String(x && x.message || x) + '\n\n' + DB_FILE); app.exit(1); return; }
    menu(); createWindow();
  });
  app.on('before-quit', () => { writeBackup('exit'); });
  app.on('window-all-closed', () => app.quit());
}
