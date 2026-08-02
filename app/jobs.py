from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from uuid import uuid4
from engine.pipeline import process_file
from engine.config import EngineConfig

@dataclass
class Job:
    id: str
    source: str
    status: str = "queued"
    result: dict | None = None
    error: str | None = None

class JobQueue:
    def __init__(self, output_dir: Path, cfg: EngineConfig, workers: int = 1):
        self.output_dir = output_dir
        self.cfg = cfg
        self.jobs: dict[str, Job] = {}
        self.lock = Lock()
        self.pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="podcast-engine")

    def submit(self, source: str) -> Job:
        job = Job(id=uuid4().hex, source=source)
        with self.lock:
            self.jobs[job.id] = job
        self.pool.submit(self._run, job.id)
        return job

    def _run(self, job_id: str) -> None:
        job = self.jobs[job_id]
        job.status = "running"
        try:
            job.result = process_file(job.source, self.output_dir, self.cfg)
            job.status = "done"
        except Exception as exc:
            job.error = f"{type(exc).__name__}: {exc}"
            job.status = "failed"

    def list(self) -> list[Job]:
        with self.lock:
            return list(self.jobs.values())
