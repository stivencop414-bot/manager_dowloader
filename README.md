# Manager Downloader v0.3

Native Android download manager built with Kotlin + Jetpack Compose.

## v0.3 engine

### HTTP acceleration
- Adaptive multi-range downloads (1–8 segments per file).
- Server capability probe with `Range: bytes=0-0`.
- Falls back automatically to a single stream when HTTP Range is unavailable.
- Each segment has its own partial file so pause/resume does not throw away completed ranges.
- Resume is validated with strong `ETag` or `Last-Modified` + `If-Range`; if the remote file changed, stale partial data is discarded instead of merged.
- Larger buffers and concurrent segment workers.
- Cookies and User-Agent from the embedded browser are forwarded to downloads.

Multi-range is not a magic bandwidth multiplier: it helps most when a server throttles individual connections or when parallel ranges improve utilization. The app deliberately caps concurrency to avoid making downloads slower or exhausting the phone/network.

### Queue modes
In **Ajustes > Rendimiento**:
- **Uno por uno**: only one file/torrent is active.
- **Simultáneos**: 2–6 transfers can run at the same time.
- HTTP connections per file are configurable from 1–8.

### BitTorrent
Uses FrostWire jlibtorrent/libtorrent 2.0.12.9.
- Magnet links.
- Remote `.torrent` URLs.
- `.torrent` files opened/shared to the app (`application/x-bittorrent`).
- Torrent progress, peer/seed count and speed in the same queue.
- Torrents are removed from the libtorrent session when completed so the app behaves as a downloader instead of seeding indefinitely.

Torrent output is kept under the app-specific `ManagerDownloader/Torrents/<task-id>` directory.

### Embedded browser
- WebView navigation with progress indication.
- Safe Browsing enabled.
- JavaScript + DOM storage for modern download sites.
- Third-party cookies disabled in the embedded browser.
- Detects downloads and magnet links.
- Preserves first-party cookies and User-Agent when sending an HTTP download to the manager.
- Mixed HTTP content inside HTTPS pages is blocked.

### Content blocking
The blocker works at the WebView request layer and at the Service Worker request layer.
- Built-in common ad/tracker domains.
- Periodic EasyList / EasyPrivacy refresh plus a hosts-format ad list for broader network-level coverage.
- Toggle ads and trackers independently in Settings.

No blocker can honestly guarantee that every website will be unable to detect blocked resources. This implementation does not inject anti-anti-adblock spoofing; it focuses on robust request blocking and a user-controlled off switch for sites that break.

## Android / build
- namespace: `com.managerdownloader.app`
- minSdk: 26
- targetSdk: 36
- compileSdk: 36
- JDK: 17
- AGP: 9.3.0
- Gradle CI: 9.5.0
- Compose BOM: 2026.06.00

GitHub Actions builds `ManagerDownloader.apk` and publishes/replaces the `latest-apk` prerelease asset for direct download.

## Important current limitations
- Downloads are still stored in the app-specific external Downloads directory. Public Downloads via MediaStore/SAF is a future improvement.
- `blob:` URLs generated entirely inside JavaScript are not captured by `WebViewClient.shouldInterceptRequest`.
- Long Android 15+ background `dataSync` foreground-service sessions are subject to platform time limits. A future version should migrate long transfers to Android user-initiated data-transfer jobs.
- Official production distribution still needs a permanent release signing key. The automatic latest APK is a debug-signed test build.
