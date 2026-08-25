# Stability Audit — v0.7.3

## Workflow
- v0.7.2 falló en preflight, no en Kotlin: el marcador `browserSafeMode` no existía en aquel ZIP.
- v0.7.3 registra cada comprobación de preflight y sube logs tanto si falla la validación como si falla Gradle.

## Browser
- WebView no se almacena en ViewModel/singleton.
- Renderer muerto: detach -> bridge/client cleanup -> destroy -> recreate -> safe mode.
- JS bridge temporal, rate-limited y con URL máxima.
- Última URL persistida en estado Compose saveable.
- Intents externos saneados y esquemas limitados.

## HTTP
- Sparse `.part` único; sin merge 2x.
- FileChannel posicional, Content-Range estricto y fallback a single stream.
- Semáforo global de workers para evitar starvation.
- SHA mismatch purga parciales.
- Re-extracción de streams temporales YouTube ante 403.

## Persistence / storage
- AtomicFile en IO, debounce y snapshots inmutables.
- SAF usa sets/mapas de nombres por directorio para colisiones.
- FileProvider restringido a directorios administrados.

## Power / torrent
- Locks solo con transferencias activas y liberación en idle/destroy.
- Magnet cancelable desde la app.
- flushCache antes de remover/mover torrent completado.
