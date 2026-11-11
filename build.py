"""Rebuilds the web test page and the desktop app page from the prototype source plus the core."""
import base64,re,subprocess,sys
R='/home/claude/pocket-ledger-desktop'
SRC=R+'/reference/prototype.html'
ES=R+'/app/node_modules/.bin/esbuild'
subprocess.run([ES,'src/bridge.ts','--bundle','--format=iife','--global-name=PLCore','--minify','--outfile=../desktop-ui/core.bundle.js','--log-level=error'],cwd=R+'/core',check=True)
subprocess.run([ES,'src/native.ts','--bundle','--platform=node','--format=cjs','--outfile=../desktop-app/src/core-native.cjs','--log-level=error'],cwd=R+'/core',check=True)
h=open(SRC).read()
h=h.replace('var LU={};/*LUCIDE_DATA*/',open(R+'/reference/lucide-data.js').read(),1)
def rep(s,a,b):
    assert a in s,a[:60]; return s.replace(a,b,1)
old="function load(){try{var r=localStorage.getItem(KEY);if(r){var o=JSON.parse(r);if(o&&o.tx&&o.accounts&&o.cats&&o.currencies)return o}}catch(e){}return null}\nfunction save(){try{localStorage.setItem(KEY,JSON.stringify(S))}catch(e){}}"
new="""var CORE_KEY='pocket-ledger-core-v1';
function plBanner(msg){var b=document.getElementById('pl-save-banner');
  if(!msg){if(b)b.remove();return}
  if(!b){b=document.createElement('div');b.id='pl-save-banner';b.setAttribute('role','alert');b.style.cssText='position:fixed;left:0;right:0;top:0;z-index:99999;background:#b3261e;color:#fff;padding:10px 16px;font:600 14px system-ui,sans-serif;text-align:center';document.body.appendChild(b)}
  b.textContent=msg}
/* Storage goes through the core: the screens' document is converted to whole minor units, checked, and kept as the core's book. */
function load(){
  try{var b=window.PLNative?PLNative.loadSync():localStorage.getItem(CORE_KEY);if(b&&window.PLCore){var o=PLCore.toPrototype(JSON.parse(b));if(o&&o.tx&&o.accounts&&o.cats&&o.currencies)return o}}catch(e){}
  if(window.PLNative)return null;
  try{var r=localStorage.getItem(KEY);if(r){var o2=JSON.parse(r);if(o2&&o2.tx&&o2.accounts&&o2.cats&&o2.currencies)return o2}}catch(e){}
  return null}
function save(){
  if(!window.PLNative){try{localStorage.setItem(KEY,JSON.stringify(S))}catch(e){}}
  if(!window.PLCore)return;
  try{var c=PLCore.check(S,function(a){return bal(a)});window.PL_CHECK=c.result;
    if(c.book){
      if(window.PLNative){var rs=PLNative.save(JSON.stringify(c.book));plBanner(rs&&rs.ok?'':'Your last change could not be saved to this computer ('+(rs&&rs.error||'unknown error')+'). It is on screen only. Use File > Back up now, then tell the developer.')}
      else localStorage.setItem(CORE_KEY,JSON.stringify(c.book))}
    else{console.warn('Pocket Ledger core check failed; kept the previous saved copy',c.result.problems);plBanner('Your last change was not saved because it did not pass the data checks ('+c.result.problems[0]+'). It is on screen only.')}}
  catch(e){window.PL_CHECK={ok:false,problems:[String(e)]};plBanner('Your last change could not be saved ('+e+').')}}"""
h=rep(h,old,new)
bundle=open(R+'/desktop-ui/core.bundle.js').read().replace('</script','<\\/script')
i=h.index('<script>\n',h.index('</style>'))
h=h[:i]+'<script>'+bundle+'</script>\n'+h[i:]
open(R+'/desktop-ui/pocket-ledger-desktop-ui.html','w').write(h)
# desktop page: fonts built in, strict CSP
def face(fam,pkg,w):
    b=base64.b64encode(open(f'{R}/desktop-app/node_modules/@fontsource/{pkg}/files/{pkg}-latin-{w}-normal.woff2','rb').read()).decode()
    return f"@font-face{{font-family:'{fam}';font-weight:{w};font-style:normal;font-display:swap;src:url(data:font/woff2;base64,{b}) format('woff2')}}"
fonts=''.join(face('Bricolage Grotesque','bricolage-grotesque',w) for w in (500,700))+''.join(face('Instrument Sans','instrument-sans',w) for w in (400,500,600))
d=re.sub(r'<link rel="preconnect"[^>]*>\n','',h); d=re.sub(r'<link href="https://fonts.googleapis.com[^>]*>\n','',d)
d=d.replace('<style>','<style>'+fonts,1)
csp="default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; font-src data:; img-src 'self' data: blob:; connect-src 'none'"
open(R+'/desktop-app/src/app.html','w').write('<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="'+csp+'"></head><body>\n'+d+'\n</body></html>')
print('built',len(h),len(d))
