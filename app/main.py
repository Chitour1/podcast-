from __future__ import annotations
import shutil
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from engine.config import load_config
from app.jobs import JobQueue

ROOT = Path(__file__).resolve().parents[1]
UPLOADS = ROOT / "uploads"
OUTPUTS = ROOT / "outputs"
UPLOADS.mkdir(exist_ok=True)
OUTPUTS.mkdir(exist_ok=True)
CFG = load_config(ROOT / "config" / "default.yaml")
QUEUE = JobQueue(OUTPUTS, CFG, workers=1)

app = FastAPI(title="ACRPS Offline Podcast Engine", version="1.0.0")

@app.get("/api/health")
def health():
    return {"ok": True, "neural_required": CFG.neural_required, "model": CFG.deepfilter_model}

@app.post("/api/jobs")
async def create_job(files: list[UploadFile] = File(...)):
    accepted = []
    for f in files:
        if not f.filename:
            continue
        safe = Path(f.filename).name
        dst = UPLOADS / safe
        with dst.open("wb") as out:
            shutil.copyfileobj(f.file, out)
        accepted.append(QUEUE.submit(str(dst)).__dict__)
    if not accepted:
        raise HTTPException(400, "No files received")
    return accepted

@app.get("/api/jobs")
def jobs():
    return [j.__dict__ for j in QUEUE.list()]

@app.get("/api/download/{name}")
def download(name: str):
    path = OUTPUTS / Path(name).name
    if not path.exists():
        raise HTTPException(404, "File not found")
    return FileResponse(path)

app.mount("/", StaticFiles(directory=ROOT / "web", html=True), name="web")
