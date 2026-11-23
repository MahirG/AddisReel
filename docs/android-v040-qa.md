# AddisReel Android 0.4.0 — release acceptance checklist

This checklist covers only the **viewer Android app**. Do not alter or deploy AddisReel Studio or catalog services as part of the 0.4.0 Android release.

## Automated gates

- [ ] `Android APK` workflow builds the debug APK successfully on Android SDK 35.
- [ ] `:app:testDebugUnitTest` passes the playback policy regression tests.
- [ ] Signed production APK is generated and `apksigner verify` succeeds.
- [ ] Backend catalog API smoke checks remain green.
- [ ] Final installed version is **0.4.0 (versionCode 13)**, signed with the same signing key as the published 0.3.0 APK.
- [ ] Run the production update publishing workflow **only after** device acceptance testing and approval.

## Real-device viewing tests

1. **MP4:** Start a licensed MP4 episode; test play, pause, seek, rewind, switch series and return. Verify it neither crashes nor stays indefinitely buffering.
2. **HLS:** Start an HLS episode and test adaptive playback, Data saver/Auto/High, captions toggle, and network handover.
3. **Preloading:** Watch to the last 25 seconds on validated, unmetered Wi-Fi. Verify the next MP4 can prime no more than 256 KiB in the shared cache. Confirm the same prefetch does **not** occur on mobile data, while ordinary current playback still works.
4. **Link freshness:** After a long episode, verify the upcoming playback URL is refreshed near its end if necessary; do not reuse an expired signed playback link.
5. **Auto-next:** Complete an episode with auto-next enabled, cancel during the countdown, confirm there is no navigation, enable it again and verify next-episode navigation. Confirm the preference survives an app restart.
6. **Watched progress:** Jump manually from EP 1 to EP 5, then open the episode sheet; EP 2–4 must not appear watched. Let EP 5 finish, reopen the sheet and verify EP 5 is marked watched. Reopen the app and verify completion persisted.
7. **Credential recovery:** Simulate a playback HTTP 401/403 response. The app should request a new episode URL at most once, retaining playback position; no unbounded refresh loop.
8. **Connection outage:** Pause connectivity mid-episode, restore Wi-Fi, and verify recovery/resume without resetting viewing position. After repeated failure, the Retry action remains available.
9. **Cinema controls:** On a narrow Android device, open Cinema Mode; episode controls and the scrollable playback settings row must remain usable. Verify quality, speed, captions, video fit/fill, and autoplay toggles.
10. **Resource budget:** Confirm the current media cache remains bounded at 80 MiB. Confirm playback is suspended when the app is backgrounded. No full-episode downloads.
11. **Upgrade:** Install 0.4.0 over the published 0.3.0 using the same Android signing key. Verify existing viewing history, saved titles, speed, captions and quality settings remain intact.

## Release gating

The development branch and draft PR are not a production release. Publishing requires a green latest commit, real-device playback acceptance, and a distinct successful `Publish AddisReel in-app update` run. Do not run publishing from a stale commit.
