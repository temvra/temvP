// Lo único que la web puede pedirle al escritorio
const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('VTDesktop',{canDuck:process.platform==='linux',audioFocus:(mode,ms,level)=>ipcRenderer.send('audio-focus',mode,ms,level),tts:(text,lang,pitch,rate)=>ipcRenderer.invoke('tts',text,lang,pitch,rate),attention:kind=>ipcRenderer.send('attention',kind),
  // Actualizaciones desde GitHub
  version:ipcRenderer.sendSync('app-version'),platform:process.platform,appImage:!!process.env.APPIMAGE,
  downloadUpdate:(url,token,name)=>ipcRenderer.invoke('upd-download',url,token,name),installUpdate:file=>ipcRenderer.invoke('upd-install',file)});
