# Manager Downloader v0.7.3

## Corrección del fallo de v0.7.2
- El run `32890603012` no llegó a compilar: falló en la validación previa del workflow porque buscaba el marcador `browserSafeMode`, ausente en el ZIP v0.7.2.
- El workflow v0.7.3 sustituye esa validación frágil por comprobaciones explícitas con mensajes y genera un log de preflight además del log de compilación.
- `main` permanece en v0.7.1 hasta que v0.7.3 compile correctamente.

## Estabilidad crítica del motor HTTP
- Se elimina la fusión final de archivos `.segN`: las descargas segmentadas escriben directamente sobre un único archivo parcial `.$id.part` mediante `FileChannel` y offsets posicionales.
- El archivo parcial se preasigna al tamaño esperado; cuando termina, se finaliza con renombrado en lugar de duplicar todo el archivo en disco.
- Se conserva migración de parciales antiguos `.segN` para no perder progreso previo.
- Se valida estrictamente `Content-Range`; si un servidor responde `200` a una petición Range o entrega un rango incoherente, se abandona el modo segmentado y se cambia de forma segura a una conexión.
- El pool fijo de segmentos se sustituye por executor elástico + `Semaphore` global, evitando starvation entre varias descargas paralelas.
- Si falla SHA-256, se elimina el parcial corrupto y el estado asociado antes de permitir otro reintento.

## Persistencia sin bloquear la interfaz
- `DownloadRepository` mantiene el estado en memoria bajo locks cortos y mueve JSON/AtomicFile a `Dispatchers.IO`.
- Persistencia atómica y debounced; progreso se guarda como máximo aproximadamente cada 3 segundos.
- Se evitan `writeText()` completos dentro del lock principal de UI/red.
- Nuevos campos compatibles hacia atrás: `referer`, `originalSourceUrl` y `sourceFormatId`.

## Navegador y WebView
- La creación/configuración de WebView queda aislada para que un proveedor WebView defectuoso no cierre la app.
- `onRenderProcessGone` desmonta y destruye el renderer muerto, devuelve `true` y recrea una vista limpia.
- Modo seguro del navegador tras caída del renderer para reducir carga de sniffer/JS hasta que el usuario lo reactive.
- Limpieza defensiva del WebView y del bridge JavaScript al salir.
- Conserva la última URL al cambiar entre pestañas y durante cambios de configuración.
- Sniffer temporal y limitado: detecta directos/HLS/DASH/blob sin acumular fragmentos `.ts/.m4s` ni mantener observadores ilimitados.
- Cookie, User-Agent y Referer se preservan en las descargas detectadas.

## Seguridad
- `FileProvider` deja de exponer `path="."`; solo comparte las carpetas administradas de descargas.
- Se deshabilita cleartext global y se agrega `network_security_config` con TLS como base.
- `openExternal()` limpia `component`, `selector` y `clipData`, exige `CATEGORY_BROWSABLE` y restringe esquemas externos.
- Sanitización reforzada para nombres `.` / `..`, separadores, NUL y caracteres problemáticos.

## Almacenamiento SAF
- Se evita `DocumentFile.findFile()` repetitivo en bucles de colisión; los nombres existentes se consultan una sola vez a `HashSet`.
- Las operaciones largas de mover archivos terminados usan un scope de aplicación en IO, no `lifecycleScope`, para no abortarse al rotar la Activity.

## Energía y Android moderno
- `DownloadService` adquiere `PARTIAL_WAKE_LOCK` y `WifiLock` únicamente mientras hay transferencias activas, con liberación defensiva al quedar inactivo/destruirse.
- Se mantiene el manejo de `onTimeout` del foreground service y el estado parcial antes de detenerlo.

## Torrents
- `fetchMagnet` se ejecuta en un Future y se sondea cada 250 ms para responder a pausa/cancelación sin bloquear 60 s el hilo de transferencia.
- Antes de mover/eliminar un torrent terminado se solicita `flushCache()` y se espera de forma acotada el `CACHE_FLUSHED` de libtorrent.
- Se mantienen límites móviles conservadores de conexiones/peers.

## YouTube
- Las tareas extraídas guardan la URL de origen y el identificador del formato.
- Ante un `HTTP 403` de un stream temporal, se intenta re-extraer un enlace fresco con NewPipeExtractor y reanudar sobre el mismo parcial.
- No se intenta eludir DRM, autenticación, contenido privado ni controles de acceso.

## Aplazado deliberadamente
- Migración completa a Koin/ViewModels/Room: requiere una refactorización transversal y no es necesaria para corregir los fallos críticos actuales.
- Streaming/remux HLS/DASH y fusión automática 1080p/4K de YouTube: se mantiene fuera de esta revisión de estabilidad.
