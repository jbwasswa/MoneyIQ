const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('PLNative', {
  loadSync: () => ipcRenderer.sendSync('pl-load'),
  save: json => ipcRenderer.sendSync('pl-save', json),
});
// The screens save files through a "downloads" helper; on the desktop that is the native Save dialog.
contextBridge.exposeInMainWorld('claude', {
  use: name => Promise.resolve(name === 'downloads' ? { save: ({ filename, data }) => ipcRenderer.invoke('pl-save-file', filename, String(data)).then(() => ({ status: 'saved' }), c => Promise.reject({ code: /declined/.test(String(c && c.message || c)) ? 'declined' : 'unavailable' })) } : null),
});
