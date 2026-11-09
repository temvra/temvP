# Temvra Personal · versión privada de Temvra

Copia personal de Temvra (`com.temvra.app.personal`): se instala junto a la versión pública sin pisarla.
Aquí se prueban cosas que todavía no van a la versión de Google Play.

## Base

Temporizador en bucle, circuitos, alarmas, agenda con bloques de trabajo y notas.
La interfaz está en `src/app.html` y la parte de Android la pone
[Capacitor](https://capacitorjs.com).

## Opción 1 · Compilar con GitHub, todo desde el móvil (recomendada)

1. Crea un repositorio **privado** en GitHub llamado `temvP` y sube el contenido de esta carpeta.
2. Guarda la clave de firma: en el repositorio, **Settings → Secrets and variables → Actions → New repository secret**.
   Crea `KEYSTORE_PASSWORD` y `KEYSTORE_BASE64` con los valores del archivo `clave-firma-vuelta-timer.txt`
   (va aparte; **no lo subas al repositorio**).
3. Cada vez que se sube un cambio, GitHub compila la app sola (pestaña **Actions**, unos 5–8 minutos).
   También puedes lanzarlo a mano: Actions → «Compilar APK» → Run workflow.
4. Entra en **Releases** desde el móvil, descarga `Temvra-1.0.N.apk` e instálalo.
   La primera vez Android te pedirá permitir instalar apps desde el navegador.
5. Las versiones nuevas se instalan encima y conservan tus datos porque se firman con la misma clave.
   Guarda el archivo de la clave en un sitio seguro: si se pierde, habría que desinstalar para actualizar.

## Opción 2 · Compilar en el ordenador (Linux)

Necesitas Node.js 22, JDK 21 y Android Studio (o solo el Android SDK).

```bash
npm install
npm run apk          # APK en android/app/build/outputs/apk/debug/
npx cap run android  # instalar y abrir en el móvil conectado por USB
```

Para firmar con tu clave, sigue las instrucciones del final de `clave-firma-vuelta-timer.txt`.

## Cambiar la app

- `src/app.html`: toda la interfaz. Edítala y sube el cambio: GitHub genera la nueva versión.
- `src/native.js`: conexión con Android (notificaciones, botón atrás, compartir y guardar archivos).
- `scripts/build-www.mjs`: prepara `www/` con las fuentes y librerías dentro de la app, para que funcione sin conexión.
- `scripts/make-icons.py`: dibuja el icono (`python3 scripts/make-icons.py`).

## Qué hace ya la versión de Android

- Alarmas, avisos de eventos, tareas y bloques, y el final de cada vuelta del temporizador llegan como
  notificaciones aunque la app esté cerrada, con **Posponer 5 min** e **Ignorar**.
- Botón atrás de Android: cierra la hoja o pantalla abierta y vuelve atrás.
- Galería y archivos (clip de las notas, fondo con imagen) con el selector de Android.
- Compartir notas, PDF, ZIP y calendarios `.ics` (WhatsApp, Drive, correo…) y guardar en `Documentos/Temvra`.
- GitHub de las notas, festivos oficiales y suscripciones de calendario sin las restricciones del navegador.
- Bóvedas de notas en una carpeta real del móvil o del PC (selector de carpetas del sistema): cada nota es un archivo `.md`
  que otras apps (gestor de archivos, Obsidian…) pueden abrir y editar, y se sincroniza en los dos sentidos. GitHub sigue
  funcionando a la vez: carpeta ⇄ Temvra ⇄ GitHub. Lo que se borra va a `.trash` dentro de la carpeta, como en Obsidian.

## Pendiente para próximas versiones

- Bajar el volumen de Spotify mientras suena el aviso (necesita código nativo de audio).
- Widget en la pantalla de inicio y notificación fija con el temporizador en marcha.
- Publicar en Google Play (cuenta de desarrollador: pago único de 25 $).
