const { app, BrowserWindow, Menu, dialog, ipcMain, shell, Tray, Notification, nativeImage } = require('electron');
const fs = require('node:fs'), path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const core = require('./core-native.cjs');

// Windows shows notifications (payment reminders) only for an app with an id.
if (process.platform === 'win32') app.setAppUserModelId('com.rumbo.app');
const userData = app.getPath('userData');
const DB_FILE = path.join(userData, 'rumbo.db');
const BACKUP_DIR = path.join(app.getPath('documents'), 'Rumbo Backups');
const KEEP = 10;
let db, win, lastBackup = 0, pending = null, timer = null, tray = null, quitting = false, hintShown = false;
const startHidden = process.argv.includes('--hidden');
/** Tray and start-with-Windows preferences live with the data (Settings > General > Reminders). Both default to on. */
let prefs = { trayOn: true, startWin: true };

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
    fs.writeFileSync(path.join(BACKUP_DIR, `rumbo-${stamp}${reason ? '-' + reason : ''}.json`), JSON.stringify({ app: 'rumbo', schema: 1, exported: new Date().toISOString(), data: S }, null, 1));
    const files = fs.readdirSync(BACKUP_DIR).filter(f => /^rumbo-.*\.json$/.test(f)).sort();
    for (const f of files.slice(0, Math.max(0, files.length - KEEP))) fs.rmSync(path.join(BACKUP_DIR, f));
    lastBackup = Date.now();
  } catch (e) { console.error('backup failed', e); }
}

function readPrefs(ui) { ui = ui || {}; return { trayOn: ui.trayOn !== false, startWin: ui.startWin !== false }; }
function applyPrefs(next) {
  prefs = next;
  if (prefs.trayOn) makeTray(); else if (tray) { tray.destroy(); tray = null; }
  // Only a real install (not a development run) registers itself to start with Windows.
  if (process.platform === 'win32' && app.isPackaged) {
    try { app.setLoginItemSettings({ openAtLogin: prefs.trayOn && prefs.startWin, path: process.execPath, args: ['--hidden'] }); } catch (e) { console.error('login item', e); }
  }
}
function showWin(view) {
  if (!win || win.isDestroyed()) { createWindow(true); }
  if (win.isMinimized()) win.restore();
  win.show(); win.focus();
  if (view) win.webContents.send('pl-goto', view);
}
function makeTray() {
  if (tray) return;
  const icon = nativeImage.createFromPath(path.join(__dirname, '..', 'icons', process.platform === 'win32' ? 'icon.ico' : 'icon.png'));
  tray = new Tray(icon.isEmpty() ? nativeImage.createEmpty() : icon);
  tray.setToolTip('Rumbo');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Rumbo', click: () => showWin() },
    { label: 'New transaction', click: () => showWin('new') },
    { label: 'Calendar', click: () => showWin('calendar') },
    { type: 'separator' },
    { label: 'Quit Rumbo', click: () => { quitting = true; app.quit(); } },
  ]));
  tray.on('click', () => showWin());
}
ipcMain.on('pl-notify', (_e, title, body, view) => {
  try {
    if (!Notification.isSupported()) return;
    const n = new Notification({ title, body, icon: path.join(__dirname, '..', 'icons', 'icon.png') });
    n.on('click', () => showWin(view || 'overview'));
    n.show();
  } catch (x) { console.error('notification failed', x); }
});
ipcMain.on('pl-load', e => { try { e.returnValue = hasData() ? JSON.stringify(core.loadBook(db)) : null; } catch (x) { console.error(x); e.returnValue = null; } });
ipcMain.on('pl-backup-dir', e => { e.returnValue = BACKUP_DIR; });
ipcMain.on('pl-save', (e, json) => {
  try {
    const book = JSON.parse(json);
    const bad = core.validateBook(book);
    if (bad.length) { e.returnValue = { ok: false, error: bad[0] }; return; }   // never write a book that fails its own checks
    core.saveBook(db, book);
    e.returnValue = { ok: true };
    const np = readPrefs(book.settings && book.settings.ui);
    if (np.trayOn !== prefs.trayOn || np.startWin !== prefs.startWin) applyPrefs(np);
    if (Date.now() - lastBackup > 3600e3) { clearTimeout(timer); timer = setTimeout(() => writeBackup('auto'), 5000); }
  } catch (x) { console.error('save failed, previous data kept', x); e.returnValue = { ok: false, error: String(x && x.message || x) }; }
});
ipcMain.handle('pl-save-file', async (_e, filename, text) => {
  const r = await dialog.showSaveDialog(win, { defaultPath: path.join(app.getPath('documents'), filename), filters: [{ name: 'Backup', extensions: ['json'] }, { name: 'CSV', extensions: ['csv'] }] });
  if (r.canceled || !r.filePath) { throw new Error('declined'); }
  fs.writeFileSync(r.filePath, text); return 'saved';
});

