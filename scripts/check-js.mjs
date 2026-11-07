// Comprueba la sintaxis de todo el JavaScript de la app: los <script> de src/app.html y src/widget.html,
// src/native.js y los archivos de Electron. Uso: node scripts/check-js.mjs  (sale con 1 si hay errores)
import fs from 'node:fs';import path from 'node:path';import os from 'node:os';import {execFileSync} from 'node:child_process';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),tmp=fs.mkdtempSync(path.join(os.tmpdir(),'temvra-chk-'));let bad=0;
const check=(name,file)=>{try{execFileSync(process.execPath,['--check',file],{stdio:'pipe'})}catch(e){bad++;console.error(`✗ ${name}\n${String(e.stderr||e.message).replace(file,name)}`)}};
for(const f of ['src/app.html','src/widget.html']){const p=path.join(root,f);if(!fs.existsSync(p))continue;
  const s=fs.readFileSync(p,'utf8'),blocks=[...s.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
  const out=path.join(tmp,path.basename(f)+'.js');fs.writeFileSync(out,blocks.join('\n;\n'));check(f+' (scripts)',out)}
for(const f of ['src/native.js','desktop/main.cjs','desktop/preload.cjs','desktop/preload-widget.cjs','scripts/build-www.mjs']){const p=path.join(root,f);if(fs.existsSync(p))check(f,p)}
fs.rmSync(tmp,{recursive:true,force:true});
if(bad){console.error(`${bad} archivo(s) con errores de sintaxis`);process.exit(1)}else console.log('✓ JavaScript sin errores de sintaxis');
