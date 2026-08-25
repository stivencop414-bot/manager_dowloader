# Manager Downloader v0.6.2

## Estabilidad
- Se elimina el arranque innecesario del servicio de descargas al cambiar ajustes cuando no hay transferencias activas.
- Las acciones hacia el servicio quedan protegidas para evitar que una excepción de arranque de foreground service cierre la interfaz.
- Se reduce la presión de memoria del motor HTTP: 16 workers de segmentos, buffers más contenidos y límites de conexiones más conservadores.
- El limitador de velocidad se reemplaza por un token bucket compartido que se adapta en tiempo real al cambio de límite y evita esperas acumuladas excesivas.
- Los cambios de los sliders se guardan al finalizar el gesto, no en cada movimiento.
- El límite configurable queda normalizado entre 0 y 100 MB/s.
- La aplicación del límite a libtorrent se protege frente a excepciones del wrapper nativo.

## Navegador y detector de medios
- El detector de medios deja de considerar JPG, PNG, WebP, GIF y demás imágenes como medios descargables.
- La lista de medios se concentra en video y audio.
- Se elimina el MutationObserver permanente que recorría todo el DOM repetidamente.
- Se elimina el puente JavaScript permanente; ahora el DOM se consulta de forma puntual y acotada.
- El escaneo del DOM devuelve como máximo 12 enlaces y la lista interna se limita a 16 elementos.
- Se desactivan ventanas emergentes automáticas y múltiples WebView auxiliares para mejorar estabilidad.

## Rendimiento
- Descargas sin límite mantienen buffers grandes para rendimiento.
- Al activar un límite se usan bloques más pequeños para una regulación más suave.
- El pool HTTP se ajusta a 32 solicitudes globales, 16 por host y 24 conexiones persistentes.

## Nota
Esta versión prioriza estabilidad sobre aumentar agresivamente el número de hilos. El servidor remoto, la red y el almacenamiento siguen determinando la velocidad máxima real.