function stateFile() { return path.join(userData, 'window.json'); }
function createWindow(forceShow) {
  let st = { width: 1280, height: 860 };
  try { st = { ...st, ...JSON.parse(fs.readFileSync(stateFile(), 'utf8')) }; } catch {}
  win = new BrowserWindow({ ...st, minWidth: 380, minHeight: 560, backgroundColor: '#eef3f0', title: 'Rumbo', show: false, icon: path.join(__dirname, '..', 'icons', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: false, backgroundThrottling: false } });
  if (st.max) win.maximize();
  win.once('ready-to-show', () => { if (forceShow || !(startHidden && prefs.trayOn)) win.show(); });
  win.loadFile(path.join(__dirname, 'app.html'));
  if (process.env.PL_SMOKE) win.webContents.once('did-finish-load', async () => {
    try { const out = await win.webContents.executeJavaScript(require('node:fs').readFileSync(process.env.PL_SMOKE, 'utf8')); console.log('SMOKE ' + JSON.stringify(out)); } catch (e) { console.log('SMOKE-ERR ' + e); }
    if (process.env.PL_SHOT) { const img = await win.webContents.capturePage(); require('node:fs').writeFileSync(process.env.PL_SHOT, img.toPNG()); }
    app.quit();
  });
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.on('close', e => {
    try { const b = win.getNormalBounds(); fs.writeFileSync(stateFile(), JSON.stringify({ ...b, max: win.isMaximized() })); } catch {}
    // With the tray on, closing the window only hides it, so reminders keep coming. Quit from the tray or File > Quit.
    if (prefs.trayOn && !quitting && !process.env.PL_SMOKE) {
      e.preventDefault(); win.hide();
      if (tray && !hintShown && process.platform === 'win32') { hintShown = true; try { tray.displayBalloon({ title: 'Rumbo is still running', content: 'Reminders will keep coming. To quit, right-click this icon and choose Quit Rumbo.' }); } catch {} }
    }
  });
}
let guideWin = null;
function openGuide() {
  if (guideWin && !guideWin.isDestroyed()) { guideWin.focus(); return; }
  guideWin = new BrowserWindow({ width: 920, height: 800, minWidth: 420, minHeight: 400, title: 'Rumbo User Guide', autoHideMenuBar: true, parent: undefined,
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
      { label: 'About Rumbo', click: () => dialog.showMessageBox(win, { type: 'info', title: 'About Rumbo', message: 'Rumbo ' + app.getVersion(), detail: 'Your data is stored on this computer only:\n' + DB_FILE }) }] },
  ]));
}
const lock = app.requestSingleInstanceLock();
if (!lock) app.quit();
else {
  app.on('second-instance', () => showWin());
  app.whenReady().then(() => {
    try { openDb(); } catch (x) { dialog.showErrorBox('Rumbo cannot open its data file', String(x && x.message || x) + '\n\n' + DB_FILE); app.exit(1); return; }
    try { prefs = readPrefs(core.loadBook(db).settings.ui); } catch { /* new data file: defaults */ }
    menu(); createWindow(); applyPrefs(prefs);
  });
  app.on('before-quit', () => { quitting = true; writeBackup('exit'); });
  app.on('window-all-closed', () => { if (!prefs.trayOn) app.quit(); });
}
