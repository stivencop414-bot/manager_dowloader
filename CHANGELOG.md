# Changelog

## 0.6.1

### Corregido
- Corrige la compilación de `BrowserScreen.kt`: el estado Compose llamado `settings` ocultaba `WebView.settings` dentro de `WebView.apply`.
- Las opciones de WebView ahora se configuran explícitamente mediante `this.settings`, incluyendo JavaScript, DOM Storage, caché, Safe Browsing y User-Agent.

## 0.6.0

— Browser compatibility, download actions & Turbo engine

### Fixed — Browser
- Search/address navigation applies the Chrome-compatible User-Agent before the first page load.
- Google searches use a simpler localized search URL and can fall back to DuckDuckGo from the error banner.
- Main-frame HTTP/network errors now offer Retry and “Load without AdBlock”.
- Added Brave Search as a fourth selectable search engine.
- Third-party cookies are now a user setting instead of a hard-coded browser policy.

### Fixed — AdBlock blank pages
- AdBlock STANDARD mode never blocks the main HTML document.
- STANDARD mode does not block non-GET requests or same-site resources.
- STRICT mode remains available for users who prefer stronger filtering.
- Essential compatibility hosts used by common Google pages are excluded from host-level blocking.
- The master AdBlock switch, tracker switch and per-site allow-list remain available.
- Service Worker interception follows the same safe blocking rules.

### Downloads / file actions
- Context menu per download.
- Active: Pause / Cancel.
- Queued: Prioritize / Cancel.
- Paused or failed: Resume / Retry / Refresh expired URL / Cancel.
- Completed HTTP file: Open / Share / Move to another user-selected folder / Delete file / Remove from history.
- Added Pause all and Resume all controls.
- Added ETA next to current download speed.
- FileProvider support for securely opening/sharing files stored in app-specific storage.

### Performance
- Turbo mode setting.
- HTTP segmented downloads can use up to 16 connections for large files.
- More aggressive adaptive segmentation in Turbo mode.
- Segment worker pool increased to 32.
- OkHttp connection pool and per-host capacity increased.
- Segmented read buffer increased to 512 KiB; single-stream buffer to 1 MiB.
- Removed unnecessary global synchronization from the unlimited-bandwidth hot path.
- Configurable 0–5 retries per segment/connection, resuming from already-written bytes.
- Progress/notification updates are slightly less frequent to reduce CPU and I/O overhead during high-speed transfers.

### Notes
- More connections do not guarantee more speed. Server throttling, latency, storage speed and ISP capacity still set the real ceiling.
- Moving completed torrents as whole directory trees is not included in 0.6.0; completed HTTP files can be moved.
