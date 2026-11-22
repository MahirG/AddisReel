# AddisReel Windows Video Optimizer

**Free local FFmpeg encoding, using existing Cloudflare R2.** No Cloudflare Stream subscription is needed.

## Setup (once)

1. Install **Python 3.10+** and **FFmpeg** on the Windows PC, including `ffprobe`. Check both are in PATH with `python --version`, `ffmpeg -version`, and `ffprobe -version`.
2. Sign in to [AddisReel Studio](https://addisreel-catalog.eddieaman475.workers.dev/admin), go to **My Account → Windows Video Optimizer**, and tap **Generate pairing key**.
3. On your PC, open PowerShell in this folder and run `./start-processor.ps1`. Paste the secret in the hidden prompt. The script encrypts it with Windows DPAPI for your Windows user only.
4. Leave PowerShell open when you want automatic processing. Every completed MP4 upload creates a job. When the PC is online, it downloads, compresses and verifies the video and puts a smaller version in R2. The viewer automatically uses the optimized version after successful verification.

If PowerShell blocks the script, temporarily run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\start-processor.ps1
```

**Re-pair:** In Studio, generate a new token and delete `%LOCALAPPDATA%\AddisReelProcessor\token.dpapi` on the PC. The old token is immediately revoked.

## Quality and safety

- Source stays private in R2 and is **never deleted**.
- Default encode: H.264 (libx264), CRF 23, medium preset, AAC 128 kbps, streaming-friendly MP4.
- Portrait videos: up to **720×1280**. Landscape: up to **1280×720**. No upscaling.
- FFprobe checks frame size, codec and duration; FFmpeg fully decodes the output for integrity.
- If the new video isn't at least 5% smaller, keep the original.
- Optimized output is uploaded in 8 MiB parts and activated only when verification completes.
- Enough free disk space is needed for both original and converted files.
- Existing series details, posters, episode ordering and publishing rights are unchanged.
- No free cloud transcoding is claimed. CPU/electricity, bandwidth and R2 usage may cost money.

**Optional: start on Windows login.** After successful pairing, run `./enable-at-login.ps1` once. This creates a shortcut in your Windows Startup folder. Delete that shortcut to turn off auto-start.

**The PC needs to stay on with this script running.** If it is off, jobs remain queued; no content is lost. Press Ctrl+C to stop.
