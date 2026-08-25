# Manager Downloader v0.7.0

## YouTube: detección real en lugar de descargar HTML/TXT
- Se añade detección específica de URLs `youtube.com/watch`, `youtu.be`, Shorts, Live, Embed, YouTube Music y enlaces con playlist.
- Una página de YouTube ya no se añade a la cola como si fuera un archivo web; esto elimina el falso `.txt` observado en pruebas.
- Se integra NewPipe Extractor v0.26.3 para analizar metadatos y streams públicos disponibles.
- El panel de YouTube muestra título, autor, duración, video con audio disponible y pistas de audio.
- Las pistas de alta calidad que vienen separadas (video-only + audio-only) se muestran, pero no se presentan como video final hasta incorporar un muxer seguro.
- Los errores del extractor (bloqueo regional, verificación, contenido no disponible, etc.) se presentan en la interfaz sin cerrar la app.

## Compartir desde Android
- Manager Downloader aparece en el Share Sheet para `text/plain`.
- Al compartir un enlace de YouTube, abre el navegador interno y lanza el análisis del video.
- También acepta una URL HTTP/HTTPS compartida sin convertir texto arbitrario en descarga.

## Navegador
- `A cola` solo aparece para URLs que parecen archivos directos; una página HTML normal ya no se descarga accidentalmente.
- El DownloadListener descarta `text/plain`, HTML, CSS, JavaScript, JSON, XML, HLS `.m3u8` y DASH `.mpd` como archivos genéricos.
- El detector genérico de medios se desactiva en páginas de YouTube para evitar recursos internos falsos.
- Si el proceso renderer de WebView muere, se captura `onRenderProcessGone`, se reconstruye WebView y se evita que cierre toda la app.

## Estabilidad
- La integración YouTube se inicializa de forma perezosa: si el extractor falla, no impide abrir Manager Downloader.
- Se mantiene la v0.6.2 de limitador de velocidad, controles de descarga, torrent, SAF y AdBlock.

## Limitaciones actuales
- No se intenta eludir DRM, contenido privado ni controles de acceso.
- 1080p/4K de YouTube suele usar pistas separadas; la fusión video+audio se deja para una fase posterior con un muxer/FFmpeg compatible.
- Los enlaces directos extraídos por servicios de streaming pueden expirar; conviene iniciar la descarga tras analizarlos.
