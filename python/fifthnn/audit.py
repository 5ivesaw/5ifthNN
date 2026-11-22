"""
Automated Dataset Health, Coverage, Void/Recovery Classification & Human-ness Auditor for 5ifthNN (v0.3).

Run this after recording BedWars matches to verify zero recording bugs BEFORE training:
  python3 -m fifthnn.audit path/to/recordings/
"""

from __future__ import annotations

import argparse
import math
from typing import Dict, List, Optional, Set, Tuple

import numpy as np

from .dataset_io import FrameRecord, find_recordings, load_tags_sidecar, read_recording
from .features import encode_frame_obs
from .schema import (
    EVT_BED_BROKEN_BY_ME,
    EVT_BLOCK_PLACED,
    EVT_DEATH,
    EVT_FINAL_KILL,
    EVT_GAME_WON,
    EVT_KILL,
    EVT_VOID_FALL,
    KEY_SNEAK,
    KEY_SPRINT,
    QFLAG_LOBBY_OR_SPEC,
    QFLAG_PAUSE_OR_CHAT,
    QFLAG_TELEPORT_TICK,
    QFLAG_VALID_IN_GAME,
    RAW_VOXEL_MIN_Y,
    RAW_VOXEL_RADIUS_XZ,
    RAW_VOXEL_SIZE_XZ,
    RAW_VOXEL_SIZE_Y,
)

SFLAG_ON_GROUND = 1 << 0
SND_PEARL = 7

SCRAP_TAG_NAMES = {
    "USER_SCRAP_3S",
    "USER_SCRAP_5S",
    "USER_SCRAP_10S",
    "USER_SCRAP_15S",
    "LAGBACK_QUARANTINE",
    "FLICKER_QUARANTINE",
    "LAG_SPIKE",
}


def _tick_in_ranges(tick: int, ranges: List[Tuple[int, int]]) -> bool:
    for lo, hi in ranges:
        if lo <= tick <= hi:
            return True
    return False


def split_contiguous_segments(
    frames: List[FrameRecord],
    tags: Optional[List[Dict]] = None,
    max_tick_gap: int = 2,
) -> List[List[FrameRecord]]:
    """
    Split valid in-game frames into contiguous temporal segments so GRU BPTT windows
    NEVER bridge across lobbies, respawns, server lagbacks/teleports, ring-buffer gaps,
    scrapped ranges, or episode boundaries — while keeping Ender Pearl landings continuous!
    """
    scrap_ranges: List[Tuple[int, int]] = []
    scrapped_episodes: Set[int] = set()
    pearl_ranges: List[Tuple[int, int]] = []

    if tags:
        for tg in tags:
            name = str(tg.get("tag", ""))
            lo = int(tg.get("startTick", -1))
            hi = int(tg.get("endTick", -1))
            ep = int(tg.get("episodeId", -1))
            if name == "USER_SCRAP_EPISODE" and ep >= 0:
                scrapped_episodes.add(ep)
            elif name in SCRAP_TAG_NAMES:
                scrap_ranges.append((lo, hi))
            elif name == "PEARL_LANDING":
                pearl_ranges.append((lo, hi))

    segments: List[List[FrameRecord]] = []
    cur: List[FrameRecord] = []
    last_pearl_drop_tick = -9999
    prev_pearls: Optional[int] = None

    for fr in frames:
        cur_pearls = fr.econ[8]
        if prev_pearls is not None and cur_pearls < prev_pearls:
            last_pearl_drop_tick = fr.tick_index
        prev_pearls = cur_pearls

        if fr.episode_id in scrapped_episodes or _tick_in_ranges(fr.tick_index, scrap_ranges):
            if cur:
                segments.append(cur)
                cur = []
            continue

        is_tp = bool(fr.quality_flags & QFLAG_TELEPORT_TICK)
        is_pearl = _tick_in_ranges(fr.tick_index, pearl_ranges)
        if is_tp and not is_pearl and cur:
            prev = cur[-1]
            jump = math.sqrt((fr.x - prev.x) ** 2 + (fr.y - prev.y) ** 2 + (fr.z - prev.z) ** 2)
            has_pearl_snd = any(s[0] == SND_PEARL for s in fr.sounds)
            if (
                fr.episode_id == prev.episode_id
                and fr.hp > 0.0
                and jump < 80.0
                and (fr.tick_index - last_pearl_drop_tick <= 120 or has_pearl_snd)
            ):
                is_pearl = True

        valid = bool(fr.quality_flags & QFLAG_VALID_IN_GAME) and (not is_tp or is_pearl)
        if not valid:
            if cur:
                segments.append(cur)
                cur = []
            continue

        if cur:
            prev = cur[-1]
            dt = fr.tick_index - prev.tick_index
            if fr.episode_id != prev.episode_id or dt <= 0 or dt > max_tick_gap:
                segments.append(cur)
                cur = []
        cur.append(fr)

    if cur:
        segments.append(cur)
    return segments


