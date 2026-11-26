// Google Drive backups for Rumbo, shared by the desktop app and the Android app.
// Rumbo only has the drive.file permission: it sees the folder and files it created, nothing else in the user's Drive.
// Every function takes the access token through getToken(fresh), so each platform keeps its own sign-in.

const API = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
const FOLDER = 'Rumbo Backups';
const FOLDER_MIME = 'application/vnd.google-apps.folder';
const FIELDS = 'id,name,createdTime,size,appProperties';

/** An error with a code the screens understand: offline, auth, quota, notfound or error. */
function fail(code, message) { const e = new Error(message); e.code = code; return e; }

function createDrive({ getToken, fetchImpl }) {
  const doFetch = fetchImpl || ((...a) => fetch(...a));
  let folderId = null;

  async function call(url, opts = {}, as = 'json') {
    for (let attempt = 0; attempt < 2; attempt++) {
      const token = await getToken(attempt > 0);
      let res;
      try {
        res = await doFetch(url, { ...opts, headers: { ...(opts.headers || {}), Authorization: 'Bearer ' + token } });
      } catch (e) {
        throw fail('offline', 'Could not reach Google Drive. Check the internet connection.');
      }
      if (res.status === 401 && attempt === 0) continue;        // token expired or was revoked: get a fresh one once
      if (res.status === 401) throw fail('auth', 'Google sign-in has expired. Sign in again.');
      if (!res.ok) {
        let reason = '', msg = '';
        try { const j = await res.json(); reason = (j.error && j.error.errors && j.error.errors[0] && j.error.errors[0].reason) || ''; msg = (j.error && j.error.message) || ''; } catch { /* not JSON */ }
        if (res.status === 403 && /storageQuotaExceeded|quotaExceeded/.test(reason)) throw fail('quota', 'Your Google Drive is full. Free up space in Drive, then back up again.');
        if (res.status === 403 && /insufficient|forbidden|accessNotConfigured/i.test(reason + msg)) throw fail('auth', 'Rumbo does not have permission to use Google Drive. Sign in again and allow access.');
        if (res.status === 404) throw fail('notfound', 'That backup is no longer in Google Drive.');
        if (res.status === 429 || res.status >= 500) throw fail('offline', 'Google Drive is busy right now. Try again in a few minutes.');
        throw fail('error', 'Google Drive said: ' + (msg || res.status));
      }
      if (as === 'none') return null;
      return as === 'text' ? res.text() : res.json();
    }
  }

  const q = s => encodeURIComponent(s);

  async function folder() {
    if (folderId) return folderId;
    const found = await call(`${API}/files?q=${q(`name='${FOLDER}' and mimeType='${FOLDER_MIME}' and trashed=false`)}&fields=files(id)&spaces=drive&pageSize=10`);
    if (found.files && found.files.length) return (folderId = found.files[0].id);
    const made = await call(`${API}/files?fields=id`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: FOLDER, mimeType: FOLDER_MIME }) });
    return (folderId = made.id);
  }

  /** The signed-in account's email, so the screens can say whose Drive the backups go to. */
  async function account() {
    const a = await call(`${API}/about?fields=user(emailAddress,displayName)`);
    return { email: (a.user && a.user.emailAddress) || '', name: (a.user && a.user.displayName) || '' };
  }

  /** Rumbo's backups, newest first. */
  async function list() {
    const id = await folder();
    let out = [], page = '';
    do {
      const r = await call(`${API}/files?q=${q(`'${id}' in parents and trashed=false and name contains 'rumbo-'`)}&orderBy=createdTime desc&pageSize=100&fields=nextPageToken,files(${FIELDS})${page ? '&pageToken=' + q(page) : ''}`);
      out = out.concat(r.files || []); page = r.nextPageToken || '';
    } while (page && out.length < 500);
    return out.sort((a, b) => String(b.createdTime).localeCompare(String(a.createdTime)));
  }

  /** Uploads one backup file with labels (device, counts, version) stored as app properties. */
  async function upload(name, text, props) {
    const id = await folder();
    const appProperties = {};
    for (const [k, v] of Object.entries(props || {})) appProperties[k] = String(v).slice(0, 100);
    const boundary = 'rumbo' + Math.random().toString(36).slice(2);
    const meta = { name, parents: [id], mimeType: 'application/json', appProperties };
    const body = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${text}\r\n--${boundary}--`;
    return call(`${UPLOAD}/files?uploadType=multipart&fields=${FIELDS}`, { method: 'POST', headers: { 'Content-Type': 'multipart/related; boundary=' + boundary }, body });
  }

  async function download(fileId) { return call(`${API}/files/${q(fileId)}?alt=media`, {}, 'text'); }

  async function remove(fileId) { return call(`${API}/files/${q(fileId)}`, { method: 'DELETE' }, 'none'); }

  /** Keeps the newest `keep` backups and deletes the rest. */
  async function prune(keep) {
    const files = await list();
    const old = files.slice(Math.max(1, keep || 10));
    for (const f of old) { try { await remove(f.id); } catch (e) { if (e.code !== 'notfound') throw e; } }
    return old.length;
  }

  return { account, list, upload, download, remove, prune, forget: () => { folderId = null; } };
}

/** Revokes Rumbo's access to the account (used on Sign out). Errors are ignored: the local sign-in is cleared anyway. */
async function revoke(token, fetchImpl) {
  try { await (fetchImpl || fetch)('https://oauth2.googleapis.com/revoke', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'token=' + encodeURIComponent(token) }); } catch { /* offline */ }
}

module.exports = { createDrive, revoke, fail, FOLDER };
