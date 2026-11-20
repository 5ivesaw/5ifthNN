"""
Phase 2: PyTorch -> ONNX Converter & Numerical Parity Verifier for 5ifthNN.

Exports a trained (or freshly initialized test) 5ifthNN PyTorch checkpoint into a single
lightweight .onnx file ready to drop straight into `.minecraft/config/5ifthnn/models/`.
Runs an exact numerical parity check comparing PyTorch vs ONNX Runtime outputs and
benchmarks CPU inference latency per tick.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

from .model import FifthNNModel, OnnxStepWrapper, count_parameters
from .schema import (
    FAST_HIDDEN_DIM,
    NN_SCALAR_OBS_DIM,
    NN_VOXEL_SIZE_XZ,
    NN_VOXEL_SIZE_Y,
    SCHEMA_VERSION,
    SLOW_HIDDEN_DIM,
    SLOW_UPDATE_INTERVAL,
)

INPUT_NAMES = ["voxel_cats", "voxel_flags", "scalar_obs", "h_fast", "h_slow", "update_slow"]
OUTPUT_NAMES = [
    "move_logits",
    "yaw_logits",
    "pitch_logits",
    "left_logits",
    "right_logits",
    "hotbar_logits",
    "gui_logits",
    "value",
    "h_fast_next",
    "h_slow_next",
]


def export_to_onnx(ckpt_path: str | None, out_onnx: str, opset: int = 17, random_init: bool = False) -> None:
    model = FifthNNModel(include_aux=False)
    if ckpt_path and not random_init:
        ckpt = torch.load(ckpt_path, map_location="cpu")
        state = ckpt.get("model_state_dict", ckpt)
        model.load_state_dict(state, strict=False)
        print(f"Loaded checkpoint: {ckpt_path}")
    else:
        print("Exporting freshly initialized smoke-test model (random weights).")

    model.eval()
    wrapper = OnnxStepWrapper(model).eval()

    dummy_vc = torch.zeros((1, NN_VOXEL_SIZE_Y, NN_VOXEL_SIZE_XZ, NN_VOXEL_SIZE_XZ), dtype=torch.long)
    dummy_vf = torch.zeros((1, 2, NN_VOXEL_SIZE_Y, NN_VOXEL_SIZE_XZ, NN_VOXEL_SIZE_XZ), dtype=torch.float32)
    dummy_obs = torch.zeros((1, NN_SCALAR_OBS_DIM), dtype=torch.float32)
    dummy_hf = torch.zeros((1, FAST_HIDDEN_DIM), dtype=torch.float32)
    dummy_hs = torch.zeros((1, SLOW_HIDDEN_DIM), dtype=torch.float32)
    dummy_upd = torch.ones((1, 1), dtype=torch.float32)

    out_path = Path(out_onnx)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    export_kwargs = dict(
        input_names=INPUT_NAMES,
        output_names=OUTPUT_NAMES,
        opset_version=opset,
        do_constant_folding=True,
    )
    import inspect
    if "dynamo" in inspect.signature(torch.onnx.export).parameters:
        export_kwargs["dynamo"] = False

    torch.onnx.export(
        wrapper,
        (dummy_vc, dummy_vf, dummy_obs, dummy_hf, dummy_hs, dummy_upd),
        str(out_path),
        **export_kwargs,
    )

    # Verify numerical parity & benchmark CPU inference speed
    with torch.no_grad():
        torch_outs = wrapper(dummy_vc, dummy_vf, dummy_obs, dummy_hf, dummy_hs, dummy_upd)

    sess_opts = ort.SessionOptions()
    sess_opts.intra_op_num_threads = 2
    sess = ort.InferenceSession(str(out_path), sess_options=sess_opts, providers=["CPUExecutionProvider"])

    ort_inputs = {
        "voxel_cats": dummy_vc.numpy(),
        "voxel_flags": dummy_vf.numpy(),
        "scalar_obs": dummy_obs.numpy(),
        "h_fast": dummy_hf.numpy(),
        "h_slow": dummy_hs.numpy(),
        "update_slow": dummy_upd.numpy(),
    }
    ort_outs = sess.run(OUTPUT_NAMES, ort_inputs)

    max_diff = max(float(np.max(np.abs(t.numpy() - o))) for t, o in zip(torch_outs, ort_outs))
    assert max_diff < 1e-4, f"ONNX parity mismatch! max_diff={max_diff}"

    # Benchmark 200 forward passes on CPU
    for _ in range(20):
        sess.run(OUTPUT_NAMES, ort_inputs)
    t0 = time.perf_counter()
    iters = 200
    for _ in range(iters):
        sess.run(OUTPUT_NAMES, ort_inputs)
    avg_ms = (time.perf_counter() - t0) * 1000.0 / iters

    size_mb = out_path.stat().st_size / (1024.0 * 1024.0)
    meta = {
        "schemaVersion": SCHEMA_VERSION,
        "params": count_parameters(model),
        "onnxSizeMb": round(size_mb, 2),
        "avgCpuLatencyMs": round(avg_ms, 3),
        "maxParityDiff": max_diff,
        "scalarObsDim": NN_SCALAR_OBS_DIM,
        "fastHiddenDim": FAST_HIDDEN_DIM,
        "slowHiddenDim": SLOW_HIDDEN_DIM,
        "slowUpdateInterval": SLOW_UPDATE_INTERVAL,
        "inputs": INPUT_NAMES,
        "outputs": OUTPUT_NAMES,
    }
    out_path.with_suffix(".onnx.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"Exported {out_path} ({size_mb:.2f} MB) | params={meta['params']:,} | parity={max_diff:.2e} | CPU={avg_ms:.2f} ms/tick")


def main() -> None:
    parser = argparse.ArgumentParser(description="Export 5ifthNN PyTorch checkpoint to lightweight ONNX")
    parser.add_argument("--ckpt", type=str, default=None, help="Path to .pt checkpoint")
    parser.add_argument("--out", type=str, default="models/fifthnn_v1.onnx")
    parser.add_argument("--random_init", action="store_true", help="Export random-weight smoke test model")
    parser.add_argument("--opset", type=int, default=17)
    args = parser.parse_args()
    export_to_onnx(args.ckpt, args.out, opset=args.opset, random_init=args.random_init)


if __name__ == "__main__":
    main()
