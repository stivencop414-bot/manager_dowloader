# v0.3 audit / implementation notes

## Replaced bottlenecks

The previous engine used one blocking OkHttp stream and one global single-thread executor. That meant:
- one file at a time only;
- no range segmentation;
- no configurable concurrency;
- download speed was entirely tied to a single server connection.

v0.3 replaces this with a scheduler, independent transfer workers and a global segment pool.

## HTTP strategy
1. Probe the server with a one-byte Range request.
2. If the response is HTTP 206 with a valid total size, choose 1–8 adaptive ranges depending on file size/user setting.
3. Resume every range independently from its partial file.
4. Bind resumable partials to a strong ETag or Last-Modified validator and send `If-Range`, so a changed remote object cannot silently corrupt a resumed file.
5. Merge only after every range is complete.
6. If range support is unavailable, use the safe single-stream resume path.

## Torrent strategy
FrostWire jlibtorrent 2.0.12.9 was selected because the current release supports Java 17 and Android native binaries for arm, arm64, x86 and x86_64. The base app minSdk remains 26.

## Browser blocker
The blocker intentionally implements the domain-only subset of ABP/EasyList rules that can be interpreted correctly without pretending to support all filter-rule semantics. Conditional rules are skipped rather than overblocked.

## Validation required
This source package was statically checked for Kotlin syntax, but this environment does not contain an Android SDK/Gradle dependency cache. GitHub Actions is the authoritative compilation test. Do not call v0.3 build-valid until the CI workflow is green.
