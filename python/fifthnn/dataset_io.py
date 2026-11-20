"""
Lossless reader & writer for 5ifthNN binary recordings (.5nn.gz).

Mirrors Java's io.fifthnn.recorder.BinaryDatasetWriter byte-for-byte (big-endian).
Designed to safely read partial/crash-truncated files by validating FRM1 headers
and payload byte lengths before unpacking.
"""

from __future__ import annotations

import gzip
import json
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np

from .schema import (
    FILE_MAGIC,
    FRAME_MAGIC,
    INVENTORY_SLOT_COUNT,
    LIDAR_RAY_COUNT,
    QFLAG_VALID_IN_GAME,
    RAW_VOXEL_COUNT,
    SCHEMA_VERSION,
)

# Precompiled big-endian structs matching Java DataOutputStream
_S_FRAME_META = struct.Struct(">qqBBhf")
_S_SELF = struct.Struct(">ddd15fHBBH")
_S_CROSSHAIR = struct.Struct(">BfiiiBBBfi")
_S_LIDAR = struct.Struct(">fBB")
_S_LANDMARK = struct.Struct(">BBfffB")
_S_PLAYER = struct.Struct(">iiBB9fBBB")
_S_THREAT = struct.Struct(">B7f")
_S_ECON = struct.Struct(">10hB6B")
_S_SLOT = struct.Struct(">BBB")
_S_GUI_HDR = struct.Struct(">BBHB")
_S_MATCH = struct.Struct(">BBB5BBBiH")
_S_SOUND = struct.Struct(">B4f")
_S_INPUT = struct.Struct(">H4fBBbbBBf")


@dataclass
class FrameRecord:
    tick_index: int
    timestamp_ms: int
    match_phase: int
    quality_flags: int
    ping_ms: int
    server_tps_est: float

    # Self state
    x: float
    y: float
    z: float
    vx: float
    vy: float
    vz: float
    yaw: float
    pitch: float
    prev_yaw: float
    prev_pitch: float
    eye_height: float
    fall_dist: float
    hp: float
    absorption: float
    hunger: float
    swing_progress: float
    attack_cooldown: float
    attack_speed_attr: float
    self_flags: int
    hurt_time: int
    own_team_id: int
    effect_flags: int

    # Crosshair
    hit_type: int
    hit_dist: float
    hit_bx: int
    hit_by: int
    hit_bz: int
    hit_face: int
    hit_block_cat: int
    hit_flags: int
    mining_progress: float
    hit_entity_id: int

    # Spatial world (1:1 lossless)
    voxels: np.ndarray        # uint8[1183] -> (7, 13, 13)
    lidar_dist: np.ndarray    # float32[32]
    lidar_cat: np.ndarray     # uint8[32]
    lidar_flags: np.ndarray   # uint8[32]

    # Dynamic lists
    landmarks: List[Tuple[int, int, float, float, float, int]]
    players: List[Tuple]
    threats: List[Tuple]

    # Inventory & economy
    econ: Tuple[int, ...]          # 10 ints
    selected_slot: int
    gear_tiers: Tuple[int, ...]    # 6 ints
    inventory: np.ndarray          # uint8[41, 3]

    # GUI
    gui_type: int
    shop_tab: int
    affordable_mask: int
    gui_slots: np.ndarray          # uint8[N, 3]

    # Match state
    own_bed_alive: int
    enemy_beds_mask: int
    enemies_alive: int
    team_upgrades: Tuple[int, ...]  # 5 ints
    diamond_tier: int
    emerald_tier: int
    match_elapsed_ticks: int
    event_flags: int

    # Sounds
    sounds: List[Tuple[int, float, float, float, float]]

    # Human ground-truth inputs & reward
    key_mask: int
    raw_mouse_dx: float
    raw_mouse_dy: float
    delta_yaw: float
    delta_pitch: float
    left_clicks: int
    right_clicks: int
    hotbar_key: int
    gui_click_slot: int
    gui_click_button: int
    gui_click_type: int
    reward_delta: float


