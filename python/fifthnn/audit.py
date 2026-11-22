"""
Automated Dataset Health, Coverage & Human-ness Auditor for 5ifthNN (.5nn.gz).

Run this after recording BedWars matches to verify zero recording bugs BEFORE training:
  python3 -m fifthnn.audit path/to/recordings/
"""

from __future__ import annotations

import argparse
import glob
import math
from pathlib import Path
from typing import Dict, List

import numpy as np

from .dataset_io import FrameRecord, read_recording
from .features import encode_frame_obs
from .schema import (
    EVT_BED_BROKEN_BY_ME,
    EVT_BLOCK_PLACED,
    EVT_FINAL_KILL,
    EVT_GAME_WON,
    EVT_KILL,
    KEY_SNEAK,
    KEY_SPRINT,
    QFLAG_LOBBY_OR_SPEC,
    QFLAG_PAUSE_OR_CHAT,
    QFLAG_TELEPORT_TICK,
    QFLAG_VALID_IN_GAME,
)


def split_contiguous_segments(frames: List[FrameRecord], max_tick_gap: int = 2) -> List[List[FrameRecord]]:
    """
    Split valid in-game frames into contiguous temporal segments so GRU BPTT windows
    NEVER bridge across lobbies, respawns, server teleports, or episode boundaries.
    """
    segments: List[List[FrameRecord]] = []
    cur: List[FrameRecord] = []
    for fr in frames:
        valid = bool(fr.quality_flags & QFLAG_VALID_IN_GAME) and not bool(fr.quality_flags & QFLAG_TELEPORT_TICK)
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
        total_frames += len(frames)
        for fr in frames:
            qf = fr.quality_flags
            if qf & QFLAG_LOBBY_OR_SPEC:
                lobby_frames += 1
            if qf & QFLAG_PAUSE_OR_CHAT:
                pause_frames += 1
            if qf & QFLAG_TELEPORT_TICK:
                tp_frames += 1

        segs = split_contiguous_segments(frames)
        for seg in segs:
            if len(seg) >= 120:
                segments_ge_6s += 1
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
    print("=" * 68)
    print("  5ifthNN DATASET QUALITY & HUMAN-NESS SCORECARD")
    print("=" * 68)
    print(f"  Files: {r['files']} | Episodes: {r['episodes']} | Contiguous Segments (>=6s): {r['segments_ge_6s']}")
    print(f"  Valid In-Game Time: {r['valid_minutes']:.2f} min ({r['valid_frames']:,} ticks / {r['total_frames']:,} total)")
    print(f"  Auto-Filtered: Lobby={r['lobby_filtered_pct']}% | Pause/Chat={r['pause_filtered_pct']}% | Teleports={r['teleport_ticks']}")
    print("-" * 68)
    print(f"  Activity: Idle={r['idle_pct']}% (human target <35%) | Bridge={r['bridge_pct']}% | LMB Combat={r['combat_click_pct']}%")
    print(f"  Actions:  ShopClicks={r['gui_clicks']} (GUI open={r['gui_open_ticks']}t) | Blocks={r['blocks_placed']} | Beds={r['beds_broken']} | Kills={r['kills']} (FK={r['final_kills']}) | Wins={r['wins']}")
    print(f"  Aim:      |dYaw| p50={r['aim_dyaw_p50']}° p90={r['aim_dyaw_p90']}° p99={r['aim_dyaw_p99']}° (your human p90 baseline ~4.7°)")
    print(f"  Sanity:   Snaps>35°={r['snaps_gt_35deg']} | 1t-Reversals>15°={r['reversals_gt_15deg']} | SNK+SPR={r['snk_spr_ticks']} | NaNs={r['nonfinite_obs']}")
    print("-" * 68)
    print(f"  Perception Coverage:")
    print(f"    Remote Player Non-Zero Velocity: {r['enemy_vel_nonzero_pct']}%")
    print(f"    Landmarks: Beds={r['landmark_bed_cov_pct']}% | Gens={r['landmark_gen_cov_pct']}% | Shops={r['landmark_shop_cov_pct']}%")
    print(f"    Player-Placed Voxel Cells: {r['voxel_placed_pct']}% | Always-Zero Scalar Cols: {r['dead_scalar_cols_count']}/456")
    print("=" * 68)

    issues = []
    if r["nonfinite_obs"] > 0:
        issues.append("CRITICAL: Non-finite values found in observations!")
    if r["valid_minutes"] < 90.0:
        issues.append(f"Need more clean gameplay for BC bootstrap ({r['valid_minutes']:.1f} / 120+ min recommended)")
    if r["gui_clicks"] < 150:
        issues.append(f"Low shop click count ({r['gui_clicks']} — consider a 20-min DRILL_SHOP session)")
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
    t = Path(args.target)
    paths = [str(t)] if t.is_file() else sorted(glob.glob(str(t / "**/*.5nn*"), recursive=True))
    if not paths:
        raise FileNotFoundError(f"No .5nn / .5nn.gz recordings found at {args.target}")
    print_scorecard(audit_recordings(paths))


if __name__ == "__main__":
    main()
