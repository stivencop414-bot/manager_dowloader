# Manager Downloader v0.6.1

Administrador de descargas Android nativo en Kotlin + Jetpack Compose.

## Funciones principales
- HTTP/HTTPS con pausa, reanudación y HTTP Range.
- Segmentación adaptativa de 1 a 16 conexiones por archivo.
- Modo Turbo y reintentos por conexión.
- Cola uno por uno o descargas simultáneas.
- Magnet, `.torrent` local y `.torrent` web mediante libtorrent.
- Navegador WebView integrado con búsqueda, detector de medios y AdBlock configurable.
- AdBlock Estándar (compatibilidad) o Estricto.
- Selección de carpeta mediante Storage Access Framework.
- Organización automática por Videos, Imágenes, Audio, Comprimidos, Programas, Documentos, Torrents y Otros.
- Acciones sobre descargas: pausar, reanudar, cancelar, priorizar, abrir, compartir, mover, eliminar y quitar del historial.
- Validación SHA-256 opcional.
- Solo Wi-Fi y limitador de ancho de banda.
- Tema Sistema / Claro / Oscuro.

## Rendimiento
Para buscar la mayor velocidad en un único archivo, prueba **Uno por uno + Modo Turbo** y aumenta las conexiones gradualmente. Si un servidor rinde peor con muchas conexiones, reduce el número de segmentos.

## Seguridad y almacenamiento
La app usa el selector de carpetas de Android (SAF) en lugar de solicitar acceso total al almacenamiento. Los archivos app-specific se comparten mediante FileProvider.

## Limitaciones
- WebView no expone `blob:` a `shouldInterceptRequest`, por lo que no se intenta saltar esa limitación ni DRM.
- Algunos sitios pueden funcionar mejor con AdBlock Estándar, permitiendo el sitio o activando cookies de terceros.
- La APK automática continúa siendo una compilación de prueba hasta configurar una clave de firma permanente.
