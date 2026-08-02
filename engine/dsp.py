from __future__ import annotations
import math
import numpy as np
from scipy import signal


def _db_to_lin(db: float) -> float:
    return 10.0 ** (db / 20.0)


def biquad_peak(x: np.ndarray, sr: int, freq: float, q: float, gain_db: float) -> np.ndarray:
    if abs(gain_db) < 1e-6:
        return x
    A = 10 ** (gain_db / 40)
    w0 = 2 * math.pi * freq / sr
    alpha = math.sin(w0) / (2*q)
    c = math.cos(w0)
    b = np.array([1+alpha*A, -2*c, 1-alpha*A])
    a = np.array([1+alpha/A, -2*c, 1-alpha/A])
    return signal.sosfilt(signal.tf2sos(b/a[0], a/a[0]), x).astype(np.float32)


def highpass_lowpass(x: np.ndarray, sr: int, hp: float, lp: float) -> np.ndarray:
    sos_hp = signal.butter(2, hp/(sr/2), btype="highpass", output="sos")
    y = signal.sosfilt(sos_hp, x)
    if lp < sr/2 - 100:
        sos_lp = signal.butter(4, lp/(sr/2), btype="lowpass", output="sos")
        y = signal.sosfilt(sos_lp, y)
    return y.astype(np.float32)


def deesser(x: np.ndarray, sr: int, threshold_db: float, ratio: float) -> np.ndarray:
    sos = signal.butter(4, [4500/(sr/2), min(11000, sr/2-200)/(sr/2)], btype="bandpass", output="sos")
    band = signal.sosfilt(sos, x)
    env = np.sqrt(signal.lfilter([1.0/240], [1, -(239.0/240)], band*band) + 1e-12)
    env_db = 20*np.log10(env+1e-9)
    over = np.maximum(0.0, env_db-threshold_db)
    reduction_db = -over*(1.0-1.0/max(ratio,1.0))
    gain = 10**(reduction_db/20)
    reduced_band = band*gain
    return (x - band + reduced_band).astype(np.float32)


def compressor(x: np.ndarray, sr: int, threshold_db: float, ratio: float, attack_ms: float, release_ms: float, max_makeup_db: float) -> np.ndarray:
    absx = np.abs(x)+1e-9
    level_db = 20*np.log10(absx)
    target = np.where(level_db>threshold_db, threshold_db+(level_db-threshold_db)/ratio-level_db, 0.0)
    a_att = math.exp(-1.0/(sr*attack_ms/1000.0))
    a_rel = math.exp(-1.0/(sr*release_ms/1000.0))
    smooth = np.empty_like(target)
    s = 0.0
    for i, t in enumerate(target):
        a = a_att if t < s else a_rel
        s = a*s+(1-a)*t
        smooth[i] = s
    gr = 10**(smooth/20)
    y = x*gr
    makeup = min(max_makeup_db, max(0.0, -float(np.percentile(smooth, 60))*0.35))
    return (y*_db_to_lin(makeup)).astype(np.float32)


def soft_limiter(x: np.ndarray, ceiling_db: float) -> np.ndarray:
    c = _db_to_lin(ceiling_db)
    return (np.tanh(x/c)*c).astype(np.float32)
