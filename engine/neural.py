from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import numpy as np
import soundfile as sf

@dataclass
class NeuralResult:
    samples: np.ndarray
    sample_rate: int
    backend: str

class NeuralBackendUnavailable(RuntimeError):
    pass

class DeepFilterNetBackend:
    def __init__(self, model_name: str = "DeepFilterNet3", post_filter: bool = False, attenuation_limit_db: float = 14.0):
        try:
            import torch
            from df.enhance import init_df, enhance
        except Exception as exc:
            raise NeuralBackendUnavailable("DeepFilterNet is not installed correctly") from exc
        self.torch = torch
        self.enhance_fn = enhance
        self.model, self.df_state, _, _ = init_df(model_name, post_filter=post_filter, log_file=None)
        self.attenuation_limit_db = attenuation_limit_db

    def process_file(self, wav_path: str | Path) -> NeuralResult:
        audio, sr = sf.read(str(wav_path), dtype="float32", always_2d=True)
        mono = np.mean(audio, axis=1, dtype=np.float32)
        tensor = self.torch.from_numpy(mono[None, :])
        enhanced = self.enhance_fn(
            self.model,
            self.df_state,
            tensor,
            pad=True,
            atten_lim_db=self.attenuation_limit_db,
        )
        out = enhanced.squeeze(0).detach().cpu().numpy().astype(np.float32, copy=False)
        if out.shape[0] != mono.shape[0]:
            out = out[: mono.shape[0]] if out.shape[0] > mono.shape[0] else np.pad(out, (0, mono.shape[0]-out.shape[0]))
        return NeuralResult(out, sr, "DeepFilterNet3")
