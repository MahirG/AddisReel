# AddisReel Studio — Native Android 1.0.0

Standalone administrator Android APK, package **`et.addisreel.studio`**. It does **not** replace the viewer app `et.addisreel.app`.

## Use

1. Install the signed **AddisReel-Studio-Signed-APK** artifact from GitHub Actions.
2. Open **AddisReel Studio**, sign in using the existing Studio owner account.
3. Use the familiar website-based wizard to create or select a series and **Save** it.
4. Tap the gold **Upload MP4** button in the native app header; choose your MP4 through Android's Documents picker, confirm the episode title, and start the transfer.
5. Minimize Studio, lock the phone, or use other apps. Your transfer uses Android 14+ user-initiated data transfer jobs, or an Android 7–13 notification-backed foreground service.
6. Watch the progress notification. Use **Pause** or **Cancel** there, and reopen Studio to **Resume**. After completion the MP4 is available in the same backend catalog and automatically queued for the existing FFmpeg optimizer.

## Security and data handling

- Web Studio is loaded only from `https://ar.eddieaman475.workers.dev/admin`.
- Content selection uses persistable Android Storage Access Framework document permissions, not blanket filesystem access.
- Transfer uses the owner's own WebView session via Android CookieManager; no API token, session secret, or administrator password is compiled into the APK.
- File bytes are read from the user-selected document and uploaded over HTTPS in 8 MiB multipart chunks to the existing Cloudflare R2 bindings.
- The app persists **only** document URI, episode metadata, R2 session ID, verified part ETags, and progress in private Android preferences. Never the video bytes or authentication cookie.
- Each upload creates its draft episode if necessary. Publishing and licensing confirmation still happen inside Studio.
- File limit remains **1 GiB** per MP4, matching the server.
- Background transfers are subject to Android's system controls. User force-stop, OS resource limits, revoked document access, and expired sign-in sessions can interrupt work. Completed R2 chunks are resumable while the upload session remains valid.
- Android 14+ implementation uses `JobScheduler.setUserInitiated(true)` and `JobService.setNotification`. Older OS versions use a `dataSync` foreground service. Transfers are explicitly started by a user tap.
- Signing: the Studio signed APK uses the repository's existing secure GitHub Actions Android signing secrets where configured; unsigned/debug is provided separately for development. Do not uninstall an existing signed Studio version and replace it with an independently signed debug version if you want later in-place upgrades.

## Build

```bash
gradle :studio:assembleDebug --no-daemon
# or
gradle :studio:assembleRelease --no-daemon
```

GitHub Actions workflow: `.github/workflows/studio-android.yml`. The APK is in `studio/build/outputs/apk/`.

## Validation status

The CI build compiles and cryptographically checks the APK. A physical Android login, 1 GB upload, minimized transfer, Pause/Resume, and post-upload FFmpeg playback still require device end-to-end verification before claiming full production readiness.
