// Temvra para escritorio: abre el mismo www/ que Android en una ventana propia.
const {app,BrowserWindow,Menu,Tray,ipcMain,net,protocol,shell}=require('electron');
const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url'),{execFile,execFileSync}=require('node:child_process');
const www=path.join(__dirname,'www'),ORIGIN='app://temvra';
// Origen propio y fijo: localStorage (los datos) no depende de dónde esté instalada la app
protocol.registerSchemesAsPrivileged([{scheme:'app',privileges:{standard:true,secure:true,supportFetchAPI:true,stream:true}}]);
app.commandLine.appendSwitch('ozone-platform-hint','auto');
// Windows solo enseña las notificaciones de apps con este identificador
if(process.platform==='win32')app.setAppUserModelId('com.temvra.app.personal.desktop');
// El temporizador y las alarmas deben sonar sin haber tocado antes la ventana
app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required');
let win=null,tray=null,quitting=false;
// En Windows la app sigue en la bandeja al cerrar la ventana: sin el motor de Android, los avisos los da este proceso
const useTray=process.platform==='win32',ICON=path.join(__dirname,'icon-512.png'),LOGIN={args:['--hidden']};
function showWin(){if(!win){open();return}if(win.isMinimized())win.restore();win.show();win.focus()}
function quit(){quitting=true;app.quit()}
function trayMenu(){tray.setContextMenu(Menu.buildFromTemplate([
  {label:'Abrir Temvra',click:showWin},
  {label:'Iniciar con Windows',type:'checkbox',checked:app.getLoginItemSettings(LOGIN).openAtLogin,click:i=>{app.setLoginItemSettings({...LOGIN,openAtLogin:i.checked});trayMenu()}},
  {type:'separator'},
  {label:'Salir',click:quit}]))}
function setupTray(){tray=new Tray(ICON);tray.setToolTip('Temvra');tray.on('click',showWin);trayMenu()}
// Solo la primera vez: si después se desactiva desde la bandeja, no vuelve a activarse. Un perfil de pruebas no debe tocar el inicio de Windows
function firstRunLogin(){
  if(!app.isPackaged||app.commandLine.hasSwitch('user-data-dir'))return;
  const f=path.join(app.getPath('userData'),'inicio-configurado');
  if(fs.existsSync(f))return;
  app.setLoginItemSettings({...LOGIN,openAtLogin:true});
  try{fs.writeFileSync(f,'1')}catch(e){}
}
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
// La alarma se queda encima de todo hasta que se para, porque Windows no deja quitarle el foco a otra app. Los demás avisos aparecen sin molestar
ipcMain.on('attention',(e,kind)=>{
  if(!win||!e.senderFrame||!e.senderFrame.url.startsWith(ORIGIN+'/'))return;
  if(kind==='alarm'){showWin();win.setAlwaysOnTop(true);return}
  if(kind==='end'){win.setAlwaysOnTop(false);return}
  if(win.isMinimized())win.restore();else if(!win.isVisible())win.showInactive();else if(!win.isFocused())win.flashFrame(true);
});
// Actualizaciones: la web encuentra la versión nueva en GitHub y aquí se descarga e instala
const fromApp=e=>e.senderFrame&&e.senderFrame.url.startsWith(ORIGIN+'/');
ipcMain.on('app-version',e=>{e.returnValue=app.getVersion()});
function fetchTo(url,token,file,hops=0){return new Promise((ok,ko)=>{
  const https=require('node:https'),h={'User-Agent':'Temvra',Accept:'application/octet-stream'};if(token)h.Authorization='Bearer '+token;
  https.get(url,{headers:h},res=>{
    // El enlace firmado al que redirige GitHub no admite el token
    if(res.statusCode>=300&&res.statusCode<400&&res.headers.location&&hops<5){res.resume();fetchTo(res.headers.location,null,file,hops+1).then(ok,ko);return}
    if(res.statusCode!==200){res.resume();ko(new Error('http '+res.statusCode));return}
    const tmp=file+'.part',out=fs.createWriteStream(tmp);res.pipe(out);
    out.on('finish',()=>out.close(()=>{try{fs.renameSync(tmp,file);ok(file)}catch(err){ko(err)}}));out.on('error',ko);
  }).on('error',ko)})}
ipcMain.handle('upd-download',async(e,url,token,name)=>{
  if(!fromApp(e)||!/^https:\/\/api\.github\.com\//.test(String(url)))return {error:'url'};
  const dir=path.join(app.getPath('userData'),'updates');fs.mkdirSync(dir,{recursive:true});
  const file=path.join(dir,path.basename(String(name||'Temvra-update')));
  try{await fetchTo(url,token,file);return {path:file}}catch(err){return {error:String(err.message||err)}}});
ipcMain.handle('upd-install',async(e,file)=>{
  if(!fromApp(e))return {error:'origin'};file=String(file||'');
  const dir=path.join(app.getPath('userData'),'updates');if(!file.startsWith(dir+path.sep)||!fs.existsSync(file))return {error:'missing'};
  try{
    if(process.platform==='win32'){const err=await shell.openPath(file);if(err)return {error:err};setTimeout(quit,1500);return {ok:true}}
    // Linux con AppImage: se sustituye el archivo y se vuelve a abrir
    if(process.env.APPIMAGE){const dst=process.env.APPIMAGE,tmp=dst+'.new';fs.copyFileSync(file,tmp);fs.chmodSync(tmp,0o755);fs.renameSync(tmp,dst);app.relaunch({execPath:dst});quitting=true;app.exit(0);return {ok:true}}
    shell.showItemInFolder(file);return {ok:true,shown:true}
  }catch(err){return {error:String(err.message||err)}}});
function open(hidden){
  win=new BrowserWindow({show:!hidden,width:1280,height:820,minWidth:360,minHeight:500,backgroundColor:'#000000',icon:ICON,webPreferences:{backgroundThrottling:false,preload:path.join(__dirname,'preload.cjs')}});
  win.on('close',e=>{if(tray&&!quitting){e.preventDefault();win.hide()}});
  win.on('session-end',()=>{quitting=true});
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
  app.on('second-instance',(e,argv)=>{if(!argv.includes('--hidden'))showWin()});
  app.on('before-quit',()=>{quitting=true});
  app.whenReady().then(()=>{
    Menu.setApplicationMenu(null);
    protocol.handle('app',req=>{
      const f=path.normalize(path.join(www,decodeURIComponent(new URL(req.url).pathname)));
      if(f!==www&&!f.startsWith(www+path.sep))return new Response('',{status:403});
      return net.fetch(pathToFileURL(f).toString());
    });
    if(useTray){setupTray();firstRunLogin()}
    open(useTray&&process.argv.includes('--hidden'));
  });
  app.on('window-all-closed',()=>{if(!tray)app.quit()});
}