def _unpack_frame(buf: bytes) -> FrameRecord:
    mv = memoryview(buf)
    off = 0

    meta = _S_FRAME_META.unpack_from(mv, off)
    off += _S_FRAME_META.size

    self_vals = _S_SELF.unpack_from(mv, off)
    off += _S_SELF.size

    ch = _S_CROSSHAIR.unpack_from(mv, off)
    off += _S_CROSSHAIR.size

    voxels = np.frombuffer(mv[off : off + RAW_VOXEL_COUNT], dtype=np.uint8).copy()
    off += RAW_VOXEL_COUNT

    lidar_dist = np.empty(LIDAR_RAY_COUNT, dtype=np.float32)
    lidar_cat = np.empty(LIDAR_RAY_COUNT, dtype=np.uint8)
    lidar_flags = np.empty(LIDAR_RAY_COUNT, dtype=np.uint8)
    for i in range(LIDAR_RAY_COUNT):
        d, c, f = _S_LIDAR.unpack_from(mv, off)
        off += _S_LIDAR.size
        lidar_dist[i] = d
        lidar_cat[i] = c
        lidar_flags[i] = f

    lm_count = mv[off]
    off += 1
    landmarks = []
    for _ in range(lm_count):
        landmarks.append(_S_LANDMARK.unpack_from(mv, off))
        off += _S_LANDMARK.size

    p_count = mv[off]
    off += 1
    players = []
    for _ in range(p_count):
        players.append(_S_PLAYER.unpack_from(mv, off))
        off += _S_PLAYER.size

    t_count = mv[off]
    off += 1
    threats = []
    for _ in range(t_count):
        threats.append(_S_THREAT.unpack_from(mv, off))
        off += _S_THREAT.size

    econ_raw = _S_ECON.unpack_from(mv, off)
    off += _S_ECON.size
    econ = econ_raw[:10]
    selected_slot = econ_raw[10]
    gear_tiers = econ_raw[11:]

    inv_bytes = mv[off : off + INVENTORY_SLOT_COUNT * 3]
    inventory = np.frombuffer(inv_bytes, dtype=np.uint8).reshape(INVENTORY_SLOT_COUNT, 3).copy()
    off += INVENTORY_SLOT_COUNT * 3

    gui_type, shop_tab, affordable_mask, gui_slot_count = _S_GUI_HDR.unpack_from(mv, off)
    off += _S_GUI_HDR.size
    if gui_slot_count > 0:
        g_bytes = mv[off : off + gui_slot_count * 3]
        gui_slots = np.frombuffer(g_bytes, dtype=np.uint8).reshape(gui_slot_count, 3).copy()
        off += gui_slot_count * 3
    else:
        gui_slots = np.zeros((0, 3), dtype=np.uint8)

    m = _S_MATCH.unpack_from(mv, off)
    off += _S_MATCH.size

    s_count = mv[off]
    off += 1
    sounds = []
    for _ in range(s_count):
        sounds.append(_S_SOUND.unpack_from(mv, off))
        off += _S_SOUND.size

    inp = _S_INPUT.unpack_from(mv, off)
    off += _S_INPUT.size

    return FrameRecord(
        tick_index=meta[0],
        timestamp_ms=meta[1],
        match_phase=meta[2],
        quality_flags=meta[3],
        ping_ms=meta[4],
        server_tps_est=meta[5],
        x=self_vals[0],
        y=self_vals[1],
        z=self_vals[2],
        vx=self_vals[3],
        vy=self_vals[4],
        vz=self_vals[5],
        yaw=self_vals[6],
        pitch=self_vals[7],
        prev_yaw=self_vals[8],
        prev_pitch=self_vals[9],
        eye_height=self_vals[10],
        fall_dist=self_vals[11],
        hp=self_vals[12],
        absorption=self_vals[13],
        hunger=self_vals[14],
        swing_progress=self_vals[15],
        attack_cooldown=self_vals[16],
        attack_speed_attr=self_vals[17],
        self_flags=self_vals[18],
        hurt_time=self_vals[19],
        own_team_id=self_vals[20],
        effect_flags=self_vals[21],
        hit_type=ch[0],
        hit_dist=ch[1],
        hit_bx=ch[2],
        hit_by=ch[3],
        hit_bz=ch[4],
        hit_face=ch[5],
        hit_block_cat=ch[6],
        hit_flags=ch[7],
        mining_progress=ch[8],
        hit_entity_id=ch[9],
        voxels=voxels,
        lidar_dist=lidar_dist,
        lidar_cat=lidar_cat,
        lidar_flags=lidar_flags,
        landmarks=landmarks,
        players=players,
        threats=threats,
        econ=econ,
        selected_slot=selected_slot,
        gear_tiers=gear_tiers,
        inventory=inventory,
        gui_type=gui_type,
        shop_tab=shop_tab,
        affordable_mask=affordable_mask,
        gui_slots=gui_slots,
        own_bed_alive=m[0],
        enemy_beds_mask=m[1],
        enemies_alive=m[2],
        team_upgrades=m[3:8],
        diamond_tier=m[8],
        emerald_tier=m[9],
        match_elapsed_ticks=m[10],
        event_flags=m[11],
        sounds=sounds,
        key_mask=inp[0],
        raw_mouse_dx=inp[1],
        raw_mouse_dy=inp[2],
        delta_yaw=inp[3],
        delta_pitch=inp[4],
        left_clicks=inp[5],
        right_clicks=inp[6],
        hotbar_key=inp[7],
        gui_click_slot=inp[8],
        gui_click_button=inp[9],
        gui_click_type=inp[10],
        reward_delta=inp[11],
    )


