from __future__ import annotations
import json
import shutil
import subprocess
from pathlib import Path

class FFmpegError(RuntimeError):
    pass


def require_tools() -> None:
    missing = [x for x in ("ffmpeg", "ffprobe") if shutil.which(x) is None]
    if missing:
        raise FFmpegError("Missing required tools: " + ", ".join(missing))


def probe(path: str | Path) -> dict:
    require_tools()
    cmd = ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)]
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode:
        raise FFmpegError(p.stderr.strip())
    return json.loads(p.stdout)


def run(args: list[str]) -> None:
    require_tools()
    p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode:
        raise FFmpegError(p.stderr.strip())


def duration_seconds(path: str | Path) -> float:
    info = probe(path)
    return float(info.get("format", {}).get("duration", 0.0))
