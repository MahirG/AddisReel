# AddisReel 1.0.0 / Build 19 — Phase A/B/C delivery

## Scope and verified architecture

- Backend: Cloudflare Worker `addisreel-catalog`, private R2 media, D1 metadata, existing Studio owner authentication.
- Android: Media3 ExoPlayer HLS/MP4 with user-selected auto/high/data-saver bitrates, metered-network limits, bounded network timeouts, bounded cache, episode resume and next-episode prefetch.
- Studio: JPG/PNG/WebP uploads; pre-encoded HLS upload/resume per file; MP4 up to 512 MiB with 8 MiB R2 multipart parts, per-part SHA-256, persistent **non-secret** part ETags in IndexedDB, and exact-size completion.
- Operations: authenticated processing queue status and last-100-jobs aggregate health dashboard. No personal viewer data collected.
- Video processor: standalone FFmpeg runner in `backend/tools/processor_runner.py` consumes `POST /v1/processor/claim`, streams the MP4 through authenticated `GET /v1/processor/jobs/{id}/source`, uploads checksum-verified renditions, then marks ready **only after all referenced HLS playlists and segments are present**.
- Media delivery supports HTTP HEAD and If-None-Match responses. Published titles remain protected by existing licensed/published controls.

## Activation required for automatic transcoding

Cloudflare Stream is not enabled on the current account and Cloudflare Containers require the paid Workers plan. The Cloudflare Worker itself does **not** execute FFmpeg.

To activate an independent processor:
1. Set a strong, random `PROCESSOR_TOKEN` secret (minimum 32 characters) on `addisreel-catalog` using Cloudflare secrets; never commit it to Git.
2. Configure exactly the same token as secret `ADDISREEL_PROCESSOR_TOKEN` in the GitHub repository or on an authorized, securely managed FFmpeg host. Never print it in logs.
3. Run `python3 backend/tools/processor_runner.py` (one job), or `python3 backend/tools/processor_runner.py --poll 30` (continuous host). The optional `video-processor.yml` GitHub Actions workflow can process one job when explicitly dispatched and the secret exists.
4. Open Content Studio, upload an MP4, create a processing job, and verify it transitions queued → processing → ready with HLS renditions. Ensure the rights checkbox was independently verified.
5. For continuous operation deploy the runner to an always-on approved compute host with secrets injected at runtime. No such host or processor secret has been configured by this code change.

## Failure and recovery

- Interrupted 8 MiB MP4 transfers: sign into Studio, reselect the **same MP4 file**, upload again. IndexedDB stores upload IDs and confirmed ETags only. If storage is disabled, a fresh session begins.
- Interrupted HLS folder transfers: reselect the same folder; confirmed files are checked by SHA-256 + byte size and skipped. A partially uploaded file restarts.
- If the Worker rejects a part checksum or wrong part size, it is not stored. If completion fails, retry the same session; do not publish partial media.
- On failed transcoding, inspect the job error in Studio, correct source/codec/host issues and explicitly queue again. Source MP4 stays in R2.
- App release rollback: keep prior signed APK and R2 version object. Restore the update manifest to the last verified version only if appropriate for existing installed versions; Android will not install a lower versionCode over a higher one. Publish a higher-version hotfix instead when Build 19 users are affected.
- Do not remove existing APK signing keys; installer signature continuity is required for in-place updates.

## Quality gates

- Node backend API tests and Android unit tests must pass.
- Debug and release APKs must build and the release APK must pass Android signature verification.
- Cloudflare backend `/health` must report phase 4 with `multipart-mp4` before Build 19 is published.
- In-app update manifest must report `1.0.0`, `versionCode:19`, HTTPS APK URL and SHA-256; signed APK package `et.addisreel.app` must match.
- Physical Android Home → drama → playback → background/return → next episode → library and installer acceptance require real-device testing. CI cannot replace those tests.

## Limitations

- Only available processing logic is implemented; automatic conversions are inactive until an authorized FFmpeg host and processor token are configured.
- 512 MiB max browser MP4 upload; one multipart part 8 MiB. Browser transfer does not continue when browser or OS stops it.
- Adaptive quality requires at least two real HLS renditions. A single MP4 cannot supply ABR by itself.
- A native Android application cannot silently install an APK without the OS confirmation prompt on normal user devices.
- No automated personalized recommendations or private viewer analytics have been deployed.
