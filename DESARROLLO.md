# Notas de desarrollo de Temvra (versión personal · temvra/temvP)

Temporizador, agenda (Día, Semana, 3 días, Mes, Año, Programación), bloques de trabajo, Notas tipo Obsidian y asistente.
Una sola base de código para **Android** (Capacitor 8, `com.temvra.app.personal`) y **escritorio** (Electron: Windows .exe y Linux AppImage).
Se prueba en un móvil Android, en un PC con Linux y en tableta.

## Dónde está cada cosa
- `src/app.html` — **toda la app** en un solo archivo (HTML + CSS + JS). El JS va dentro de una IIFE: sus funciones no son globales.
- `src/widget.html` — widget «Ahora» del escritorio y de la vista previa de Ajustes (recibe datos por `postMessage` o por `window.VTWidget`).
- `src/native.js` — puente con el plugin nativo (`window.VTNative`): temporizador en segundo plano, alarmas, widgets.
- `android/app/src/main/java/com/temvra/app/` — plugin y servicios nativos: `TimerService`, `Alerts`, `Widgets` (todos los widgets), `WidgetTick` (tic de cada minuto), `VaultDir` (bóveda en una carpeta real con el selector de carpetas del sistema).
- `desktop/` — Electron: `main.cjs`, `preload.cjs`, `preload-widget.cjs` y `vault-dir.cjs` (carpeta real de la bóveda; no usa Electron, se prueba con Node).
- `scripts/build-www.mjs` — genera `www/` (añade el `<head>` con el viewport, copia `native.js`, `widget.html` y las fuentes).
- `www/` y `android/app/src/main/assets/public/` se generan: no se editan a mano.

## Publicación
- **Cada push a `main` compila y publica una release** (`.github/workflows/android.yml`): la versión es `1.0.<run_number>` y sube el APK, el .exe y el AppImage. No hagas push de trabajo a medias.
- Los cambios que solo tocan archivos `*.md` no lanzan compilación.
- Commits en español, con un título que diga qué cambia para el usuario.
- Antes de subir: `node scripts/check-js.mjs` sin errores, prueba en Chromium lo que hayas tocado (móvil y escritorio, sin `pageerror`) y revisa con `git status` que no haya keystores, tokens, `www/` ni archivos de prueba.
- Después, vigila la compilación hasta el final y comprueba que la release tenga el .apk, el .exe y el .AppImage:
  - `gh run watch <id> -R temvra/temvP --exit-status` (si falla: `gh run view <id> -R temvra/temvP --log-failed`)
  - `gh api repos/temvra/temvP/releases?per_page=1 -q '.[0] | .tag_name + " " + .html_url'`

