# AddisReel Phase 3 — Content Studio and Video Infrastructure

Architecture: one isolated Cloudflare Worker (addisreel-catalog), one D1 database (addisreel-catalog), one private R2 bucket (addisreel-media). Nothing in this package changes the existing AddisReel APK update Worker or release bucket.

## Endpoints

- https://addisreel-catalog.eddieaman475.workers.dev/health
- GET /v1/catalog — public licensed/published series, including episode count
- GET /v1/series/:id/episodes — public episode metadata
- GET /v1/play/:id/:number — HLS playback resolver
- GET /v1/media/:id/:number/:file — private R2 media gateway (checks publication rights)
- GET /v1/posters/:id — licensed series posters
- GET /admin — Content Studio interface
- GET /v1/admin/series — authenticated catalog management
- POST /v1/admin/series, POST /v1/admin/episodes — authenticated upserts
- PUT /v1/admin/posters/:id — authenticated poster upload (maximum 5 MB)
- PUT /v1/admin/hls/:id/:episode/:file — authenticated HLS upload (maximum 16 MB per asset)

## One-time secure admin setup

In the Cloudflare dashboard, open Workers & Pages, select addisreel-catalog, then Settings, Variables and Secrets. Add an encrypted secret named ADMIN_TOKEN with a unique random value at least 32 characters long. Keep it in your password manager, not in Git, an APK, chat messages, or screenshots. Without a secret the admin APIs fail closed with HTTP 503. The Content Studio never stores the token in local storage; enter it when connecting.

The Worker uses bindings DB (D1 database c37a1d75-d548-4d69-b6ed-498677f9ba16) and MEDIA (R2 bucket addisreel-media). Do not make the R2 bucket publicly readable.

## Publishing a licensed drama

1. Obtain and document legal distribution rights for the series and each episode. The admin rights checkbox is an attestation; the system does not automatically establish a legal license.
2. In /admin, create a new drama in Draft and a new episode in Draft.
3. On your authorized computer, transcode the video into prepared adaptive HLS with at least a 480p and 720p rendition, master.m3u8, child playlists, and media segments. R2 stores files but does NOT transcode video.
4. Use the Studio directory selector to upload the entire HLS folder. It preserves subfolders under series/{slug}/episodes/{episode}/ and expects master.m3u8 at the root of that episode directory. Individual HLS files must be under 16 MB.
5. Upload licensed poster art (JPG, PNG, or WebP, maximum 5 MB).
6. Check your media packaging, file paths, and license rights. Mark the episode Licensed and Published, then the series Licensed and Published. Publishing an episode without master.m3u8 is rejected.
7. Relaunch Android to refresh published catalog, and test the Watch screen and HLS bitrate adaptation.

Example FFmpeg command for an input WITH an audio track, run after creating the hls directory:

    mkdir -p hls
    ffmpeg -i licensed_input.mp4 \
      -filter_complex "[0:v]split=2[v720][v480];[v720]scale=720:-2[o720];[v480]scale=480:-2[o480]" \
      -map "[o720]" -map 0:a:0 -map "[o480]" -map 0:a:0 \
      -c:v:0 libx264 -b:v:0 1600k -maxrate:v:0 1750k -bufsize:v:0 3200k \
      -c:v:1 libx264 -b:v:1 650k -maxrate:v:1 715k -bufsize:v:1 1300k \
      -c:a:0 aac -b:a:0 96k -c:a:1 aac -b:a:1 64k \
      -f hls -hls_time 4 -hls_playlist_type vod \
      -hls_segment_filename 'hls/v%v/seg_%04d.ts' \
      -master_pl_name master.m3u8 -var_stream_map 'v:0,a:0 v:1,a:1' \
      'hls/v%v/index.m3u8'

Check the master playlist and segments before marking the episode published. Sources without audio need adjusted mapping. The Android app uses Media3 ExoPlayer with HLS.

## Security and limitations

- Series AND episode must both be published and marked licensed to become publicly visible.
- All public catalog and playback endpoints are read-only. Admin mutations require the Worker-secret bearer token.
- The HLS gateway intentionally serves published FREE titles publicly; this is not DRM and does not prevent copying. Premium entitlement/payment enforcement is a later phase.
- Cloudflare Stream is not currently enabled on the account. Pre-encoded HLS in private R2 is the Phase 3 fallback; automated transcoding is not claimed.
- No licensed films were supplied. The public catalog is expected to be empty, and Android preview entries are explicitly identified as previews.
- CI runs Node Worker API tests, Android debug build, and signed release build.

## Release

Phase 3 must ship all together as Android 0.1.6, versionCode 7. Do not merge or publish until the complete Android and backend test suite passes and the admin secret and live API are verified. Publish via the existing in-app release workflow after successful verification.