def read_recording(path: str | Path, valid_only: bool = False) -> Tuple[Dict, List[FrameRecord]]:
    """
    Read a .5nn or .5nn.gz recording safely.
    Truncated tail frames (e.g. from a sudden client crash) are cleanly discarded.
    """
    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else open

    frames: List[FrameRecord] = []
    header: Dict = {}

    with opener(path, "rb") as f:
        magic = f.read(4)
        if magic != FILE_MAGIC:
            raise ValueError(f"Invalid 5ifthNN file magic in {path}: {magic!r}")
        (version,) = struct.unpack(">H", f.read(2))
        if version != SCHEMA_VERSION:
            raise ValueError(f"Unsupported schema version {version} (expected {SCHEMA_VERSION})")
        (hdr_len,) = struct.unpack(">I", f.read(4))
        header = json.loads(f.read(hdr_len).decode("utf-8"))

        while True:
            try:
                fmagic = f.read(4)
                if len(fmagic) < 4:
                    break
                if fmagic != FRAME_MAGIC:
                    break
                plen_bytes = f.read(4)
                if len(plen_bytes) < 4:
                    break
                (plen,) = struct.unpack(">I", plen_bytes)
                payload = f.read(plen)
                if len(payload) < plen:
                    break
                frame = _unpack_frame(payload)
                if not valid_only or (frame.quality_flags & QFLAG_VALID_IN_GAME):
                    frames.append(frame)
            except (EOFError, OSError, struct.error):
                # Graceful recovery if the recording ended abruptly during a crash
                break

    return header, frames


def summarize_recording(path: str | Path) -> Dict:
    header, frames = read_recording(path, valid_only=False)
    valid = [fr for fr in frames if (fr.quality_flags & QFLAG_VALID_IN_GAME)]
    total_reward = sum(fr.reward_delta for fr in valid)
    gui_ticks = sum(1 for fr in valid if fr.gui_type > 0)
    click_ticks = sum(1 for fr in valid if fr.left_clicks > 0 or fr.right_clicks > 0)
    return {
        "file": str(path),
        "mode": header.get("recordingMode", "UNKNOWN"),
        "server": header.get("serverProfile", "unknown"),
        "map": header.get("mapName", "unknown"),
        "pvp_mode": header.get("pvpMode", "1.8"),
        "total_frames": len(frames),
        "valid_in_game_frames": len(valid),
        "valid_seconds": round(len(valid) / 20.0, 2),
        "gui_frames": gui_ticks,
        "click_frames": click_ticks,
        "total_reward": round(total_reward, 3),
    }


if __name__ == "__main__":
    import sys

    for arg in sys.argv[1:]:
        print(json.dumps(summarize_recording(arg), indent=2))
