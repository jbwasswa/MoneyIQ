// Android storage and device glue for the Rumbo screens.
// Data lives in one file inside the app's private storage. Every save keeps the previous version as a fallback,
// and the screens' own data checks run before anything is written.
import { Filesystem, Directory, Encoding } from '@capacitor/filesystem';
import { App } from '@capacitor/app';
import { Share } from '@capacitor/share';
import { LocalNotifications } from '@capacitor/local-notifications';
import { Device } from '@capacitor/device';
import { registerPlugin } from '@capacitor/core';
import { createDrive, revoke } from '../../desktop-app/src/gdrive.cjs';

// Google Drive backups. RumboDrive (MainActivity's plugin) gets short-lived access tokens from Google;
// the first sign-in shows Google's account picker, later ones are silent.
const RumboDrive = registerPlugin('RumboDrive');
const GD_ACCOUNT = 'rumbo-gd-account';
let gdToken = null, gdTokenAt = 0, deviceName = 'Android phone';
const gdErr = e => { const c = String(e && e.code || ''); return Object.assign(new Error(String(e && e.message || e)), { code: /^(AUTH|auth)$/.test(c) ? 'auth' : /CANCEL/i.test(c) ? 'cancelled' : (e && e.code) || 'error' }); };
async function gdGetToken(fresh, interactive) {
  if (!fresh && gdToken && Date.now() - gdTokenAt < 45 * 60e3) return gdToken;
  if (fresh && gdToken) { try { await RumboDrive.clear({ token: gdToken }); } catch { /* ignore */ } gdToken = null; }
  try {
    const r = await RumboDrive.token({ interactive: !!interactive });
    gdToken = r.accessToken; gdTokenAt = Date.now();
    return gdToken;
  } catch (e) {
    const err = gdErr(e);
    if (err.code === 'auth' && !interactive) { try { localStorage.removeItem(GD_ACCOUNT); } catch { /* ignore */ } }
    throw err;
  }
}
const gdrive = createDrive({ getToken: fresh => gdGetToken(fresh, false) });
const gdWrap = fn => (...a) => fn(...a).catch(e => Promise.reject({ code: e.code || 'error', message: String(e.message || e) }));
const PLCloud = {
  status: gdWrap(async () => { const email = localStorage.getItem(GD_ACCOUNT); return { signedIn: email != null, email: email || '' }; }),
  signIn: gdWrap(async () => {
    await gdGetToken(true, true);
    gdrive.forget();
    const acct = await gdrive.account();
    localStorage.setItem(GD_ACCOUNT, acct.email || '');
    return { signedIn: true, email: acct.email };
  }),
  signOut: gdWrap(async () => {
    const t = gdToken; localStorage.removeItem(GD_ACCOUNT); gdrive.forget();
    if (t) { await revoke(t); try { await RumboDrive.clear({ token: t }); } catch { /* ignore */ } }
    try { await RumboDrive.signOut(); } catch { /* ignore */ }
    gdToken = null; return true;
  }),
  list: gdWrap(() => gdrive.list()),
  upload: gdWrap((name, text, props) => gdrive.upload(String(name), String(text), props || {})),
  download: gdWrap(id => gdrive.download(String(id))),
  prune: gdWrap(keep => gdrive.prune(+keep || 10)),
  device: () => deviceName,
};

const DIR = Directory.Data, FILE = 'pocket-ledger/book.json', BAK = 'pocket-ledger/book.previous.json';
let cached = null, queued = null, writing = false;

function banner(msg) {
  let b = document.getElementById('pl-native-banner');
  if (!msg) { if (b) b.remove(); return; }
  if (!b) {
    b = document.createElement('div'); b.id = 'pl-native-banner'; b.setAttribute('role', 'alert');
    b.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:99999;background:#b3261e;color:#fff;padding:calc(10px + env(safe-area-inset-top,0px)) 16px 10px;font:600 14px system-ui,sans-serif;text-align:center';
    document.body.appendChild(b);
  }
  b.textContent = msg;
}
const good = s => { try { const o = JSON.parse(s); return o && Array.isArray(o.tx) && Array.isArray(o.accounts) && o.settings; } catch { return false; } };
async function readFile(path) { try { return (await Filesystem.readFile({ path, directory: DIR, encoding: Encoding.UTF8 })).data; } catch { return null; } }

async function flush() {
  if (writing) return;
  writing = true;
  try {
    while (queued !== null) {
      const json = queued; queued = null;
      try {
        const prev = await readFile(FILE);
        if (prev && good(prev)) await Filesystem.writeFile({ path: BAK, directory: DIR, data: prev, encoding: Encoding.UTF8, recursive: true });
        await Filesystem.writeFile({ path: FILE, directory: DIR, data: json, encoding: Encoding.UTF8, recursive: true });
        if (!good((await readFile(FILE)) || '')) throw new Error('the saved file did not read back correctly');
        banner('');
      } catch (e) {
        banner('Your last change could not be saved on this phone (' + (e && e.message || e) + '). It is on screen only. Use Settings > Backup & restore to save a copy.');
      }
    }
  } finally { writing = false; }
}

