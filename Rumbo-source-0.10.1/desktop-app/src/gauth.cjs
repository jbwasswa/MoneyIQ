// Google sign-in for the desktop app (OAuth for installed apps: system browser, loopback redirect, PKCE).
// The refresh token is kept on this computer, encrypted with Windows' own protection (safeStorage) where available.
const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const { fail } = require('./gdrive.cjs');

const SCOPE = 'https://www.googleapis.com/auth/drive.file';
const b64url = buf => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const PAGE = (title, text) => `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title></head><body style="font:16px system-ui,sans-serif;display:grid;place-items:center;min-height:90vh;background:#EDF3F1;color:#1B2A4A"><div style="background:#fff;padding:32px 40px;border-radius:16px;max-width:440px;text-align:center;box-shadow:0 4px 24px #0001"><h1 style="font-size:22px;margin:0 0 8px">${title}</h1><p style="margin:0;color:#4a5568">${text}</p></div></body></html>`;

/**
 * opts: clientId, clientSecret, file (where the sign-in is kept), crypt ({ encrypt(str)->Buffer, decrypt(Buffer)->str } or null),
 * openUrl(url), fetchImpl, authUrl, tokenUrl, timeoutMs
 */
function createDesktopAuth(opts) {
  const doFetch = opts.fetchImpl || ((...a) => fetch(...a));
  const AUTH = opts.authUrl || 'https://accounts.google.com/o/oauth2/v2/auth';
  const TOKEN = opts.tokenUrl || 'https://oauth2.googleapis.com/token';
  let access = null, expires = 0, signingIn = null;

  function readStore() {
    try {
      const o = JSON.parse(fs.readFileSync(opts.file, 'utf8'));
      const rt = o.enc && opts.crypt ? opts.crypt.decrypt(Buffer.from(o.rt, 'base64')) : o.rt;
      return rt ? { rt, email: o.email || '' } : null;
    } catch { return null; }
  }
  function writeStore(rt, email) {
    const enc = !!opts.crypt;
    const data = { rt: enc ? opts.crypt.encrypt(rt).toString('base64') : rt, enc, email: email || '' };
    fs.writeFileSync(opts.file, JSON.stringify(data), { mode: 0o600 });
  }
  function clearStore() { try { fs.rmSync(opts.file); } catch { /* none */ } access = null; expires = 0; }

  async function tokenRequest(params) {
    let res;
    try {
      res = await doFetch(TOKEN, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ client_id: opts.clientId, client_secret: opts.clientSecret, ...params }).toString() });
    } catch { throw fail('offline', 'Could not reach Google. Check the internet connection.'); }
    const j = await res.json().catch(() => ({}));
    if (!res.ok) {
      if (j.error === 'invalid_grant') throw fail('auth', 'Google sign-in has expired. Sign in again.');
      throw fail('error', 'Google sign-in failed: ' + (j.error_description || j.error || res.status));
    }
    return j;
  }

  /** A valid access token; refreshes it when needed. */
  async function getToken(fresh) {
    if (!fresh && access && Date.now() < expires - 60e3) return access;
    const s = readStore();
    if (!s) throw fail('auth', 'Not signed in to Google.');
    try {
      const j = await tokenRequest({ grant_type: 'refresh_token', refresh_token: s.rt });
      access = j.access_token; expires = Date.now() + (j.expires_in || 3600) * 1000;
      return access;
    } catch (e) { if (e.code === 'auth') clearStore(); throw e; }
  }

  /** Opens Google's sign-in in the browser and waits for it to come back to Rumbo. */
  function signIn() {
    if (signingIn) return signingIn;
    signingIn = new Promise((resolve, reject) => {
      const verifier = b64url(crypto.randomBytes(48));
      const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
      const state = b64url(crypto.randomBytes(16));
      let redirect = '', done = false;
      const server = http.createServer(async (req, res) => {
        const u = new URL(req.url, 'http://127.0.0.1');
        if (u.pathname !== '/' || done) { res.writeHead(404); res.end(); return; }
        const finish = (ok, title, text, value) => {
          res.writeHead(ok ? 200 : 400, { 'Content-Type': 'text/html; charset=utf-8' }); res.end(PAGE(title, text));
          done = true; clearTimeout(timer); server.close();
          ok ? resolve(value) : reject(value);
        };
        if (u.searchParams.get('state') !== state) { res.writeHead(400); res.end('Unexpected sign-in response.'); return; }
        if (u.searchParams.get('error')) {
          const denied = u.searchParams.get('error') === 'access_denied';
          finish(false, 'Not signed in', denied ? 'You did not allow access. You can close this tab and try again in Rumbo.' : 'Google sign-in did not finish. You can close this tab.', fail(denied ? 'cancelled' : 'error', denied ? 'Sign-in was cancelled.' : 'Google sign-in failed: ' + u.searchParams.get('error')));
          return;
        }
        try {
          const j = await tokenRequest({ grant_type: 'authorization_code', code: u.searchParams.get('code') || '', code_verifier: verifier, redirect_uri: redirect });
          if (!j.refresh_token) throw fail('error', 'Google did not keep Rumbo signed in. Try again.');
          writeStore(j.refresh_token, '');
          access = j.access_token; expires = Date.now() + (j.expires_in || 3600) * 1000;
          finish(true, 'Signed in to Rumbo', 'You can close this tab and go back to Rumbo.', true);
        } catch (e) { finish(false, 'Not signed in', 'Something went wrong. You can close this tab and try again in Rumbo.', e); }
      });
      const timer = setTimeout(() => { if (!done) { done = true; server.close(); reject(fail('cancelled', 'Sign-in took too long, so it was stopped. Try again.')); } }, opts.timeoutMs || 5 * 60e3);
      server.on('error', e => { clearTimeout(timer); reject(fail('error', 'Could not start the sign-in: ' + e.message)); });
      server.listen(0, '127.0.0.1', () => {
        redirect = 'http://127.0.0.1:' + server.address().port;
        const url = AUTH + '?' + new URLSearchParams({ client_id: opts.clientId, redirect_uri: redirect, response_type: 'code', scope: SCOPE, code_challenge: challenge, code_challenge_method: 'S256', state, access_type: 'offline', prompt: 'consent select_account' }).toString();
        Promise.resolve(opts.openUrl(url)).catch(e => { clearTimeout(timer); server.close(); reject(fail('error', 'Could not open the browser: ' + e.message)); });
      });
    }).finally(() => { signingIn = null; });
    return signingIn;
  }

  return {
    getToken, signIn,
    status: () => { const s = readStore(); return { signedIn: !!s, email: s ? s.email : '' }; },
    setEmail: email => { const s = readStore(); if (s) writeStore(s.rt, email); },
    signOut: () => { const s = readStore(); clearStore(); return s ? s.rt : null; },
  };
}

module.exports = { createDesktopAuth, SCOPE };
