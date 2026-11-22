#!/usr/bin/env python3
"""AddisReel Windows FFmpeg processor; Python 3.10+, ffmpeg and ffprobe."""
import argparse
import json
import logging
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

API = "https://addisreel-catalog.eddieaman475.workers.dev"
LOG = logging.getLogger("addisreel.processor")
TOKEN = os.environ.get("ADDISREEL_PROCESSOR_TOKEN", "").strip()

def api(path, method="GET", data=None, lease=None, timeout=180):
    headers = {"Authorization": "Bearer " + TOKEN, "Accept": "application/json"}
    if lease:
        headers["X-Processor-Lease"] = lease
    if isinstance(data, dict):
        data = json.dumps(data).encode("utf8")
        headers["Content-Type"] = "application/json"
    elif data is not None:
        headers["Content-Type"] = "application/octet-stream"
    req = Request(API + path, data=data, headers=headers, method=method)
    try:
        with urlopen(req, timeout=timeout) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError("Processor HTTP %s: %s" % (error.code,
            error.read(350).decode("utf8", "replace"))) from error

def probe(path):
    proc = subprocess.run(["ffprobe", "-v", "error", "-show_format", "-show_streams",
        "-of", "json", str(path)], capture_output=True, text=True, check=True, timeout=120)
    info = json.loads(proc.stdout)
    tracks = [s for s in info.get("streams", []) if s.get("codec_type") == "video"]
    if not tracks:
        raise ValueError("No video track found")
    track = tracks[0]
    return {"width": int(track.get("width") or 0), "height": int(track.get("height") or 0),
        "duration": float(track.get("duration") or info.get("format",{}).get("duration") or 0),
        "codec": track.get("codec_name")}

def encode(source, destination):
    before = probe(source)
    if before["width"] < 2 or before["height"] < 2 or before["duration"] <= 0:
        raise ValueError("Source MP4 has invalid dimensions or duration")
    width, height = (720, 1280) if before["height"] > before["width"] else (1280, 720)
    # Adapt to portrait or landscape and do not upscale lower-resolution footage.
    scale = ("scale=w='min(iw,%d)':h='min(ih,%d)':" % (width, height) +
        "force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos")
    command = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", str(source),
        "-map", "0:v:0", "-map", "0:a:0?", "-vf", scale,
        "-c:v", "libx264", "-preset", "medium", "-crf", "23",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-ac", "2",
        "-movflags", "+faststart", "-map_metadata", "-1", str(destination)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=4*3600)
    if result.returncode:
        raise RuntimeError("FFmpeg conversion failed: " + result.stderr[-500:])
    after = probe(destination)
    if after["codec"] != "h264" or after["width"] > width or after["height"] > height:
        raise ValueError("Video resolution or codec validation failed")
    if abs(after["duration"]-before["duration"]) > max(2.5, 0.02*before["duration"]):
        raise ValueError("Output video duration mismatch")
    verify = subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i",
        str(destination), "-f", "null", "-"], capture_output=True, text=True, timeout=4*3600)
    if verify.returncode:
        raise ValueError("The compressed MP4 contains unreadable frames")
    return before, after

def source_download(job, file):
    path = "/v1/processor/source/%s/%s" % (job["seriesId"], job["episodeNumber"])
    req = Request(API + path, headers={"Authorization":"Bearer "+TOKEN,
        "X-Processor-Lease":job["lease"]})
    with urlopen(req, timeout=180) as response, file.open("wb") as target:
        while True:
            block = response.read(1024*1024)
            if not block: break
            target.write(block)
    if file.stat().st_size != job["originalBytes"]:
        raise ValueError("Original MP4 download did not pass integrity check")

def output_upload(job, file):
    prefix = "/v1/processor/output/%s/%s" % (job["seriesId"],job["episodeNumber"])
    size = file.stat().st_size
    upload = api(prefix+"?action=start","POST",{"size":size},lease=job["lease"])
    items = []
    with file.open("rb") as stream:
        index = 0
        while True:
            part = stream.read(upload["partSize"])
            if not part: break
            index += 1
            for attempt in range(3):
                try:
                    result = api(prefix+"?"+urlencode({"action":"part","uploadId":upload["uploadId"],
                        "partNumber":index}),"PUT",part,lease=job["lease"])
                    items.append({"partNumber":result["partNumber"],"etag":result["etag"]})
                    break
                except (URLError, RuntimeError):
                    if attempt==2: raise
                    time.sleep(2*(attempt+1))
            LOG.info("Uploaded video part %d/%d",index,upload["totalParts"])
    api(prefix+"?"+urlencode({"action":"finish","uploadId":upload["uploadId"]}),
        "POST",{"size":size,"parts":items},lease=job["lease"])
    result=api(prefix+"?action=complete","POST",{"size":size},lease=job["lease"])
    if result.get("status")!="ready":
        raise ValueError("Server did not verify optimized MP4")
    return result

def heartbeat(job,stop):
    path="/v1/processor/output/%s/%s?action=heartbeat" % (job["seriesId"],job["episodeNumber"])
    while not stop.wait(300):
        try: api(path,"POST",{},lease=job["lease"])
        except Exception as error: LOG.warning("Processor heartbeat failed: %s",error)

def process(job):
    LOG.info("Optimizing %s episode %s",job["seriesId"],job["episodeNumber"])
    prefix="/v1/processor/output/%s/%s" % (job["seriesId"],job["episodeNumber"])
    stop=threading.Event()
    thread=threading.Thread(target=heartbeat,args=(job,stop),daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="addisreel-") as folder:
            original=Path(folder)/"original.mp4"
            optimized=Path(folder)/"optimized.mp4"
            source_download(job,original)
            encode(original,optimized)
            before=original.stat().st_size
            after=optimized.stat().st_size
            if after >= 0.95 * before:
                api(prefix+"?action=skip","POST",{"reason":"Original MP4 already efficient"},
                    lease=job["lease"])
                LOG.info("No worthwhile size reduction; preserved original")
            else:
                output_upload(job,optimized)
                LOG.info("Verified compression: %.1f%% smaller",100*(1-after/before))
    except Exception as error:
        LOG.error("Episode processing failed: %s",error)
        try: api(prefix+"?action=failed","POST",{"reason":str(error)[:160]},lease=job["lease"])
        except Exception: LOG.error("Failure could not be reported; the job lease will expire")
    finally:
        stop.set()
        thread.join(timeout=1)

def main():
    parser=argparse.ArgumentParser(description="AddisReel automatic video optimizer")
    parser.add_argument("--once",action="store_true",help="Process one video and exit")
    args=parser.parse_args()
    logging.basicConfig(level=logging.INFO,format="%(asctime)s %(levelname)s %(message)s")
    if len(TOKEN)<40:
        raise SystemExit("Missing processor pairing key. Open AddisReel Studio → My Account.")
    for app in ("ffmpeg","ffprobe"):
        if not shutil.which(app): raise SystemExit(app+" not installed or not in PATH")
    LOG.info("Connected. Videos will be processed while this PC is running.")
    while True:
        try:
            job=api("/v1/processor/claim","POST",{}).get("job")
            if job: process(job)
            elif args.once: break
            else: time.sleep(30)
        except KeyboardInterrupt: break
        except (RuntimeError,URLError) as error:
            LOG.error("Processor connection failed: %s",error)
            if args.once: raise
            time.sleep(45)

if __name__=="__main__":
    main()
