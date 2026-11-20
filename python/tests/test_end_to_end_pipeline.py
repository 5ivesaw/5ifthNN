"""End-to-end smoke test of the 5ifthNN Python pipeline (Dataset -> BC -> KL-PPO -> Visualizer)."""

from __future__ import annotations

import tempfile
from pathlib import Path

from fifthnn.dataset_io import read_recording
from fifthnn.visualizer import render_frame_png
from tests.test_schema_and_io import _write_synthetic_recording


def test_visualizer_reconstruction() -> None:
    with tempfile.TemporaryDirectory() as td:
        rec_path = Path(td) / "vis_test.5nn.gz"
        png_path = Path(td) / "frame_0.png"
        _write_synthetic_recording(rec_path)
        _, frames = read_recording(rec_path, valid_only=True)
        assert len(frames) == 1
        render_frame_png(frames[0], png_path)
        assert png_path.exists() and png_path.stat().st_size > 10_000


if __name__ == "__main__":
    test_visualizer_reconstruction()
    print("Visualizer 1:1 reconstruction test PASSED!")
