# Manager Downloader v0.4

Android nativo con Kotlin + Jetpack Compose.

## v0.4

- Nuevo icono de aplicación Manager Downloader.
- Selector de carpeta mediante Storage Access Framework (SAF): Android otorga acceso persistente solo a la carpeta elegida.
- Organización automática de archivos terminados en: Videos, Imagenes, Audio, Comprimidos, Programas, Documentos, Torrents y Otros.
- Los parciales permanecen en almacenamiento específico de la app para mantener la reanudación y las descargas segmentadas; al terminar se publica el archivo en la carpeta elegida.
- HTTP Range adaptativo hasta 12 conexiones por archivo, con Dispatcher/ConnectionPool de OkHttp ajustados para mayor concurrencia.
- Modos Uno por uno y Simultáneos.
- Magnet, torrents web y selector de archivos `.torrent` locales dentro de la app.
- Detección mejorada en WebView de enlaces directos de video, audio e imagen vistos durante la navegación.
- Bloqueo de anuncios/rastreadores a nivel de solicitudes WebView + Service Worker, sin inyección de JavaScript anti-anti-adblock.
- Safe Browsing y bloqueo de contenido mixto siguen activos.
- Preparado para APK release firmado con clave permanente mediante variables de entorno/GitHub Secrets.

## Seguridad y Play Protect

El APK debug distribuido fuera de Google Play puede seguir siendo tratado como una app desconocida. La v0.4 permite compilar un APK `release` firmado de forma estable mediante `release-signed.yml`. Esto mejora la identidad de actualización de la app, pero no garantiza que Play Protect deje de analizar o advertir sobre una app instalada por sideload. Para la experiencia de confianza de Google más fuerte, la distribución por Google Play sigue siendo la vía principal.

## Almacenamiento Android

No se solicita `MANAGE_EXTERNAL_STORAGE` ni acceso total al dispositivo. Se usa el selector del sistema (`ACTION_OPEN_DOCUMENT_TREE`) para que el usuario elija una carpeta concreta. En Android 11+ el sistema no permite seleccionar algunas raíces protegidas; se recomienda crear/elegir una subcarpeta como `ManagerDownloader` dentro del proveedor disponible.

## Limitaciones

- La detección multimedia identifica enlaces directos observados por WebView. No intenta saltarse DRM y `blob:` no puede interceptarse mediante `shouldInterceptRequest`.
- Torrents descargan primero a un directorio temporal accesible por libtorrent y luego se publican en el árbol SAF elegido; para torrents muy grandes esto puede requerir espacio temporal adicional durante el movimiento/copia final.
- La velocidad real siempre depende del servidor, peers, ISP, Wi-Fi y límites por host. Más segmentos no garantizan más velocidad; por eso el motor sigue siendo adaptativo.
