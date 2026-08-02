from engine.config import load_config

def test_default_config():
    c=load_config('config/default.yaml')
    assert c.sample_rate==48000
    assert c.neural_required is True
