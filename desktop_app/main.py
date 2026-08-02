from __future__ import annotations

import dataclasses
import os
import sys
import threading
import traceback
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import webview


def resource_root() -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS)
    return Path(__file__).resolve().parents[1]


ROOT = resource_root()
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

BIN_DIR = ROOT / "bin"
os.environ["PATH"] = str(BIN_DIR) + os.pathsep + os.environ.get("PATH", "")

from engine.config import load_config
from engine.pipeline import process_file


DEFAULT_OUTPUT = Path.home() / "Documents" / "ACRPS Podcast Outputs"
DEFAULT_OUTPUT.mkdir(parents=True, exist_ok=True)


class DesktopApi:
    def __init__(self) -> None:
        self.window = None
        self.output_dir = DEFAULT_OUTPUT
        self.lock = threading.RLock()
        self.jobs: dict[str, dict] = {}
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="acrps-podcast")
        config = load_config(ROOT / "config" / "default.yaml")
        self.config = dataclasses.replace(
            config,
            deepfilter_model=str(ROOT / "models" / "DeepFilterNet3"),
            neural_required=True,
        )

    def attach_window(self, window) -> None:
        self.window = window

    def state(self) -> dict:
        model_ok = (ROOT / "models" / "DeepFilterNet3" / "config.ini").exists()
        ffmpeg_ok = (BIN_DIR / "ffmpeg.exe").exists() and (BIN_DIR / "ffprobe.exe").exists()
        return {
            "ready": bool(model_ok and ffmpeg_ok),
            "model_ready": model_ok,
            "ffmpeg_ready": ffmpeg_ok,
            "output_dir": str(self.output_dir),
            "version": "1.0.0-portable",
        }

    def choose_files(self) -> list[str]:
        if self.window is None:
            return []
        result = self.window.create_file_dialog(
            webview.FileDialog.OPEN,
            allow_multiple=True,
            file_types=(
                "Audio Files (*.wav;*.mp3;*.m4a;*.aac;*.flac;*.ogg;*.opus;*.mpeg)",
                "All files (*.*)",
            ),
        )
        return list(result or [])

    def choose_output_folder(self) -> str:
        if self.window is None:
            return str(self.output_dir)
        result = self.window.create_file_dialog(
            webview.FileDialog.FOLDER,
            directory=str(self.output_dir),
        )
        if result:
            selected = Path(result[0] if isinstance(result, (list, tuple)) else result)
            selected.mkdir(parents=True, exist_ok=True)
            self.output_dir = selected
        return str(self.output_dir)

    def start_jobs(self, paths: list[str]) -> list[dict]:
        accepted: list[dict] = []
        for raw in paths or []:
            source = Path(raw)
            if not source.is_file():
                continue
            job_id = uuid4().hex
            record = {
                "id": job_id,
                "source": str(source),
                "name": source.name,
                "status": "queued",
                "stage": "في قائمة الانتظار",
                "error": None,
                "result": None,
            }
            with self.lock:
                self.jobs[job_id] = record
            self.pool.submit(self._run_job, job_id)
            accepted.append(dict(record))
        return accepted

    def _run_job(self, job_id: str) -> None:
        with self.lock:
            job = self.jobs[job_id]
            job["status"] = "running"
            job["stage"] = "تحميل المحرك العصبي ومعالجة الملف"
            source = job["source"]
        try:
            report = process_file(source, self.output_dir, self.config)
            with self.lock:
                job = self.jobs[job_id]
                job["status"] = "done"
                job["stage"] = "اكتملت المعالجة"
                job["result"] = report
        except Exception as exc:
            with self.lock:
                job = self.jobs[job_id]
                job["status"] = "failed"
                job["stage"] = "فشلت المعالجة"
                job["error"] = f"{type(exc).__name__}: {exc}"
                job["trace"] = traceback.format_exc()

    def list_jobs(self) -> list[dict]:
        with self.lock:
            return [dict(item) for item in self.jobs.values()]

    def open_output_folder(self) -> bool:
        self.output_dir.mkdir(parents=True, exist_ok=True)
        os.startfile(str(self.output_dir))
        return True

    def open_result(self, path: str) -> bool:
        candidate = Path(path)
        if candidate.exists():
            os.startfile(str(candidate))
            return True
        return False

    def shutdown(self) -> None:
        self.pool.shutdown(wait=False, cancel_futures=False)


def self_test() -> int:
    api = DesktopApi()
    state = api.state()
    if not state["ready"]:
        print(state)
        return 2
    from engine.ffmpeg import require_tools
    from engine.neural import DeepFilterNetBackend

    require_tools()
    DeepFilterNetBackend(
        model_name=str(ROOT / "models" / "DeepFilterNet3"),
        post_filter=False,
        attenuation_limit_db=8.0,
    )
    print("SELF_TEST_OK")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    api = DesktopApi()
    index = ROOT / "desktop_web" / "index.html"
    window = webview.create_window(
        "محرك بودكاست المركز العربي",
        url=index.as_uri(),
        js_api=api,
        width=1180,
        height=800,
        min_size=(900, 620),
        text_select=True,
        confirm_close=False,
    )
    api.attach_window(window)
    window.events.closed += api.shutdown
    webview.start(debug=False, private_mode=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
