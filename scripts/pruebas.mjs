// Pruebas de la app en Electron sin ventana y con perfiles temporales: no tocan los datos ni el llavero del usuario y no salen a internet.
// Uso: npm test (Linux, con desktop/node_modules instalado). Sale con 1 si algo falla.
import {spawn,execFileSync} from 'node:child_process';import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),app=path.join(root,'desktop'),enlace=path.join(app,'www');
const sleep=ms=>new Promise(r=>setTimeout(r,ms)),temporales=[],abiertas=[];let fallos=0;
const ok=(c,n,d)=>{console.log((c?'✓ ':'✗ ')+n+(c||d===undefined?'':' → '+JSON.stringify(d)));if(!c)fallos++};

async function abre(puerto,[w,h],movil=false){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'temvra-prueba-'));temporales.push(dir);
  const proc=spawn(path.join(app,'node_modules/.bin/electron'),[app,'--ozone-platform=headless','--password-store=basic','--host-rules=MAP * ~NOTFOUND',`--user-data-dir=${dir}`,`--remote-debugging-port=${puerto}`],{stdio:'ignore'});
  let t=null;for(let i=0;i<80&&!t;i++){await sleep(250);try{const l=await (await fetch(`http://127.0.0.1:${puerto}/json/list`)).json();t=l.find(x=>x.type==='page'&&/index\.html/.test(x.url))}catch(e){}}
  if(!t)throw new Error('la app no ha arrancado');
  const ws=new WebSocket(t.webSocketDebuggerUrl);await new Promise((r,k)=>{ws.onopen=r;ws.onerror=k});
  let id=0;const P=new Map(),errores=[];
  ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id&&P.has(m.id)){P.get(m.id)(m);P.delete(m.id)}if(m.method==='Runtime.exceptionThrown'){const d=m.params.exceptionDetails;errores.push((d.exception&&d.exception.description||d.text).split('\n')[0])}};
  const send=(method,params={})=>new Promise(r=>{const i=++id;P.set(i,r);ws.send(JSON.stringify({id:i,method,params}))});
  const ev=async x=>{const r=await send('Runtime.evaluate',{expression:x,awaitPromise:true,returnByValue:true});if(r.result.exceptionDetails)throw new Error(r.result.exceptionDetails.exception?.description||r.result.exceptionDetails.text);return r.result.result.value};
  const raton=(type,x,y,buttons=0,button='none')=>send('Input.dispatchMouseEvent',{type,x,y,button,buttons,clickCount:type==='mouseMoved'?0:1});
  await send('Runtime.enable');await send('Page.enable');await send('Emulation.setDeviceMetricsOverride',{width:w,height:h,deviceScaleFactor:1,mobile:movil});
  const listo=async()=>{for(let i=0;i<60;i++){try{if(await ev(`typeof VTAPI==='object'&&!!document.querySelector('.nav [data-view=cal]')`))return}catch(e){}await sleep(150)}throw new Error('la app no termina de cargar')};
  const recarga=async()=>{await send('Page.reload');await sleep(400);await listo();await sleep(500)};
  const a={ev,raton,errores,recarga,cierra(){try{ws.close()}catch(e){}proc.kill()}};abiertas.push(a);await listo();await sleep(700);return a}

const agenda=`(async()=>{const a=document.querySelector('.nav [data-view=cal]');if(a.getAttribute('aria-current')!=='page')a.click();await new Promise(r=>setTimeout(r,1000));document.querySelector('#cal-vseg [data-cv=month]').click();await new Promise(r=>setTimeout(r,1300))})()`;
const toque=`document.querySelector('#cal-vseg [aria-pressed="false"]').click()`;

