# Auditoría v0.7.0

Base: v0.6.2 confirmada en `main`.

Objetivo: corregir los falsos `.txt` y dar a YouTube un flujo dedicado, sin volver a hacer agresivo el sniffer genérico.

Decisiones:
- NewPipe Extractor se usa solo para extracción de metadatos/streams; no se inicializa en el arranque de la app.
- La página HTML de YouTube nunca se envía al motor HTTP como descarga.
- `ACTION_SEND text/plain` permite recibir enlaces desde el Share Sheet de Android.
- Los streams combinados video+audio y audio-only pueden ir al motor HTTP existente.
- Las pistas video-only se informan pero no se fusionan todavía.
- WebView captura pérdida del renderer para evitar cierres completos.

Compatibilidad:
- minSdk 26 requiere core library desugaring para NewPipeExtractor.
- JitPack se añade como repositorio exclusivamente para NewPipeExtractor.
- NewPipeExtractor es GPL-3.0-or-later; revisar obligaciones de licencia antes de distribuir binarios públicos.
