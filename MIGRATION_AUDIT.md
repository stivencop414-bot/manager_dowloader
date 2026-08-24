# Auditoría de la versión Replit

## Hallazgos principales

La versión importada desde Replit era una aplicación Expo/React Native basada en Expo Router.

### Lo que sí estaba implementado

- Diseño visual de la pantalla principal.
- Tema claro/oscuro.
- Diálogo para pegar una URL.
- Persistencia de una lista mediante AsyncStorage.
- Botones que cambiaban visualmente entre activo y pausa.
- Datos demo para simular progreso.

### Lo que era simulación

- No existía transferencia HTTP de archivos.
- `addDownload` solo añadía un objeto a una lista.
- `togglePause` únicamente cambiaba el estado y textos.
- La velocidad, progreso, tamaño y tiempo restante eran datos estáticos.
- No existía un servicio de descargas en segundo plano.
- No existía HTTP Range.
- No existía cola secuencial real.
- No existía reordenamiento de prioridad.
- No existía mini navegador dentro de la navegación principal.

## Decisión de migración

Para este producto el núcleo es el trabajo en segundo plano, acceso a archivos, notificaciones, WebView, recuperación de descargas y comportamiento específico de Android. Por eso se migró a Kotlin + Compose en vez de seguir ampliando la maqueta Expo.

La apariencia conserva la paleta original:

- Fondo: `#F4F7FB`
- Primario: `#146BFF`
- Éxito: `#17A673`
- Advertencia: `#D98A21`
- Error: `#D94B5B`
- Violeta: `#8064D9`
