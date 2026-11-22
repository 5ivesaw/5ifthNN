"""Self-contained verification tests for 5ifthNN schema v1/v2, Mu-Law quantization, binary I/O, and audit."""

from __future__ import annotations

import gzip
import json
import struct
import tempfile
from pathlib import Path
from typing import List, Tuple

import numpy as np

from fifthnn.audit import audit_recordings, split_contiguous_segments
from fifthnn.dataset_io import (
    _S_CROSSHAIR,
    _S_ECON,
    _S_FRAME_META_V1,
    _S_FRAME_META_V2,
    _S_GUI_HDR,
    _S_INPUT,
    _S_LANDMARK,
    _S_LIDAR,
    _S_MATCH,
    _S_PLAYER,
    _S_SELF,
    read_recording,
)
from fifthnn.features import encode_frame_obs, encode_frame_targets
from fifthnn.schema import (
    EVT_BLOCK_PLACED,
    FILE_MAGIC,
    FRAME_MAGIC,
    INVENTORY_SLOT_COUNT,
    KEY_A,
    KEY_D,
    KEY_W,
    LIDAR_RAY_COUNT,
    MAX_YAW_DEG_PER_TICK,
    NN_SCALAR_OBS_DIM,
    QFLAG_LOBBY_OR_SPEC,
    QFLAG_TELEPORT_TICK,
    QFLAG_VALID_IN_GAME,
    RAW_VOXEL_COUNT,
    SCHEMA_VERSION,
    dequantize_angle_mulaw,
    quantize_angle_mulaw,
)


