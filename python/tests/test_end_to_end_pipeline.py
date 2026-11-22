"""End-to-end smoke test of the 5ifthNN Python pipeline (Dataset -> Audit -> Visualizer)."""

from __future__ import annotations

import tempfile
from pathlib import Path

from fifthnn.audit import audit_recordings, print_scorecard
from fifthnn.dataset_io import read_recording
from fifthnn.visualizer import render_frame_png
from tests.test_schema_and_io import _write_synthetic_recording


def test_visualizer_and_audit_pipeline() -> None:
    with tempfile.TemporaryDirectory() as td:
        rec_path = Path(td) / "vis_test.5nn.gz"
        png_path = Path(td) / "frame_0.png"
        _write_synthetic_recording(rec_path)
        _, frames = read_recording(rec_path, valid_only=True)
        assert len(frames) == 1
        render_frame_png(frames[0], png_path)
        assert png_path.exists() and png_path.stat().st_size > 10_000

        report = audit_recordings([str(rec_path)])
        assert report["valid_frames"] == 1
        assert report["nonfinite_obs"] == 0
        print_scorecard(report)


if __name__ == "__main__":
    test_visualizer_and_audit_pipeline()
    print("Visualizer + Audit end-to-end test PASSED!")
