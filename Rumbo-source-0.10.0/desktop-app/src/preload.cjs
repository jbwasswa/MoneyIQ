const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('PLNative', {
  loadSync: () => ipcRenderer.sendSync('pl-load'),
  save: json => ipcRenderer.sendSync('pl-save', json),
  backupDir: () => ipcRenderer.sendSync('pl-backup-dir'),
  desktop: true,
  // Reminders: a Windows notification shown by the main process; clicking it opens Rumbo on `view`.
  notify: (title, body, view) => ipcRenderer.send('pl-notify', String(title), String(body), String(view || 'overview')),
  // The tray menu and notifications ask the screens to open a page ('overview', 'calendar', 'new', ...).
  onGoto: cb => ipcRenderer.on('pl-goto', (_e, v) => cb(String(v))),
  // Writes a backup file on this computer straight away (used before restoring a backup over the current data).
  localBackup: reason => ipcRenderer.sendSync('pl-local-backup', String(reason || 'manual')),
});
// Google Drive backups: sign-in and Drive calls run in the main process. Failures reject with { code, message }.
const gdCall = (name, ...args) => ipcRenderer.invoke('gd-' + name, ...args).then(r => r && r.ok ? r.value : Promise.reject({ code: (r && r.code) || 'error', message: (r && r.message) || 'Something went wrong' }));
contextBridge.exposeInMainWorld('PLCloud', {
  status: () => gdCall('status'),
  signIn: () => gdCall('signin'),
  signOut: () => gdCall('signout'),
  list: () => gdCall('list'),
  upload: (name, text, props) => gdCall('upload', String(name), String(text), props || {}),
  download: id => gdCall('download', String(id)),
  prune: keep => gdCall('prune', keep),
  device: () => ipcRenderer.sendSync('gd-device'),
});
// The screens save files through a "downloads" helper; on the desktop that is the native Save dialog.
contextBridge.exposeInMainWorld('claude', {
  use: name => Promise.resolve(name === 'downloads' ? { save: ({ filename, data }) => ipcRenderer.invoke('pl-save-file', filename, String(data)).then(() => ({ status: 'saved' }), c => Promise.reject({ code: /declined/.test(String(c && c.message || c)) ? 'declined' : 'unavailable' })) } : null),
});
