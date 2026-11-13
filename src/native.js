/* Temvra · puente con Android (Capacitor).
   Programa los avisos (alarmas, eventos, bloques y temporizador) cuando la app pasa a segundo plano,
   gestiona el botón atrás, compartir/guardar archivos y el color de la barra de estado. */
(function(){
  const C=window.Capacitor;if(!C||!C.isNativePlatform||!C.isNativePlatform())return;
  const P=C.Plugins,LN=P.LocalNotifications,App=P.App,FS=P.Filesystem,SH=P.Share,SB=P.StatusBar,TV=P.Temvra;
  const api=()=>window.VTAPI;
  const hash=s=>{let h=0;for(let i=0;i<s.length;i++)h=(h*31+s.charCodeAt(i))|0;return Math.abs(h)%2000000000+1};
  const b64=blob=>new Promise((ok,ko)=>{const r=new FileReader();r.onload=()=>ok(String(r.result).split(',')[1]);r.onerror=ko;r.readAsDataURL(blob)});
  const safe=n=>String(n||'archivo').replace(/[\\/:*?"<>|]/g,'-');

  /* ---------- Archivos ---------- */
  window.VTNative={
    async share(name,blob,title,text){try{const r=await FS.writeFile({path:safe(name),data:await b64(blob),directory:'CACHE'});await SH.share({title:title||name,text:text||undefined,files:[r.uri],dialogTitle:'Compartir'})}catch(e){if(!/cancel/i.test(String(e&&e.message)))api()&&api().toast('No se pudo compartir: '+(e.message||e))}},
    /* Texto (un enlace de Temvra) con el menú de compartir del sistema */
    async shareText(title,text){try{await SH.share({title:title||'Temvra',text,dialogTitle:'Compartir'})}catch(e){}},
    async save(name,blob){try{await FS.writeFile({path:'Temvra/'+safe(name),data:await b64(blob),directory:'DOCUMENTS',recursive:true});api()&&api().toast('Guardado en Documentos/Temvra/'+safe(name))}catch(e){this.share(name,blob,name)}},
    /* Plugin propio (TemvraPlugin.java): música de otras apps, notificación del temporizador y widgets */
    hasPlugin:!!TV,
    audioFocus(mode,ms){if(TV)TV.audioFocus({mode:mode||'duck',ms:ms||3000}).catch(()=>{})},
    timerNotify(st){if(!TV)return;(st&&st.show?TV.timerNotify(st):TV.timerClear()).catch(()=>{})},
    widgets(data){if(TV&&data)TV.widgets({data}).catch(()=>{})},
    /* Sonido nativo por el canal de alarma, con el volumen elegido en la app */
    bg:false,
    playAlert(o){if(TV)TV.playAlert(o).catch(()=>{})},
    stopAlert(){if(TV)TV.stopAlert().catch(()=>{})},
    saveOwnAudio(data){if(TV&&data)TV.saveOwnAudio({data}).catch(()=>{})},
    permStatus(){return TV?TV.permStatus():Promise.resolve(null)},
    openPerm(which){if(TV)TV.openPerm({which}).catch(()=>{})},
    askNotifications(){return LN?LN.requestPermissions():Promise.resolve(null)}
  };
  if(TV){TV.addListener('action',e=>{const go=()=>api()?api().timerAction(e.action):setTimeout(go,300);go()});
    /* Un .ics o una bóveda abiertos desde WhatsApp u otra app */
    TV.addListener('file',e=>{const go=()=>api()?api().importFile(e):setTimeout(go,300);go()});
    /* Un enlace de otro Temvra (temvra://a?d=…) */
    TV.addListener('link',e=>{const go=()=>api()?api().openLink(e.url):setTimeout(go,300);go()})}
  const pushWidgets=()=>{const a=api();if(a&&a.widgetData)window.VTNative.widgets(a.widgetData())};
  setInterval(()=>{if(!window.VTNative.bg)pushWidgets()},60000);

  /* ---------- Notificaciones ---------- */
  /* Los avisos los programa el plugin propio (Alerts.java): salen y suenan con el móvil bloqueado */
  let ready=false;
  async function setup(){if(!TV)return;
    try{if(LN){let p=await LN.checkPermissions();if(p.display!=='granted')p=await LN.requestPermissions();
        /* Lo que pudiera dejar programado la versión anterior */
        const old=await LN.getPending();if(old.notifications.length)await LN.cancel({notifications:old.notifications.map(n=>({id:n.id}))})}
      ready=true}catch(e){console.warn(e);ready=true}}
  async function clearAll(){if(TV)try{await TV.clearAlerts()}catch(e){}}
  async function schedule(){if(!ready||!api()||!TV)return;
    try{const s=api().state();await TV.scheduleAlerts({list:api().upcoming(7),cfg:{sound:s.sound,soundOn:s.soundOn!==false,vol:s.vol,aviso:s.aviso,vib:s.vib,snooze:s.snooze,refresh:s.avRefresh==null?10:s.avRefresh,tts:s.tts,timer:api().timerSnapshot()}})}catch(e){console.warn('schedule',e)}}
  /* Lo que se haya tocado desde el bloqueo (pausa, reinicio…) y las vueltas que han pasado */
  async function syncTimer(){if(!TV||!api())return;try{api().timerApply(await TV.timerState())}catch(e){}}
  async function syncFired(){if(!TV||!api())return;try{const r=await TV.consumeFired();if(r.fired&&r.fired.length)api().firedAlarms(r.fired)}catch(e){}}

  /* ---------- Ciclo de vida ---------- */
  let bg=false;
  if(App){App.addListener('pause',()=>{bg=true;window.VTNative.bg=true;schedule();pushWidgets()});
    /* Hasta recoger el estado del temporizador que llevaba Android, la app no cierra vueltas por su cuenta: sonarían otra vez y se contarían dos veces */
    App.addListener('resume',()=>{bg=false;let d=0;const done=()=>{if(!d){d=1;window.VTNative.bg=false}};syncTimer().then(clearAll).finally(done);setTimeout(done,1500);syncFired();theme();pushWidgets()});
    App.addListener('backButton',()=>{const a=api();if(a&&a.back())return;App.minimizeApp()})}
  /* Programar también al cambiar cosas, por si Android cierra la app sin avisar */
  let t;const deb=()=>{clearTimeout(t);t=setTimeout(()=>{if(bg)schedule()},800)};
  window.addEventListener('storage',deb);

  /* ---------- Barra de estado ---------- */
  /* La app se dibuja de borde a borde (viewport-fit=cover): solo hay que decirle a Android si los iconos de las barras van claros u oscuros */
  let lastStyle='';
  async function theme(){if(!api())return;try{const th=api().theme(),st=th.dark?'DARK':'LIGHT',key=st+th.bg;if(key===lastStyle)return;lastStyle=key;
    /* La zona de las barras toma el color del tema: si no, los iconos oscuros se pierden sobre negro */
    if(TV)TV.setBars({color:th.hex,dark:th.dark});
    if(!TV&&P.SystemBars){await P.SystemBars.setStyle({style:st})}}catch(e){lastStyle=''}}
  /* Márgenes de las barras del sistema: los pone el lado nativo y se piden al cargar la página */
  if(TV)TV.applyBars().catch(()=>{});
  const boot=()=>{if(!api()){setTimeout(boot,200);return}setup().then(()=>{syncTimer().then(clearAll);syncFired()});theme();setInterval(theme,4000);setTimeout(pushWidgets,1500)};
  boot();
})();
