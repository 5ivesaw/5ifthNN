"""
Deterministic Egocentric Feature Encoder + Left-Right Mirror Augmentation.

CRITICAL: Must match Java's io.fifthnn.inference.FeatureEncoder bit-for-bit
so training features and live ONNX inference features are 100% identical!
"""

from __future__ import annotations

import math
from typing import Dict, Tuple

import numpy as np

from .dataset_io import FrameRecord
from .schema import (
    KEY_A,
    KEY_D,
    KEY_JUMP,
    KEY_LEFT_HELD,
    KEY_RIGHT_HELD,
    KEY_S,
    KEY_SNEAK,
    KEY_SPRINT,
    KEY_W,
    LIDAR_RAY_COUNT,
    MAX_PITCH_DEG_PER_TICK,
    MAX_YAW_DEG_PER_TICK,
    NN_MAX_PLAYERS,
    NN_MAX_THREATS,
    NN_PLAYER_FEAT_DIM,
    NN_SCALAR_OBS_DIM,
    NN_THREAT_FEAT_DIM,
    NN_VOXEL_MIN_Y,
    NN_VOXEL_RADIUS_XZ,
    NN_VOXEL_SIZE_XZ,
    NN_VOXEL_SIZE_Y,
    NUM_AIM_BINS,
    NUM_BLOCK_CATS,
    NUM_ITEM_CATS,
    RAW_VOXEL_MIN_Y,
    RAW_VOXEL_RADIUS_XZ,
    RAW_VOXEL_SIZE_XZ,
    RAW_VOXEL_SIZE_Y,
    quantize_angle_mulaw,
)


def _world_to_ego(dx: float, dz: float, yaw_deg: float) -> Tuple[float, float]:
    """
    Convert world-space horizontal delta (dx, dz) into Minecraft player-egocentric
    (forward, right) coordinates.
    In Minecraft: yaw=0 faces +Z, yaw=90 faces -X, yaw=180 faces -Z, yaw=-90 faces +X.
      forward vector = (-sin(yaw), +cos(yaw))
      right   vector = (-cos(yaw), -sin(yaw))  (wait: cross(forward, up) = (cos(yaw), sin(yaw))? Let's verify!)
    Check at yaw=0 (facing +Z):
      forward = (0, +1). Looking +Z with Y up, right hand points to -X!
      Check (-cos(0), -sin(0)) = (-1, 0) = -X. YES!
    Check at yaw=-90 (facing +X):
      forward = (+1, 0). Looking +X with Y up, right hand points to +Z!
      Check (-cos(-90), -sin(-90)) = (0, +1) = +Z. YES!
    """
    rad = math.radians(yaw_deg)
    sin_y = math.sin(rad)
    cos_y = math.cos(rad)
    fwd = dx * (-sin_y) + dz * cos_y
    right = dx * (-cos_y) + dz * (-sin_y)
    return fwd, right


