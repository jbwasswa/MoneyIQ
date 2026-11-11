"""Builds www/ (the Android web layer) from the desktop page, then Capacitor copies it into the Android project."""
import re,subprocess,os,shutil
R='/home/claude/pocket-ledger-desktop'; A=R+'/android-app'
subprocess.run(['python3',R+'/build.py'],check=True)
h=open(R+'/desktop-app/src/app.html').read()
h=re.sub(r'<meta http-equiv="Content-Security-Policy"[^>]*>','',h)
h=h.replace('<meta name="viewport" content="width=device-width,initial-scale=1">','<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#0F2A25">',1)
i=h.rindex('<script>\n'); assert 'id="pl-main"' not in h
h=h[:i]+'<script type="text/plain" id="pl-main">\n'+h[i+len('<script>\n'):]
h=h.replace('</body>','<script src="bridge.js"></script>\n</body>',1)
os.makedirs(A+'/www',exist_ok=True)
open(A+'/www/index.html','w').write(h)
subprocess.run([A+'/node_modules/.bin/esbuild','src/bridge.js','--bundle','--format=iife','--minify','--outfile=www/bridge.js','--log-level=error'],cwd=A,check=True)
print('www built',len(h))
