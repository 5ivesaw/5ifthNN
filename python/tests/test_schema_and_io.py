"""Self-contained verification tests for 5ifthNN schema v1/v2, Mu-Law, binary I/O, tags, void classifier & invariants."""

from __future__ import annotations

import gzip
import json
import struct
import tempfile
from pathlib import Path
from typing import List, Tuple

import numpy as np

from fifthnn.audit import audit_recordings, classify_void_falls, detect_edge_recoveries, split_contiguous_segments
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
    find_recordings,
    load_tags_sidecar,
    read_recording,
)
from fifthnn.features import encode_frame_obs, encode_frame_targets
from fifthnn.schema import (
    EVT_BLOCK_PLACED,
    EVT_VOID_FALL,
    FILE_MAGIC,
    FRAME_MAGIC,
    INVENTORY_SLOT_COUNT,
    KEY_A,
    KEY_D,
    KEY_SNEAK,
    KEY_SPRINT,
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
    right_clicks: int = 0,
    event_flags: int = EVT_BLOCK_PLACED,
    include_entities: bool = True,
    y: float = 64.0,
    pitch: float = 15.0,
    key_mask: int = KEY_W | KEY_A,
    self_flags: int = 1,
    hurt_time: int = 0,
    own_bed_alive: int = 1,
    diamonds: int = 2,
    gui_type: int = 0,
    selected_slot: int = 2,
    hotbar_key: int = 2,
) -> bytes:
    payload = bytearray()
    if schema_version >= 2:
        payload += _S_FRAME_META_V2.pack(tick, 1700000000000 + tick * 50, episode_id, 3, quality_flags, 25, 20.0)
    else:
        payload += _S_FRAME_META_V1.pack(tick, 1700000000000 + tick * 50, 3, quality_flags, 25, 20.0)

    payload += _S_SELF.pack(
        10.5, y, -5.2, 0.1, 0.0, 0.2, 45.0, pitch, 40.0, pitch, 1.62, 0.0, 20.0, 0.0, 20.0, 0.0, 1.0, 16.0, self_flags, hurt_time, 1, 0
    )
    payload += _S_CROSSHAIR.pack(1, 2.5, 11, 63, -4, 1, 2, 3, 0.25, -1)
    voxels = np.zeros(RAW_VOXEL_COUNT, dtype=np.uint8)
    voxels[RAW_VOXEL_COUNT // 2] = 2 | 0x40 | 0x80
    payload += voxels.tobytes()
    for _ in range(LIDAR_RAY_COUNT):
        payload += _S_LIDAR.pack(12.0, 1, 0)

    if include_entities:
        payload += bytes([2])
        payload += _S_LANDMARK.pack(1, 2, 32.0, 65.0, -4.0, 1)
        payload += _S_LANDMARK.pack(3, 0, 20.0, 64.0, 18.0, 1)
        payload += bytes([1])
        payload += _S_PLAYER.pack(42, 123456, 2, 0, 15.0, 64.0, -3.0, 0.12, 0.0, -0.08, 90.0, 5.0, 20.0, 10, 0, 4)
        payload += bytes([0])
    else:
        payload += bytes([0, 0, 0])

    payload += _S_ECON.pack(16, 4, diamonds, 0, 64, 1, 0, 0, 0, 0, selected_slot, 2, 1, 0, 0, 1, 0)
    inv = np.zeros((INVENTORY_SLOT_COUNT, 3), dtype=np.uint8)
    inv[0] = [9, 1, 0]   # slot 0: stone sword
    inv[2] = [1, 64, 0]  # slot 2: wool
    payload += inv.tobytes()
    payload += _S_GUI_HDR.pack(gui_type, 0, 0x0F, 0)
    payload += _S_MATCH.pack(own_bed_alive, 0x07, 3, 1, 0, 0, 0, 0, 1, 1, 600, event_flags)
    payload += bytes([0])
    payload += _S_INPUT.pack(key_mask, 12.0, -3.0, delta_yaw, -1.0, left_clicks, right_clicks, hotbar_key, -1, 0, 0, 0.0)
    return bytes(payload)


def _write_synthetic_recording(
    path: Path,
    frame_specs: List[Tuple[int, int, int]] | None = None,
    schema_version: int = SCHEMA_VERSION,
    append_truncated_tail: bool = True,
    extra_kwargs_fn=None,
) -> None:
    hdr = json.dumps({"recordingMode": "FULL_MATCH", "serverProfile": "pika", "pvpMode": "1.8"}).encode()
    if frame_specs is None:
        frame_specs = [(1, 1, QFLAG_VALID_IN_GAME)]

    with gzip.open(path, "wb") as f:
        f.write(FILE_MAGIC)
        f.write(struct.pack(">HI", schema_version, len(hdr)))
        f.write(hdr)
        for idx, (tick, ep, qf) in enumerate(frame_specs):
            kw = extra_kwargs_fn(idx, tick) if extra_kwargs_fn else {}
            payload = _build_frame_payload(tick=tick, episode_id=ep, quality_flags=qf, schema_version=schema_version, **kw)
            f.write(FRAME_MAGIC)
            f.write(struct.pack(">I", len(payload)))
            f.write(payload)
        if append_truncated_tail:
            f.write(FRAME_MAGIC)
            f.write(struct.pack(">I", 9999))
            f.write(b"\x00\x01\x02")


def test_mulaw_roundtrip() -> None:
    for deg in [-35.0, -10.0, -1.5, 0.0, 1.5, 10.0, 35.0]:
        b = quantize_angle_mulaw(deg, MAX_YAW_DEG_PER_TICK)
        rec = dequantize_angle_mulaw(b, MAX_YAW_DEG_PER_TICK)
        assert abs(rec - deg) < 3.0, f"Mu-law error too large for {deg}: {rec}"


def test_binary_io_v2_mirror_and_togglesprint() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "sample_v2.5nn.gz"
        _write_synthetic_recording(
            p,
            [(10, 3, QFLAG_VALID_IN_GAME)],
            schema_version=2,
            extra_kwargs_fn=lambda i, t: {"key_mask": KEY_W | KEY_A | KEY_SNEAK | KEY_SPRINT},
        )
        hdr, frames = read_recording(p, valid_only=True)
        assert hdr["recordingMode"] == "FULL_MATCH"
        assert len(frames) == 1
        fr = frames[0]
        assert fr.episode_id == 3

        vc, vf, obs = encode_frame_obs(fr, mirror=False)
        vc_m, vf_m, obs_m = encode_frame_obs(fr, mirror=True)
        assert vc.shape == (5, 9, 9) and vf.shape == (2, 5, 9, 9) and obs.shape == (NN_SCALAR_OBS_DIM,)
        assert np.all(np.isfinite(obs)) and np.all(np.isfinite(obs_m))

        t0 = encode_frame_targets(fr, mirror=False)
        tm = encode_frame_targets(fr, mirror=True)
        assert t0["move"][1] == 1.0 and t0["move"][3] == 0.0
        assert tm["move"][1] == 0.0 and tm["move"][3] == 1.0
        assert t0["yaw_bin"] + tm["yaw_bin"] == 24
        # ToggleSprint normalization: SNK + SPR must normalize sprint target to 0.0!
        assert t0["move"][5] == 1.0 and t0["move"][6] == 0.0


def test_invariant2_gui_safe_hotbar_permutation() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "hb.5nn.gz"
        specs = [(1, 1, QFLAG_VALID_IN_GAME), (2, 1, QFLAG_VALID_IN_GAME)]
        _write_synthetic_recording(
            p,
            specs,
            extra_kwargs_fn=lambda i, t: {"gui_type": 0 if i == 0 else 2, "selected_slot": 2, "hotbar_key": 2},
        )
        _, frames = read_recording(p, valid_only=True)
        # Permutation moves old slot 2 -> new slot 7
        perm = [0, 1, 3, 4, 5, 6, 8, 2, 7]
        # Closed GUI: permute consistently
        _, _, obs_p = encode_frame_obs(frames[0], hotbar_perm=perm)
        tgt_p = encode_frame_targets(frames[0], hotbar_perm=perm)
        s0 = 128 + 6 * 18 + 6 * 10
        assert obs_p[s0 + 63 + 7] == 1.0 and obs_p[s0 + 63 + 2] == 0.0
        assert tgt_p["hotbar"] == 8  # slot 7 -> 1-based action 8

        # Open GUI: permutation MUST be ignored!
        _, _, obs_gui = encode_frame_obs(frames[1], hotbar_perm=perm)
        tgt_gui = encode_frame_targets(frames[1], hotbar_perm=perm)
        assert obs_gui[s0 + 63 + 2] == 1.0
        assert tgt_gui["hotbar"] == 3  # original slot 2 -> 1-based action 3


def test_invariant1_tactical_void_vs_accidental_slip() -> None:
    with tempfile.TemporaryDirectory() as td:
        # Case A: Tactical void — player looks down (pitch=75°!), NO bridging/sneaking/placing, own bed alive, 0 dia
        p_tac = Path(td) / "tactical.5nn.gz"
        specs = [(t, 1, QFLAG_VALID_IN_GAME) for t in range(1, 35)]
        _write_synthetic_recording(
            p_tac,
            specs,
            extra_kwargs_fn=lambda i, t: {
                "pitch": 75.0,
                "key_mask": KEY_W,
                "right_clicks": 0,
                "event_flags": EVT_VOID_FALL if i == 33 else 0,
                "own_bed_alive": 1,
                "diamonds": 0,
                "y": 64.0 if i < 28 else 10.0,
                "self_flags": 1 if i < 28 else 0,
            },
        )
        _, frames_tac = read_recording(p_tac, valid_only=True)
        res_tac = classify_void_falls(frames_tac)
        assert len(res_tac["tactical_void"]) == 1 and len(res_tac["accidental_slip"]) == 0
        assert len(res_tac["mask_indices"]) == 0

        # Case B: Accidental bridging slip — player was sneaking + placing blocks
        p_slip = Path(td) / "slip.5nn.gz"
        _write_synthetic_recording(
            p_slip,
            specs,
            extra_kwargs_fn=lambda i, t: {
                "pitch": 75.0,
                "key_mask": KEY_W | KEY_SNEAK,
                "right_clicks": 1 if i == 25 else 0,
                "event_flags": EVT_VOID_FALL if i == 33 else (EVT_BLOCK_PLACED if i == 25 else 0),
                "own_bed_alive": 1,
                "diamonds": 0,
                "y": 64.0 if i < 28 else 10.0,
                "self_flags": 1 if i < 28 else 0,
            },
        )
        _, frames_slip = read_recording(p_slip, valid_only=True)
        res_slip = classify_void_falls(frames_slip)
        assert len(res_slip["accidental_slip"]) == 1 and len(res_slip["tactical_void"]) == 0
        assert len(res_slip["mask_indices"]) >= 20


def test_invariant3_ring_buffer_gaps_pearl_and_sidecar_tags() -> None:
    specs = [
        (1, 1, QFLAG_VALID_IN_GAME),
        (2, 1, QFLAG_VALID_IN_GAME),
        # Pearl landing with QFLAG_TELEPORT_TICK -> must stay continuous via PEARL_LANDING tag!
        (3, 1, QFLAG_VALID_IN_GAME | QFLAG_TELEPORT_TICK),
        (4, 1, QFLAG_VALID_IN_GAME),
        # Ring-buffer scrubbed ticks 5..14 -> real tickIndex gap (4 -> 15) must split!
        (15, 1, QFLAG_VALID_IN_GAME),
        (16, 1, QFLAG_VALID_IN_GAME),
        # Scrapped by F7 USER_SCRAP_5S tag (ticks 17..18)
        (17, 1, QFLAG_VALID_IN_GAME),
        (18, 1, QFLAG_VALID_IN_GAME),
        (19, 1, QFLAG_VALID_IN_GAME),
    ]
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "multi.5nn.gz"
        _write_synthetic_recording(p, specs, schema_version=2)
        # Write sidecar .tags.jsonl + .meta.json (ensure find_recordings skips sidecars!)
        Path(str(p) + ".meta.json").write_text("{}", encoding="utf-8")
        Path(str(p) + ".tags.jsonl").write_text(
            json.dumps({"startTick": 2, "endTick": 4, "episodeId": 1, "tag": "PEARL_LANDING"}) + "\n"
            + json.dumps({"startTick": 17, "endTick": 18, "episodeId": 1, "tag": "USER_SCRAP_5S"}) + "\n",
            encoding="utf-8",
        )
        found = find_recordings(td)
        assert found == [str(p)]

        _, all_frames = read_recording(p, valid_only=False)
        tags = load_tags_sidecar(p)
        segs = split_contiguous_segments(all_frames, tags=tags)
        # Expect: [1,2,3,4] (pearl continuous!), [15,16] (split by ring-buffer gap!), [19] (17..18 scrapped!)
        assert [len(s) for s in segs] == [4, 2, 1], f"Unexpected segment splits: {[len(s) for s in segs]}"

        report = audit_recordings(found)
        assert report["valid_frames"] == 7
        assert report["nonfinite_obs"] == 0


def test_schema_v1_backward_compat() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "legacy_v1.5nn.gz"
        _write_synthetic_recording(p, [(5, 99, QFLAG_VALID_IN_GAME)], schema_version=1)
        _, frames = read_recording(p, valid_only=True)
        assert len(frames) == 1
        assert frames[0].tick_index == 5 and frames[0].episode_id == 1


if __name__ == "__main__":
    test_mulaw_roundtrip()
    test_binary_io_v2_mirror_and_togglesprint()
    test_invariant2_gui_safe_hotbar_permutation()
    test_invariant1_tactical_void_vs_accidental_slip()
    test_invariant3_ring_buffer_gaps_pearl_and_sidecar_tags()
    test_schema_v1_backward_compat()
    print("ALL 5ifthNN v0.3 Python schema, causal, void, hotbar & sidecar tests PASSED!")