async function vacio(a,pc){
  const r=JSON.parse(await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),S=VTAPI.state(),v=[];for(const n of ['time','notes','reg','cal']){document.querySelector('.nav [data-view='+n+']').click();await w(800)}if(${pc})for(const c of ['day','3d','week','month','year','sched','month']){document.querySelector('#cal-vseg [data-cv="'+c+'"]').click();await w(1250);v.push(c)}
    const panel=document.body.classList.contains('deskcal');document.querySelector('.nav [data-view=time]').click();await w(600);document.querySelector('#tabs .tab[data-tab=circ]').click();await w(600);
    return JSON.stringify({porDefecto:{categorias:S.blockCats.length,registros:S.regs.length,pasos:S.circuit.steps.length,plantillas:document.querySelectorAll('#tpl [data-tpl]').length,festivos:!!S.calShow.hol},vistas:v.length,creados:['notes','folders','atts','events','appts','timers','tfolders','myCircuits','myTpl','alarms','habits','dayTpls','log','trash','cities'].filter(k=>S[k]&&S[k].length),imgs:Object.keys(localStorage).filter(k=>/img_/.test(k)).length,panel})})()`));
  ok(!r.creados.length,'perfil vacío: ningún elemento creado',r.creados);ok(!r.imgs,'perfil vacío: ninguna imagen',r.imgs);const d=r.porDefecto;ok(!d.categorias&&!d.registros&&!d.pasos&&!d.plantillas&&!d.festivos,'perfil vacío: nada creado por defecto',d);return r}

/* La visita guiada de la primera vez tapa la pantalla: las pulsaciones reales le llegarían a ella */
async function sinVisita(a){await a.ev(`(async()=>{VTAPI.state().tourDone=1;${toque};await new Promise(r=>setTimeout(r,500))})()`);await a.recarga()}

async function centrar(a){
  await a.ev(agenda);
  const r=JSON.parse(await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),g=document.getElementById('cal-grid'),m=()=>g.style.gridTemplateRows,i=m();document.getElementById('cal-next').click();await w(1000);document.getElementById('cal-next').click();await w(1000);document.getElementById('cal-nowb').click();await w(1500);return JSON.stringify({antes:i,despues:m()})})()`));
  const fila=t=>+t.match(/repeat\(\d+,\s*(\d+)px/)[1];ok(Math.abs(fila(r.antes)-fila(r.despues))<=3,'calendario: al avanzar meses y centrar conserva la altura',r)}

/* El aro de arcoíris que se ve dentro de la esfera es el halo curvado: el recorte junto a un borde no puede llegar a él */
async function aro(a,vertical){
  const [cx,cy]=await a.ev(`(()=>{const r=document.querySelector('.nav-in button[data-view=cal]').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()`);
  await a.raton('mouseMoved',cx,cy);await a.raton('mousePressed',cx,cy,1,'left');await sleep(900);
  const r=await a.ev(`(()=>{const g=document.querySelector('.lg-hglow');if(!g)return null;const R=parseFloat(g.style.getPropertyValue('--hR')),c=(g.style.clipPath.match(/-?[\\d.]+px|0(?= |\\))/g)||[]).map(parseFloat);return {R,c,esfera:g.classList.contains('sph')}})()`);
  ok(r&&r.esfera,'gota: al pulsar se forma la esfera',r);
  if(r){const anillo=r.R*.26-.01,cortes=r.c.filter(v=>v>-anillo);ok(!cortes.length,'gota: el recorte del halo no corta el aro',r)}
  const sc=await a.ev(`(()=>{const L=document.querySelector('.nav-in .lg-lens');return {will:getComputedStyle(L).willChange,tr:L.style.transform}})()`);ok(sc.will==='auto'&&!/3d/.test(sc.tr),'gota: sin capa propia que deforme los iconos de al lado',sc);
  /* Se suelta tras moverla, para que no cuente como tocar la pestaña activa */
  const dx=vertical?0:-14,dy=vertical?-14:0;await a.raton('mouseMoved',cx+dx,cy+dy,1,'left');await sleep(50);await a.raton('mouseMoved',cx,cy,1,'left');await sleep(50);await a.raton('mouseReleased',cx,cy,0,'left');await sleep(800)}

