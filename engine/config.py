from __future__ import annotations
from dataclasses import dataclass, fields
from pathlib import Path
import yaml

@dataclass(frozen=True)
class EngineConfig:
    sample_rate: int = 48000
    output_bit_depth: int = 24
    target_lufs: float = -16.0
    true_peak_dbfs: float = -1.2
    neural_required: bool = True
    deepfilter_model: str = "DeepFilterNet3"
    attenuation_limit_db: float = 14.0
    post_filter: bool = False
    highpass_hz: float = 55.0
    lowpass_hz: float = 18500.0
    presence_db: float = 1.0
    presence_hz: float = 2800.0
    mud_cut_db: float = -1.2
    mud_hz: float = 280.0
    deesser_threshold_db: float = -26.0
    deesser_ratio: float = 2.0
    compressor_threshold_db: float = -20.0
    compressor_ratio: float = 1.7
    compressor_attack_ms: float = 20.0
    compressor_release_ms: float = 180.0
    max_makeup_db: float = 4.0
    write_mp3: bool = True
    mp3_bitrate: str = "192k"
    preserve_duration_tolerance_ms: int = 15


def load_config(path: str | Path) -> EngineConfig:
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    allowed = {f.name for f in fields(EngineConfig)}
    unknown = sorted(set(data) - allowed)
    if unknown:
        raise ValueError(f"Unknown config keys: {unknown}")
    return EngineConfig(**data)