def _build_frame_payload(
    tick: int = 1,
    episode_id: int = 1,
    quality_flags: int = QFLAG_VALID_IN_GAME,
    schema_version: int = SCHEMA_VERSION,
    delta_yaw: float = 5.0,
    left_clicks: int = 2,
    event_flags: int = EVT_BLOCK_PLACED,
    include_entities: bool = True,
) -> bytes:
    payload = bytearray()
    if schema_version >= 2:
        payload += _S_FRAME_META_V2.pack(tick, 1700000000000 + tick * 50, episode_id, 3, quality_flags, 25, 20.0)
    else:
        payload += _S_FRAME_META_V1.pack(tick, 1700000000000 + tick * 50, 3, quality_flags, 25, 20.0)

    payload += _S_SELF.pack(
        10.5, 64.0, -5.2, 0.1, 0.0, 0.2, 45.0, 15.0, 40.0, 14.0, 1.62, 0.0, 20.0, 0.0, 20.0, 0.0, 1.0, 16.0, 1, 0, 1, 0
    )
    payload += _S_CROSSHAIR.pack(1, 2.5, 11, 63, -4, 1, 2, 3, 0.25, -1)
    voxels = np.zeros(RAW_VOXEL_COUNT, dtype=np.uint8)
    voxels[RAW_VOXEL_COUNT // 2] = 2 | 0x40 | 0x80  # player-placed breakable wool
    payload += voxels.tobytes()
    for _ in range(LIDAR_RAY_COUNT):
        payload += _S_LIDAR.pack(12.0, 1, 0)

    if include_entities:
        # 2 landmarks (enemy bed + diamond gen)
        payload += bytes([2])
        payload += _S_LANDMARK.pack(1, 2, 32.0, 65.0, -4.0, 1)
        payload += _S_LANDMARK.pack(3, 0, 20.0, 64.0, 18.0, 1)
        # 1 visible enemy with non-zero finite-difference velocity
        payload += bytes([1])
        payload += _S_PLAYER.pack(42, 123456, 2, 0, 15.0, 64.0, -3.0, 0.12, 0.0, -0.08, 90.0, 5.0, 20.0, 10, 0, 4)
        # 0 threats
        payload += bytes([0])
    else:
        payload += bytes([0, 0, 0])

    payload += _S_ECON.pack(16, 4, 2, 0, 64, 1, 0, 0, 0, 0, 2, 2, 1, 0, 0, 1, 0)
    payload += bytes(INVENTORY_SLOT_COUNT * 3)
    payload += _S_GUI_HDR.pack(0, 0, 0x0F, 0)
    payload += _S_MATCH.pack(1, 0x07, 3, 1, 0, 0, 0, 0, 1, 1, 600, event_flags)
    payload += bytes([0])  # 0 sounds
    payload += _S_INPUT.pack(KEY_W | KEY_A, 12.0, -3.0, delta_yaw, -1.0, left_clicks, 0, 2, -1, 0, 0, 0.0)
    return bytes(payload)


def _write_synthetic_recording(
    path: Path,
    frame_specs: List[Tuple[int, int, int]] | None = None,
    schema_version: int = SCHEMA_VERSION,
    append_truncated_tail: bool = True,
) -> None:
    """
    frame_specs: list of (tick_index, episode_id, quality_flags).
    """
    hdr = json.dumps({"recordingMode": "DRILL_BRIDGING", "serverProfile": "pika", "pvpMode": "1.8"}).encode()
    if frame_specs is None:
        frame_specs = [(1, 1, QFLAG_VALID_IN_GAME)]

    with gzip.open(path, "wb") as f:
        f.write(FILE_MAGIC)
        f.write(struct.pack(">HI", schema_version, len(hdr)))
        f.write(hdr)
        for tick, ep, qf in frame_specs:
            payload = _build_frame_payload(tick=tick, episode_id=ep, quality_flags=qf, schema_version=schema_version)
            f.write(FRAME_MAGIC)
            f.write(struct.pack(">I", len(payload)))
            f.write(payload)
        if append_truncated_tail:
            # Simulate crash-truncated tail frame — reader must cleanly recover earlier frames!
            f.write(FRAME_MAGIC)
            f.write(struct.pack(">I", 9999))
            f.write(b"\x00\x01\x02")


def test_mulaw_roundtrip() -> None:
    for deg in [-35.0, -10.0, -1.5, 0.0, 1.5, 10.0, 35.0]:
        b = quantize_angle_mulaw(deg, MAX_YAW_DEG_PER_TICK)
        rec = dequantize_angle_mulaw(b, MAX_YAW_DEG_PER_TICK)
        assert abs(rec - deg) < 3.0, f"Mu-law error too large for {deg}: {rec}"


def test_binary_io_v2_and_mirror_encoding() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "sample_v2.5nn.gz"
        _write_synthetic_recording(p, [(10, 3, QFLAG_VALID_IN_GAME)], schema_version=2)
        hdr, frames = read_recording(p, valid_only=True)
        assert hdr["recordingMode"] == "DRILL_BRIDGING"
        assert len(frames) == 1
        fr = frames[0]
        assert fr.episode_id == 3
        assert fr.left_clicks == 2
        assert fr.econ[0] == 16
        assert abs(fr.mining_progress - 0.25) < 1e-5
        assert len(fr.landmarks) == 2
        assert len(fr.players) == 1

        vc, vf, obs = encode_frame_obs(fr, mirror=False)
        vc_m, vf_m, obs_m = encode_frame_obs(fr, mirror=True)
        assert vc.shape == (5, 9, 9)
        assert vf.shape == (2, 5, 9, 9)
        assert obs.shape == (NN_SCALAR_OBS_DIM,)
        assert np.all(np.isfinite(obs)) and np.all(np.isfinite(obs_m))

        t0 = encode_frame_targets(fr, mirror=False)
        tm = encode_frame_targets(fr, mirror=True)
        assert t0["move"][1] == 1.0 and t0["move"][3] == 0.0  # A pressed
        assert tm["move"][1] == 0.0 and tm["move"][3] == 1.0  # mirrored -> D pressed
        assert t0["yaw_bin"] + tm["yaw_bin"] == 24  # symmetric bin flip!


def test_schema_v1_backward_compat() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "legacy_v1.5nn.gz"
        _write_synthetic_recording(p, [(5, 99, QFLAG_VALID_IN_GAME)], schema_version=1)
        _, frames = read_recording(p, valid_only=True)
        assert len(frames) == 1
        assert frames[0].tick_index == 5
        assert frames[0].episode_id == 1  # defaulted for v1 files


def test_segment_splitting_and_audit() -> None:
    specs = [
        # Segment 1 (Ep 1, ticks 1..3)
        (1, 1, QFLAG_VALID_IN_GAME),
        (2, 1, QFLAG_VALID_IN_GAME),
        (3, 1, QFLAG_VALID_IN_GAME),
        # Teleport tick (must split!)
        (4, 1, QFLAG_VALID_IN_GAME | QFLAG_TELEPORT_TICK),
        # Segment 2 (Ep 1, ticks 5..6)
        (5, 1, QFLAG_VALID_IN_GAME),
        (6, 1, QFLAG_VALID_IN_GAME),
        # Lobby frame (must split!)
        (7, 1, QFLAG_LOBBY_OR_SPEC),
        # Segment 3 (Ep 2, ticks 8..9)
        (8, 2, QFLAG_VALID_IN_GAME),
        (9, 2, QFLAG_VALID_IN_GAME),
        # Large tick gap (must split!)
        (20, 2, QFLAG_VALID_IN_GAME),
        (21, 2, QFLAG_VALID_IN_GAME),
        # Immediate Episode switch without gap (must split!)
        (22, 3, QFLAG_VALID_IN_GAME),
    ]
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "multi_ep.5nn.gz"
        _write_synthetic_recording(p, specs, schema_version=2)
        _, all_frames = read_recording(p, valid_only=False)
        segs = split_contiguous_segments(all_frames)
        assert [len(s) for s in segs] == [3, 2, 2, 2, 1], f"Unexpected segment splits: {[len(s) for s in segs]}"

        report = audit_recordings([str(p)])
        assert report["episodes"] == 3
        assert report["valid_frames"] == 10
        assert report["teleport_ticks"] == 1
        assert report["nonfinite_obs"] == 0
        assert report["enemy_vel_nonzero_pct"] == 100.0
        assert report["landmark_bed_cov_pct"] == 100.0
        assert report["landmark_gen_cov_pct"] == 100.0
        assert report["blocks_placed"] == 10


if __name__ == "__main__":
    test_mulaw_roundtrip()
    test_binary_io_v2_and_mirror_encoding()
    test_schema_v1_backward_compat()
    test_segment_splitting_and_audit()
    print("ALL 5ifthNN Python schema v1/v2, mirror, segmentation & audit tests PASSED!")
