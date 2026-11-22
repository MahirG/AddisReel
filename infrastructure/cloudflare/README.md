# AddisReel R2 update service

The source repository stays private. The public Worker exposes only read-only release metadata and APK downloads. R2 remains private.

## Cloudflare configuration

1. Create private R2 bucket `addisreel-android-releases`.
2. Deploy `addisreel-updates-worker.js` as a Worker with R2 binding `RELEASES` pointing at that bucket.
3. Publish a **stable-signed** APK to `releases/0.1.2/AddisReel.apk` and its verified SHA-256 in `releases/latest.json`.
4. Set the Android build property `ADDISREEL_UPDATE_BASE_URL` to the public HTTPS Worker origin. Do not commit credentials.
5. Test `GET /v1/latest`, `GET /v1/apk/0.1.2`, SHA-256, signing certificate continuity, and Android installer consent.

Example metadata:
```json
{"version":"0.1.2","versionCode":3,"notes":"Playback improvements","objectKey":"releases/0.1.2/AddisReel.apk","sha256":"<64 lowercase hex characters>"}
```

Do not publish until the metadata hash matches the actual APK. Use a long-lived signing key stored securely outside Git. Android 8+ may require permission to install unknown apps. Google Play-distributed builds should use Play In-App Updates instead of sideload installation where required by store policies.
