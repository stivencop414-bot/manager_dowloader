# Manager Downloader

## v0.7.3
Administrador de descargas Android nativo en Kotlin + Jetpack Compose.

### Navegador estable
- WebView alojado en un contenedor seguro y recuperación ante `onRenderProcessGone`.
- Sniffer temporal para medios dinámicos, con límites para evitar saturación.
- Detección separada de archivos directos, HLS/DASH y blob.
- Descarga múltiple de archivos directos detectados.
- Cookie, User-Agent y Referer conservados al enviar descargas desde el navegador.


### Rendimiento
- HTTP/HTTPS con Range y hasta 8 conexiones adaptativas por archivo.
- Perfil recomendado: 6 conexiones por archivo en Modo Turbo.
- Buffers directos y FileChannel para reducir presión de memoria.
- Limitador global, Solo Wi-Fi, pausa, reanudación y reintentos.
- BitTorrent/magnet con perfil de conexiones más conservador para móviles.

### Navegador y YouTube
- Mantiene detección dedicada de YouTube con NewPipe Extractor.
- Evita descargar páginas HTML/TXT como archivos.
- Share Sheet de Android, AdBlock configurable y recuperación de WebView.

### Almacenamiento
- Storage Access Framework, categorías, abrir, compartir, mover y eliminar archivos terminados.
