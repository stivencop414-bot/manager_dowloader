# Manager Downloader v0.8.0

## Auditoría aplicada
- 8 parches de estabilidad del informe maestro v0.7.5 → v0.8.0.
- HTTP cleartext habilitado deliberadamente para mirrors/NAS/trackers no TLS.
- Importación segura de .torrent SAF y ACTION_VIEW HTTP/HTTPS hacia el navegador integrado.
- Validación defensiva de TorrentHandle JNI.

## Video
- Alta calidad YouTube con pistas separadas de video+audio y fusión nativa MediaMuxer MP4/WebM sin recodificación.
- URLs temporales de formatos HD se refrescan al comenzar el trabajo.
- Playlists de YouTube se extraen con paginación y se encolan como trabajos JIT para no guardar URLs directas caducables.
- Sniffer web intercepta fetch/XHR para HLS/DASH, permite rescaneo reactivo y analiza variantes de HLS Master.

## Portapapeles
- Detección únicamente cuando la app está en primer plano/con foco, respetando las restricciones de Android 10+. No se promete monitoreo global en background.

## Compatibilidad
- El modelo persistido añade solo campos opcionales; las colas v0.7.5 siguen cargando.
- No se intenta eludir DRM, autenticación, contenido privado ni controles de acceso.

# Manager Downloader v0.7.5

## Enfoque
Esta versión está dedicada a estabilización, corrección de casos borde y reducción de falsos positivos tomando como base la auditoría técnica de v0.7.3. No introduce una migración arquitectónica grande ni nuevas funciones de alto riesgo.

## Torrent
- Pausar o cancelar mientras libtorrent todavía resuelve el `TorrentHandle` ya no termina en un falso error de inicio.
- La espera inicial y el bucle de progreso usan pausas cancelables en intervalos cortos para responder más rápido a Pausa/Cancelar.
- Se conservan el vaciado de caché previo a retirar el handle y la resolución cancelable de magnets de v0.7.3.

## YouTube / NewPipe
- `OkHttpExtractorDownloader` deja de copiar el encabezado `Accept-Encoding` enviado por NewPipe.
- OkHttp recupera su descompresión gzip transparente, evitando que HTML/JSON comprimido llegue al extractor como bytes ilegibles.
- Se conserva la re-extracción automática de URLs temporales cuando una descarga extraída falla con HTTP 403.

## Navegador y sniffer
- Nuevo `MediaSnifferFilter` centralizado.
- Descarta fragmentos `.ts/.m4s/.cmfv/.cmfa`, subtítulos, claves HLS, imágenes, iconos y patrones comunes de analytics/telemetría.
- Los elementos conocidos de menos de 150 KB se excluyen de la lista pasiva de medios para reducir sonidos de interfaz y previews irrelevantes.
- La deduplicación usa una URL canónica que elimina solo parámetros de fragmentación `range`, `bytes`, `start` y `end`.
- La URL original se conserva intacta para descargar, evitando romper firmas, tokens o parámetros de expiración de CDN.
- El JavaScript sniffer aplica también filtros y deduplicación antes de cruzar el `JavascriptInterface`.
- HLS/DASH y `blob:` continúan identificados como streams/no descargables directos; no se guardan accidentalmente como MP4.
- Una descarga explícita iniciada por `DownloadListener` sigue siendo accionable aunque sea pequeña; el umbral de 150 KB solo limpia detecciones pasivas.

## Cola y rendimiento estable
- El modo simultáneo queda limitado a 2–4 descargas activas; 3 sigue siendo el valor predeterminado.
- En datos móviles o red no identificada, la segmentación se limita automáticamente a 4 conexiones por archivo.
- En modo paralelo, el presupuesto de los 8 permisos globales se reparte de forma más conservadora entre tareas activas.
- En modo secuencial sobre Wi‑Fi se conserva el perfil adaptativo de hasta 8 conexiones para archivos grandes.

## Conservado de v0.7.3
- Archivo único `.part`, escritura posicional y preasignación.
- Persistencia asíncrona con `AtomicFile`.
- `WakeLock`/`WifiLock` durante transferencias.
- Validación estricta de `Content-Range`.
- Purga de parciales corruptos en SHA-256.
- SAF optimizado, FileProvider restringido y cleartext deshabilitado.
- WebView persistente entre pestañas y recuperación de renderer.
- Sanitización de intents externos y nombres.
- Flush de caché de torrent antes de publicar archivos.

## Fuera de alcance de v0.7.5
MediaMuxer para 1080p/4K, playlists de YouTube y lectura del portapapeles se dejan para versiones posteriores. La prioridad de esta entrega es no ampliar la superficie de fallos antes de validar estabilidad.