def extract_egocentric_voxels(raw_voxels: np.ndarray, yaw_deg: float, mirror: bool = False) -> Tuple[np.ndarray, np.ndarray]:
    """
    Rotate world-aligned 13x7x13 voxel patch into player-egocentric 9x5x9 patch.
    Returns:
      voxel_cats:  int64[5, 9, 9]   semantic block IDs in [0, NUM_BLOCK_CATS-1]
      voxel_flags: float32[2, 5, 9, 9]  (isBreakable, isPlayerPlaced)
    Axis convention: (dy_idx, fwd_idx [back->front], right_idx [left->right]).
    """
    rad = math.radians(yaw_deg)
    sin_y = math.sin(rad)
    cos_y = math.cos(rad)

    cats = np.zeros((NN_VOXEL_SIZE_Y, NN_VOXEL_SIZE_XZ, NN_VOXEL_SIZE_XZ), dtype=np.int64)
    flags = np.zeros((2, NN_VOXEL_SIZE_Y, NN_VOXEL_SIZE_XZ, NN_VOXEL_SIZE_XZ), dtype=np.float32)

    raw3d = raw_voxels.reshape(RAW_VOXEL_SIZE_Y, RAW_VOXEL_SIZE_XZ, RAW_VOXEL_SIZE_XZ)

    for yi in range(NN_VOXEL_SIZE_Y):
        dy = NN_VOXEL_MIN_Y + yi
        raw_yi = dy - RAW_VOXEL_MIN_Y
        for fi in range(NN_VOXEL_SIZE_XZ):
            fwd = fi - NN_VOXEL_RADIUS_XZ
            for ri in range(NN_VOXEL_SIZE_XZ):
                right = ri - NN_VOXEL_RADIUS_XZ
                eff_right = -right if mirror else right
                # Inverse transform from ego (fwd, right) to world (dx, dz):
                # world = fwd * (-sin, cos) + right * (-cos, -sin)
                wx = int(round(fwd * (-sin_y) + eff_right * (-cos_y)))
                wz = int(round(fwd * cos_y + eff_right * (-sin_y)))
                raw_xi = wx + RAW_VOXEL_RADIUS_XZ
                raw_zi = wz + RAW_VOXEL_RADIUS_XZ
                if 0 <= raw_xi < RAW_VOXEL_SIZE_XZ and 0 <= raw_zi < RAW_VOXEL_SIZE_XZ:
                    packed = int(raw3d[raw_yi, raw_zi, raw_xi])
                    cats[yi, fi, ri] = min(packed & 0x3F, NUM_BLOCK_CATS - 1)
                    flags[0, yi, fi, ri] = 1.0 if (packed & 0x40) else 0.0
                    flags[1, yi, fi, ri] = 1.0 if (packed & 0x80) else 0.0

    return cats, flags


def _mirror_lidar_index(idx: int) -> int:
    """Mirror ray index left<->right for the 32-ray lidar layout."""
    if idx < 16:
        # 16 horizontal rays: 0 is forward (0 deg), k is k*22.5 deg clockwise
        return (16 - idx) % 16
    if idx < 24:
        # 8 downward ledge rays: 16 + k, 0 is forward
        k = idx - 16
        return 16 + ((8 - k) % 8)
    # 8 forward fan rays: pairs of (-yaw, +yaw)
    k = idx - 24
    return 24 + (k ^ 1)