async function guardado(a){
  const idb=`new Promise((ok,ko)=>{const r=indexedDB.open('vt-state',1);r.onupgradeneeded=()=>r.result.createObjectStore('s');r.onsuccess=()=>{const q=r.result.transaction('s').objectStore('s').get('state');q.onsuccess=()=>{ok(q.result?q.result.length:0);r.result.close()};q.onerror=()=>ko(q.error)};r.onerror=()=>ko(r.error)})`;
  await a.ev(agenda);
  await a.ev(`(async()=>{const t='x'.repeat(1<<19);for(let n=0;n<400;n++){try{localStorage.setItem('vt_lleno_'+n,t)}catch(e){break}}const p='x'.repeat(4096);for(let m=0;m<600;m++){try{localStorage.setItem('vt_llenop_'+m,p)}catch(e){break}}const S=VTAPI.state();S.pruebaMarca='uno';S.pruebaRelleno='y'.repeat(4e5);${toque};await new Promise(r=>setTimeout(r,1600))})()`);
  const r=await a.ev(`(async()=>({bandera:localStorage.getItem('vt2_stidb'),copia:localStorage.getItem('vt2_state')!==null,idb:await ${idb}}))()`);
  ok(r.bandera==='1'&&!r.copia&&r.idb>4e5,'guardado: si no cabe en localStorage pasa a IndexedDB',r);
  await a.ev(`(async()=>{VTAPI.state().pruebaMarca2='dos';${toque};await new Promise(r=>setTimeout(r,800))})()`);
  await a.recarga();
  const s=await a.ev(`({m:VTAPI.state().pruebaMarca,m2:VTAPI.state().pruebaMarca2,r:(VTAPI.state().pruebaRelleno||'').length})`);
  ok(s.m==='uno'&&s.m2==='dos'&&s.r===4e5,'guardado: los datos siguen ahí al reiniciar',s);
  await a.ev(`(()=>{for(let i=localStorage.length-1;i>=0;i--){const k=localStorage.key(i);if(k.startsWith('vt_llen'))localStorage.removeItem(k)}})()`)}

async function token(a){
  await a.ev(`(async()=>{const S=VTAPI.state();S.gh=Object.assign(S.gh||{links:{}},{token:'de_prueba',login:'qa'});${toque};await new Promise(r=>setTimeout(r,700))})()`);
  await a.recarga();
  ok(await a.ev(`VTAPI.state().gh.token==='de_prueba'`),'token: sin llavero en el sistema se conserva como antes')}

/* Encabezado del día en una nota que ya existe: va tras las propiedades, con la agenda de ese día fija, y no se repite */
async function encabezado(a){
  await a.ev(`(async()=>{VTAPI.state().notes.unshift({id:'nqa1',title:'Prueba',folder:'',pinned:false,created:Date.now(),updated:Date.now(),body:'---\\ntags: qa\\n---\\nTexto de prueba para seleccionar con el ratón. Es una línea larga que ocupa buena parte del ancho de la nota.\\n\\nOtro párrafo con más texto.\\n'});${toque};await new Promise(r=>setTimeout(r,500))})()`);
  await a.recarga();
  await a.ev(`(async()=>{document.querySelector('.nav [data-view=notes]').click();await new Promise(r=>setTimeout(r,900));const b=document.querySelector('[data-note="nqa1"],[data-id="nqa1"],[data-nid="nqa1"]');b&&b.click();await new Promise(r=>setTimeout(r,900))})()`);
  const hoy=`(async()=>{document.getElementById('nm-dayh').click();await new Promise(r=>setTimeout(r,600));const b=[...document.querySelectorAll('.sheet-back:not([hidden]) button')].find(x=>/^Hoy/.test(x.textContent.trim()));b&&b.click();await new Promise(r=>setTimeout(r,800))})()`;
  await a.ev(hoy);
  const r=await a.ev(`(()=>{const n=VTAPI.state().notes.find(x=>x.id==='nqa1'),p=v=>String(v).padStart(2,'0'),d=new Date(),k=d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate()),b=document.querySelector('#ne-read .agenda-blk');return {tras:/^---\\ntags: qa\\n---\\n# \\S/.test(n.body),agenda:n.body.includes('\`\`\`agenda '+k),bloque:!!b&&b.dataset.k===k}})()`);
  ok(r.tras&&r.agenda&&r.bloque,'notas: encabezado del día tras las propiedades, con la agenda de ese día',r);
  await a.ev(hoy);
  ok(await a.ev(`(VTAPI.state().notes.find(x=>x.id==='nqa1').body.match(/^# /gm)||[]).length===1`),'notas: el encabezado del mismo día no se repite')}

