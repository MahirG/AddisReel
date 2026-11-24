# AddisReel 1.2.0 — Build 21 · Fast Start and Safe Public Catalog Cache

## What changed

- Public `GET /v1/catalog` now supplies a stable, SHA-256-derived strong ETag for its licensed/published JSON representation, responds with HTTP 304 (no response body) when unchanged, and uses a short `public, max-age=30, must-revalidate` cache policy.
- The native Android app conditionally revalidates its public catalog using the last server-generated ETag. It bounds response decoding to 200,000 characters and a maximum of 200 validated series.
- A local snapshot retains only public series IDs, titles, genres, descriptions and counts for up to 24 hours, with strict sizes and timestamps. **Never** cache playback links, license tokens, admin credentials, or personal viewing events in this snapshot.
- While offline, a validated snapshot can still populate discovery and poster rails, but playback remains subject to the live Worker license/publication checks. Offline playback is not claimed.
- A successful empty/withdrawn live catalog erases the old snapshot. A 304 is honored only with a fresh cached copy. On errors or empty catalog, previews are clearly marked and the Home screen offers Retry without restarting.
- Preserves existing poster WebP variants, Android Coil disk/memory caches, Wi-Fi HLS manifest prefetch, large MP4 transfer recovery, and the secure Studio processing queue.

## Release gates

- Node API tests verify strong ETags, no-body 304, metadata invalidation, CORS and no-store admin responses.
- Android unit tests verify snapshot TTL, size caps, validated public metadata, and strict ETag acceptance.
- Real Phase 6 Cloudflare smoke checks `/health/ready`, `/v1/catalog` response ETag, cache policy and conditional 304.
- Signed APK retains `et.addisreel.app` and original signing identity and increases to `1.2.0`, `versionCode=21`.
- Existing release workflow must build and verify signature, run the tests, reject downgrades and upload the immutable versioned APK *before* switching the in-app manifest.

## Security and operational caveats

- Cached titles may be stale while offline for at most 24 hours. A connected viewer cannot play a delisted/unlicensed series because `/v1/play` re-checks the current server state.
- A 30-second public metadata cache may momentarily delay discovery of a rights withdrawal; protected playback and poster requests are separately gated. Purge caches when emergency takedowns require immediate metadata revocation.
- No per-viewer server analytics or tracking are introduced.
- Automatic FFmpeg conversion remains **inactive** without a separately authorized host and matching processor secret; the app update does not create this infrastructure.
- Physical Android acceptance of upgrade, navigation, local snapshot and weak-network transitions remains necessary.
