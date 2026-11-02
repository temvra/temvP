/* Temvra · puente con Android (Capacitor).
   Programa las notificaciones (alarmas, eventos, bloques y temporizador) cuando la app pasa a segundo plano,
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
    async save(name,blob){try{await FS.writeFile({path:'Temvra/'+safe(name),data:await b64(blob),directory:'DOCUMENTS',recursive:true});api()&&api().toast('Guardado en Documentos/Temvra/'+safe(name))}catch(e){this.share(name,blob,name)}},
    /* Plugin propio (TemvraPlugin.java): música de otras apps, notificación del temporizador y widgets */
    hasPlugin:!!TV,
    audioFocus(mode,ms){if(TV)TV.audioFocus({mode:mode||'duck',ms:ms||3000}).catch(()=>{})},
    timerNotify(st){if(!TV)return;(st&&st.show?TV.timerNotify(st):TV.timerClear()).catch(()=>{})},
    widgets(data){if(TV&&data)TV.widgets({data}).catch(()=>{})}
  };
  if(TV){TV.addListener('action',e=>{const go=()=>api()?api().timerAction(e.action):setTimeout(go,300);go()})}
  const pushWidgets=()=>{const a=api();if(a&&a.widgetData)window.VTNative.widgets(a.widgetData())};
  setInterval(pushWidgets,60000);

  /* ---------- Notificaciones ---------- */
  let ready=false;
  async function setup(){if(!LN)return;
    try{let p=await LN.checkPermissions();if(p.display!=='granted')p=await LN.requestPermissions();
      await LN.createChannel({id:'alarms',name:'Alarmas',description:'Alarmas de Temvra',importance:5,visibility:1,vibration:true,sound:undefined});
      await LN.createChannel({id:'reminders',name:'Eventos, tareas y bloques',description:'Avisos del calendario',importance:4,visibility:1,vibration:true});
      await LN.createChannel({id:'timer',name:'Temporizador',description:'Fin de cada vuelta',importance:5,visibility:1,vibration:true});
      await LN.registerActionTypes({types:[{id:'REM',actions:[{id:'snooze',title:'Posponer 5 min'},{id:'dismiss',title:'Ignorar',destructive:true}]},{id:'ALARM',actions:[{id:'snooze',title:'Posponer 5 min'},{id:'dismiss',title:'Parar',destructive:true}]}]});
      try{const ex=await LN.checkExactNotificationSetting();if(ex&&ex.exact_alarm!=='granted'&&!localStorage.getItem('vt_exact_asked')){localStorage.setItem('vt_exact_asked','1');await LN.changeExactNotificationSetting()}}catch(e){}
      ready=p.display==='granted'}catch(e){console.warn(e)}}
  async function clearAll(){try{const p=await LN.getPending();if(p.notifications.length)await LN.cancel({notifications:p.notifications.map(n=>({id:n.id}))})}catch(e){}}
  async function schedule(){if(!ready||!api())return;await clearAll();const list=api().upcoming(4);if(!list.length)return;
    try{await LN.schedule({notifications:list.map(n=>({id:hash(n.key),title:n.title,body:n.body,channelId:n.channel,schedule:{at:new Date(n.at),allowWhileIdle:true},actionTypeId:n.channel==='alarms'?'ALARM':n.channel==='reminders'?'REM':undefined,extra:n.extra,ongoing:false,autoCancel:true}))})}catch(e){console.warn('schedule',e)}}
  async function snooze(n){const at=new Date(Date.now()+5*6e4);try{await LN.schedule({notifications:[{id:hash('sz|'+Date.now()),title:n.title,body:'Pospuesto · '+at.toTimeString().slice(0,5),channelId:n.channelId||'reminders',schedule:{at,allowWhileIdle:true},actionTypeId:n.actionTypeId,extra:n.extra}]})}catch(e){}}
  if(LN){LN.addListener('localNotificationActionPerformed',a=>{const n=a.notification||{};
    if(a.actionId==='snooze'){snooze(n);if(api()&&n.extra&&n.extra.type==='event')api().snoozeEvent(n.extra.id,5);return}
    if(a.actionId==='dismiss')return;
    const go=()=>api()?api().open(n.extra):setTimeout(go,300);go()})}

  /* ---------- Ciclo de vida ---------- */
  let bg=false;
  if(App){App.addListener('pause',()=>{bg=true;schedule();pushWidgets()});
    App.addListener('resume',()=>{bg=false;clearAll();theme()});
    App.addListener('backButton',()=>{const a=api();if(a&&a.back())return;App.minimizeApp()})}
  /* Programar también al cambiar cosas, por si Android cierra la app sin avisar */
  let t;const deb=()=>{clearTimeout(t);t=setTimeout(()=>{if(bg)schedule()},800)};
  window.addEventListener('storage',deb);

  /* ---------- Barra de estado ---------- */
  /* La app se dibuja de borde a borde (viewport-fit=cover): solo hay que decirle a Android si los iconos de las barras van claros u oscuros */
  let lastStyle='';
  async function theme(){if(!api())return;try{const th=api().theme(),st=th.dark?'DARK':'LIGHT';if(st===lastStyle)return;lastStyle=st;
    if(P.SystemBars){await P.SystemBars.setStyle({style:st})}
    else if(SB){await SB.setOverlaysWebView({overlay:true});await SB.setStyle({style:st})}}catch(e){lastStyle=''}}
  const boot=()=>{if(!api()){setTimeout(boot,200);return}setup().then(()=>{clearAll()});theme();setInterval(theme,4000);setTimeout(pushWidgets,1500)};
  boot();
})();