def classify_void_falls(seg: List[FrameRecord]) -> Dict[str, object]:
    """
    3-Way Void Classification (Critical Invariant #1 — NO pitch-only trap!):
      - pvp_knockoff:    hurt_time > 0 within prior 2.0s (40 ticks) -> KEEP
      - accidental_slip: hurt_time == 0 AND (own_bed_alive == 0 OR carrying dia/em OR
                         actual bridging/placing/sneaking activity in prior 1.5s) -> MASK final 25 ticks
      - tactical_void:   own_bed_alive == 1, hurt_time == 0, NO bridging/sneaking/placing in prior 1.5s,
                         no unbanked dia/em (even if pitch > 50 deg!) -> KEEP (1.0x-1.5x)
    """
    tactical: List[int] = []
    knockoffs: List[int] = []
    slips: List[int] = []
    mask_indices: Set[int] = set()
    tactical_indices: Set[int] = set()

    n = len(seg)
    for i, fr in enumerate(seg):
        is_void_event = bool(fr.event_flags & EVT_VOID_FALL) or (
            bool(fr.event_flags & EVT_DEATH) and fr.y < 15.0
        )
        # Also detect segment ending mid-void-fall (falling rapidly below Y=15)
        if not is_void_event and i == n - 1 and fr.y < 15.0 and fr.vy < -0.5 and fr.fall_dist > 5.0:
            is_void_event = True
        if not is_void_event:
            continue

        # Backtrack to find the tick where the player actually left solid ground (fall_start)
        fall_start = i
        for j in range(i, max(-1, i - 60), -1):
            if seg[j].self_flags & SFLAG_ON_GROUND:
                fall_start = j
                break

        lo40 = max(0, fall_start - 40)
        lo30 = max(0, fall_start - 30)

        was_hit = any(seg[k].hurt_time > 0 for k in range(lo40, min(n, fall_start + 5)))
        if was_hit:
            knockoffs.append(fall_start)
            continue

        # Strictly gate accidental slip on ACTUAL bridging/sneaking/placing activity (NOT raw pitch!)
        had_bridging_activity = any(
            bool(seg[k].key_mask & KEY_SNEAK)
            or seg[k].right_clicks > 0
            or bool(seg[k].event_flags & EVT_BLOCK_PLACED)
            for k in range(lo30, min(n, fall_start + 2))
        )
        ref = seg[fall_start]
        carrying_valuables = (ref.econ[2] > 0) or (ref.econ[3] > 0)  # diamonds or emeralds
        if ref.own_bed_alive == 0 or carrying_valuables or had_bridging_activity:
            slips.append(fall_start)
            for m in range(max(0, fall_start - 25), min(n, i + 1)):
                mask_indices.add(m)
        else:
            tactical.append(fall_start)
            for m in range(max(0, fall_start - 20), min(n, fall_start + 15)):
                tactical_indices.add(m)

    return {
        "tactical_void": tactical,
        "pvp_knockoff": knockoffs,
        "accidental_slip": slips,
        "mask_indices": mask_indices,
        "tactical_indices": tactical_indices,
    }


def detect_edge_recoveries(seg: List[FrameRecord]) -> Set[int]:
    """
    Detect successful edge/knockback recoveries (briefly falling / knocked near void -> regains
    solid ground within 40 ticks without dying) so BC can oversample clutch recoveries 6x!
    """
    recoveries: Set[int] = set()
    n = len(seg)
    for i in range(n - 5):
        fr = seg[i]
        on_ground = bool(fr.self_flags & SFLAG_ON_GROUND)
        if on_ground:
            continue
        near_void = any(bool(fr.lidar_flags[r] & 0x04) for r in range(16, 24))
        in_danger = (fr.fall_dist > 0.35 or fr.hurt_time > 0) and near_void
        if not in_danger:
            continue
        # Look ahead up to 40 ticks: did player regain solid ground and survive?
        for j in range(i + 1, min(n, i + 40)):
            if seg[j].event_flags & (EVT_DEATH | EVT_VOID_FALL):
                break
            if (seg[j].self_flags & SFLAG_ON_GROUND) and seg[j].y >= fr.y - 3.0:
                for m in range(max(0, i - 5), min(n, j + 5)):
                    recoveries.add(m)
                break
    return recoveries


