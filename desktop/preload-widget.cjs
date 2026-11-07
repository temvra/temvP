// El widget «Ahora» solo puede recibir sus datos, ocultarse y abrir la app
const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('VTWidget',{onData:cb=>ipcRenderer.on('widget-data',(e,d)=>cb(d)),hide:()=>ipcRenderer.send('widget-hide'),open:a=>ipcRenderer.send('widget-open',String(a||'agenda'))});
