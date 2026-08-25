# Auditoría v0.7.1

Base: v0.7.0 confirmada en `main` (commit 2cd63d15c8854dad19f5bbf89daa00eb5b568733).

Esta revisión toma del informe de optimización los cambios de rendimiento que encajan con el motor actual sin reemplazar toda la arquitectura:
- techo móvil de 6–8 conexiones HTTP;
- buffers directos y FileChannel;
- límites de red/torrent más conservadores;
- menor frecuencia de notificaciones;
- monitor de red y onTimeout ya existentes se conservan.

Se difieren Koin/Room/UIDT, preasignación `posix_fallocate` y streaming torrent porque requieren cambiar el modelo de persistencia/partial files o añadir una nueva capa de reproducción.
