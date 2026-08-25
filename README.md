# Manager Downloader

Aplicación Android nativa en Kotlin + Jetpack Compose para descargas HTTP/HTTPS y BitTorrent.

## v0.6.2
Esta revisión está enfocada en estabilidad: detector multimedia selectivo para video/audio, navegador con menos procesos auxiliares, limitador de velocidad seguro y aplicación de ajustes sin iniciar servicios innecesarios.

### Descargas
- HTTP/HTTPS con reanudación y Range cuando el servidor lo permite.
- Segmentación configurable y modo Turbo.
- Cola secuencial o simultánea.
- Magnet, `.torrent` web y `.torrent` local.
- Pausar, continuar, cancelar, reintentar, priorizar y actualizar URL.
- Abrir, compartir, mover y eliminar archivos HTTP completados.

### Navegador
- DuckDuckGo, Google, Bing y Brave.
- AdBlock activable/desactivable.
- Detector de video/audio; las imágenes decorativas no se agregan a la lista de medios.

### Almacenamiento
- Selección de carpeta mediante Storage Access Framework.
- Organización por Videos, Imagenes, Audio, Comprimidos, Programas, Documentos, Torrents y Otros.

### Rendimiento y estabilidad
- Limitador global 0–100 MB/s con token bucket compartido.
- Menor presión de memoria en descargas segmentadas.
- Los sliders no reinician el servicio cuando no hay transferencias activas.
