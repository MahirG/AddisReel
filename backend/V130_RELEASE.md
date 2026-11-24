# AddisReel 1.3.0 (Build 22) — Network-Smart Playback

## Implemented

- Opening the app on Home, searching stories, or browsing the Library no longer calls `/v1/play`. The player resolves the current episode only when the Watch surface is visible.
- Switching away from Watch clears in-memory playback URL state; it never persists media authorization tokens to device storage. A keyed stream guard prevents a URL resolved for an old episode from being shown on a newly chosen episode.
- The next episode is **not** resolved eagerly when current playback starts. The existing player asks for it near the final 35 seconds, and Wi-Fi-only, bounded manifest/MP4 preloading happens near the final 25 seconds.
- Thumbnail card requests default to the optimized 320px poster WebP instead of 640px for compact 112–142dp cards. Hero artwork retains the existing 960px high-resolution version.
- Poster preload is limited to two below-the-fold images, and only when the Home surface is visible and Android reports a validated unmetered network. No speculative poster warmups on mobile data or captive portal networks.
- Does not change the currently deployed Content Studio, catalog rights protections, the existing Android package identifier or signing key.

## Quality checks

- Unit tests cover on-demand video resolution, preview/malformed IDs, validated network poster warmups, and bounded poster tile dimensions.
- GitHub Actions runs backend unit tests, Android unit tests, debug build, signed-release APK and live backend smoke.
- The Build 22 publication workflow checks the stable Phase 6 backend, production signing, and the monotonic versionCode guard. Upload immutable `releases/1.3.0/AddisReel.apk` before updating `releases/latest.json`.

## Explicitly not claimed

- The Android OS does not permit silent APK installation on normal devices; the user must accept the in-app update install confirmation.
- The app doesn't download full videos for offline watching. Saved posters and public catalog metadata can work offline within existing bounds; licensed playback requires live server approval.
- Real startup latency, battery, video buffering and Wi-Fi/cellular transitions still need measurement on a physical phone. No synthetic performance numbers are claimed.
- Automatic FFmpeg conversion still requires a separate authorized processing host and matching encrypted credential; the currently deployed backend doesn't transcode by itself.
