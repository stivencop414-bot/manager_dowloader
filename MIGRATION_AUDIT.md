# Auditoría aplicada — v0.7.5

Base confirmada: v0.7.3 en `main`, commit `335c2e6906cb20faec61fce1cc600c89ab46bc58`.

## Hallazgos nuevos del informe y tratamiento

### Pausa temprana de torrent
Hallazgo confirmado en `TorrentEngine.kt`: tras abandonar la espera de `manager.find(info)` por `control.stopped`, el código podía llegar a la validación de handle nulo. v0.7.5 retorna de forma normal cuando la transferencia ya fue detenida y usa espera cancelable.

### GZIP de NewPipe
Hallazgo confirmado en `YouTubeSupport.kt`: los headers del extractor se copiaban íntegros, incluido `Accept-Encoding`. v0.7.5 omite exclusivamente ese header para que OkHttp gestione transparent gzip. No se cambia el `Accept-Encoding: identity` del motor de descargas Range, donde se necesita identidad byte-a-byte.

### Ruido del sniffer
Se añade `MediaSnifferFilter` con:
- extensiones descartadas;
- patrones de tracking;
- umbral opcional de 150 KB cuando Content-Length es conocido;
- clave canónica para deduplicar requests por rango sin modificar la URL real;
- deduplicación también en `enqueueBatch`.

El sniffer DOM sigue acotado temporalmente y no se convierte en un observer ilimitado.

### Cola
La auditoría recomienda secuencial para archivos grandes y paralelo moderado para lotes pequeños. v0.7.5 mantiene `SEQUENTIAL` como predeterminado, reduce el techo paralelo a 4 tareas y añade un presupuesto de segmentos condicionado por Wi‑Fi/celular y número de transferencias activas.

## Decisiones de riesgo
No se integran MediaMuxer, playlists, portapapeles, Koin ni Room en esta revisión. Son cambios funcionales/arquitectónicos y no correcciones necesarias para los casos borde auditados.

## Compatibilidad
No cambia el modelo persistido de descargas. Las preferencias antiguas con `maxParallelDownloads` de 5 o 6 se normalizan automáticamente a 4 al iniciar.