def encode_frame_obs(
    fr: FrameRecord,
    server_profile_id: int = 0,
    pvp_mode_18: bool = True,
    mirror: bool = False,
    hotbar_perm: list[int] | None = None,
    aug_noise: bool = False,
    rng: np.random.Generator | None = None,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """
    Convert one raw 1:1 FrameRecord into neural network input tensors:
      voxel_cats:  int64[5, 9, 9]
      voxel_flags: float32[2, 5, 9, 9]
      scalar_obs:  float32[456]

    Optional training-only augmentations:
      - mirror:      exact left-right mirror symmetry (2x free data)
      - hotbar_perm: GUI-safe 0..8 hotbar permutation (applied ONLY when fr.gui_type == 0!)
      - aug_noise:   tiny sensor jitter on remote velocities/lidar + 5% distant landmark/sound dropout
    """
    sign_r = -1.0 if mirror else 1.0
    voxel_cats, voxel_flags = extract_egocentric_voxels(fr.voxels, fr.yaw, mirror=mirror)

    obs = np.zeros(NN_SCALAR_OBS_DIM, dtype=np.float32)
    off = 0

    # 1. Panoramic 32-ray lidar (128 floats)
    for i in range(LIDAR_RAY_COUNT):
        src = _mirror_lidar_index(i) if mirror else i
        d_norm = min(fr.lidar_dist[src] / 32.0, 1.0)
        if aug_noise and rng is not None:
            d_norm = float(np.clip(d_norm + rng.normal(0.0, 0.004), 0.0, 1.0))
        obs[off + 0] = d_norm
        obs[off + 1] = fr.lidar_cat[src] / float(NUM_BLOCK_CATS)
        obs[off + 2] = 1.0 if (fr.lidar_flags[src] & 0x01) else 0.0
        obs[off + 3] = 1.0 if (fr.lidar_flags[src] & 0x04) else 0.0
        off += 4

    # 2. Top-6 Visible Players (108 floats)
    for pi in range(min(len(fr.players), NN_MAX_PLAYERS)):
        p = fr.players[pi]
        # (entityId, uuidHash, teamId, flags, x, y, z, vx, vy, vz, yaw, pitch, hpEst, mainHand, offHand, armorSum)
        flags = p[3]
        dx, dy, dz = p[4] - fr.x, p[5] - fr.y, p[6] - fr.z
        fwd, right = _world_to_ego(dx, dz, fr.yaw)
        right *= sign_r
        dist = math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6
        rvx, rvz = _world_to_ego(p[7] - fr.vx, p[9] - fr.vz, fr.yaw)
        rvz *= sign_r

        rel_yaw = math.radians(p[10] - fr.yaw) * sign_r
        # Check if enemy is looking toward us
        e_rad = math.radians(p[10])
        ex_fwd, ez_fwd = -math.sin(e_rad), math.cos(e_rad)
        to_me_x, to_me_z = -dx / dist, -dz / dist
        looking_at_me = ex_fwd * to_me_x + ez_fwd * to_me_z

        base = off + pi * NN_PLAYER_FEAT_DIM
        obs[base + 0] = 1.0  # present
        obs[base + 1] = 1.0 if (flags & 0x01) else 0.0  # isTeammate
        obs[base + 2] = max(-1.0, min(1.0, fwd / 32.0))
        obs[base + 3] = max(-1.0, min(1.0, right / 32.0))
        obs[base + 4] = max(-1.0, min(1.0, dy / 16.0))
        obs[base + 5] = min(dist / 48.0, 1.0)
        obs[base + 6] = max(-1.0, min(1.0, rvx + (float(rng.normal(0.0, 0.015)) if (aug_noise and rng is not None) else 0.0)))
        obs[base + 7] = max(-1.0, min(1.0, p[8] - fr.vy))
        obs[base + 8] = max(-1.0, min(1.0, rvz + (float(rng.normal(0.0, 0.015)) if (aug_noise and rng is not None) else 0.0)))
        obs[base + 9] = math.sin(rel_yaw)
        obs[base + 10] = math.cos(rel_yaw)
        obs[base + 11] = max(-1.0, min(1.0, looking_at_me))
        obs[base + 12] = max(0.0, min(1.0, p[12] / 20.0))
        obs[base + 13] = min(p[15] / 16.0, 1.0)
        obs[base + 14] = p[13] / float(NUM_ITEM_CATS)
        obs[base + 15] = 1.0 if (flags & 0x04) else 0.0  # sneaking
        obs[base + 16] = 1.0 if (flags & 0x20) else 0.0  # swinging
        obs[base + 17] = 1.0 if (flags & 0x40) else 0.0  # hurt
    off += NN_MAX_PLAYERS * NN_PLAYER_FEAT_DIM

    # 3. Top-6 World Threats & Dropped Resources (60 floats)
    for ti in range(min(len(fr.threats), NN_MAX_THREATS)):
        t = fr.threats[ti]
        # (type, x, y, z, vx, vy, vz, extra)
        dx, dy, dz = t[1] - fr.x, t[2] - fr.y, t[3] - fr.z
        fwd, right = _world_to_ego(dx, dz, fr.yaw)
        right *= sign_r
        dist = math.sqrt(dx * dx + dy * dy + dz * dz)
        rvx, rvz = _world_to_ego(t[4], t[6], fr.yaw)
        rvz *= sign_r

        base = off + ti * NN_THREAT_FEAT_DIM
        obs[base + 0] = 1.0
        obs[base + 1] = t[0] / 12.0
        obs[base + 2] = max(-1.0, min(1.0, fwd / 24.0))
        obs[base + 3] = max(-1.0, min(1.0, right / 24.0))
        obs[base + 4] = max(-1.0, min(1.0, dy / 16.0))
        obs[base + 5] = min(dist / 32.0, 1.0)
        obs[base + 6] = max(-1.0, min(1.0, rvx))
        obs[base + 7] = max(-1.0, min(1.0, t[5]))
        obs[base + 8] = max(-1.0, min(1.0, rvz))
        obs[base + 9] = min(math.log1p(max(0.0, t[7])) / 5.0, 1.0)
    off += NN_MAX_THREATS * NN_THREAT_FEAT_DIM

    # 4. Self Kinematics + Crosshair + Inventory + Match + Sound + GUI (160 floats)
    s0 = off
    ego_vf, ego_vr = _world_to_ego(fr.vx, fr.vz, fr.yaw)
    ego_vr *= sign_r

    # 4A: Self kinematics & vitals (24 floats: s0+0 .. s0+23)
    obs[s0 + 0] = max(-2.0, min(2.0, ego_vf))
    obs[s0 + 1] = max(-2.0, min(2.0, ego_vr))
    obs[s0 + 2] = max(-2.0, min(2.0, fr.vy))
    obs[s0 + 3] = fr.pitch / 90.0
    obs[s0 + 4] = max(-1.0, min(1.0, (fr.y - 30.0) / 80.0))
    obs[s0 + 5] = fr.eye_height / 2.0
    obs[s0 + 6] = min(fr.fall_dist / 10.0, 1.0)
    obs[s0 + 7] = max(0.0, min(1.0, fr.hp / 20.0))
    obs[s0 + 8] = max(0.0, min(1.0, fr.absorption / 20.0))
    obs[s0 + 9] = max(0.0, min(1.0, fr.hunger / 20.0))
    obs[s0 + 10] = fr.swing_progress
    obs[s0 + 11] = fr.attack_cooldown
    obs[s0 + 12] = min(fr.attack_speed_attr / 24.0, 1.0)
    obs[s0 + 13] = 1.0 if (fr.self_flags & 0x01) else 0.0  # onGround
    obs[s0 + 14] = 1.0 if (fr.self_flags & 0x02) else 0.0  # sneaking
    obs[s0 + 15] = 1.0 if (fr.self_flags & 0x04) else 0.0  # sprinting
    obs[s0 + 16] = 1.0 if (fr.self_flags & 0x08) else 0.0  # inWater
    obs[s0 + 17] = 1.0 if (fr.self_flags & 0x10) else 0.0  # onFire
    obs[s0 + 18] = min(fr.hurt_time / 10.0, 1.0)
    obs[s0 + 19] = 1.0 if (fr.effect_flags & 0x01) else 0.0  # speed
    obs[s0 + 20] = 1.0 if (fr.effect_flags & 0x02) else 0.0  # jump
    obs[s0 + 21] = 1.0 if (fr.effect_flags & 0x04) else 0.0  # invis
    obs[s0 + 22] = 1.0 if (fr.effect_flags & 0x08) else 0.0  # regen/absorption
    obs[s0 + 23] = 1.0 if pvp_mode_18 else 0.0

    # 4B: Crosshair exact target (14 floats: s0+24 .. s0+37)
    obs[s0 + 24] = 1.0 if fr.hit_type == 1 else 0.0  # block
    obs[s0 + 25] = 1.0 if fr.hit_type == 2 else 0.0  # entity
    obs[s0 + 26] = min(fr.hit_dist / 6.0, 1.0)
    obs[s0 + 27] = fr.hit_block_cat / float(NUM_BLOCK_CATS)
    obs[s0 + 28] = 1.0 if (fr.hit_flags & 0x01) else 0.0  # breakable
    obs[s0 + 29] = 1.0 if (fr.hit_flags & 0x02) else 0.0  # playerPlaced
    obs[s0 + 30] = 1.0 if (fr.hit_flags & 0x04) else 0.0  # enemyBed
    obs[s0 + 31] = 1.0 if (fr.hit_flags & 0x08) else 0.0  # ownBed
    obs[s0 + 32] = fr.hit_face / 6.0
    obs[s0 + 33] = fr.mining_progress
    obs[s0 + 34] = 1.0 if (fr.hit_type == 2 and (fr.hit_flags & 0x10)) else 0.0  # enemy player
    obs[s0 + 35] = 1.0 if (fr.hit_type == 2 and (fr.hit_flags & 0x20)) else 0.0  # shop NPC
    obs[s0 + 36] = min(fr.ping_ms / 250.0, 1.0)
    obs[s0 + 37] = min(fr.server_tps_est / 20.0, 1.0)

    # 4C: Inventory, Economy, Gear & Hotbar (46 floats: s0+38 .. s0+83)
    for i in range(10):
        obs[s0 + 38 + i] = min(math.log1p(max(0, fr.econ[i])) / 5.0, 1.0)
    for i in range(6):
        obs[s0 + 48 + i] = fr.gear_tiers[i] / 4.0
    # Hotbar 9 item categories + 9 selected-slot one-hot + 9 stack fill ratios + 3 reserve = 30 floats
    # Critical Invariant #2: Only apply 0..8 hotbar permutation when GUI is closed (gui_type == 0)!
    use_perm = hotbar_perm is not None and len(hotbar_perm) == 9 and fr.gui_type == 0
    eff_selected = fr.selected_slot
    if use_perm and 0 <= fr.selected_slot < 9:
        eff_selected = hotbar_perm.index(fr.selected_slot)
    for slot in range(9):
        src_slot = hotbar_perm[slot] if use_perm else slot
        obs[s0 + 54 + slot] = fr.inventory[src_slot, 0] / float(NUM_ITEM_CATS)
        obs[s0 + 63 + slot] = 1.0 if eff_selected == slot else 0.0
        obs[s0 + 72 + slot] = min(fr.inventory[src_slot, 1] / 64.0, 1.0)
    obs[s0 + 81] = float(fr.inventory[fr.selected_slot, 0]) / float(NUM_ITEM_CATS) if 0 <= fr.selected_slot < 9 else 0.0
    obs[s0 + 82] = min(fr.inventory[fr.selected_slot, 1] / 64.0, 1.0) if 0 <= fr.selected_slot < 9 else 0.0
    obs[s0 + 83] = 1.0 if (0 <= fr.selected_slot < 9 and fr.inventory[fr.selected_slot, 2] > 0) else 0.0

    # 4D: Match & Egocentric Landmark Vectors (40 floats: s0+84 .. s0+123)
    obs[s0 + 84] = float(fr.own_bed_alive)
    obs[s0 + 85] = bin(fr.enemy_beds_mask).count("1") / 7.0
    obs[s0 + 86] = min(fr.enemies_alive / 7.0, 1.0)
    for i in range(5):
        obs[s0 + 87 + i] = fr.team_upgrades[i] / 4.0
    obs[s0 + 92] = fr.diamond_tier / 3.0
    obs[s0 + 93] = fr.emerald_tier / 3.0
    obs[s0 + 94] = min(fr.match_elapsed_ticks / 12000.0, 1.0)
    obs[s0 + 95] = (server_profile_id % 8) / 8.0

    # Pack closest landmarks by type:
    # 0=HOME/OWN_BED, 1=NEAREST_ENEMY_BED, 2=2ND_ENEMY_BED, 3=DIAMOND_GEN, 4=EMERALD_GEN, 5=SHOP_NPC, 6=UPGRADE_NPC
    def _write_lm(slot_offset: int, lm_tuple: Tuple[int, int, float, float, float, int] | None) -> None:
        if lm_tuple is None:
            return
        dx, dy, dz = lm_tuple[2] - fr.x, lm_tuple[3] - fr.y, lm_tuple[4] - fr.z
        dist = math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6
        if aug_noise and rng is not None and dist > 20.0 and rng.random() < 0.05:
            return
        fwd, right = _world_to_ego(dx, dz, fr.yaw)
        right *= sign_r
        obs[slot_offset + 0] = 1.0
        obs[slot_offset + 1] = min(dist / 128.0, 1.0)
        obs[slot_offset + 2] = fwd / dist
        obs[slot_offset + 3] = right / dist

    lm_home = None
    lm_enemy_beds = []
    lm_diamond = None
    lm_emerald = None
    lm_shop = None
    lm_upgrade = None
    for lm in fr.landmarks:
        ltype = lm[0]
        dist_sq = (lm[2] - fr.x) ** 2 + (lm[3] - fr.y) ** 2 + (lm[4] - fr.z) ** 2
        if ltype in (0, 2):  # OWN_BED or HOME_SPAWN
            if lm_home is None or ltype == 0:
                lm_home = lm
        elif ltype == 1 and lm[5] > 0:  # living ENEMY_BED
            lm_enemy_beds.append((dist_sq, lm))
        elif ltype == 3:
            if lm_diamond is None or dist_sq < ((lm_diamond[2] - fr.x) ** 2 + (lm_diamond[4] - fr.z) ** 2):
                lm_diamond = lm
        elif ltype == 4:
            if lm_emerald is None or dist_sq < ((lm_emerald[2] - fr.x) ** 2 + (lm_emerald[4] - fr.z) ** 2):
                lm_emerald = lm
        elif ltype == 5:
            if lm_shop is None or dist_sq < ((lm_shop[2] - fr.x) ** 2 + (lm_shop[4] - fr.z) ** 2):
                lm_shop = lm
        elif ltype == 6:
            if lm_upgrade is None or dist_sq < ((lm_upgrade[2] - fr.x) ** 2 + (lm_upgrade[4] - fr.z) ** 2):
                lm_upgrade = lm

    lm_enemy_beds.sort(key=lambda x: x[0])
    _write_lm(s0 + 96, lm_home)
    _write_lm(s0 + 100, lm_enemy_beds[0][1] if len(lm_enemy_beds) > 0 else None)
    _write_lm(s0 + 104, lm_enemy_beds[1][1] if len(lm_enemy_beds) > 1 else None)
    _write_lm(s0 + 108, lm_diamond)
    _write_lm(s0 + 112, lm_emerald)
    _write_lm(s0 + 116, lm_shop)
    _write_lm(s0 + 120, lm_upgrade)

    # 4E: Directional Sound Radar (12 floats: s0+124 .. s0+135)
    # 4 slots of top sounds: (cat/8, fwd_dir, right_dir)
    for si in range(min(len(fr.sounds), 4)):
        scat, sx, sy, sz, svol = fr.sounds[si]
        dx, dz = sx - fr.x, sz - fr.z
        fwd, right = _world_to_ego(dx, dz, fr.yaw)
        right *= sign_r
        dist = math.sqrt(dx * dx + dz * dz) + 1e-6
        obs[s0 + 124 + si * 3 + 0] = (scat + 1) / 10.0
        obs[s0 + 124 + si * 3 + 1] = fwd / dist
        obs[s0 + 124 + si * 3 + 2] = right / dist

    # 4F: GUI & Affordability Context (24 floats: s0+136 .. s0+159)
    obs[s0 + 136] = 1.0 if fr.gui_type > 0 else 0.0
    obs[s0 + 137] = fr.gui_type / 8.0
    obs[s0 + 138] = fr.shop_tab / 8.0
    obs[s0 + 139] = len(fr.gui_slots) / 54.0
    for bit in range(16):
        obs[s0 + 140 + bit] = 1.0 if (fr.affordable_mask & (1 << bit)) else 0.0
    # 4 summary stats of open GUI contents (filled slots ratio, affordable items ratio, etc.)
    if len(fr.gui_slots) > 0:
        non_empty = np.count_nonzero(fr.gui_slots[:, 0])
        aff_cnt = np.count_nonzero(fr.gui_slots[:, 2] & 0x01)
        obs[s0 + 156] = non_empty / float(len(fr.gui_slots))
        obs[s0 + 157] = aff_cnt / float(len(fr.gui_slots))
    obs[s0 + 158] = 1.0 if fr.left_clicks > 0 else 0.0
    obs[s0 + 159] = 1.0 if fr.right_clicks > 0 else 0.0

    return voxel_cats, voxel_flags, obs


def encode_frame_targets(
    fr: FrameRecord,
    mirror: bool = False,
    hotbar_perm: list[int] | None = None,
) -> Dict[str, np.ndarray | int]:
    """
    Encode ground-truth human actions from a FrameRecord (v0.3):
      - Exact left-right mirror symmetry
      - ToggleSprint / Hold-Sprint normalization (active sprint intent: W held & NOT sneaking)
      - GUI-safe 0..8 hotbar permutation (applied ONLY when fr.gui_type == 0!)
    """
    km = fr.key_mask
    w_down = bool(km & KEY_W)
    sneak_down = bool(km & KEY_SNEAK)
    sprinting_active = bool(fr.self_flags & (1 << 2))
    sprint_intent = 1.0 if ((sprinting_active or bool(km & KEY_SPRINT)) and w_down and not sneak_down) else 0.0

    move = np.array(
        [
            1.0 if w_down else 0.0,
            1.0 if (km & (KEY_D if mirror else KEY_A)) else 0.0,
            1.0 if (km & KEY_S) else 0.0,
            1.0 if (km & (KEY_A if mirror else KEY_D)) else 0.0,
            1.0 if (km & KEY_JUMP) else 0.0,
            1.0 if sneak_down else 0.0,
            sprint_intent,
        ],
        dtype=np.float32,
    )

    dyaw = -fr.delta_yaw if mirror else fr.delta_yaw
    yaw_bin = quantize_angle_mulaw(dyaw, MAX_YAW_DEG_PER_TICK, NUM_AIM_BINS)
    pitch_bin = quantize_angle_mulaw(fr.delta_pitch, MAX_PITCH_DEG_PER_TICK, NUM_AIM_BINS)

    # Combat states: 0=none, 1=tap/click, 2=hold
    if fr.left_clicks > 0:
        left_state = 1
    elif km & KEY_LEFT_HELD:
        left_state = 2
    else:
        left_state = 0

    if fr.right_clicks > 0:
        right_state = 1
    elif km & KEY_RIGHT_HELD:
        right_state = 2
    else:
        right_state = 0

    use_perm = hotbar_perm is not None and len(hotbar_perm) == 9 and fr.gui_type == 0
    if 0 <= fr.hotbar_key <= 8:
        mapped_hb = hotbar_perm.index(fr.hotbar_key) if use_perm else fr.hotbar_key
        hotbar = mapped_hb + 1
    else:
        hotbar = 0

    # GUI action: 0=noop, 1=close_gui, 2..55=slot 0..53
    if fr.gui_click_slot == -2:
        gui_action = 1
    elif 0 <= fr.gui_click_slot < 54:
        gui_action = 2 + fr.gui_click_slot
    else:
        gui_action = 0

    return {
        "move": move,
        "yaw_bin": yaw_bin,
        "pitch_bin": pitch_bin,
        "left_state": left_state,
        "right_state": right_state,
        "hotbar": hotbar,
        "gui_action": gui_action,
        "gui_open": 1 if fr.gui_type > 0 else 0,
    }
