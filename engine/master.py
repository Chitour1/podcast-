from __future__ import annotations
import numpy as np
import pyloudnorm as pyln
from .dsp import highpass_lowpass, biquad_peak, deesser, compressor, soft_limiter
from .config import EngineConfig


def master(samples: np.ndarray, sr: int, cfg: EngineConfig) -> tuple[np.ndarray, dict]:
    x = np.asarray(samples, dtype=np.float32)
    x = x - np.mean(x, dtype=np.float64)
    x = highpass_lowpass(x, sr, cfg.highpass_hz, cfg.lowpass_hz)
    x = biquad_peak(x, sr, cfg.mud_hz, 0.9, cfg.mud_cut_db)
    x = biquad_peak(x, sr, cfg.presence_hz, 0.8, cfg.presence_db)
    x = deesser(x, sr, cfg.deesser_threshold_db, cfg.deesser_ratio)
    x = compressor(x, sr, cfg.compressor_threshold_db, cfg.compressor_ratio, cfg.compressor_attack_ms, cfg.compressor_release_ms, cfg.max_makeup_db)
    meter = pyln.Meter(sr)
    lufs_before = float(meter.integrated_loudness(x))
    target_gain = float(np.clip(cfg.target_lufs - lufs_before, -8.0, 8.0))
    x = x * (10 ** (target_gain/20))
    x = soft_limiter(x, cfg.true_peak_dbfs)
    lufs_after = float(meter.integrated_loudness(x))
    peak = float(20*np.log10(np.max(np.abs(x))+1e-12))
    return x.astype(np.float32), {"lufs_before": lufs_before, "lufs_after": lufs_after, "sample_peak_dbfs": peak}
