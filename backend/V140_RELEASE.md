# AddisReel 1.4.0 · Build 23 — Watch Integrity and Resume Reliability

## Changes
- Viewing History and last-watched title update after real playback starts and advances to 3 seconds (or when an unusually short episode genuinely ends). Merely selecting or opening a drama, tapping a chapter, searching, saving, or displaying an unavailable stream does not create a new history entry.
- Most-recently-played episode is updated only after actual playback, not a tap. The 12-title local recents list and on-device recommendation system retain their privacy protections.
- Continue Watching now skips completed episodes using `PlaybackPolicy.resumeEpisode` when reopening a drama, restoring a published catalog, and returning to Library.
- Resume position is clamped to the known media duration with a 5-second safety margin so a changed-length rendition or a nearly finished interrupted video does not get stuck at END without marking it completed. Unknown duration preserves the valid saved point.
- Saved Library rows distinguish **Start watching**, **Continue**, and **Watch again**. Opening a preview title does not add a real watched event.
- The existing bounded, private SharedPreferences progress persistence remains. Nothing about user behavior is uploaded to the backend and the existing Cloudflare catalog and video-processing services remain unchanged.

## Quality and release gates
- Pure Kotlin unit tests cover minimum real-playback threshold, edge cases on resume position, normal playback decisions, and prior regressions.
- GitHub Actions must pass Node backend tests, Android unit tests, APK assembly, production signature verification and live Cloudflare playback/caching checks.
- The one-time signed publishing workflow must publish the immutable APK to `releases/1.4.0/AddisReel.apk` before promoting the manifest to versionCode 23.
- Confirm installation, 3-second watch history, navigating away before playback, resuming near the ending, and Watch Again on a real Android phone.

## Limitations
- Legacy history recorded by older app versions remains untouched; the new strict semantics apply to newly recorded watch events. Avoid discarding real user history during upgrade.
- Physical device playback quality and battery consumption must still be measured. CI does not certify a phone installation.
- An external FFmpeg host and secret remain required for automated video transcoding. This milestone does not change it.
