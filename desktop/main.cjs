// Temvra para escritorio: abre el mismo www/ que Android en una ventana propia.
const {app,BrowserWindow,Menu,net,protocol,shell}=require('electron');
const path=require('node:path'),{pathToFileURL}=require('node:url');
const www=path.join(__dirname,'www'),ORIGIN='app://temvra';
// Origen propio y fijo: localStorage (los datos) no depende de dónde esté instalada la app
protocol.registerSchemesAsPrivileged([{scheme:'app',privileges:{standard:true,secure:true,supportFetchAPI:true,stream:true}}]);
app.commandLine.appendSwitch('ozone-platform-hint','auto');
// El temporizador y las alarmas deben sonar sin haber tocado antes la ventana
app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required');
let win=null;
const external=u=>{if(/^(https?:|mailto:)/i.test(u))shell.openExternal(u)};
function open(){
  win=new BrowserWindow({width:1280,height:820,minWidth:360,minHeight:500,backgroundColor:'#000000',icon:path.join(__dirname,'icon-512.png'),webPreferences:{backgroundThrottling:false}});
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
