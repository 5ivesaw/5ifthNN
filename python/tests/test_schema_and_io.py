"""Self-contained verification tests for 5ifthNN schema, Mu-Law quantization, and binary I/O."""

from __future__ import annotations

import gzip
import json
import struct
import tempfile
from pathlib import Path

import numpy as np

from fifthnn.dataset_io import (
    _S_CROSSHAIR,
    _S_ECON,
    _S_FRAME_META,
    _S_GUI_HDR,
    _S_INPUT,
    _S_LIDAR,
    _S_MATCH,
    _S_SELF,
    read_recording,
)
from fifthnn.features import encode_frame_obs, encode_frame_targets
from fifthnn.schema import (
    FILE_MAGIC,
    FRAME_MAGIC,
    INVENTORY_SLOT_COUNT,
    KEY_A,
    KEY_D,
    KEY_W,
    LIDAR_RAY_COUNT,
    MAX_YAW_DEG_PER_TICK,
    NN_SCALAR_OBS_DIM,
    QFLAG_VALID_IN_GAME,
    RAW_VOXEL_COUNT,
    SCHEMA_VERSION,
    dequantize_angle_mulaw,
    quantize_angle_mulaw,
)


def _write_synthetic_recording(path: Path) -> None:
    hdr = json.dumps({"recordingMode": "DRILL_BRIDGING", "serverProfile": "local", "pvpMode": "1.8"}).encode()
    payload = bytearray()
    payload += _S_FRAME_META.pack(1, 1700000000000, 3, QFLAG_VALID_IN_GAME, 25, 20.0)
    payload += _S_SELF.pack(
        10.5, 64.0, -5.2, 0.1, 0.0, 0.2, 45.0, 15.0, 40.0, 14.0, 1.62, 0.0, 20.0, 0.0, 20.0, 0.0, 1.0, 16.0, 1, 0, 1, 0
    )
    payload += _S_CROSSHAIR.pack(1, 2.5, 11, 63, -4, 1, 2, 3, 0.0, -1)
    voxels = np.zeros(RAW_VOXEL_COUNT, dtype=np.uint8)
    voxels[RAW_VOXEL_COUNT // 2] = 2 | 0x40 | 0x80  # player-placed breakable wool
    payload += voxels.tobytes()
    for _ in range(LIDAR_RAY_COUNT):
        payload += _S_LIDAR.pack(12.0, 1, 0)
    payload += bytes([0, 0, 0])  # 0 landmarks, 0 players, 0 threats
    payload += _S_ECON.pack(16, 4, 2, 0, 64, 1, 0, 0, 0, 0, 2, 2, 1, 0, 0, 1, 0)
    payload += bytes(INVENTORY_SLOT_COUNT * 3)
    payload += _S_GUI_HDR.pack(0, 0, 0x0F, 0)
    payload += _S_MATCH.pack(1, 0x07, 3, 1, 0, 0, 0, 0, 1, 1, 600, 0)
    payload += bytes([0])  # 0 sounds
    payload += _S_INPUT.pack(KEY_W | KEY_A, 12.0, -3.0, 5.0, -1.0, 2, 0, 2, -1, 0, 0, 0.0)

    with gzip.open(path, "wb") as f:
        f.write(FILE_MAGIC)
        f.write(struct.pack(">HI", SCHEMA_VERSION, len(hdr)))
        f.write(hdr)
        f.write(FRAME_MAGIC)
        f.write(struct.pack(">I", len(payload)))
        f.write(payload)
        # Simulate crash-truncated tail frame — reader must cleanly recover earlier frame!
        f.write(FRAME_MAGIC)
        f.write(struct.pack(">I", 9999))
        f.write(b"\x00\x01\x02")


def test_mulaw_roundtrip() -> None:
    for deg in [-35.0, -10.0, -1.5, 0.0, 1.5, 10.0, 35.0]:
        b = quantize_angle_mulaw(deg, MAX_YAW_DEG_PER_TICK)
        rec = dequantize_angle_mulaw(b, MAX_YAW_DEG_PER_TICK)
        assert abs(rec - deg) < 3.0, f"Mu-law error too large for {deg}: {rec}"


def test_binary_io_and_mirror_encoding() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "sample.5nn.gz"
        _write_synthetic_recording(p)
        hdr, frames = read_recording(p, valid_only=True)
        assert hdr["recordingMode"] == "DRILL_BRIDGING"
        assert len(frames) == 1
        fr = frames[0]
        assert fr.left_clicks == 2
        assert fr.econ[0] == 16

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


if __name__ == "__main__":
    test_mulaw_roundtrip()
    test_binary_io_and_mirror_encoding()
    print("ALL 5ifthNN Python schema/IO/mirror tests PASSED!")
