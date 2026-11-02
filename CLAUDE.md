# Temvra Personal

App personal de Android: temporizador en bucle, circuitos, alarmas, agenda con bloques de trabajo, notas en Markdown y registros. Paquete `com.temvra.app.personal`, repositorio `temvra/temvP`, rama de trabajo `main`.

Antes de seguir, lee `PROGRESS.md` (estado y pendientes) y `DECISIONS.md` (por qué está hecho así). Algunas partes de `README.md` están desactualizadas.

## Tecnologías

- **Interfaz:** HTML, CSS y JavaScript sin framework ni empaquetador, en un solo archivo.
- **Android:** Capacitor 8 con código Java propio. `compileSdk` y `targetSdk` 36, `minSdk` 24.
- **Compilación:** Node 22 en CI, JDK 21 obligatorio, Gradle 8.14.3.
- **Librerías locales** (copiadas a `www/libs`): jsPDF, JSZip y pdf.js. La app funciona sin conexión.

## Estructura

| Ruta | Contenido |
|---|---|
| `src/app.html` | Toda la interfaz: CSS y un único script en una función autoejecutada. Más de 6.000 líneas, muchas muy largas: búscalas con `grep`. |
| `src/native.js` | Puente con Android: avisos, ciclo de vida, barras del sistema y archivos recibidos. |
| `scripts/build-www.mjs` | Genera `www/` desde `src/`. |
| `android/app/src/main/java/com/temvra/app/` | Código nativo (ver abajo). |
| `android/app/src/main/res/` | Layouts de widgets, iconos, previews y `xml/widget_*_info.xml`. |
| `parked/asistente-ia.js` | Asistente con Claude y Gemini, aparcado y fuera de la compilación. |
| `.github/workflows/android.yml` | Al subir a `main`, compila el APK y lo publica en Releases. |

`www/`, `node_modules/`, `android/local.properties` y los assets generados están en `.gitignore`.

### Código nativo

- `TemvraPlugin`: plugin de Capacitor que une JS y Android; también gestiona los márgenes de las barras (`edgeToEdge`, `setBars`).
- `Alerts`, `AlertReceiver`: lista de avisos programados; solo se arma el siguiente. Sobrevive a reinicios.
- `AlarmService`, `AlarmActivity`: alarma sonando y pantalla sobre el bloqueo.
- `AlertSound`: tonos sintetizados, volumen y salida por altavoz o auriculares.
- `TimerCtl`, `TimerMedia`, `TimerService`: temporizador en segundo plano, tarjeta multimedia del bloqueo y su servicio.
- `Widgets`, `WidgetListService`, `WidgetAction`, `WidgetPick`, `WidgetTick`, `*Widget`: widgets. `DayWidget` es el calendario mensual.

### Conceptos del script principal

- **Estado:** objeto `S`, guardado por `save()` en `localStorage` con la clave `vt2_state`. Las claves de `LS` llevan el prefijo `vt2_`.
- **Migraciones:** bloques `if((S.uiV||0)<N)` cerca del final del script. La versión actual es `uiV` 6. Añade una nueva para cambios de datos; no edites las anteriores.
- **Bóvedas:** `S.notes`, `S.folders` y `S.atts` son la bóveda abierta; las demás están en `S.vaultData`.
- **Vistas:** `show(v)` cambia de sección. La navegación inferior tiene `time`, `cal`, `notes` y `reg`.
- **Puente:** `window.VTAPI` lo usa `native.js`; `window.VTNative` lo expone `native.js` a la app. `VTAPI.timerAction(a)` recibe las acciones de widgets y notificaciones.
- **Datos de widgets:** `VTAPI.widgetData()` genera el JSON que lee `Widgets.java`.
- **Orden de inicio:** el código del final se ejecuta de arriba abajo. Una llamada a una función que use un `const` definido más abajo rompe el arranque.

## Comandos

```bash
npm ci                 # instalar dependencias
npm run build          # generar www/
npm run sync           # build + npx cap sync android
npm run apk            # sync + APK de depuración
```

Requisitos locales, no incluidos en el repositorio:

- `android/local.properties` con `sdk.dir=/home/KINGROOT/Android/Sdk`.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`: el `java` por defecto de este equipo es el 27 y Gradle 8.14 no lo soporta.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
npm run apk
~/Android/Sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`adb` no está en el PATH. La firma de CI usa los secretos `KEYSTORE_BASE64` y `KEYSTORE_PASSWORD` de GitHub; sus valores no deben estar en el repositorio.

## Pruebas

No hay pruebas automáticas ni comando `test`. Se comprueba así:

1. **Sintaxis del script:** tras `npm run build`,

   ```bash
   node -e "const h=require('fs').readFileSync('www/index.html','utf8');for(const x of h.matchAll(/<script>([\s\S]*?)<\/script>/g))new Function(x[1]);console.log('ok')"
   ```

2. **Compilación Java:** `cd android && ./gradlew :app:compileDebugJavaWithJavac`.
3. **En el móvil:** la WebView de depuración se inspecciona por CDP:

   ```bash
   adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof com.temvra.app.personal)
   ```

   Con eso se ejecuta JS y se capturan solo pantallas de la app.

## Instrucciones permanentes

- Responde en español, con lenguaje claro.
- **Commits:** mensajes en español que digan qué cambia y por qué. Sin referencias a Claude ni a Anthropic y sin `Co-Authored-By: Claude`. Haz commit o push solo cuando se pida.
- **Nunca guardes** contraseñas, tokens ni claves API en el repositorio ni en estos documentos.
- **Capturas del móvil:** comprueba antes con `dumpsys window | grep mCurrentFocus` que Temvra está en primer plano. Una captura ciega ya recogió una conversación de WhatsApp. Avisa antes de bloquear el móvil o hacer sonar algo.
- **Datos reales:** el móvil de pruebas tiene datos reales. Guarda una copia de `vt2_state` antes de pruebas que escriban. Para restaurar, repón también `S` en memoria y espera unos segundos antes de cerrar la app: `localStorage` se escribe en diferido y `force-stop` puede perder el cambio.
- **Comprueba antes de afirmar:** di qué has visto funcionar y qué no. Los widgets y la pantalla de bloqueo no se pueden validar del todo por CDP.

## Convenciones de código

- Sigue el estilo del archivo: JS compacto, nombres cortos y comentarios en español solo donde aporten un motivo.
- CSS nuevo para móvil en bloques `@media (max-width:600px)`. El orden importa, porque hay muchas reglas superpuestas.
- Márgenes de las barras con `var(--sat,env(safe-area-inset-top,0px))`, y lo mismo con `--sab`, `--sal` y `--sar`. No uses `env()` solo.
- Colores siempre por variables de tema (`--bg`, `--surface`, `--fg`, `--accent`...), probados en claro, oscuro y con foto de fondo (`.has-bg`).
- Menús como hojas con `obSheet` o `.sheet-back`, para heredar el gesto de deslizar y el botón atrás. Pulsación larga con `holdMenu`.
- Widgets con vistas de texto, no con imágenes: Android limita la memoria de los bitmaps y el launcher de OPPO reescala y los estira.
