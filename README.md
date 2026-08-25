# Manager Downloader

## v0.7.5
Administrador de descargas Android nativo en Kotlin + Jetpack Compose.

Esta revisión prioriza estabilidad y corrige casos borde encontrados después de la auditoría de v0.7.3.

### Descargas
- HTTP/HTTPS con Range, archivo único `.part`, pausa y reanudación.
- Segmentación adaptativa de 1–8 conexiones; en datos móviles se limita a 4.
- Cola secuencial por defecto o paralelo moderado de 2–4 tareas.
- Limitador global, Solo Wi‑Fi, WakeLock/WifiLock durante transferencias y verificación SHA-256.
- BitTorrent/magnet con cancelación responsive y flush de cache antes de publicar.

### Navegador
- WebView persistente entre pestañas con recuperación del renderer.
- AdBlock configurable.
- Sniffer dinámico con filtrado de recursos auxiliares, tracking y deduplicación por URL canónica.
- HLS/DASH/blob se identifican sin tratarlos como archivos directos.

### YouTube
- NewPipeExtractor para videos públicos compatibles.
- Transparent gzip corregido en el cliente interno.
- Re-extracción de enlaces temporales tras HTTP 403.
- Las pistas 1080p/4K separadas siguen sin fusionarse en esta versión.

### Seguridad
- FileProvider restringido.
- HTTP cleartext deshabilitado por defecto.
- Intents externos sanitizados.
- Nombres de archivo y rutas administradas validados.

El usuario debe descargar únicamente contenido que tenga derecho o permiso para guardar.
