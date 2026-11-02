# Estado del proyecto

Pausado el 2026-11-02. El último trabajo está en `main` y subido a GitHub. Instalado en el móvil de pruebas: OPPO Find X9 Ultra, Android 16, ColorOS, WebView 138.

## Último punto de trabajo

Se añadió la pulsación larga en Registros y se ajustaron la barra inferior y las filas del mes. No hay ninguna tarea a medias en el código.

## Implementado

**Base:** temporizador en bucle, circuitos, cronómetro, alarmas, reloj mundial, agenda con vistas y bloques de trabajo, notas Markdown con bóvedas, enlaces, etiquetas, lienzos y grafo, sincronización con GitHub y copias de seguridad.

**Avisos nativos**
- Alarmas, fin de temporizador y recordatorios programados en Android, con reprogramación tras reiniciar.
- Pantalla de alarma sobre el bloqueo con Detener y Posponer.
- Temporizador como tarjeta multimedia con iconos; se controla sin abrir la app.
- Volumen del 10 al 200 %, modo sonido, vibración o ambos, y salida solo por auriculares o Bluetooth si están conectados.

**Notas**
- Menú lateral con elementos que se pueden quitar y recuperar, y campo de escritura con selector **+**.
- Bóvedas desde el botón Notas de la barra inferior.
- Tamaño y color del texto (`<span style>` en Markdown), colores de título y carpeta, imagen de bóveda.
- Exportar a PDF, Word, ODT, RTF, HTML, TXT y Markdown. Compartir bóvedas en ZIP e importar `.ics` y bóvedas abiertos desde otras apps.

**Registros** (cuarta pestaña)
- Guía por tipo: Trabajo, Estudio, Entrenamiento, Lista de la compra, Ingresos y gastos, Deberes y Otro.
- Tablas por días con fila diaria automática, totales, columnas editables y mínimo de 1 fila por día y 2 columnas.
- Diario con texto y tablas por día.
- Bloque en la línea de tiempo, nota acumulada por etiqueta en la carpeta Registros y `/a/b/[carpeta]`.
- Mantener pulsado abre eliminar, duplicar y compartir en registros, filas, columnas, tablas del diario y días. El campo que ya se está editando conserva la selección de texto del sistema.

**Widgets:** calendario mensual, bloques, línea de tiempo con 1 a 3 días, notas en árbol con selector de bóveda y lupa, alarmas, temporizador y registro.

**Diseño:** fondo de borde a borde, negro en oscuro y blanco en claro, temáticas con foto, cabeceras que se comprimen al bajar, horizontal a dos paneles, menús que se cierran deslizando, ejemplos cotidianos.

## Sin confirmar en el móvil

Prueba esto primero al retomar:

1. Temporizador visible en la pantalla de bloqueo. Por CDP y `dumpsys` se vio la sesión multimedia activa y la pausa con la app cerrada, pero no la tarjeta en el bloqueo. El móvil se desbloquea solo con la cara a los pocos segundos.
2. Alarma a pantalla completa con el móvil realmente bloqueado.
3. Widgets colocados: calendario mensual, notas, alarmas, temporizador y registro. Solo se ha visto el de línea de tiempo.
4. Botón **Ahora** del widget de línea de tiempo: comprobar que centra la línea roja.
5. Sonido solo por auriculares o Bluetooth.
6. **Guardar temática con foto** y volver a aplicarla.
7. Abrir un `.ics` o un ZIP de bóveda desde WhatsApp.
8. Mantener pulsado en tablas largas con scroll y el **×** de las columnas. Las acciones se probaron con toques reales en tablas cortas; compartir solo hasta abrir el selector de Android.
9. Cuadro de la semana con foto de fondo y cabeceras comprimidas en la vista semanal.
10. Flujo de CI `android.yml` tras los últimos push: no se ha comprobado que termine bien.

## Errores y limitaciones conocidos

- `npm audit` avisa de 9 vulnerabilidades (2 críticas y 3 altas), entre ellas `tar` dentro de `@mapbox/node-pre-gyp`. No se han revisado ni corregido.
- Los enlaces de carpeta (`📁`) de Registros no tienen menú de pulsación larga: se quitan editando el texto.
- Si se elimina el Diario, no hay opción en la interfaz para recuperarlo (`S.noDiary`).
- Selector **+** → Archivo crea una nota «Adjuntos»; si se cancela, queda vacía.
- Word, ODT y RTF exportan las imágenes como «[Imagen: nombre]», sin incrustarlas.
- Los totales suman todas las columnas numéricas salvo las de peso; series y repeticiones también se suman.
- Con auriculares conectados pero no puestos, la alarma podría no oírse.
- Android puede mostrar notificaciones duplicadas de agrupación automática.
- El asistente sencillo local y las conversaciones antiguas siguen ocultos en el código y en `S.ai`.
- `README.md` está desactualizado: habla de la integración con Claude, del sistema antiguo de notificaciones y de `clave-firma-vuelta-timer.txt`, un archivo con la clave de firma que no está en el repositorio. Si aparece, no lo subas.
- Riesgo para Google Play: el temporizador usa un servicio `mediaPlayback` sin reproducir audio, y la app pide alarmas exactas, pantalla completa y exención de batería. Google podría pedir justificación o rechazarlo.
- Gradle avisa de funciones obsoletas (incompatibles con Gradle 9) y de `flatDir`.

## Dudas abiertas

- `/[carpeta]`: ahora crea la carpeta y un acceso directo. Falta confirmar si la palabra debe ser además una nota dentro de esa carpeta.
- Si los totales deben sumar series y repeticiones.
- Con auriculares, si el despertador debe sonar también por el altavoz.
- Dónde está la otra versión de la app, la pública, que iba a usar Gemini. No está en este repositorio.

## Próximos pasos

1. Hacer las comprobaciones de la lista anterior y corregir lo que falle.
2. Actualizar `README.md`.
3. Añadir recuperación del Diario y evitar la nota vacía de Adjuntos.
4. Revisar `npm audit` y las advertencias de Gradle.

**Idea aparcada, no empezar sin confirmación:** versión de pago con suscripción y asistente que reparta entre varias IA, cuentas de usuario y compartir sincronizado. Requiere servidor con Firebase o similar, claves solo en el servidor, cupos por usuario y cobro por Google Play. Ver `DECISIONS.md`.
