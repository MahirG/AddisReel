#!/usr/bin/env python3
"""Prepare a licensed MP4 as adaptive HLS for AddisReel Studio upload.

Usage: python3 backend/tools/prepare_hls.py input.mp4 output-directory
Requires ffmpeg and ffprobe installed. This is an offline processing tool,
NOT an automatic Cloudflare background transcoding service.
"""
import json
import pathlib
import shutil
import subprocess
import sys

def run(args):
    subprocess.run(args, check=True)

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: prepare_hls.py INPUT.mp4 OUTPUT_DIRECTORY")
    source = pathlib.Path(sys.argv[1]).resolve()
    output = pathlib.Path(sys.argv[2]).resolve()
    if not source.is_file() or source.suffix.lower() != ".mp4":
        raise SystemExit("Input must be an existing MP4")
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Output directory must be empty (avoid stale segments)")
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            raise SystemExit(f"{tool} is required")
    info = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_streams", "-of", "json", str(source)
    ]))
    if not any(s.get("codec_type") == "video" for s in info.get("streams", [])):
        raise SystemExit("Input has no video stream")
    has_audio = any(s.get("codec_type") == "audio" for s in info.get("streams", []))
    output.mkdir(parents=True, exist_ok=True)
    # Two renditions share the same source but have independent HLS playlists.
    # Use scaled-down variants only; never upscale low-resolution sources.
    width = next(s.get("width", 0) for s in info["streams"] if s.get("codec_type") == "video")
    height = next(s.get("height", 0) for s in info["streams"] if s.get("codec_type") == "video")
    # For portrait shorts, target the short edge to preserve portrait framing.
    short_edge = min(width, height)
    targets = [x for x in (480, 720) if short_edge >= x]
    if not targets:
        targets = [short_edge // 2 * 2]
    variants = []
    for target in targets:
        label = f"v{target}"
        folder = output / label
        folder.mkdir(exist_ok=True)
        args = ["ffmpeg", "-hide_banner", "-y", "-i", str(source),
                "-map", "0:v:0"]
        if has_audio:
            args += ["-map", "0:a:0"]
        args += ["-vf", f"scale={target}:-2" if width <= height else f"scale=-2:{target}",
                 "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                 "-pix_fmt", "yuv420p", "-g", "48", "-keyint_min", "48",
                 "-sc_threshold", "0"]
        if has_audio:
            args += ["-c:a", "aac", "-b:a", "96k"]
        args += ["-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
                 "-hls_segment_filename", str(folder / "segment_%05d.ts"),
                 str(folder / "index.m3u8")]
        run(args)
        variants.append((label, target))
    lines = ["#EXTM3U", "#EXT-X-VERSION:3"]
    for label, target in variants:
        # Conservative advertised bandwidth; clients adapt from actual throughput.
        bandwidth = 1_200_000 if target <= 480 else 2_500_000
        lines += [f"#EXT-X-STREAM-INF:BANDWIDTH={bandwidth}", f"{label}/index.m3u8"]
    (output / "master.m3u8").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Prepared {len(variants)} HLS rendition(s) in {output}")
    print("Upload the entire output folder to the episode's HLS directory in Studio.")

if __name__ == "__main__":
    main()
