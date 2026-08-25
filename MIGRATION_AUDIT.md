# Auditoría v0.6.2

Base: v0.6.1 confirmada en `main`.

Objetivo de esta revisión: reducir cierres de ejecución observados al cambiar ajustes, limitar velocidad y utilizar el detector multimedia.

Cambios de riesgo reducido:
- no cambia el modelo persistido de descargas;
- no borra parciales ni historial;
- no modifica la estructura de carpetas;
- mantiene HTTP Range, reanudación, torrent y SAF;
- reemplaza el limitador de ancho de banda por una implementación acotada;
- evita iniciar el servicio desde Ajustes si el servicio no está activo;
- elimina el escaneo DOM continuo y las imágenes del detector de medios.

El workflow de instalación debe compilar `assembleDebug` antes de modificar `main`.
