# AddisReel 1.1.0 Build 20 — Discovery + Processor Reliability

## What's new
- The native Home hero, Picked for You rail, and For You sort share the same on-device ranking. It combines existing local bookmarked titles, recent watch history, completed series and preferred genres, with genre diversity to avoid a monotonous shelf.
- No viewer history, watched-series identifiers or preference scores are transmitted to Cloudflare. This is local personalization, not a remote recommendation engine.
- The native Media3 preloader can warm only a trusted upcoming HLS `master.m3u8` (up to 64 KiB) on validated unmetered networks, without preloading media segments. MP4 prefetch stays capped at 256 KiB.
- Content Studio shows stalled video processing jobs and an owner-only Retry action for failed or more-than-120-minute-inactive jobs. Active jobs cannot be interrupted.
- Job creation now uses an atomic D1 `INSERT … WHERE NOT EXISTS … RETURNING` to avoid duplicated active processing jobs.
- `/health` advertises phase 5 and `processor-job-recovery`. Production release workflow requires a healthy live Phase 5 backend, signature checks, and a versioned `.release/1.1.0-publish.json` approval marker.

## Release and acceptance gates
1. GitHub Actions backend Node suite, Android unit tests, debug assembly, production-signed release and explicit live backend smoke checks pass.
2. Production `addisreel-catalog` Worker contains phase 5 plus retry API and is deployed to 100%. Unauthenticated POST to the retry endpoint must return HTTP 401.
3. Version 1.1.0 (versionCode 20) must use the previous package ID `et.addisreel.app` and the unchanged signing identity for in-place installation.
4. Release APK is published as immutable `releases/1.1.0/AddisReel.apk` in Cloudflare R2. `releases/latest.json` is updated only after the APK upload succeeds.
5. A physical Android phone should confirm installation, resume, bookmarking, recommendations, and Wi-Fi/metered playback transitions. CI cannot certify device behavior.

## Current limitations
- The Cloudflare account still has no connected FFmpeg host or processor token; server-side automatic video transcoding is not active. The retry button queues work, it does not manufacture FFmpeg capacity.
- There is no cross-device recommendation sync or per-viewer analytics, by design.
- For HLS, the preloader warms only the master manifest, not HLS segments or video data.
- No silent Android APK installation is possible without the operating system user confirmation on ordinary devices.
