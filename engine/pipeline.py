from __future__ import annotations
import json
import tempfile
from pathlib import Path
import soundfile as sf
from .config import EngineConfig
from .ffmpeg import run, duration_seconds
from .neural import DeepFilterNetBackend, NeuralBackendUnavailable
from .master import master

class ProcessingError(RuntimeError):
    pass


def process_file(source: str | Path, output_dir: str | Path, cfg: EngineConfig) -> dict:
    source = Path(source)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    stem = source.stem
    with tempfile.TemporaryDirectory(prefix="acrps-podcast-") as td:
        td = Path(td)
        normalized = td / "input_48k_mono.wav"
        run(["-i", str(source), "-map_metadata", "-1", "-ac", "1", "-ar", str(cfg.sample_rate), "-c:a", "pcm_f32le", str(normalized)])
        original_duration = duration_seconds(normalized)
        try:
            neural = DeepFilterNetBackend(cfg.deepfilter_model, cfg.post_filter, cfg.attenuation_limit_db)
            nr = neural.process_file(normalized)
        except NeuralBackendUnavailable:
            if cfg.neural_required:
                raise
            samples, sr = sf.read(normalized, dtype="float32")
            nr = type("R", (), {"samples": samples, "sample_rate": sr, "backend": "none"})()
        mastered, metrics = master(nr.samples, nr.sample_rate, cfg)
        out_wav = output_dir / f"{stem}_PODCAST_MASTER.wav"
        sf.write(out_wav, mastered, nr.sample_rate, subtype="PCM_24")
        out_duration = duration_seconds(out_wav)
        drift_ms = abs(out_duration-original_duration)*1000
        if drift_ms > cfg.preserve_duration_tolerance_ms:
            raise ProcessingError(f"Duration drift {drift_ms:.2f}ms exceeds tolerance")
        out_mp3 = None
        if cfg.write_mp3:
            out_mp3 = output_dir / f"{stem}_PODCAST_ACCESS.mp3"
            run(["-i", str(out_wav), "-c:a", "libmp3lame", "-b:a", cfg.mp3_bitrate, str(out_mp3)])
        report = {"source": str(source), "backend": nr.backend, "wav": str(out_wav), "mp3": str(out_mp3) if out_mp3 else None, "duration_seconds": out_duration, "duration_drift_ms": drift_ms, **metrics}
        (output_dir / f"{stem}_REPORT.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        return report
