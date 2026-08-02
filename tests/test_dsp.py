import numpy as np
from engine.dsp import highpass_lowpass, compressor, soft_limiter

def test_length_preserved():
    x=np.random.default_rng(1).normal(0,.05,48000).astype('float32')
    y=highpass_lowpass(x,48000,55,18000)
    y=compressor(y,48000,-20,1.7,20,180,4)
    y=soft_limiter(y,-1.2)
    assert len(y)==len(x)
    assert np.isfinite(y).all()
