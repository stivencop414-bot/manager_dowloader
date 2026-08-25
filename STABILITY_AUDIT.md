# Stability Audit — v0.7.5

## Revisión estática realizada
- Base v0.7.3 extraída del paquete que produjo la versión confirmada en `main`.
- Verificación de delimitadores Kotlin en todos los `.kt`.
- Búsqueda de regresiones conocidas: `writeText` síncrono del repositorio, `findFile` O(N²), `path="."`, `usesCleartextTraffic="true"`, `!!`, `GlobalScope`.
- Validación de presencia de `AtomicFile`, single `.part`, `Content-Range`, `SafePowerManager`, torrent `flushCache`, refresh HTTP 403 de YouTube y WebView safe mode.
- Revisión específica de los hallazgos nuevos del informe.

## Cambios de estabilidad
1. Torrent stop-before-handle tratado como salida normal.
2. Sleeps de torrent divididos en ventanas cancelables de 100 ms.
3. Transparent gzip restaurado en el downloader interno de NewPipe.
4. Filtro de sniffer centralizado y deduplicación canónica.
5. Filtro de tamaño solo para detección pasiva, sin bloquear descargas explícitas.
6. Paralelismo de archivos reducido a máximo 4.
7. Segmentación dinámica: máximo 4 en celular/desconocido y reparto más conservador en cola paralela.

## Validación de compilación
El contenedor local no incluye un Android SDK/Gradle configurado para compilar esta app completa. El workflow v0.7.5 ejecuta `:app:assembleDebug` antes de tocar `main` y publica los logs de preflight y compilación incluso cuando existe un fallo.