/* Con ratón, ni burbuja ni gel al pulsar o seleccionar texto; fuera del texto sí (sigue en la nota abierta por encabezado()) */
async function sobreTexto(a){
  const p=await a.ev(`(()=>{const ps=[...document.querySelectorAll('#ne-read p')],q=ps.find(x=>/Texto de prueba/.test(x.textContent)),q2=ps.find(x=>/Otro párrafo/.test(x.textContent)).getBoundingClientRect(),r=document.createRange();r.selectNodeContents(q.firstChild);const b=r.getClientRects()[0],c=document.getElementById('ne-read').getBoundingClientRect();return {tx:b.left+40,ty:b.top+b.height/2,vx:c.right-30,vy:q2.bottom+60}})()`);
  const huecos=()=>a.ev(`document.querySelectorAll('.lg-hole').length`);
  await a.raton('mouseMoved',p.tx,p.ty);await a.raton('mousePressed',p.tx,p.ty,1,'left');await sleep(350);const enTexto=await huecos();
  for(let i=1;i<=6;i++){await a.raton('mouseMoved',p.tx+i*25,p.ty,1,'left');await sleep(30)}await sleep(250);const arrastrando=await huecos(),sel=await a.ev(`getSelection().toString().length`);
  await a.raton('mouseReleased',p.tx+150,p.ty,0,'left');await sleep(600);await a.ev(`getSelection().removeAllRanges()`);
  await a.raton('mouseMoved',p.vx,p.vy);await a.raton('mousePressed',p.vx,p.vy,1,'left');await sleep(350);const fuera=await huecos();
  await a.raton('mouseReleased',p.vx,p.vy,0,'left');await sleep(800);
  ok(!enTexto&&!arrastrando&&sel>0&&fuera===1,'PC: sin burbuja al pulsar o seleccionar texto con el ratón, sí fuera del texto',{enTexto,arrastrando,sel,fuera})}

/* Un aviso que sale tarde (PC dormido, app cerrada) dice cuánto hace que empezó, no lo que faltaba */
async function avisoTarde(a){
  const r=await a.ev(`(async()=>{const S=VTAPI.state(),p=v=>String(v).padStart(2,'0'),st=new Date(Date.now()-20*6e4);st.setSeconds(0,0);const hm=d=>p(d.getHours())+':'+p(d.getMinutes()),dk=d=>d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate());
    S.events.push({id:'eqa2',kind:'event',title:'Reunión',date:dk(st),start:hm(st),end:hm(new Date(st.getTime()+36e5)),allDay:false,remind:30,done:false});await new Promise(r=>setTimeout(r,2500));
    return {texto:document.getElementById('bn-sub').textContent,visible:!document.getElementById('banner').hidden,fila:document.getElementById('avrf-row').hidden}})()`);
  ok(r.visible&&/^Hace 2[01] min · a las \d\d:\d\d$/.test(r.texto),'avisos: si salen tarde dicen cuánto hace que empezó',r);
  ok(r.fila,'avisos: «Actualizar avisos cada» solo sale en Android')}

