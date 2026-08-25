# Manager Downloader

Administrador de descargas Android nativo en Kotlin + Jetpack Compose.

## v0.7.0

### Descargas
- HTTP/HTTPS con Range, pausa, reanudación y segmentación.
- Cola secuencial o simultánea, Modo Turbo y limitador global.
- Magnet, `.torrent` local y `.torrent` web.
- Abrir, compartir, mover y eliminar archivos terminados.

### Navegador
- DuckDuckGo, Google, Bing y Brave.
- AdBlock configurable y allow-list por sitio.
- Detector genérico limitado a video/audio directos.
- Las páginas HTML y payloads de texto no se añaden accidentalmente como archivos `.txt`.

### YouTube
- Reconoce `watch`, `youtu.be`, Shorts, Live, Embed y YouTube Music.
- Puede recibir enlaces con el menú Compartir de Android.
- Analiza metadatos y streams con NewPipe Extractor v0.26.3.
- Permite descargar streams combinados video+audio disponibles y audio-only.
- Las pistas 1080p/4K separadas se muestran pero todavía requieren una fase de muxing.

### Seguridad y límites
- No intenta eludir DRM, autenticación, contenido privado ni restricciones de acceso.
- El usuario debe descargar únicamente contenido que tenga derecho o permiso para guardar.
- El APK automático sigue siendo una build de prueba hasta configurar firma release permanente.
