#!/usr/bin/env python3
"""Build the "compact" Supertonic 3 model: int8 text encoder + vector estimator, fp32 vocoder.

  pip install supertonic onnx onnxruntime
  python quantize.py [src_model_dir] [out_dir]

src defaults to the supertonic SDK cache (~/.cache/supertonic3). Only the two quantized files
are new; the app downloads everything else from Supertone's repo. Quantization is deterministic,
so the SHA-256s in ModelManager.java can be reproduced from the pinned upstream commit.
"""
import os, sys, onnx
from onnxruntime.quantization import quantize_dynamic, QuantType

src = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/supertonic3")
out = sys.argv[2] if len(sys.argv) > 2 else "supertonic-3-int8"
os.makedirs(f"{out}/onnx", exist_ok=True)

for name in ["text_encoder", "vector_estimator"]:
    path = f"{src}/onnx/{name}.onnx"
    m = onnx.load(path)
    # ORT's CPU ConvInteger has no depthwise (grouped) kernel, so those convs stay fp32.
    depthwise = [n.name for n in m.graph.node if n.op_type == "Conv" and any(a.name == "group" and a.i != 1 for a in n.attribute)]
    quantize_dynamic(path, f"{out}/onnx/{name}.onnx", weight_type=QuantType.QUInt8, per_channel=True,
                     op_types_to_quantize=["MatMul", "Conv"], nodes_to_exclude=depthwise)
    print(name, os.path.getsize(f"{out}/onnx/{name}.onnx") // 1_000_000, "MB")
# The vocoder is deliberately NOT quantized: int8 turns its output into noise.
