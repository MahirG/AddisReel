#!/usr/bin/env python3
"""AddisReel's isolated FFmpeg worker.

Run on a trusted machine with FFmpeg, FFprobe and PROCESSOR_TOKEN configured.
The Cloudflare Worker only stores/serves data; it does not perform transcoding.
This runner downloads sources from authenticated API endpoints, converts them to
adaptive HLS, sends verified rendition files, then marks the job ready.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from urllib import error, parse, request

API = os.getenv("ADDISREEL_CATALOG_URL", "https://addisreel-catalog.eddieaman475.workers.dev").rstrip("/")
TOKEN = os.getenv("PROCESSOR_TOKEN", "")
MAX_SOURCE = 2 * 1024 * 1024 * 1024
MAX_HLS_FILE = 16 * 1024 * 1024


def call(path, method="GET", body=None, binary=False, sha256=None):
    headers = {"Authorization": "Bearer " + TOKEN, "Accept": "application/json"}
    if sha256:
        headers["X-Upload-SHA256"] = sha256
        headers["Content-Type"] = "application/octet-stream"
    elif body is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(body).encode("utf-8")
    req = request.Request(API + path, data=body, headers=headers, method=method)
    try:
        response = request.urlopen(req, timeout=120)
        if binary:
            return response
        with response:
            return json.load(response)
    except error.HTTPError as exc:
        message = exc.read(1024).decode("utf-8", "replace")
        raise RuntimeError(f"{method} {path}: HTTP {exc.code} {message}") from exc


def report(job_id, status, percent, message=None):
    payload = {"status": status, "progress": percent}
    if message is not None:
        payload["error"] = str(message)[:300]
    return call("/v1/processor/jobs/" + job_id, method="PATCH", body=payload)


def download_source(job_id, destination):
    response = call("/v1/processor/jobs/" + job_id + "/source", binary=True)
    size = 0
    with response, destination.open("wb") as output:
        while True:
            data = response.read(1024 * 1024)
            if not data:
                break
            size += len(data)
            if size > MAX_SOURCE:
                raise RuntimeError("Source exceeds the configured 2 GiB safety limit")
            output.write(data)
    if size < 1024:
        raise RuntimeError("Source is empty or not a valid MP4")
    return size


def process(job):
    job_id = job["id"]
    if not isinstance(job_id, str) or len(job_id) != 36:
        raise RuntimeError("Invalid claimed job ID")
    with tempfile.TemporaryDirectory(prefix="addisreel-processor-") as tmp:
        root = Path(tmp)
        source = root / "source.mp4"
        destination = root / "hls"
        download_source(job_id, source)
        report(job_id, "processing", 5)
        script = Path(__file__).with_name("prepare_hls.py")
        subprocess.run([sys.executable, str(script), str(source), str(destination)],
                       check=True, timeout=60 * 60)
        files = sorted(p for p in destination.rglob("*") if p.is_file())
        master = destination / "master.m3u8"
        if master not in files or len(files) < 3:
            raise RuntimeError("Transcoding produced no valid HLS rendition files")
        files.remove(master)
        files.append(master)  # Publish master last, after the segments exist.
        total = sum(p.stat().st_size for p in files)
        if total < 1:
            raise RuntimeError("Transcoding produced empty HLS files")
        confirmed = 0
        for file in files:
            relative = file.relative_to(destination).as_posix()
            if file.is_symlink() or ".." in relative.split("/"):
                raise RuntimeError("Unsafe transcoded output filename")
            size = file.stat().st_size
            if size < 1 or size > MAX_HLS_FILE:
                raise RuntimeError(f"Output file {relative} is outside the 1-16 MB limit")
            data = file.read_bytes()
            checksum = hashlib.sha256(data).hexdigest()
            url = "/v1/processor/jobs/" + job_id + "/files/" + parse.quote(relative, safe="/")
            call(url, method="PUT", body=data, sha256=checksum)
            confirmed += size
            report(job_id, "processing", min(99, 5 + (confirmed * 94 // total)))
        report(job_id, "ready", 100)
    return job_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--poll", type=int, default=0,
                        help="Poll every N seconds; omit to process one job and exit")
    args = parser.parse_args()
    if len(TOKEN) < 32:
        raise SystemExit("PROCESSOR_TOKEN secret is missing (minimum 32 characters)")
    if not all(shutil.which(p) for p in ("ffmpeg", "ffprobe")):
        raise SystemExit("ffmpeg and ffprobe must be installed on the processing host")
    while True:
        job = call("/v1/processor/claim", method="POST").get("job")
        if job:
            try:
                identifier = process(job)
                print(f"Processed job {identifier}", flush=True)
            except Exception as exc:
                print(f"Job failed: {exc}", file=sys.stderr, flush=True)
                try:
                    report(job["id"], "failed", 0, str(exc))
                except Exception as status_error:
                    print(f"Could not report job failure: {status_error}", file=sys.stderr)
                if not args.poll:
                    raise
        elif not args.poll:
            print("No queued processing jobs")
        if not args.poll:
            return
        time.sleep(max(10, args.poll))


if __name__ == "__main__":
    main()
