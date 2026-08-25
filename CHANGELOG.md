# Manager Downloader v0.7.1

## Motor HTTP móvil
- Conexiones por archivo limitadas a 8; 6 por defecto.
- Selección adaptativa: 1/2/4/6/8 conexiones según tamaño y Modo Turbo.
- Pool global de segmentos reducido a 8 workers para evitar saturación de CPU, radio y memoria.
- OkHttp limita 8 solicitudes por host y usa un pool persistente más conservador.
- Lectura/escritura usa `ByteBuffer.allocateDirect` + `FileChannel`, reduciendo objetos temporales y presión del GC.
- Progreso de UI se actualiza aproximadamente cada 500 ms y la notificación se limita a 1.5 s.
- Validación preventiva de espacio libre antes de iniciar archivos con tamaño conocido.
- Las llamadas OkHttp activas se retiran del control al terminar, evitando acumular referencias durante reintentos largos.

## Torrents
- `maxConnections` baja a 150 y `maxPeers` a 300 para un perfil más apropiado para móviles.
- Máximo interno de 4 torrents activos en libtorrent.
- Las descargas `.torrent` remotas eliminan sus llamadas HTTP del registro al terminar.

## Estabilidad
- Conserva las correcciones v0.7.0 de YouTube, Share Sheet, WebView renderer, AdBlock y falsos `.txt`.
- Conserva pausa/reanudación, HTTP Range, limitador dinámico, SAF y verificación SHA-256.

## No incluido todavía
- No se migra aún a Koin + Room + UIDT: es una refactorización arquitectónica separada.
- No se activa `WakeLock`/Wi-Fi high-performance permanente: se evita aumentar consumo de batería sin una política de uso y timeout bien definida.
- No se implementa todavía streaming torrent mientras descarga; se reserva para la fase de reproductor Media3.
