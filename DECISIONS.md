# Decisiones técnicas

## Plataforma

### Un solo `src/app.html`, sin framework
**Motivo:** la app nació así y se genera un `www/` autónomo que funciona sin conexión.
**Descartado:** migrar a un framework, por el coste de rehacer miles de líneas sin una necesidad concreta.
**Consecuencia:** el orden de ejecución del script importa y las nuevas funciones se añaden con cuidado.

### Migraciones con `S.uiV`
**Motivo:** los datos viven en `localStorage` de cada dispositivo. Cada cambio de datos es un bloque que se ejecuta una sola vez.

## Sonido y avisos

### Motor nativo en vez de la WebView
**Motivo:** con el móvil bloqueado, la WebView no garantiza el sonido y el plugin de notificaciones no permite controlar volumen ni pantalla completa.
**Descartado:** `@capacitor/local-notifications` para programar. Se conserva solo para pedir permisos.
**Cómo:** se guarda la lista de avisos y se arma únicamente el siguiente. `setAlarmClock` para alarmas y temporizador, porque no se retrasan con el móvil en reposo; `setExactAndAllowWhileIdle` para recordatorios.

### Canal de alarma con volumen forzado y refuerzo
**Motivo:** el sonido no debe depender del volumen multimedia. Se ajusta el canal de alarma mientras suena y se restaura después. Del 100 al 200 % se usa `LoudnessEnhancer`, y los tonos se sintetizan en frecuencias donde el altavoz rinde más.

### Auriculares: canal multimedia
**Motivo:** Android saca el canal de alarma también por el altavoz, y se pidió que sonara solo por los auriculares.
**Riesgo aceptado:** una alarma puede no oírse si los auriculares están conectados pero no puestos.

### Las alarmas siempre suenan
**Motivo:** antes no sonaban con el aviso en «Nada» o «Solo bajar música», lo que se consideró un fallo. Esos ajustes siguen aplicándose al temporizador.

### Temporizador como tarjeta multimedia
**Motivo:** ColorOS no muestra la lista de notificaciones en el bloqueo, pero sí la tarjeta multimedia (se observó con Spotify). Además da botones solo con icono, como se pidió.
**Descartado:** notificación con importancia baja, que el sistema oculta en el bloqueo; vista personalizada con botones, por el mismo motivo; Live Updates de Android 16, incompatibles con vistas propias y sin garantía en ColorOS.
**Cómo:** `MediaSession` del sistema sin dependencias nuevas y un servicio en primer plano que mantiene viva la sesión.
**Riesgo:** tipo `mediaPlayback` sin audio ante una revisión de Google Play.

### Estado del temporizador también en nativo
**Motivo:** los botones del bloqueo deben funcionar con la app cerrada. `TimerCtl` es la autoridad en segundo plano y la app recoge el estado al volver (`timerState`, `timerApply`).

## Interfaz

### Márgenes de las barras gestionados por la app
**Motivo:** con WebView anterior a la 140, Capacitor rellena el espacio de las barras y deja franjas, y `env(safe-area-inset-*)` no sirve.
**Cómo:** `insetsHandling: "disable"` en `capacitor.config.json`, y `TemvraPlugin` inyecta `--sat`, `--sab`, `--sal` y `--sar`.

### `EdgeToEdge.enable` después de `super.onCreate`
**Motivo:** llamado antes, creaba la ventana con el tema de arranque y aparecía una barra de acción con el logo aplastado.

### Horizontal con el diseño de escritorio
**Motivo:** reutiliza los dos paneles existentes ampliando la condición de `DESK`, con ajustes para poca altura.

### Oscuro y claro quitan la foto
**Motivo:** se pidió fondo negro o blanco por defecto, con la foto como opción aparte. Las temáticas guardadas permiten recuperarla.

### Formato de texto con `<span style>`
**Motivo:** Markdown no tiene tamaño ni color, y este HTML también se ve en Obsidian. Solo se aceptan `font-size` y `color`.
**Descartado:** sintaxis propia, que no sería portable.

### Registros: acciones con pulsación larga
**Motivo:** se pidió eliminar, duplicar y compartir al mantener pulsado cada elemento. Sustituye a los botones de compartir y eliminar de días, tablas y cajón, que llenaban cada tarjeta.
**Cómo:** `holdMenu` con un filtro para no actuar en botones ni en el campo que se está editando; así este conserva la selección y el pegado. Los campos sin foco no permiten seleccionar texto.
**Duplicar registro:** la copia pierde la etiqueta y los bloques enlazados, para no repetirse en la nota acumulada ni tocar eventos del original.
**Duplicar día:** el diario tiene un día por fecha, así que se copia en hoy, o en mañana si ya es hoy.

### Altura del mes medida contra la barra
**Motivo:** la barra flota sobre los botones del sistema y la fórmula anterior ignoraba ese margen, de modo que pisaba la última fila. Se mide contra la ventana porque, al cambiar de vista, la cabecera anterior aún encoge el contenedor.

## Widgets

### Línea de tiempo con vistas de texto
**Motivo:** con imágenes se superaba el límite de memoria (35 MB frente a 27) por la conversión de adaptadores de Android, y el launcher de OPPO reescala y estiraba el dibujo. Ahora son 96 franjas de 15 minutos con `TextView`.
**Concesión:** menos control visual que con Canvas.

### `WidgetAction` para los toques en listas
**Motivo:** una lista solo admite una plantilla de `PendingIntent`, y algunos toques abren la app mientras otros, como plegar carpetas, no. Una actividad invisible decide.

### Refresco por minuto sin despertar el móvil
**Motivo:** ahorrar batería con una alarma `RTC`, que se entrega al encender la pantalla. Los widgets no se actualizan con la pantalla apagada.

## Compartir

### Por archivo, sin cuentas
**Motivo:** funciona ya y sin servidor. Eventos en `.ics`, bóvedas en ZIP, tablas en CSV y diario en texto.
**Limitación:** cada envío es una copia independiente. La sincronización requeriría cuentas y servidor.

## Asistente con IA (aparcado)

Se construyó con herramientas para crear y modificar recursos de la app, y se retiró a petición del usuario. Se conserva en `parked/asistente-ia.js`.

- **Claude:** usaba el SDK oficial `@anthropic-ai/sdk`, empaquetado con esbuild en `www/libs/anthropic.js`. Ambas dependencias se quitaron. La API se paga por uso, aparte de la suscripción de Claude.
- **Gemini:** por su API REST. Nunca se probó con una clave real.
- **Pruebas:** con respuestas simuladas y una clave falsa contra la API real; nunca con una clave válida.
- **Clave:** la introducía el usuario y se guardaba solo en el dispositivo. La migración `uiV` 5 borra esa configuración.
- **Imágenes remotas:** no se cargaban en las respuestas, para evitar sacar datos mediante instrucciones ocultas en una nota.

### Si se retoma como producto de pago
- Las claves no pueden ir en el APK: hace falta un servidor con cuentas y cupos por usuario.
- Las herramientas seguirían ejecutándose en el móvil, y el servidor solo pasaría mensajes al modelo.
- Plan estudiado: modelo económico por defecto y cupos para los caros. El coste por usuario era una estimación sin medir: hay que medirlo antes de fijar precios.
- Pendiente de verificar en fuentes oficiales: condiciones comerciales de Anthropic, uso de Gemini gratuito con usuarios europeos, cuota de Firebase Authentication y comisiones de Google Play. Cloud Functions probablemente requiera activar la facturación.