## Reglas
- **Nunca** leas, subas ni compartas el keystore, `android/keystore.properties` ni tokens. Los secretos solo viven en los secretos del repositorio (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`).
- Textos de la interfaz en español; las traducciones a otros 9 idiomas van en `DICT` (`DICT.push(...)`).
- El estado vive en `S` y se guarda con `save()` en `localStorage` (`vt2_state`). Las migraciones van por `S.uiV`: súbelo si añades una.
- Animaciones: solo `transform` y `opacity` en lo que se mueve cada fotograma; escribe en el DOM solo si el valor cambia (`setTx`, `put` en las lentes). Nada de bucles `requestAnimationFrame` que sigan corriendo en reposo.
- El color del rayo de la hora sale de las variables `--lz`, `--lz2`, `--lz3`, `--lzd` (formato `R G B`): no pongas rojos fijos.
- Sincronización con GitHub: Notas usa `ghSync`; Agenda y Time usan `appSync` y guardan un JSON por elemento en `.temvra/` del repo de la bóveda. Nunca sincronices con un repositorio de código (`GH_CODE_MARK`).
- Bóveda en una carpeta real: `dirSync` en `src/app.html` (fusión de 3 vías con `v.dir.base`, por bóveda en `v.dir`); en Android `VaultDir.java` y en el PC `vault-dir.cjs`, que solo abre carpetas elegidas en el diálogo (`carpetas.json` del perfil). Nunca se borra en la carpeta: va a su `.trash`. La carpeta y GitHub (`ghSync`) no corren a la vez (`window.__dirBusy`) y lo que llega de la carpeta se sube a GitHub como cualquier cambio.
- Cambios pequeños y comentados en español, siguiendo el estilo compacto que ya tiene el archivo.

## Android (Capacitor 8 · targetSdk 36 · JDK 21)
- Paquete Java `com.temvra.app`; id de aplicación `com.temvra.app.personal`.
- Widgets: todos se pintan en `Widgets.java` (`refreshAll` recorre cada proveedor). Un widget nuevo necesita: clase `AppWidgetProvider`, layout en `res/layout`, `res/xml/<nombre>_info.xml`, `<receiver>` en el manifest, textos en `strings.xml` y añadirlo a `refreshAll` y al recuento de `scheduleTick`.
- En RemoteViews solo valen layouts y vistas permitidas (FrameLayout, LinearLayout, TextView, ImageView, TextClock, Chronometer, ProgressBar…). No uses `View` como separador: usa un `LinearLayout` vacío.
- Los datos de los widgets llegan de JS (`VTAPI.widgetData()`) y se guardan en SharedPreferences; el tic de cada minuto (`WidgetTick`) los refresca sin abrir la app.
- `android:allowBackup="true"` devuelve datos viejos al reinstalar: tenlo en cuenta al probar.
- Sin el SDK de Android a mano, la compilación se comprueba en GitHub Actions. Valida al menos que los XML estén bien formados y que todo recurso nuevo esté declarado (manifest, `strings.xml`, `res/xml`).

## Escritorio (Electron)
- La app se sirve desde el protocolo propio `app://temvra` (`ORIGIN`); solo esa ventana puede usar el IPC (`fromApp(e)`).
- Todo lo que la página puede pedir pasa por `preload.cjs` (`window.VTDesktop`); el widget tiene su propio `preload-widget.cjs` (`window.VTWidget`) con lo mínimo.
- Archivo nuevo en `desktop/` que deba ir en el instalador → añádelo a `build.files` de `desktop/package.json`.
- Se publica para Windows (.exe) y Linux (AppImage).

## Cómo probar
- `npm test`: sintaxis y pruebas en Electron sin ventana (`scripts/pruebas.mjs`), con perfiles temporales, en español (sea cual sea el idioma del equipo) y sin internet: perfil vacío, todas las vistas, calendario al centrar el día, aro de la burbuja, crear un bloque sin categorías y guardado cuando no cabe en localStorage. Hazlo antes de publicar.
- Sintaxis: `node scripts/check-js.mjs` después de cada cambio en `src/`, `desktop/` o `scripts/`.
- En navegador: Playwright + Chromium sirviendo `src/app.html` envuelto en
  `<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"></head><body>` + archivo + `</body></html>`
  (sin el viewport el móvil se ve como escritorio). Siembra el estado con `addInitScript` en `localStorage.vt2_state` (con `tourDone:1` y lo que necesites) y abre la página con `?notour`.
- Navega con clics reales: `.nav-in button[data-view="cal|time|notes"]`, `.nav-set` (Ajustes), `#cal-vseg [data-cv=…]` y `#bk-vseg [data-cv=…]` para las vistas de la agenda.
- Tamaños: móvil 412×900 (`hasTouch`), tableta 800×1280 y 1280×800 (`isMobile`), escritorio 1440×900. `DESK` = ancho ≥ 900 (o apaisado ≥ 640).
- Rendimiento: con CDP `Emulation.setCPUThrottlingRate {rate:4}` para parecerse a un móvil, mide fotogramas con `requestAnimationFrame` y `Performance.getMetrics` (`TaskDuration`) en reposo y en las transiciones. En la app hay un medidor de fps (Ajustes › Rendimiento) que copia un informe por vistas.
- Carpeta real: en el navegador define `window.__dirTestBackend` con `addInitScript` (`kind`, `pick`, `list`, `read`, `write`, `trash`, `move`, `mkdir` sobre un objeto en memoria). En Electron, escribe `carpetas.json` (array con la ruta) en el perfil temporal para permitir una carpeta sin el diálogo. Electron como root necesita `--no-sandbox`.
- Sincronización con GitHub: no uses la API real; simula `api.github.com` con un servidor git en memoria (refs, commits, trees, blobs) compartido por dos contextos del navegador.
- Android: con el móvil conectado por USB, `npm run apk` y `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`; logs con `adb logcat | grep -i temvra`.
