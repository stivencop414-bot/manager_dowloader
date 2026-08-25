# Auditoría aplicada — v0.7.3

Base de producción: v0.7.1. El intento v0.7.2 no modificó `main` porque su workflow falló antes de compilar.

Hallazgos del informe contrastados con el código y tratados en esta versión:

- BUG-01: corregido con un único archivo `.part` y escritura posicional; no hay merge final 2x.
- BUG-02: corregido con executor elástico + semáforo global y espera cancelable de Futures.
- BUG-03: corregido con persistencia AtomicFile asíncrona y coalescida fuera del lock.
- BUG-04: corregido evitando búsquedas SAF O(N²) para colisiones de nombre.
- SEC-01: corregido restringiendo FileProvider a carpetas de descargas administradas.
- SEC-02: corregido deshabilitando cleartext global por defecto.
- SEC-03: corregido endureciendo intents externos del WebView.
- BUG-05: corregido con WakeLock/WifiLock acotados al trabajo activo.
- BUG-06: corregido validando HTTP 206/Content-Range y haciendo fallback seguro a una conexión.
- BUG-07: mitigado mediante flushCache + espera acotada antes de quitar el TorrentHandle y mover datos.
- BUG-08: mitigado haciendo fetchMagnet cancelable desde el flujo de la app.
- BUG-09: mitigado conservando URL/sesión lógica del navegador y recuperación del renderer; no se retiene WebView en ViewModel.
- BUG-10: mitigado re-extrayendo URLs temporales de YouTube ante 403 usando originalSourceUrl/sourceFormatId.
- BUG-11: corregido usando scope de aplicación IO para operaciones de movimiento de archivos.
- SEC-04: corregido reforzando sanitización de nombres.
- SEC-05: corregido eliminando parciales/metadata tras fallo SHA-256.
- BUG-12: implementado de manera acotada: observer/bridge temporal y rate-limited, con HLS/DASH/blob clasificados; no se deja observador infinito.
- BUG-13: diferido. Koin/ViewModels es una modernización arquitectónica, no una corrección necesaria para evitar pérdida de datos o crashes en esta versión.

Riesgos que siguen existiendo:
- Android System WebView/Chromium es un proceso externo y el SO puede matar su renderer bajo presión de memoria; la app ahora lo recupera.
- jlibtorrent contiene JNI/C++; se reducen carreras conocidas, pero un fallo nativo no puede aislarse completamente desde Kotlin.
- Los proveedores SAF pueden ser lentos por implementación del fabricante/nube incluso reduciendo IPC repetitivo.
- La validación definitiva es `:app:assembleDebug` en GitHub Actions más pruebas reales en dispositivo.
