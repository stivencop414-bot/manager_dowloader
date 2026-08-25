# v0.6 audit

Esta versión parte del motor v0.5.2 y se concentra en cuatro problemas observados durante pruebas reales: búsqueda de Google en WebView, páginas en blanco con AdBlock, falta de acciones sobre archivos terminados y rendimiento HTTP.

## Decisiones
- El User-Agent de compatibilidad se instala antes de la primera navegación.
- El AdBlock Estándar no intercepta el documento principal, POST ni recursos same-site.
- Se conserva un modo Estricto como opción explícita.
- Se añaden acciones de archivo usando content URI/FileProvider y SAF.
- El motor HTTP elimina contención en el limitador ilimitado, usa buffers mayores, segmentación hasta 16 conexiones y reintentos resumibles.

## Validación
El workflow de upgrade compila `assembleDebug` antes de hacer commit a `main`. Si la compilación falla, la fuente vigente en `main` no se reemplaza.