def _count_flickers_and_rubberbands(frames: List[FrameRecord]) -> Tuple[int, int]:
    face_deltas = {0: (0, -1, 0), 1: (0, 1, 0), 2: (0, 0, -1), 3: (0, 0, 1), 4: (-1, 0, 0), 5: (1, 0, 0)}

    def get_cat(fr: FrameRecord, wx: int, wy: int, wz: int) -> Optional[int]:
        dx, dy, dz = wx - math.floor(fr.x), wy - math.floor(fr.y), wz - math.floor(fr.z)
        if abs(dx) > RAW_VOXEL_RADIUS_XZ or abs(dz) > RAW_VOXEL_RADIUS_XZ:
            return None
        iy = dy - RAW_VOXEL_MIN_Y
        if iy < 0 or iy >= RAW_VOXEL_SIZE_Y:
            return None
        raw = fr.voxels.reshape(RAW_VOXEL_SIZE_Y, RAW_VOXEL_SIZE_XZ, RAW_VOXEL_SIZE_XZ)
        return int(raw[iy, dz + RAW_VOXEL_RADIUS_XZ, dx + RAW_VOXEL_RADIUS_XZ]) & 0x3F

    flickers = 0
    rbs = 0
    for i, fr in enumerate(frames):
        if i > 0:
            prev = frames[i - 1]
            if fr.tick_index - prev.tick_index == 1 and fr.y - prev.y > 0.55 and not (prev.self_flags & SFLAG_ON_GROUND):
                rbs += 1
        if fr.event_flags & EVT_BLOCK_PLACED:
            fd = face_deltas.get(fr.hit_face, (0, 0, 0))
            tx, ty, tz = fr.hit_bx + fd[0], fr.hit_by + fd[1], fr.hit_bz + fd[2]
            seq = []
            for k in range(0, min(12, len(frames) - i)):
                cur_f = frames[i + k]
                if k > 0 and (cur_f.episode_id != fr.episode_id or cur_f.tick_index - frames[i + k - 1].tick_index > 2):
                    break
                seq.append(get_cat(cur_f, tx, ty, tz))
            if len(seq) >= 6 and seq[0] and seq[0] > 0:
                if any(seq[k] == 0 and any(x and x > 0 for x in seq[k + 1 :]) for k in range(1, min(9, len(seq) - 1))):
                    flickers += 1
    return flickers, rbs


