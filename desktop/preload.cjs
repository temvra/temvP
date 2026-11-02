// Lo único que la web puede pedirle al escritorio
const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('VTDesktop',{audioFocus:(mode,ms,level)=>ipcRenderer.send('audio-focus',mode,ms,level),tts:(text,lang,pitch,rate)=>ipcRenderer.invoke('tts',text,lang,pitch,rate)});
