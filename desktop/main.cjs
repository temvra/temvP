// Temvra para escritorio: abre el mismo www/ que Android en una ventana propia.
const {app,BrowserWindow,Menu,ipcMain,net,protocol,shell}=require('electron');
const path=require('node:path'),{pathToFileURL}=require('node:url'),{execFile,execFileSync}=require('node:child_process');
const www=path.join(__dirname,'www'),ORIGIN='app://temvra';
// Origen propio y fijo: localStorage (los datos) no depende de dónde esté instalada la app
protocol.registerSchemesAsPrivileged([{scheme:'app',privileges:{standard:true,secure:true,supportFetchAPI:true,stream:true}}]);
app.commandLine.appendSwitch('ozone-platform-hint','auto');
// Windows solo enseña las notificaciones de apps con este identificador
if(process.platform==='win32')app.setAppUserModelId('com.temvra.app.personal.desktop');
// El temporizador y las alarmas deben sonar sin haber tocado antes la ventana
app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required');
let win=null;
const external=u=>{if(/^(https?:|mailto:)/i.test(u))shell.openExternal(u)};
// Música de otras apps durante un aviso, como el foco de audio de Android. Usa pactl y las órdenes MPRIS del reproductor; si faltan, no hace nada
const run=(c,a)=>new Promise(r=>execFile(c,a,{timeout:3000},(e,o)=>r(e?null:o)));
const mpris=(n,m,...a)=>run('dbus-send',['--session','--print-reply','--dest='+n,'/org/mpris/MediaPlayer2',m,...a]);
const ducked=new Map();let paused=[],audioT=null,audioQ=Promise.resolve();
async function duck(level){
  let list=[];try{list=JSON.parse(await run('pactl',['-f','json','list','sink-inputs'])||'[]')}catch(e){}
  const own=new Set(app.getAppMetrics().map(m=>String(m.pid)));
  for(const x of list){
    // Si ya está bajado, se conserva el volumen original guardado
    if(x.corked||ducked.has(x.index)||own.has(String(x.properties['application.process.id'])))continue;
    const vol=Object.values(x.volume).map(c=>c.value);if(!vol.length)continue;
    ducked.set(x.index,vol);
    await run('pactl',['set-sink-input-volume',String(x.index),...vol.map(v=>String(Math.round(v*level/100)))]);
  }
}
async function pause(){
  const names=(await run('dbus-send',['--session','--print-reply','--dest=org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus.ListNames'])||'').match(/org\.mpris\.MediaPlayer2\.[^"]+/g)||[];
  for(const n of names){
    const st=await mpris(n,'org.freedesktop.DBus.Properties.Get','string:org.mpris.MediaPlayer2.Player','string:PlaybackStatus');
    if(st&&st.includes('Playing')&&await mpris(n,'org.mpris.MediaPlayer2.Player.Pause')!==null&&!paused.includes(n))paused.push(n);
  }
}
async function restoreAudio(){
  for(const [id,vol] of ducked)await run('pactl',['set-sink-input-volume',String(id),...vol.map(String)]);
  ducked.clear();
  for(const n of paused)await mpris(n,'org.mpris.MediaPlayer2.Player.Play');
  paused=[];
}
// Al salir no se puede esperar: si la app se cierra durante un aviso, la música no debe quedarse baja
function restoreAudioNow(){for(const [id,vol] of ducked){try{execFileSync('pactl',['set-sink-input-volume',String(id),...vol.map(String)],{timeout:1000})}catch(e){}}ducked.clear();for(const n of paused){try{execFileSync('dbus-send',['--session','--dest='+n,'/org/mpris/MediaPlayer2','org.mpris.MediaPlayer2.Player.Play'],{timeout:1000})}catch(e){}}paused=[]}
ipcMain.on('audio-focus',(e,mode,ms,level)=>{
  if(!e.senderFrame||!e.senderFrame.url.startsWith(ORIGIN+'/'))return;
  if(mode==='release'){clearTimeout(audioT);audioQ=audioQ.then(restoreAudio).catch(()=>{});return}
  const t=Math.min(60000,Math.max(500,+ms||3000)),l=Math.min(100,Math.max(0,+level||0));
  audioQ=audioQ.then(()=>mode==='pause'?pause():duck(l)).catch(()=>{});
  clearTimeout(audioT);audioT=setTimeout(()=>{audioQ=audioQ.then(restoreAudio).catch(()=>{})},t);
});
app.on('will-quit',()=>{clearTimeout(audioT);restoreAudioNow()});
// Electron no trae voces en Linux: la voz la genera espeak-ng y la reproduce la web, con el volumen de la app
const VOICES={'es-MX':'es-419','en-US':'en-us','en-GB':'en-gb','pt-BR':'pt-br','zh-CN':'cmn'};
ipcMain.handle('tts',(e,text,lang,pitch,rate)=>new Promise(r=>{
  if(!e.senderFrame||!e.senderFrame.url.startsWith(ORIGIN+'/'))return r(null);
  const l=String(lang||'es-ES'),p=Math.round(Math.min(99,Math.max(0,(+pitch||1)*50))),s=Math.round(Math.min(450,Math.max(80,(+rate||1)*175)));
  const c=execFile('espeak-ng',['-v',VOICES[l]||l.slice(0,2),'-p',String(p),'-s',String(s),'-b','1','--stdin','--stdout'],{encoding:'buffer',maxBuffer:32e6,timeout:10000},(err,out)=>{
    if(err||!out||out.length<44)return r(null);
    // Por la salida estándar la cabecera WAV no lleva el tamaño real
    const d=out.indexOf('data');if(d<0)return r(null);
    out.writeUInt32LE(out.length-8,4);out.writeUInt32LE(out.length-d-8,d+4);r(out);
  });
  c.on('error',()=>r(null));c.stdin.on('error',()=>{});c.stdin.end(String(text||'').slice(0,500));
}));
function open(){
  win=new BrowserWindow({width:1280,height:820,minWidth:360,minHeight:500,backgroundColor:'#000000',icon:path.join(__dirname,'icon-512.png'),webPreferences:{backgroundThrottling:false,preload:path.join(__dirname,'preload.cjs')}});
  win.on('closed',()=>{win=null});
  const wc=win.webContents;
  wc.setWindowOpenHandler(({url})=>{external(url);return{action:'deny'}});
  wc.on('will-navigate',(e,u)=>{if(!u.startsWith(ORIGIN+'/')){e.preventDefault();external(u)}});
  wc.on('context-menu',(_,p)=>{
    const t=p.isEditable?[{role:'cut',label:'Cortar'},{role:'copy',label:'Copiar'},{role:'paste',label:'Pegar'},{type:'separator'},{role:'selectAll',label:'Seleccionar todo'}]:p.selectionText?[{role:'copy',label:'Copiar'}]:[];
    if(t.length)Menu.buildFromTemplate(t).popup({window:win})});
  win.loadURL(ORIGIN+'/index.html');
}
if(!app.requestSingleInstanceLock())app.quit();
else{
  app.on('second-instance',()=>{if(win){if(win.isMinimized())win.restore();win.focus()}});
  app.whenReady().then(()=>{
    Menu.setApplicationMenu(null);
    protocol.handle('app',req=>{
      const f=path.normalize(path.join(www,decodeURIComponent(new URL(req.url).pathname)));
      if(f!==www&&!f.startsWith(www+path.sep))return new Response('',{status:403});
      return net.fetch(pathToFileURL(f).toString());
    });
    open();
  });
  app.on('window-all-closed',()=>app.quit());
}