def audit_recordings(paths: List[str]) -> Dict:
    total_frames = 0
    valid_frames = 0
    lobby_frames = 0
    pause_frames = 0
    tp_frames = 0

    idle_ticks = 0
    snk_spr_ticks = 0
    bridge_ticks = 0
    combat_ticks = 0
    gui_open_ticks = 0
    gui_clicks = 0
    blocks_placed = 0
    beds_broken = 0
    kills = 0
    final_kills = 0
    wins = 0
    tactical_voids = 0
    accidental_slips = 0
    edge_recovery_ticks = 0
    flickers_total = 0
    rubberbands_total = 0
    episodes_seen = set()

    dyaw_list: List[float] = []
    snaps35 = 0
    reversals15 = 0

    player_obs_total = 0
    player_vel_nonzero = 0
    lm_bed_frames = 0
    lm_gen_frames = 0
    lm_shop_frames = 0
    vox_placed_cells = 0
    vox_total_cells = 0

    sampled_obs: List[np.ndarray] = []
    segments_ge_6s = 0

    for fidx, path in enumerate(paths):
        header, frames = read_recording(path, valid_only=False)
        tags = load_tags_sidecar(path)
        total_frames += len(frames)
        flk, rbs = _count_flickers_and_rubberbands(frames)
        flickers_total += flk
        rubberbands_total += rbs

        for fr in frames:
            qf = fr.quality_flags
            if qf & QFLAG_LOBBY_OR_SPEC:
                lobby_frames += 1
            if qf & QFLAG_PAUSE_OR_CHAT:
                pause_frames += 1
            if qf & QFLAG_TELEPORT_TICK:
                tp_frames += 1

        segs = split_contiguous_segments(frames, tags=tags)
        for seg in segs:
            if len(seg) >= 120:
                segments_ge_6s += 1
            vf_info = classify_void_falls(seg)
            tactical_voids += len(vf_info["tactical_void"])
            accidental_slips += len(vf_info["accidental_slip"])
            edge_recovery_ticks += len(detect_edge_recoveries(seg))

            prev_dy = None
            for i, fr in enumerate(seg):
                valid_frames += 1
                episodes_seen.add((fidx, fr.episode_id))
                if fr.key_mask == 0:
                    idle_ticks += 1
                if (fr.key_mask & KEY_SNEAK) and (fr.key_mask & KEY_SPRINT):
                    snk_spr_ticks += 1
                if (fr.key_mask & KEY_SNEAK) and fr.pitch > 55.0:
                    bridge_ticks += 1
                if fr.left_clicks > 0:
                    combat_ticks += 1
                if fr.gui_type > 0:
                    gui_open_ticks += 1
                if fr.gui_click_slot >= 0:
                    gui_clicks += 1

                ev = fr.event_flags
                if ev & EVT_BLOCK_PLACED:
                    blocks_placed += 1
                if ev & EVT_BED_BROKEN_BY_ME:
                    beds_broken += 1
                if ev & EVT_KILL:
                    kills += 1
                if ev & EVT_FINAL_KILL:
                    final_kills += 1
                if ev & EVT_GAME_WON:
                    wins += 1

                dy = fr.delta_yaw
                dyaw_list.append(abs(dy))
                if math.hypot(dy, fr.delta_pitch) > 35.0:
                    snaps35 += 1
                if prev_dy is not None and abs(prev_dy) > 15.0 and abs(dy) > 15.0 and prev_dy * dy < 0:
                    reversals15 += 1
                prev_dy = dy

                for p in fr.players:
                    player_obs_total += 1
                    if abs(p[7]) > 1e-5 or abs(p[9]) > 1e-5:
                        player_vel_nonzero += 1

                lm_types = {lm[0] for lm in fr.landmarks}
                if 0 in lm_types or 1 in lm_types:
                    lm_bed_frames += 1
                if 3 in lm_types or 4 in lm_types:
                    lm_gen_frames += 1
                if 5 in lm_types or 6 in lm_types:
                    lm_shop_frames += 1

                vox_total_cells += len(fr.voxels)
                vox_placed_cells += int(np.count_nonzero(fr.voxels & 0x80))

                if i % max(1, len(seg) // 25) == 0 and len(sampled_obs) < 2000:
                    _, _, o = encode_frame_obs(fr)
                    sampled_obs.append(o)

    dy_arr = np.array(dyaw_list, dtype=np.float32) if dyaw_list else np.zeros(1, dtype=np.float32)
    nonfinite = 0
    dead_cols = []
    if sampled_obs:
        mat = np.stack(sampled_obs, axis=0)
        nonfinite = int((~np.isfinite(mat)).sum())
        dead_cols = np.where(np.abs(mat).max(axis=0) == 0)[0].tolist()

    v = max(1, valid_frames)
    report = {
        "files": len(paths),
        "episodes": len(episodes_seen),
        "segments_ge_6s": segments_ge_6s,
        "total_frames": total_frames,
        "valid_frames": valid_frames,
        "valid_minutes": round(valid_frames / 1200.0, 2),
        "lobby_filtered_pct": round(100.0 * lobby_frames / max(1, total_frames), 1),
        "pause_filtered_pct": round(100.0 * pause_frames / max(1, total_frames), 1),
        "teleport_ticks": tp_frames,
        "idle_pct": round(100.0 * idle_ticks / v, 1),
        "bridge_pct": round(100.0 * bridge_ticks / v, 1),
        "combat_click_pct": round(100.0 * combat_ticks / v, 1),
        "gui_open_ticks": gui_open_ticks,
        "gui_clicks": gui_clicks,
        "blocks_placed": blocks_placed,
        "beds_broken": beds_broken,
        "kills": kills,
        "final_kills": final_kills,
        "wins": wins,
        "tactical_voids": tactical_voids,
        "accidental_slips": accidental_slips,
        "edge_recovery_ticks": edge_recovery_ticks,
        "block_flickers": flickers_total,
        "midair_rubberbands": rubberbands_total,
        "aim_dyaw_p50": round(float(np.percentile(dy_arr, 50)), 2),
        "aim_dyaw_p90": round(float(np.percentile(dy_arr, 90)), 2),
        "aim_dyaw_p99": round(float(np.percentile(dy_arr, 99)), 2),
        "snaps_gt_35deg": snaps35,
        "reversals_gt_15deg": reversals15,
        "snk_spr_ticks": snk_spr_ticks,
        "enemy_vel_nonzero_pct": round(100.0 * player_vel_nonzero / max(1, player_obs_total), 1),
        "landmark_bed_cov_pct": round(100.0 * lm_bed_frames / v, 1),
        "landmark_gen_cov_pct": round(100.0 * lm_gen_frames / v, 1),
        "landmark_shop_cov_pct": round(100.0 * lm_shop_frames / v, 1),
        "voxel_placed_pct": round(100.0 * vox_placed_cells / max(1, vox_total_cells), 2),
        "nonfinite_obs": nonfinite,
        "dead_scalar_cols_count": len(dead_cols),
    }
    return report


def print_scorecard(r: Dict) -> None:
    print("=" * 72)
    print("  5ifthNN DATASET QUALITY, PROTOCOL HEALTH & HUMAN-NESS SCORECARD (v0.3)")
    print("=" * 72)
    print(f"  Files: {r['files']} | Episodes: {r['episodes']} | Contiguous Segments (>=6s): {r['segments_ge_6s']}")
    print(f"  Valid In-Game Time: {r['valid_minutes']:.2f} min ({r['valid_frames']:,} ticks / {r['total_frames']:,} total)")
    print(f"  Auto-Filtered: Lobby={r['lobby_filtered_pct']}% | Pause/Chat={r['pause_filtered_pct']}% | Teleports={r['teleport_ticks']}")
    print(f"  Protocol Health: W->AIR->W Flickers={r['block_flickers']} | Midair Rubberbands={r['midair_rubberbands']}")
    print("-" * 72)
    print(f"  Activity: Idle={r['idle_pct']}% | Bridge={r['bridge_pct']}% | LMB Combat={r['combat_click_pct']}%")
    print(f"  Actions:  ShopClicks={r['gui_clicks']} (GUI={r['gui_open_ticks']}t) | Blocks={r['blocks_placed']} | Beds={r['beds_broken']} | Kills={r['kills']} (FK={r['final_kills']}) | Wins={r['wins']}")
    print(f"  Voids & Clutches: TacticalVoids={r['tactical_voids']} (kept) | AccidentalSlips={r['accidental_slips']} (tail-masked) | RecoveryTicks={r['edge_recovery_ticks']}")
    print(f"  Aim:      |dYaw| p50={r['aim_dyaw_p50']}° p90={r['aim_dyaw_p90']}° p99={r['aim_dyaw_p99']}° (human baseline ~4.7°)")
    print(f"  Sanity:   Snaps>35°={r['snaps_gt_35deg']} | 1t-Reversals>15°={r['reversals_gt_15deg']} | NaNs={r['nonfinite_obs']}")
    print("-" * 72)
    print(f"  Perception Coverage:")
    print(f"    Remote Player Non-Zero Velocity: {r['enemy_vel_nonzero_pct']}%")
    print(f"    Landmarks: Beds={r['landmark_bed_cov_pct']}% | Gens={r['landmark_gen_cov_pct']}% | Shops={r['landmark_shop_cov_pct']}%")
    print(f"    Player-Placed Voxel Cells: {r['voxel_placed_pct']}% | Always-Zero Scalar Cols: {r['dead_scalar_cols_count']}/456")
    print("=" * 72)

    issues = []
    if r["nonfinite_obs"] > 0:
        issues.append("CRITICAL: Non-finite values found in observations!")
    if r["block_flickers"] > 10:
        issues.append(f"High block flicker count ({r['block_flickers']}) — use ViaFabricPlus 5.1.3+ (1.8.x) or built-in Flicker Guard")
    if r["valid_minutes"] < 90.0:
        issues.append(f"Need more clean gameplay for BC bootstrap ({r['valid_minutes']:.1f} / 120+ min recommended)")
    if r["gui_clicks"] < 150:
        issues.append(f"Low shop click count ({r['gui_clicks']} — consider a 15-min shop speedrun drill)")
    if r["idle_pct"] > 45.0:
        issues.append(f"High idle % ({r['idle_pct']}%) — check if lobby filtering missed a hub")

    if not issues:
        print("  VERDICT: CLEAN & READY FOR COLAB L4 BC TRAINING!")
    else:
        print("  RECOMMENDATIONS:")
        for it in issues:
            print(f"    - {it}")


def main() -> None:
    p = argparse.ArgumentParser(description="Audit 5ifthNN .5nn.gz recordings before training")
    p.add_argument("target", help="Recording file or directory containing .5nn.gz files")
    args = p.parse_args()
    paths = find_recordings(args.target)
    if not paths:
        raise FileNotFoundError(f"No .5nn / .5nn.gz recordings found at {args.target}")
    print_scorecard(audit_recordings(paths))


if __name__ == "__main__":
    main()
