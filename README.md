# Manager Downloader

## v0.7.1
Administrador de descargas Android nativo en Kotlin + Jetpack Compose.

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