/* Sin categorías también se puede crear un bloque: se toca la línea de tiempo y se elige Bloque */
async function bloque(a,x,y){
  await a.ev(`(async()=>{document.querySelector('.nav [data-view=cal]').getAttribute('aria-current')==='page'||document.querySelector('.nav [data-view=cal]').click();await new Promise(r=>setTimeout(r,900));document.querySelector('#cal-vseg [data-cv=day]').click();await new Promise(r=>setTimeout(r,1300))})()`);
  await a.raton('mouseMoved',x,y);await a.raton('mousePressed',x,y,1,'left');await sleep(60);await a.raton('mouseReleased',x,y,0,'left');await sleep(900);
  const r=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),b=[...document.querySelectorAll('.sheet-back:not([hidden]) button')].find(x=>/Bloque/.test(x.textContent));if(!b)return {menu:false};b.click();await w(700);document.getElementById('bks-save').click();await w(900);const e=VTAPI.state().events.at(-1);return {menu:true,cat:e&&e.cat,enPantalla:document.querySelectorAll('#v-blocks .bk-b').length}})()`);
  ok(r.menu&&r.cat&&r.enPantalla>0,'sin categorías: se puede crear un bloque y se ve',r)}

/* Instalación antigua con copias repetidas y objetos de ejemplo sin usar */
async function migracion(a){
  await a.ev(`(()=>{const st=JSON.parse(localStorage.getItem('vt2_state')),ev=(id,t,s,x)=>Object.assign({id,done:false,kind:'event',cat:'gym',title:t,date:'2026-11-01',start:s,end:'23:00',allDay:false,notes:'',remind:-1,ag:'a1'},x||{});st.uiV=10;delete st.noDiary;st.blockCats=[{id:'gym',name:'Gimnasio',emoji:'x',color:'#f2622e'},{id:'rest',name:'Descanso',emoji:'x',color:'#2fb35b'}];st.regs=[{id:'diario',kind:'diary',name:'Diario',days:{}}];st.circuit={name:'Abdominales',rounds:2,roundRest:45,steps:[['work','Crunch',30],['rest','Descanso',15],['work','Elevación de piernas',30],['rest','Descanso',15],['work','Plancha lateral',30],['rest','Descanso',15],['work','Bicicleta',30]].map(([type,name,sec],i)=>({id:'s'+i,type,name,sec}))};st.events=[ev('bkmw4e533v','Gimnasio','02:45'),ev('bkmw4e59pt65','Gimnasio','01:15'),ev('bkmw4e59qs76','Gimnasio','01:15'),ev('bkmw2v8qcw','Gimnasio','04:45',{date:'2026-10-30'}),ev('bkmw277m12','Pierna','10:00'),ev('bkmw277m13','Pierna','12:00')];localStorage.setItem('vt2_state',JSON.stringify(st))})()`);
  await a.recarga();
  const r=await a.ev(`(S=>({uiV:S.uiV,eventos:S.events.map(e=>e.title+e.start),cats:S.blockCats.map(c=>c.id),regs:S.regs.length,pasos:S.circuit.steps.length}))(VTAPI.state())`);
  ok(r.uiV===11&&JSON.stringify(r.eventos)===JSON.stringify(['Gimnasio02:45','Gimnasio04:45','Pierna10:00','Pierna12:00']),'actualización: quita las copias repetidas y conserva lo demás',r);
  ok(JSON.stringify(r.cats)==='["gym"]'&&!r.regs&&!r.pasos,'actualización: quita lo de ejemplo que no se ha usado',r)}

execFileSync(process.execPath,[path.join(root,'scripts/build-www.mjs')],{stdio:'ignore'});
const yaHabia=fs.existsSync(enlace);if(!yaHabia)fs.symlinkSync('../www',enlace);
try{
  console.log('— PC 1440×900');
  const pc=await abre(9701,[1440,900]);
  const r=await vacio(pc,true);ok(r.vistas===7&&r.panel,'PC: todas las vistas de la Agenda y panel de días junto al Mes',r);
  await sinVisita(pc);await centrar(pc);await aro(pc,true);await guardado(pc);await token(pc);
  await encabezado(pc);await sobreTexto(pc);await avisoTarde(pc);
  ok(!pc.errores.length,'PC: sin errores de JavaScript',pc.errores);pc.cierra();
  console.log('— Móvil 412×915');
  const mv=await abre(9702,[412,915],true);
  await vacio(mv,false);ok(!await mv.ev(`document.body.classList.contains('deskcal')`),'móvil: sin panel de días');
  await sinVisita(mv);await centrar(mv);await aro(mv,false);await bloque(mv,200,520);await migracion(mv);
  ok(!mv.errores.length,'móvil: sin errores de JavaScript',mv.errores);mv.cierra();
}catch(e){fallos++;console.error('✗ Prueba interrumpida:',e.message)}
finally{abiertas.forEach(a=>a.cierra());await sleep(400);if(!yaHabia)try{fs.unlinkSync(enlace)}catch(e){}for(const d of temporales)try{fs.rmSync(d,{recursive:true,force:true})}catch(e){}}
console.log(fallos?`\n${fallos} prueba(s) fallida(s)`:'\nTodo correcto');process.exit(fallos?1:0);
