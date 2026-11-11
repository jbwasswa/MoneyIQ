import { signal, computed } from '@preact/signals';
import type { Book } from '../../core/src/types';
import { migratePrototype } from '../../core/src/migrate';
import demo from '../../core/test/fixtures/prototype-backup.json';

const KEY = 'pocket-ledger-desktop-dev';
function initial(): Book {
  try { const s = localStorage.getItem(KEY); if (s) return JSON.parse(s); } catch { /* ignore */ }
  return migratePrototype(demo).book;
}
export const book = signal<Book>(initial());
export const today = () => new Date().toISOString().slice(0, 10);
/** Apply a change to a copy; commit only if it does not throw, so a failed edit changes nothing. */
export function mutate(fn: (b: Book) => void): string | null {
  const next = structuredClone(book.value);
  try { fn(next); } catch (e) { return (e as Error).message; }
  book.value = next;
  try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* ignore */ }
  return null;
}
export const dec = computed(() => Object.fromEntries(book.value.currencies.map(c => [c.code, c.decimals])));

export const dialog = signal<{ msg: string; ok: string; resolve: (v: boolean) => void } | null>(null);
/** In-page confirmation (browser confirm() is unavailable in some hosts). */
export const ask = (msg: string, ok = 'Delete') => new Promise<boolean>(resolve => { dialog.value = { msg, ok, resolve }; });
export function clearSample() {
  mutate(b => { b.accounts = []; b.tx = []; b.parties = []; b.projects = []; b.recurring = []; b.settings.sample = false; });
}
/** Saves a text file: host download capability if present, otherwise a normal browser download. */
export async function saveFile(filename: string, text: string): Promise<string> {
  try {
    const c = (globalThis as any).claude;
    if (c?.use) { const dl = await c.use('downloads'); if (dl) { await dl.save({ filename, data: text }); return 'Backup ready.'; } }
  } catch (e: any) { if (e?.code === 'declined') return 'Save cancelled.'; }
  const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' })); a.download = filename; a.click();
  return 'Backup saved to your downloads.';
}
