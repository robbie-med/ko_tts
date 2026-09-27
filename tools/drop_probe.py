"""Syllable-drop probe: same phrases, several random seeds, count exact Whisper matches per variant.

Supertonic sometimes swallows syllables, and it's random per seed, so this needs repeats.
Variants are (noise_scale, floor_sec_per_syllable).
Usage: drop_probe.py <model_dir> <phrases.txt>
"""
import sys, re, json, unicodedata, numpy as np, onnxruntime as ort, soundfile as sf, tempfile, os, warnings
warnings.filterwarnings("ignore")
from faster_whisper import WhisperModel

md = sys.argv[1]
cfg = json.load(open(f"{md}/onnx/tts.json"))
SR = cfg["ae"]["sample_rate"]; CC = cfg["ttl"]["chunk_compress_factor"]
CHUNK = cfg["ae"]["base_chunk_size"] * CC; LD = cfg["ttl"]["latent_dim"] * CC
idx = np.array(json.load(open(f"{md}/onnx/unicode_indexer.json")), dtype=np.int64)
so = ort.SessionOptions(); so.intra_op_num_threads = 8
S = {n: ort.InferenceSession(f"{md}/onnx/{n}.onnx", so) for n in ["duration_predictor", "text_encoder", "vector_estimator", "vocoder"]}
st = json.load(open(f"{md}/voice_styles/F1.json"))
ttl = np.array(st["style_ttl"]["data"], np.float32); dp = np.array(st["style_dp"]["data"], np.float32)


def synth(text, noise, floor, seed, steps=6, speed=1.05):
    t = unicodedata.normalize("NFKD", text).strip()
    if not re.search(r"[.!?,]$", t): t += "."
    ids = idx[[ord(c) for c in "<ko>" + t + "</ko>"]]; ids = ids[ids >= 0][None]
    mask = np.ones((1, 1, ids.shape[1]), np.float32)
    dur = float(S["duration_predictor"].run(None, {"text_ids": ids, "style_dp": dp, "text_mask": mask})[0].reshape(-1)[0]) / speed
    dur = max(dur, len(re.findall(r"[가-힣]", text)) * floor / speed)
    emb = S["text_encoder"].run(None, {"text_ids": ids, "style_ttl": ttl, "text_mask": mask})[0]
    L = max(1, -(-int(dur * SR) // CHUNK))
    xt = (np.random.default_rng(seed).standard_normal((1, LD, L)) * noise).astype(np.float32)
    lm = np.ones((1, 1, L), np.float32)
    for s in range(steps):
        xt = S["vector_estimator"].run(None, {"noisy_latent": xt, "text_emb": emb, "style_ttl": ttl, "latent_mask": lm,
             "text_mask": mask, "current_step": np.array([s], np.float32), "total_step": np.array([steps], np.float32)})[0]
    return S["vocoder"].run(None, {"latent": xt})[0].reshape(-1)[: int(dur * SR)]


texts = ["애 보느라 힘들지?"] + [l.strip() for l in open(sys.argv[2]) if l.strip()][:11]
asr = WhisperModel("small", device="cpu", compute_type="int8")
hang = lambda s: re.sub(r"[^가-힣]", "", s)
tmp = os.path.join(tempfile.gettempdir(), "drop_probe.wav")
# Result 2026-09-27: noise 1.0 → 4.2% dropped, 0.8 → 1.4%, 0.6 → 0%; floor 0.17 → 4.2% (no help).
for noise, floor in [(1.0, 0), (0.8, 0), (0.6, 0), (1.0, 0.17)]:
    ok = short = 0; fails = []
    for t in texts:
        for seed in range(6):
            sf.write(tmp, synth(t, noise, floor, seed), SR)
            hyp = "".join(x.text for x in asr.transcribe(tmp, language="ko", beam_size=5, without_timestamps=True)[0])
            ok += hang(hyp) == hang(t); short += len(hang(hyp)) < len(hang(t))
            if len(hang(hyp)) < len(hang(t)): fails.append((t, hyp.strip()))
    n = len(texts) * 6
    print(f"noise={noise} floor={floor}: exact {ok}/{n} ({100*ok/n:.0f}%), shorter-than-text {short}/{n} ({100*short/n:.1f}%)", flush=True)
    for f in fails[:4]: print("    ", f, flush=True)
