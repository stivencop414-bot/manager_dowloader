# v0.4 implementation audit

## Storage
Uses SAF instead of broad storage permissions. Final files are categorized and published to the selected persistent tree URI. Partial/random-access data remains app-specific so resume and segmented HTTP remain reliable.

## HTTP
OkHttp dispatcher: 64 max requests, 16 per host; connection pool 16; segmented pool 24 workers; user-configurable 1–12 ranges with adaptive caps based on file size. Resume still validates range state with ETag/Last-Modified.

## Torrent
Magnet, remote `.torrent`, Android VIEW intents and an in-app local `.torrent` picker are supported. libtorrent still downloads to app-specific filesystem storage because SAF URIs are not normal filesystem paths; finished torrent trees are copied/published to the selected destination.

## Browser
Request-level content blocking remains in place. Direct media extensions encountered by WebView are surfaced to the user as detected media. DRM bypass and blob interception are intentionally not implemented.

## Signing
Build configuration can consume a permanent release keystore via environment variables. `release-signed.yml` is provided separately and expects GitHub Secrets. A consistent release signature is required for normal Android upgrades, but sideloaded apps may still be scanned/warned by Play Protect.

## Validation
Static source checks were performed here. GitHub Actions remains the authoritative Android compilation test.