let scheduleQueue = null, scheduling = false, asked = false;
async function runSchedule() {
  if (scheduling) return;
  scheduling = true;
  try {
    while (scheduleQueue !== null) {
      const list = scheduleQueue; scheduleQueue = null;
      try {
        if (list.length) {
          let p = await LocalNotifications.checkPermissions();
          if (p.display !== 'granted' && !asked) { asked = true; p = await LocalNotifications.requestPermissions(); }
          if (p.display !== 'granted') continue;
        }
        const pending = await LocalNotifications.getPending();
        const ours = (pending.notifications || []).filter(n => n.id >= 7001 && n.id < 8000);
        if (ours.length) await LocalNotifications.cancel({ notifications: ours.map(n => ({ id: n.id })) });
        if (list.length) await LocalNotifications.schedule({ notifications: list.map(x => ({
          id: x.id, title: x.title, body: x.body, largeBody: x.body, channelId: 'reminders',
          smallIcon: 'ic_stat_rumbo', iconColor: '#C9A227',
          schedule: { at: new Date(x.at), allowWhileIdle: true }, extra: { view: x.view || 'overview' },
        })) });
      } catch (e) { console.warn('Rumbo: could not schedule reminders', e); }
    }
  } finally { scheduling = false; }
}

async function boot() {
  let s = await readFile(FILE);
  if (!(s && good(s))) { const b = await readFile(BAK); s = b && good(b) ? b : null; }
  cached = s;
  try { const info = await Device.getInfo(); deviceName = (info.name && info.name.trim()) || [info.manufacturer, info.model].filter(Boolean).join(' ') || deviceName; } catch { /* keep default */ }
  window.PLCloud = PLCloud;
  window.PLNative = {
    loadSync: () => cached,
    save: json => { cached = json; queued = json; flush(); return { ok: true }; },
    // Reminders: the screens hand over the next two months of reminders; the phone delivers them even with Rumbo closed.
    scheduleReminders: list => { scheduleQueue = list; runSchedule(); },
    askNotify: () => LocalNotifications.requestPermissions().catch(() => {}),
  };
  try { await LocalNotifications.createChannel({ id: 'reminders', name: 'Reminders', description: 'Repeating entries that are coming up', importance: 4, visibility: 0 }); } catch { /* older Android: no channels */ }
  // Tapping a reminder opens Rumbo on the Overview, where the Coming up box has Record today.
  LocalNotifications.addListener('localNotificationActionPerformed', a => {
    const v = (a && a.notification && a.notification.extra && a.notification.extra.view) || 'overview';
    if (window.PLGoto) window.PLGoto(v); else window.__plGoto = v;
  });
  // The screens save files through a "downloads" helper; on Android that is the share sheet (Drive, Files, email...).
  window.claude = {
    use: name => Promise.resolve(name !== 'downloads' ? null : {
      save: async ({ filename, data }) => {
        try {
          await Filesystem.writeFile({ path: filename, directory: Directory.Cache, data: String(data), encoding: Encoding.UTF8, recursive: true });
          const { uri } = await Filesystem.getUri({ path: filename, directory: Directory.Cache });
          await Share.share({ title: filename, url: uri, dialogTitle: 'Save or send this file' });
          return { status: 'saved' };
        } catch (e) {
          const m = String(e && e.message || e);
          throw { code: /cancel/i.test(m) ? 'declined' : 'unavailable' };
        }
      },
    }),
  };
  // Phone back button: close what is open, then follow the same back-navigation
  // the on-screen back button (top-left, "Back to Accounts"/"Back to Settings") would,
  // then go to Overview, then leave the app.
  App.addListener('backButton', () => {
    const lock = document.getElementById('lock');
    if (lock && !lock.hidden) { App.minimizeApp(); return; }
    const open = ['#dlg', '#sheet', '#drawer'].some(s => { const e = document.querySelector(s); return e && !e.hidden; });
    if (open) { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); return; }
    const backBtn = document.querySelector('#top [aria-label^="Back to"]');
    if (backBtn) { backBtn.click(); return; }
    const home = document.querySelector('[data-a="tab"][data-v="overview"]');
    const onHome = home && /\bon\b|active|current/.test(home.className + ' ' + (home.getAttribute('aria-current') || ''));
    if (home && !onHome) { home.click(); return; }
    App.exitApp();
  });
  // Start the screens now that storage is ready.
  const code = document.getElementById('pl-main').textContent;
  const s2 = document.createElement('script'); s2.textContent = code; document.body.appendChild(s2);
}
boot().catch(e => { document.body.insertAdjacentHTML('afterbegin', '<p style="padding:24px;font:16px system-ui">Rumbo could not start: ' + String(e && e.message || e) + '</p>'); });
