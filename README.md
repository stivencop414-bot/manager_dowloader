# Manager Downloader — Native Android

Conversión del prototipo de Replit/Expo a Android nativo con Kotlin + Jetpack Compose.

## Qué ya es real en esta versión

- Cola secuencial: se descarga un archivo a la vez.
- Reordenamiento de pendientes con prioridad manual.
- Descarga HTTP real con OkHttp.
- Pausar y continuar conservando el archivo parcial.
- Reanudación mediante `Range` cuando el servidor lo admite.
- Si el servidor ignora `Range`, la app reinicia ese archivo de forma segura.
- Cancelar y quitar elementos de la cola.
- Persistencia local de la cola y del progreso.
- Notificación de progreso.
- Continuación en servicio en primer plano mientras la app se cierra.
- Mini navegador WebView.
- Detección de enlaces que disparan una descarga.
- Transferencia de Cookie y User-Agent del WebView al motor para enlaces autenticados.
- Tema claro/oscuro inspirado en el diseño original de Replit.
- GitHub Actions para compilar un APK Debug con cada push a `main`.

## Estructura

- `app/src/main/java/com/managerdownloader/app/data`: cola y persistencia.
- `app/src/main/java/com/managerdownloader/app/download`: motor HTTP y servicio.
- `app/src/main/java/com/managerdownloader/app/ui`: Compose y mini navegador.
- `.github/workflows/build-apk.yml`: build automático.

## Limitaciones conocidas

### Android 15+ y descargas de muchas horas

Los servicios `dataSync` en segundo plano tienen un límite acumulado de 6 horas en 24 horas en Android 15+ para apps que apuntan a esas versiones. La app maneja el timeout pausando de forma segura, pero una versión posterior debería incorporar User-Initiated Data Transfer (UIDT) para transferencias extremadamente largas.

### Carpeta de destino

Esta versión guarda archivos en la carpeta de descargas específica de la aplicación:

`Android/data/com.managerdownloader.app/files/Download/ManagerDownloader`

No requiere permisos de almacenamiento. Una siguiente iteración puede añadir selección de carpeta con Storage Access Framework o publicación en `Downloads` mediante MediaStore.

### Enlaces `blob:`

El mini navegador detecta descargas HTTP/HTTPS normales. Los sitios que crean archivos exclusivamente mediante `blob:`/JavaScript requieren un puente específico y todavía no están soportados.

### Release firmado

El workflow de release genera por ahora un APK release sin firma. Antes de distribuir la primera versión oficial hay que configurar una clave permanente mediante GitHub Secrets. No conviene publicar una APK oficial antes de fijar esa firma, porque Android exige la misma clave para actualizar una instalación existente.

## Build

Cada push a `main` ejecuta `Build Android APK`. Al finalizar, descarga el artifact desde la ejecución de GitHub Actions.

## Siguiente fase sugerida

1. Validar el primer APK en un teléfono real.
2. Añadir carpeta pública configurable.
3. Implementar UIDT para Android 14+.
4. Añadir firma de release permanente.
5. Añadir actualizador desde GitHub Releases.
6. Añadir captura de enlaces `blob:` cuando sea viable.
